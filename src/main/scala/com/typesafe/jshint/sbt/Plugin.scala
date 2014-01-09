package com.typesafe.jshint.sbt

import sbt._
import sbt.Keys._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import com.typesafe.web.sbt.WebPlugin.WebKeys
import com.typesafe.jse.sbt.JsEnginePlugin.JsEngineKeys
import com.typesafe.jse.{Rhino, PhantomJs, Node, CommonNode, Trireme}
import com.typesafe.jshint.{JshintError, Jshinter}
import com.typesafe.web.sbt._
import xsbti.{CompileFailed, Severity, Problem}
import akka.util.Timeout
import scala.collection.immutable
import com.typesafe.web.sbt.incremental._
import scala.Some


/**
 * The sbt plugin plumbing around the JSHint library.
 */
object JSHintPlugin extends sbt.Plugin {

  object JshintKeys {

    val jshint = TaskKey[Unit]("jshint", "Perform JavaScript linting.")
    val jshintTest = TaskKey[Unit]("jshint-test", "Perform JavaScript linting for tests.")

    val jshintOptions = TaskKey[String]("jshint-options", "An array of jshint options to pass to the linter. This options are found via jshint-resolved-config. If there is no config then the options will be specified such that the JSHint defaults are used.")

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

  private def jshintOptionsTask(someConfig: Option[File]): String = {
    someConfig
      .map(config => IO.read(config))
      .getOrElse("{}")
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
                          jsSources: Seq[File],
                          jshintOptions: String,
                          engineType: EngineType.Value,
                          parallelism: Int,
                          s: TaskStreams,
                          reporter: LoggerReporter,
                          testing: Boolean
                          ): Unit = {

    import WebPlugin._

    val timeoutPerSource = 10.seconds

    val engineProps = engineType match {
      case EngineType.CommonNode => CommonNode.props()
      case EngineType.Node => Node.props(stdModulePaths = immutable.Seq(nodeModules.getCanonicalPath))
      case EngineType.PhantomJs => PhantomJs.props()
      case EngineType.Rhino => Rhino.props()
      case EngineType.Trireme => Trireme.props(stdModulePaths = immutable.Seq(nodeModules.getCanonicalPath))
    }

    implicit val opInputHasher =
      OpInputHasher[File](source => OpInputHash.hashString(source + "|" + jshintOptions))

    val problems: Seq[Problem] = incremental.runIncremental(s.cacheDirectory, jsSources) {
      modifiedJsSources: Seq[File] =>

        if (modifiedJsSources.size > 0) {

          val testKeyword = if (testing) "test " else ""
          s.log.info(s"JavaScript linting on ${modifiedJsSources.size} ${testKeyword}source(s)")

          val resultBatches: Seq[Future[Seq[(File, Seq[JshintError])]]] =
            try {
              val sourceBatches = (modifiedJsSources grouped Math.max(modifiedJsSources.size / parallelism, 1)).toSeq
              sourceBatches.map {
                sourceBatch =>
                  implicit val timeout = Timeout(timeoutPerSource * sourceBatch.size)
                  withActorRefFactory(state, this.getClass.getName) {
                    arf =>
                      val engine = arf.actorOf(engineProps)
                      val jshinter = new Jshinter(engine, shellSource)
                      jshinter.lint(sourceBatch, jshintOptions)
                  }
              }
            }

          val pendingResults = Future.sequence(resultBatches)
          val problemMappings: Map[File, Seq[Problem]] = (for {
            allResults <- Await.result(pendingResults, timeoutPerSource * modifiedJsSources.size)
            result <- allResults
          } yield {
            val source = result._1
            val errors = result._2
            val problems = errors.map {
              e =>
                val severity = e.id match {
                  case "(error)" => Some(Severity.Error)
                  case "(info)" => Some(Severity.Info)
                  case "(warn)" => Some(Severity.Warn)
                  case _ => None
                }
                severity match {
                  case Some(s) => new LineBasedProblem(e.reason, s, e.line, e.character - 1, e.evidence, source)
                  case _ => new GeneralProblem(s"Unknown type of error: $e.id with reason: ${e.reason}", source)
                }
            }
            (source, problems)
          }).toMap

          val results: Map[File, OpResult] = problemMappings.map {
            (entry) =>
              val source = entry._1
              val problems = entry._2
              val result = if (problems.isEmpty) OpSuccess(Set(source), Set.empty) else OpFailure
              source -> result
          }
          val problems = problemMappings.values.toSeq.flatten

          (results, problems)

        } else {
          (Map.empty, Seq.empty)
        }
    }

    CompileProblems.report(reporter, problems)

  }

}


class LintingFailedException(override val problems: Array[Problem])
  extends CompileFailed
  with FeedbackProvidedException {

  override val arguments: Array[String] = Array.empty
}

