package com.typesafe.jshint.sbt

import sbt._
import sbt.Keys._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import spray.json._
import com.typesafe.web.sbt.WebPlugin.WebKeys
import com.typesafe.jse.sbt.JsEnginePlugin.JsEngineKeys
import com.typesafe.jse.{Rhino, PhantomJs, Node, CommonNode, Trireme}
import com.typesafe.jshint.Jshinter
import com.typesafe.web.sbt._
import scala.Some
import xsbti.{CompileFailed, Severity, Problem}
import akka.util.Timeout
import org.webjars.WebJarExtractor
import scala.collection.immutable


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

    val jsFiles = TaskKey[Seq[File]]("jshint-js-files", "Determine the Js files.")
    val jsTestFiles = TaskKey[Seq[File]]("jshint-js-test-files", "Determine the Js files for test.")

    val shellSource = TaskKey[File]("jshint-shelljs-source", "The target location of the js shell script to use.")

  }

  import WebKeys._
  import JshintKeys._
  import JsEngineKeys._

  def jshintSettings = Seq(

    config := None,
    resolvedConfig <<= (config, baseDirectory) map resolveConfigTask,
    jshintOptions <<= resolvedConfig map jshintOptionsTask,

    jsFiles := ((unmanagedSources in Assets).value ** (jsFilter in Assets).value).get,
    jsTestFiles := ((unmanagedSources in TestAssets).value ** (jsFilter in TestAssets).value).get,

    shellSource in jshint <<= (target in LocalRootProject) map copyShellSourceTask,

    jshint <<= (
      state,
      shellSource in jshint,
      nodeModules in Plugin,
      jsFiles,
      jshintOptions,
      engineType,
      parallelism,
      streams,
      reporter
      ) map (jshintTask(_, _, _, _, _, _, _, _, _, testing = false)),
    jshintTest <<= (
      state,
      shellSource in jshint,
      nodeModules in Plugin,
      jsTestFiles,
      jshintOptions,
      engineType,
      parallelism,
      streams,
      reporter
      ) map (jshintTask(_, _, _, _, _, _, _, _, _, testing = true)),

    compile in Compile <<= (compile in Compile).dependsOn(jshint),
    test in Test <<= (test in Test).dependsOn(jshint, jshintTest)

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

  private def copyShellSourceTask(target: File): File = {
    WebPlugin.copyResourceTo(
      target / "jshint-plugin",
      "shell.js",
      JSHintPlugin.getClass.getClassLoader
    )
  }

  private def jshintTask(
                          state: State,
                          shellSource: File,
                          nodeModules: File,
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

    // FIXME: Should be made configurable.
    implicit val timeout = Timeout(1.hour)

    reporter.reset()

    val engineProps = engineType match {
      case EngineType.CommonNode => CommonNode.props()
      case EngineType.Node => Node.props(stdModulePaths = immutable.Seq(nodeModules.getCanonicalPath))
      case EngineType.PhantomJs => PhantomJs.props()
      case EngineType.Rhino => Rhino.props()
      case EngineType.Trireme => Trireme.props(stdModulePaths = immutable.Seq(nodeModules.getCanonicalPath))
    }


    val testKeyword = if (testing) "test " else ""
    if (modifiedJsSources.size > 0) {
      s.log.info(s"JavaScript linting on ${modifiedJsSources.size} ${testKeyword}source(s)")
    }

    val resultBatches: Seq[Future[JsArray]] =
      try {
        val sourceBatches = (modifiedJsSources grouped Math.max(modifiedJsSources.size / parallelism, 1)).toSeq
        sourceBatches.map {
          sourceBatch =>
            withActorRefFactory(state, this.getClass.getName) {
              arf =>
                val engine = arf.actorOf(engineProps)
                val jshinter = new Jshinter(engine, shellSource)
                jshinter.lint(sourceBatch, jshintOptions)
            }
        }
      }

    val pendingResults = Future.sequence(resultBatches)
    val allProblems: Seq[Problem] = (for {
      allResults <- Await.result(pendingResults, 10.seconds)
      result <- allResults.elements
    } yield {
      val resultTuple = result.convertTo[JsArray]
      val source = file(resultTuple.elements(0).convertTo[String])
      resultTuple.elements(1).convertTo[JsArray].elements.map {
        case o: JsObject =>
          def getReason(o: JsObject): String = o.fields.get("reason").get.toString()

          def lineBasedProblem(o: JsObject, s: Severity): Problem =
            new LineBasedProblem(
              getReason(o),
              s,
              java.lang.Double.parseDouble(o.fields.get("line").get.toString()).toInt,
              java.lang.Double.parseDouble(o.fields.get("character").get.toString()).toInt - 1,
              o.fields.get("evidence") match {
                case Some(JsString(line)) => line
                case _ => ""
              },
              source
            )

          o.fields.get("id") match {
            case Some(JsString("(error)")) => lineBasedProblem(o, Severity.Error)
            case Some(JsString("(info)")) => lineBasedProblem(o, Severity.Info)
            case Some(JsString("(warn)")) => lineBasedProblem(o, Severity.Warn)
            case Some(id@_) => new GeneralProblem(s"Unknown type of error: $id with reason: ${getReason(o)}", source)
            case _ => new GeneralProblem(s"Malformed error with reason: ${getReason(o)}", source)
          }
        case x@_ => new GeneralProblem(s"Malformed result: $x", source)
      }
    }).flatten

    allProblems.foreach(p => reporter.log(p.position(), p.message(), p.severity()))
    reporter.printSummary()
    allProblems.find(_.severity() == Severity.Error).foreach(_ => throw new LintingFailedException(allProblems.toArray))
  }

}


class LintingFailedException(override val problems: Array[Problem])
  extends CompileFailed
  with FeedbackProvidedException {

  override val arguments: Array[String] = Array.empty
}

