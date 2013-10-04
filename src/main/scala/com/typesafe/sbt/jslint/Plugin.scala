package com.typesafe.sbt.jslint

import sbt.Keys._
import sbt._
import akka.actor.ActorRef
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import spray.json._
import scala.util.{Failure, Success}
import com.typesafe.sbt.jslint.JslintEngine.LintResult

/**
 * The sbt plugin plumbing around the JslintEngine
 */
object Plugin extends sbt.Plugin {

  // FIXME: These things should be defined outside of this plugin

  object WebdriverKeys {
    val JavaScript = config("js")
    val jsSource = SettingKey[File]("js-source", "The main source directory for JavaScript.")
  }

  import WebdriverKeys._

  def webdriverSettings = Seq(
    jsSource in JavaScript := (sourceDirectory in Compile).value / "js",
    includeFilter in JavaScript := GlobFilter("*.js"),
    sources in JavaScript <<= (jsSource in JavaScript, includeFilter in JavaScript, excludeFilter in JavaScript) map {
      (sourceDirectory, includeFilter, excludeFilter) => (sourceDirectory * (includeFilter -- excludeFilter)).get
    }
  )


  object JslintKeys {
    val engineState = TaskKey[ActorRef]("engine-state", "An actor representing the state of the jslint engine.")
    val jslint = TaskKey[Unit]("jslint", "Perform JavaScript linting.")
  }

  private val engineStateAttrKey = AttributeKey[ActorRef]("engine-state")

  override val globalSettings: Seq[Setting[_]] = Seq(
    onLoad in Global := (onLoad in Global).value andThen {
      state: State =>
        val engineState = JslintEngine.start()
        state.put(engineStateAttrKey, engineState)
    },

    onUnload in Global := (onUnload in Global).value andThen {
      state: State =>
        val engineState = state.get(engineStateAttrKey)
        engineState.foreach(JslintEngine.stop(_))
        state.remove(engineStateAttrKey)
    }
  )

  def jslintSettings = webdriverSettings ++ Seq(
    JslintKeys.engineState <<= engineStateTask,
    JslintKeys.jslint <<= jslintTask
  )

  def engineStateTask = (state) map (_.get(engineStateAttrKey).get)

  private def jslintTask = (JslintKeys.engineState, sources in JavaScript, streams) map {
    (engineState: ActorRef, sources: Seq[File], s: TaskStreams) =>
      s.log.info(s"JavaScript linting on ${sources.size} source(s)")

      val resultBatches: Seq[Future[Seq[LintResult]]] =
        try {
          // Declares the maximum number of parallel lints we wish to perform against the engine.
          // FIXME: This should be configurable in future particularly for situations where
          // engines have differing parallelism characteristics, and where the engines are
          // running on other machines.
          val parallelism = java.lang.Runtime.getRuntime.availableProcessors()
          val sourceBatches = (sources grouped Math.max(sources.size / parallelism, 1)).toSeq
          sourceBatches.map(lintForSources(engineState, _))
        }

      val completedResultBatches = Future.sequence(resultBatches)
      Await.ready(completedResultBatches, 10.seconds).onComplete {
        case Success(allResults) =>
          for {
            results <- allResults
            result <- results
            //if !result.success
          } {
            println(s">>>> Result = ${result.errors}")
            //logErrors(s.log, result.errors)
            // FIXME: How should I stop sbt if there are failures?
          }
        case Failure(t) => s.log.error(s"Failed linting: $t")
      }
  }

  /*
   * lints a sequence of sources and returns a future representing the results of all.
   */
  private def lintForSources(engineState: ActorRef, sources: Seq[File]): Future[Seq[LintResult]] = {
    JslintEngine.beginLint(engineState, JsObject()).flatMap[Seq[LintResult]] {
      lintState =>
        val results = sources.map {
          source =>
            val lintResult = JslintEngine.lint(lintState, source)
            lintResult.onComplete {
              case _ => JslintEngine.endLint(lintState)
            }
            lintResult
        }
        Future.sequence(results)
    }
  }

  private def logErrors(log: Logger, jslintResult: JsValue): Unit = {
    // FIXME: This needs writing.
    log.error(jslintResult.toString())
  }
}
