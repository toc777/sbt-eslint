package com.typesafe.jslint.sbt

import sbt.Keys._
import sbt._
import akka.actor.ActorRef
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import spray.json._
import scala.util.{Failure, Success}
import com.typesafe.jslint.Jslinter
import xsbti.{Maybe, Position, Severity}
import com.typesafe.webdriver.sbt.WebDriverPlugin

/**
 * The WebDriver sbt plugin plumbing around the JslintEngine
 */
object Plugin extends WebDriverPlugin {

  import WebDriverKeys._

  object JslintKeys {
    val jslint = TaskKey[Unit]("jslint", "Perform JavaScript linting.")
    // TODO: Define the jslint keys and pass them on to the jslinter.
  }

  def jslintSettings = webDriverSettings ++ Seq(
    JslintKeys.jslint <<= jslintTask
  )

  // TODO: This can be abstracted further so that source batches can be determined generally.
  private def jslintTask = (
    browser in JavaScript,
    parallelism in JavaScript,
    sources in JavaScript,
    streams,
    reporter in JavaScript
    ) map {
    (browser: ActorRef, parallelism: Int, sources: Seq[File], s: TaskStreams, reporter: LoggerReporter) =>
      s.log.info(s"JavaScript linting on ${sources.size} source(s)")

      val resultBatches: Seq[Future[Seq[(File, JsArray)]]] =
        try {
          val sourceBatches = (sources grouped Math.max(sources.size / parallelism, 1)).toSeq
          sourceBatches.map(lintForSources(browser, _))
        }

      val allResults = Future.sequence(resultBatches).flatMap(rb => Future(rb.flatten))
      Await.ready(allResults, 10.seconds).onComplete {
        case Success(results) =>
          results.foreach {
            result => logErrors(reporter, s.log, result._1, result._2)
          }
        case Failure(t) =>
          s.log.error(s"Failed linting: $t")
      }
  }


  private val jslinter = Jslinter()

  /*
   * lints a sequence of sources and returns a future representing the results of all.
   */
  private def lintForSources(browser: ActorRef, sources: Seq[File]): Future[Seq[(File, JsArray)]] = {
    jslinter.beginLint(browser).flatMap[Seq[(File, JsArray)]] {
      lintState =>
        val results = sources.map {
          source =>
            val lintResult = jslinter.lint(lintState, source, JsObject())
              .map(result => (source, result))
            lintResult.onComplete {
              case _ => jslinter.endLint(lintState)
            }
            lintResult
        }
        Future.sequence(results)
    }
  }

  private def logErrors(reporter: LoggerReporter, log: Logger, source: File, jslintErrors: JsArray): Unit = {

    jslintErrors.elements.map {
      case o: JsObject =>

        def getReason(o: JsObject): String = o.fields.get("reason").get.toString()

        def logWithSeverity(o: JsObject, s: Severity): Unit = {
          val p = new Position {
            def line(): Maybe[Integer] = Maybe.just(Integer.parseInt(o.fields.get("line").get.toString()))

            def lineContent(): String = o.fields.get("evidence").get.toString()

            def offset(): Maybe[Integer] = Maybe.just(Integer.parseInt(o.fields.get("character").get.toString()))

            def pointer(): Maybe[Integer] = offset()

            def pointerSpace(): Maybe[String] = Maybe.just(
              lineContent().take(pointer().get).map {
                case '\t' => '\t'
                case x => ' '
              })

            def sourcePath(): Maybe[String] = Maybe.just(source.getPath)

            def sourceFile(): Maybe[File] = Maybe.just(source)
          }
          val r = getReason(o)
          reporter.log(p, r, s)
        }

        o.fields.get("id") match {
          case Some(JsString("(error)")) => logWithSeverity(o, Severity.Error)
          case Some(JsString("(info)")) => logWithSeverity(o, Severity.Info)
          case Some(JsString("(warn)")) => logWithSeverity(o, Severity.Warn)
          case Some(id@_) => log.error(s"Unknown type of error: $id with reason: ${getReason(o)}")
          case _ => log.error(s"Malformed error with reason: ${getReason(o)}")
        }
      case x@_ => log.error(s"Malformed result: $x")
    }
    reporter.printSummary()
  }
}
