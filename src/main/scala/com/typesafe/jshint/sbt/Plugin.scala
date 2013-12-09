package com.typesafe.jshint.sbt

import sbt._
import sbt.Keys._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import spray.json._
import xsbti.{Maybe, Position, Severity}
import java.lang.RuntimeException
import com.typesafe.sbt.web.WebPlugin.WebKeys
import com.typesafe.jse.sbt.JsEnginePlugin.JsEngineKeys
import com.typesafe.jse.{Rhino, PhantomJs, Node, CommonNode}
import scala.collection.immutable
import com.typesafe.jshint.Jshinter
import com.typesafe.sbt.web._
import scala.Some
import sbt.File


/**
 * The sbt plugin plumbing around the JSHint library.
 */
object JSHintPlugin extends sbt.Plugin {

  object JshintKeys {

    val jshint = TaskKey[Unit]("jshint", "Perform JavaScript linting.")
    val jshintTest = TaskKey[Unit]("jshint-test", "Perform JavaScript linting for tests.")

    val jshintOptions = TaskKey[JsObject]("jshint-options", "An array of jshint options to pass to the linter. This options are found via jshint-resolved-config. If there is no config then the options will be specified such that the JSHint defaults are used.")

    val config = SettingKey[Option[File]]("jshint-config", "The location of a JSHint configuration file.")
    val resolvedConfig = TaskKey[Option[File]]("jshint-resolved-config", "The actual location of a JSHint configuration file if present. If jshint-config is none then the task will seek a .jshintrc in the project folder. If that's not found then .jshintrc will be searched for in the user's home folder. This behaviour is consistent with other JSHint tooling.")

    val modifiedJsFiles = TaskKey[Seq[File]]("jshint-modified-js-files", "Determine the Js files that are modified.")
    val modifiedJsTestFiles = TaskKey[Seq[File]]("jshint-modified-js-test-files", "Determine the Js files that are modified for test.")

    val shellSource = SettingKey[File]("jshint-shelljs-source", "The target location of the js shell script to use.")
    val jshintSource = SettingKey[File]("jshint-jshintjs-source", "The target location of the jshint script to use.")

  }

  import WebKeys._
  import JshintKeys._
  import JsEngineKeys._

  def jshintSettings = Seq(

    config := None,
    resolvedConfig <<= (config, baseDirectory) map resolveConfigTask,
    jshintOptions <<= resolvedConfig map jshintOptionsTask,

    modifiedJsFiles <<= (
      streams,
      unmanagedSources in Assets,
      jsFilter,
      jshintOptions
      ) map getModifiedJsFilesTask,
    modifiedJsTestFiles <<= (
      streams,
      unmanagedSources in TestAssets,
      jsTestFilter,
      jshintOptions
      ) map getModifiedJsFilesTask,

    shellSource := getFileInTarget(target.value, "shell.js"),
    // FIXME: This resource will eventually be located from its webjar. For now
    // we use a webjar until the webjar is updated with my fix (waiting for a
    // new release of jshint).
    jshintSource := getFileInTarget(target.value, "jshint.js"),

    jshint <<= (
      state,
      shellSource,
      jshintSource,
      modifiedJsFiles,
      jshintOptions,
      engineType,
      parallelism,
      streams,
      reporter
      ) map (jshintTask(_, _, _, _, _, _, _, _, _, testing = false)),
    jshintTest <<= (
      state,
      shellSource,
      jshintSource,
      modifiedJsTestFiles,
      jshintOptions,
      engineType,
      parallelism,
      streams,
      reporter
      ) map (jshintTask(_, _, _, _, _, _, _, _, _, testing = true)),

    test <<= (test in Test).dependsOn(jshint, jshintTest)

  )

  private def resolveConfigTask(someConfig: Option[File], baseDirectory: File): Option[File] = {
    someConfig.orElse {
      val JsHintRc = ".jshintrc"
      val projectRc = baseDirectory / JsHintRc
      if (projectRc.exists()) {
        Some(projectRc)
      } else {
        val homeRc = file(System.getProperty("user.home")) / JsHintRc
        if (homeRc.exists()) {
          Some(homeRc)
        } else {
          None
        }
      }
    }
  }

  private def jshintOptionsTask(someConfig: Option[File]): JsObject = {
    someConfig
      .map(config => IO.read(config).asJson.asJsObject)
      .getOrElse(JsObject())
  }

  // FIXME: Abstract this into sbt-web?
  private def getFileInTarget(target: File, name: String): File = {
    val is = this.getClass.getClassLoader.getResourceAsStream(name)
    try {
      val f = target / this.getClass.getSimpleName / name
      IO.transfer(is, f)
      f
    } finally {
      is.close()
    }
  }

  private def getModifiedJsFilesTask(s: TaskStreams, unmanagedSources: Seq[File], jsFilter: FileFilter, jshintOptions: JsObject): Seq[File] = {
    val sourceFileManager = SourceFileManager(s.cacheDirectory / this.getClass.getName)
    val jsSources = (unmanagedSources ** jsFilter).get
    val buildSettingsDigest = jsFilter.toString + jshintOptions
    val modifiedJsSources = sourceFileManager.setAndCompareBuildStamps(
      jsSources.map(jsSource => (jsSource, jsSource.lastModified().toString + buildSettingsDigest))
        .to[immutable.Seq]
    )
    if (modifiedJsSources.size > 0) {
      sourceFileManager.save()
    }
    modifiedJsSources
  }

  private def jshintTask(
                          state: State,
                          shellSource: File,
                          jshintSource: File,
                          modifiedJsSources: Seq[File],
                          jshintOptions: JsObject,
                          engineType: EngineType.Value,
                          parallelism: Int,
                          s: TaskStreams,
                          reporter: LoggerReporter,
                          testing: Boolean
                          ): Unit = {

    import DefaultJsonProtocol._
    import WebPlugin._

    reporter.reset()

    val engineProps = engineType match {
      case EngineType.CommonNode => CommonNode.props()
      case EngineType.Node => Node.props()
      case EngineType.PhantomJs => PhantomJs.props()
      case EngineType.Rhino => Rhino.props()
    }


    val testKeyword = if (testing) "test " else ""
    if (modifiedJsSources.size > 0) {
      s.log.info(s"JavaScript linting on ${modifiedJsSources.size} ${testKeyword}source(s)")
    }

    val resultBatches: immutable.Seq[Future[JsArray]] =
      try {
        val sourceBatches = (modifiedJsSources grouped Math.max(modifiedJsSources.size / parallelism, 1)).to[immutable.Seq]
        sourceBatches.map {
          sourceBatch =>
            withActorRefFactory(state, this.getClass.getName) {
              arf =>
                val engine = arf.actorOf(engineProps)
                val jshinter = Jshinter(engine, shellSource, jshintSource)
                jshinter.lint(sourceBatch.to[immutable.Seq], jshintOptions)
            }
        }
      }

    val pendingResults = Future.sequence(resultBatches)
    for {
      allResults <- Await.result(pendingResults, 10.seconds)
      result <- allResults.elements
    } {
      val resultTuple = result.convertTo[JsArray]
      logErrors(
        reporter,
        s.log,
        file(resultTuple.elements(0).toString()),
        resultTuple.elements(1).convertTo[JsArray]
      )
    }

    reporter.printSummary()
    if (reporter.hasErrors()) {
      throw new LintingFailedException
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
  }
}

class LintingFailedException extends RuntimeException("JavaScript linting failed") with FeedbackProvidedException {
  override def toString = getMessage
}
