package com.typesafe.jshint.sbt

import sbt.Keys._
import sbt._
import akka.actor.ActorRef
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Await, Future}
import scala.concurrent.duration._
import spray.json._
import com.typesafe.jshint.Jshinter
import xsbti.{Maybe, Position, Severity}
import java.lang.RuntimeException
import com.typesafe.js.sbt.WebPlugin.WebKeys
import com.typesafe.jse.sbt.JsEnginePlugin.JsEngineKeys
import com.typesafe.jse.sbt.JsEnginePlugin


/**
 * The WebDriver sbt plugin plumbing around the JshintEngine
 */
object Plugin extends sbt.Plugin {

  object JshintKeys {
    val jshint = TaskKey[Unit]("jshint", "Perform JavaScript linting.")
    val jshintTest = TaskKey[Unit]("jshint-test", "Perform JavaScript linting for tests.")
    val ass = SettingKey[Option[Boolean]]("jshint-ass", "If assignment expressions should be allowed.")
    val bitwise = SettingKey[Option[Boolean]]("jshint-bitwise", "If bitwise operators should be allowed.")
    val browser = SettingKey[Option[Boolean]]("jshint-browser", "If the standard browser globals should be predefined.")
    val closure = SettingKey[Option[Boolean]]("jshint-closure", "If Google Closure idioms should be tolerated.")
    val continue = SettingKey[Option[Boolean]]("jshint-continue", "If the continuation statement should be tolerated.")
    val debug = SettingKey[Option[Boolean]]("jshint-debug", "if debugger statements should be allowed.")
    val devel = SettingKey[Option[Boolean]]("jshint-devel", "if logging should be allowed (console, alert, etc.).")
    val eqeq = SettingKey[Option[Boolean]]("jshint-eqeq", "if == should be allowed.")
    val es5 = SettingKey[Option[Boolean]]("jshint-es5", "if ES5 syntax should be allowed.")
    val evil = SettingKey[Option[Boolean]]("jshint-evil", "if eval should be allowed.")
    val forin = SettingKey[Option[Boolean]]("jshint-forin", "if for in statements need not filter.")
    val indent = SettingKey[Option[Int]]("jshint-indent", "the indentation factor.")
    val maxerr = SettingKey[Option[Int]]("jshint-maxerr", "the maximum number of errors to allow.")
    val maxlen = SettingKey[Option[Int]]("jshint-maxlen", "the maximum length of a source line.")
    val newcap = SettingKey[Option[Boolean]]("jshint-newcap", "if constructor names capitalization is ignored.")
    val node = SettingKey[Option[Boolean]]("jshint-node", "if Node.js globals should be predefined.")
    val nomen = SettingKey[Option[Boolean]]("jshint-nomen", "if names may have dangling.")
    val passfail = SettingKey[Option[Boolean]]("jshint-passfail", "if the scan should stop on first error.")
    val plusplus = SettingKey[Option[Boolean]]("jshint-plusplus", "if increment/decrement should be allowed.")
    val properties = SettingKey[Option[Boolean]]("jshint-properties", "if all property names must be declared with /*properties*/.")
    val regexp = SettingKey[Option[Boolean]]("jshint-regexp", "if the . should be allowed in regexp literals.")
    val rhino = SettingKey[Option[Boolean]]("jshint-rhino", "if the Rhino environment globals should be predefined.")
    val unparam = SettingKey[Option[Boolean]]("jshint-unparam", "if unused parameters should be tolerated.")
    val sloppy = SettingKey[Option[Boolean]]("jshint-sloppy", "if the 'use strict'; pragma is optional.")
    val stupid = SettingKey[Option[Boolean]]("jshint-stupid", "if really stupid practices are tolerated.")
    val sub = SettingKey[Option[Boolean]]("jshint-sub", "if all forms of subscript notation are tolerated.")
    val todo = SettingKey[Option[Boolean]]("jshint-todo", "if TODO comments are tolerated.")
    val vars = SettingKey[Option[Boolean]]("jshint-vars", "if multiple var statements per function should be allowed.")
    val white = SettingKey[Option[Boolean]]("jshint-white", "if sloppy whitespace is tolerated.")
    val jshintOptions = TaskKey[JsObject]("jshint-options", "An array of jshint options to pass to the linter.")
  }

  import WebKeys._
  import JshintKeys._
  import JsEngineKeys._

  def jshintSettings = Seq(
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

    jshintOptions <<= state map jshintOptionsTask,

    jshint <<= (
      jshintOptions,
      unmanagedSources in Assets,
      jsFilter,
      parallelism,
      streams,
      reporter
      ) map (jshintTask(_, _, _, _, _, _, testing = false)),
    jshintTest <<= (
      jshintOptions,
      unmanagedSources in TestAssets,
      jsFilter,
      parallelism,
      streams,
      reporter
      ) map (jshintTask(_, _, _, _, _, _, testing = true)),

    test <<= (test in Test).dependsOn(jshint, jshintTest)
  )

