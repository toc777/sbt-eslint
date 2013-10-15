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
    val jslintTest = TaskKey[Unit]("jslint-test", "Perform JavaScript linting for tests.")
    val ass = SettingKey[Option[Boolean]]("ass", "If assignment expressions should be allowed.")
    val bitwise = SettingKey[Option[Boolean]]("bitwise", "If bitwise operators should be allowed.")
    val browser = SettingKey[Option[Boolean]]("browser", "If the standard browser globals should be predefined.")
    val closure = SettingKey[Option[Boolean]]("closure", "If Google Closure idioms should be tolerated.")
    val continue = SettingKey[Option[Boolean]]("continue", "If the continuation statement should be tolerated.")
    val debug = SettingKey[Option[Boolean]]("debug", "if debugger statements should be allowed.")
    val devel = SettingKey[Option[Boolean]]("devel", "if logging should be allowed (console, alert, etc.).")
    val eqeq = SettingKey[Option[Boolean]]("eqeq", "if == should be allowed.")
    val es5 = SettingKey[Option[Boolean]]("es5", "if ES5 syntax should be allowed.")
    val evil = SettingKey[Option[Boolean]]("evil", "if eval should be allowed.")
    val forin = SettingKey[Option[Boolean]]("forin", "if for in statements need not filter.")
    val indent = SettingKey[Option[Int]]("indent", "the indentation factor.")
    val maxerr = SettingKey[Option[Int]]("maxerr", "the maximum number of errors to allow.")
    val maxlen = SettingKey[Option[Int]]("maxlen", "the maximum length of a source line.")
    val newcap = SettingKey[Option[Boolean]]("newcap", "if constructor names capitalization is ignored.")
    val node = SettingKey[Option[Boolean]]("node", "if Node.js globals should be predefined.")
    val nomen = SettingKey[Option[Boolean]]("nomen", "if names may have dangling.")
    val passfail = SettingKey[Option[Boolean]]("passfail", "if the scan should stop on first error.")
    val plusplus = SettingKey[Option[Boolean]]("plusplus", "if increment/decrement should be allowed.")
    val properties = SettingKey[Option[Boolean]]("properties", "if all property names must be declared with /*properties*/.")
    val regexp = SettingKey[Option[Boolean]]("regexp", "if the . should be allowed in regexp literals.")
    val rhino = SettingKey[Option[Boolean]]("rhino", "if the Rhino environment globals should be predefined.")
    val unparam = SettingKey[Option[Boolean]]("unparam", "if unused parameters should be tolerated.")
    val sloppy = SettingKey[Option[Boolean]]("sloppy", "if the 'use strict'; pragma is optional.")
    val stupid = SettingKey[Option[Boolean]]("stupid", "if really stupid practices are tolerated.")
    val sub = SettingKey[Option[Boolean]]("sub", "if all forms of subscript notation are tolerated.")
    val todo = SettingKey[Option[Boolean]]("todo", "if TODO comments are tolerated.")
    val vars = SettingKey[Option[Boolean]]("vars", "if multiple var statements per function should be allowed.")
    val white = SettingKey[Option[Boolean]]("white", "if sloppy whitespace is tolerated.")
    val jslintOptions = TaskKey[JsObject]("jslint-options", "An array of jslint options to pass to the linter.")
  }

  import JslintKeys._

  def jslintSettings = webDriverSettings ++ Seq(
    ass := None,
    bitwise := None,
    browser := None,
    closure := None,
    continue := None,
    debug := None,
    devel := None,
    eqeq := None,
    es5 := None,
    evil := None,
    forin := None,
    indent := None,
    maxerr := None,
    maxlen := None,
    newcap := None,
    node := None,
    nomen := None,
    passfail := None,
    plusplus := None,
    properties := None,
    regexp := None,
    rhino := None,
    unparam := None,
    sloppy := None,
    stupid := None,
    sub := None,
    todo := None,
    vars := None,
    white := None,

    jslintOptions <<= state map jslintOptionsTask,

    jslint <<= (
      jslintOptions,
      webBrowser,
      parallelism,
      sources in JavaScript,
      streams,
      reporter
      ) map (jslintTask(_, _, _, _, _, _, false)),
    jslintTest <<= (
      jslintOptions,
      webBrowser,
      parallelism,
      sources in JavaScriptTest,
      streams,
      reporter
      ) map (jslintTask(_, _, _, _, _, _, true)),

    test <<= (test in Test).dependsOn(jslint, jslintTest)
  )

  private def jslintOptionsTask(state: State): JsObject = {
    val extracted = Project.extract(state)
    JsObject(List(
      extracted.get(ass in JavaScript).map(v => "ass" -> JsBoolean(v)),
      extracted.get(bitwise in JavaScript).map(v => "bitwise" -> JsBoolean(v)),
      extracted.get(browser in JavaScript).map(v => "browser" -> JsBoolean(v)),
      extracted.get(closure in JavaScript).map(v => "closure" -> JsBoolean(v)),
      extracted.get(continue in JavaScript).map(v => "continue" -> JsBoolean(v)),
      extracted.get(debug in JavaScript).map(v => "debug" -> JsBoolean(v)),
      extracted.get(devel in JavaScript).map(v => "devel" -> JsBoolean(v)),
      extracted.get(eqeq in JavaScript).map(v => "eqeq" -> JsBoolean(v)),
      extracted.get(es5 in JavaScript).map(v => "es5" -> JsBoolean(v)),
      extracted.get(evil in JavaScript).map(v => "evil" -> JsBoolean(v)),
      extracted.get(forin in JavaScript).map(v => "forin" -> JsBoolean(v)),
      extracted.get(indent in JavaScript).map(v => "indent" -> JsNumber(v)),
      extracted.get(maxerr in JavaScript).map(v => "maxerr" -> JsNumber(v)),
      extracted.get(maxlen in JavaScript).map(v => "maxlen" -> JsNumber(v)),
      extracted.get(newcap in JavaScript).map(v => "newcap" -> JsBoolean(v)),
      extracted.get(node in JavaScript).map(v => "node" -> JsBoolean(v)),
      extracted.get(nomen in JavaScript).map(v => "nomen" -> JsBoolean(v)),
      extracted.get(passfail in JavaScript).map(v => "passfail" -> JsBoolean(v)),
      extracted.get(plusplus in JavaScript).map(v => "plusplus" -> JsBoolean(v)),
      extracted.get(properties in JavaScript).map(v => "properties" -> JsBoolean(v)),
      extracted.get(regexp in JavaScript).map(v => "regexp" -> JsBoolean(v)),
      extracted.get(rhino in JavaScript).map(v => "rhino" -> JsBoolean(v)),
      extracted.get(unparam in JavaScript).map(v => "unparam" -> JsBoolean(v)),
      extracted.get(sloppy in JavaScript).map(v => "sloppy" -> JsBoolean(v)),
      extracted.get(stupid in JavaScript).map(v => "stupid" -> JsBoolean(v)),
      extracted.get(sub in JavaScript).map(v => "sub" -> JsBoolean(v)),
      extracted.get(todo in JavaScript).map(v => "todo" -> JsBoolean(v)),
      extracted.get(vars in JavaScript).map(v => "vars" -> JsBoolean(v)),
      extracted.get(white in JavaScript).map(v => "white" -> JsBoolean(v))
    ).flatten)
  }

  // TODO: This can be abstracted further so that source batches can be determined generally.
  private def jslintTask(jslintOptions: JsObject,
                         browser: ActorRef,
                         parallelism: Int,
                         sources: Seq[File],
                         s: TaskStreams,
                         reporter: LoggerReporter,
                         testing: Boolean
                          ): Unit = {

    val testKeyword = if (testing) "test " else ""
    s.log.info(s"JavaScript linting on ${sources.size} ${testKeyword}source(s)")

    val resultBatches: Seq[Future[Seq[(File, JsArray)]]] =
      try {
        val sourceBatches = (sources grouped Math.max(sources.size / parallelism, 1)).toSeq
        sourceBatches.map(lintForSources(jslintOptions, browser, _))
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
  private def lintForSources(options: JsObject, browser: ActorRef, sources: Seq[File]): Future[Seq[(File, JsArray)]] = {
    jslinter.beginLint(browser).flatMap[Seq[(File, JsArray)]] {
      lintState =>
        val results = sources.map {
          source =>
            val lintResult = jslinter.lint(lintState, source, options)
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
