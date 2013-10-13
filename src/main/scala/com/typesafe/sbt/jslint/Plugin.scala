package com.typesafe.sbt.jslint

import sbt.Keys._
import sbt._
import akka.actor.ActorRef
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import spray.json._
import scala.util.{Failure, Success}
import com.typesafe.jslint.JslintEngine
import xsbti.{Maybe, Position, Severity}

/**
 * The sbt plugin plumbing around the JslintEngine
 */
object Plugin extends sbt.Plugin {

  // FIXME: These things should be defined outside of this plugin

  object WebdriverKeys {
    val JavaScript = config("js")
    val jsSource = SettingKey[File]("js-source", "The main source directory for JavaScript.")
    val parallelism = SettingKey[Int]("parallelism", "The number of parallel tasks for the webdriver host. Defaults to the # of available processors + 1 to keep things busy.")
    val reporter = TaskKey[LoggerReporter]("reporter", "The reporter to use for conveying processing results.")
  }

  import WebdriverKeys._

  def webdriverSettings = Seq(
    jsSource in JavaScript := (sourceDirectory in Compile).value / "js",
    parallelism in JavaScript := java.lang.Runtime.getRuntime.availableProcessors() + 1,
    reporter in JavaScript := new LoggerReporter(5, streams.value.log),
    includeFilter in JavaScript := GlobFilter("*.js"),
    sources in JavaScript <<= (jsSource in JavaScript, includeFilter in JavaScript, excludeFilter in JavaScript) map {
      (sourceDirectory, includeFilter, excludeFilter) => (sourceDirectory * (includeFilter -- excludeFilter)).get
    }
  )


  object JslintKeys {
    val engineState = TaskKey[ActorRef]("engine-state", "An actor representing the state of the jslint engine.")
    val jslint = TaskKey[Unit]("jslint", "Perform JavaScript linting.")
  }

  private val jsLintEngine = JslintEngine()

  private val engineStateAttrKey = AttributeKey[ActorRef]("engine-state")

  override val globalSettings: Seq[Setting[_]] = Seq(
    onLoad in Global := (onLoad in Global).value andThen {
      state: State =>
        val engineState = jsLintEngine.start()
        state.put(engineStateAttrKey, engineState)
    },

    onUnload in Global := (onUnload in Global).value andThen {
      state: State =>
        val engineState = state.get(engineStateAttrKey)
        engineState.foreach(jsLintEngine.stop)
        state.remove(engineStateAttrKey)
    }
  )

  def jslintSettings = webdriverSettings ++ Seq(
    JslintKeys.engineState <<= engineStateTask,
    JslintKeys.jslint <<= jslintTask
  )

  def engineStateTask = state map (_.get(engineStateAttrKey).get)

  private def jslintTask = (
    JslintKeys.engineState,
    parallelism in JavaScript,
    sources in JavaScript,
    streams,
    reporter in JavaScript
    ) map {
    (
      engineState: ActorRef, parallelism: Int, sources: Seq[File], s: TaskStreams, reporter: LoggerReporter) =>
      s.log.info(s"JavaScript linting on ${sources.size} source(s)")

      val resultBatches: Seq[Future[Seq[(File, JsArray)]]] =
        try {
          val sourceBatches = (sources grouped Math.max(sources.size / parallelism, 1)).toSeq
          sourceBatches.map(lintForSources(engineState, _))
        }

      val completedResultBatches = Future.sequence(resultBatches)
      Await.ready(completedResultBatches, 10.seconds).onComplete {
        case Success(allResults) =>
          for {
            results <- allResults
            result <- results
          } logErrors(reporter, s.log, result._1, result._2)
        case Failure(t) => s.log.error(s"Failed linting: $t")
      }
  }

  /*
   * lints a sequence of sources and returns a future representing the results of all.
   */
  private def lintForSources(engineState: ActorRef, sources: Seq[File]): Future[Seq[(File, JsArray)]] = {
    jsLintEngine.beginLint(engineState).flatMap[Seq[(File, JsArray)]] {
      lintState =>
        val results = sources.map {
          source =>
            val lintResult = jsLintEngine.lint(lintState, source, JsObject())
              .map(result => (source, result))
            lintResult.onComplete {
              case _ => jsLintEngine.endLint(lintState)
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