  private def jshintOptionsTask(state: State): JsObject = {
    val extracted = Project.extract(state)
    JsObject(List(
      extracted.get(ass).map(v => "ass" -> JsBoolean(v)),
      extracted.get(bitwise).map(v => "bitwise" -> JsBoolean(v)),
      extracted.get(browser).map(v => "browser" -> JsBoolean(v)),
      extracted.get(closure).map(v => "closure" -> JsBoolean(v)),
      extracted.get(continue).map(v => "continue" -> JsBoolean(v)),
      extracted.get(debug).map(v => "debug" -> JsBoolean(v)),
      extracted.get(devel).map(v => "devel" -> JsBoolean(v)),
      extracted.get(eqeq).map(v => "eqeq" -> JsBoolean(v)),
      extracted.get(es5).map(v => "es5" -> JsBoolean(v)),
      extracted.get(evil).map(v => "evil" -> JsBoolean(v)),
      extracted.get(forin).map(v => "forin" -> JsBoolean(v)),
      extracted.get(indent).map(v => "indent" -> JsNumber(v)),
      extracted.get(maxerr).map(v => "maxerr" -> JsNumber(v)),
      extracted.get(maxlen).map(v => "maxlen" -> JsNumber(v)),
      extracted.get(newcap).map(v => "newcap" -> JsBoolean(v)),
      extracted.get(node).map(v => "node" -> JsBoolean(v)),
      extracted.get(nomen).map(v => "nomen" -> JsBoolean(v)),
      extracted.get(passfail).map(v => "passfail" -> JsBoolean(v)),
      extracted.get(plusplus).map(v => "plusplus" -> JsBoolean(v)),
      extracted.get(properties).map(v => "properties" -> JsBoolean(v)),
      extracted.get(regexp).map(v => "regexp" -> JsBoolean(v)),
      extracted.get(rhino).map(v => "rhino" -> JsBoolean(v)),
      extracted.get(unparam).map(v => "unparam" -> JsBoolean(v)),
      extracted.get(sloppy).map(v => "sloppy" -> JsBoolean(v)),
      extracted.get(stupid).map(v => "stupid" -> JsBoolean(v)),
      extracted.get(sub).map(v => "sub" -> JsBoolean(v)),
      extracted.get(todo).map(v => "todo" -> JsBoolean(v)),
      extracted.get(vars).map(v => "vars" -> JsBoolean(v)),
      extracted.get(white).map(v => "white" -> JsBoolean(v))
    ).flatten)
  }

  // TODO: This can be abstracted further so that source batches can be determined generally?
  private def jshintTask(jshintOptions: JsObject,
                         unmanagedSources: Seq[File],
                         jsFilter: FileFilter,
                         parallelism: Int,
                         s: TaskStreams,
                         reporter: LoggerReporter,
                         testing: Boolean
                          ): Unit = {

    reporter.reset()

    val sources = (unmanagedSources ** jsFilter).get

    val testKeyword = if (testing) "test " else ""
    if (sources.size > 0) {
      s.log.info(s"JavaScript linting on ${sources.size} ${testKeyword}source(s)")
    }

    val resultBatches: Seq[Future[Seq[(File, JsArray)]]] =
      try {
        val sourceBatches = (sources grouped Math.max(sources.size / parallelism, 1)).toSeq
        //sourceBatches.map(lintForSources(jshintOptions, browser, _))
        Nil
      }

    val pendingResults = Future.sequence(resultBatches).flatMap(rb => Future(rb.flatten))
    val results = Await.result(pendingResults, 10.seconds)
    results.foreach {
      result =>
        //logErrors(reporter, s.log, result._1, result._2)
    }
    reporter.printSummary()
    if (reporter.hasErrors()) {
      throw new LintingFailedException
    }
  }

  implicit val jseSystem = JsEnginePlugin.jseSystem
  implicit val jseTimeout = JsEnginePlugin.jseTimeout

 /* private val jshinter = Jshinter()

  /*
   * lints a sequence of sources and returns a future representing the results of all.
   */
  private def lintForSources(options: JsObject, browser: ActorRef, sources: Seq[File]): Future[Seq[(File, JsArray)]] = {
    jshinter.beginLint(browser).flatMap[Seq[(File, JsArray)]] {
      session =>
        val promisedResults = Seq.fill(sources.size)(Promise[(File, JsArray)]())
        lintNextSource(session, options, sources, promisedResults)
        val allResults = Future.sequence(promisedResults.map(_.future))
        allResults.onComplete {
          case _ => jshinter.endLint(session)
        }
        allResults
    }
  }

  /*
   * lint the next source in sequence and call again upon completion for the next one after that.
   */
  private def lintNextSource(session: ActorRef, options: JsObject, sources: Seq[File], promises: Seq[Promise[(File, JsArray)]]): Unit = {
    if (sources.size > 0) {
      val source = sources.head

      jshinter.lint(session, source, options)
        .map((source, _))
        .onComplete {
        c =>
          lintNextSource(session, options, sources.tail, promises.tail)
          promises.head.complete(c)
      }
    }
  }

  private def logErrors(reporter: LoggerReporter, log: Logger, source: File, jshintErrors: JsArray): Unit = {

    jshintErrors.elements.map {
      case o: JsObject =>

        def getReason(o: JsObject): String = o.fields.get("reason").get.toString()

        def logWithSeverity(o: JsObject, s: Severity): Unit = {
          val p = new Position {
            def line(): Maybe[Integer] =
              Maybe.just(java.lang.Double.parseDouble(o.fields.get("line").get.toString()).toInt)

            def lineContent(): String = o.fields.get("evidence") match {
              case Some(JsString(line)) => line
              case _ => ""
            }

            def offset(): Maybe[Integer] =
              Maybe.just(java.lang.Double.parseDouble(o.fields.get("character").get.toString()).toInt - 1)

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
  }   */
}

class LintingFailedException extends RuntimeException("JavaScript linting failed") with FeedbackProvidedException {
  override def toString = getMessage
}
