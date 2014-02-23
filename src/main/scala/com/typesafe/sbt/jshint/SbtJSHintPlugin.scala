package com.typesafe.sbt.jshint

import sbt._
import sbt.Keys._
import com.typesafe.sbt.web.SbtWebPlugin._
import com.typesafe.sbt.web.incremental._
import sbt.File
import scala.Some
import com.typesafe.sbt.jse.SbtJsTaskPlugin
import com.typesafe.sbt.web.PathMappings

/**
 * The sbt plugin plumbing around the JSHint library.
 */
object SbtJSHintPlugin extends SbtJsTaskPlugin {

  object JshintKeys {

    val jshint = TaskKey[PathMappings]("jshint", "Perform JavaScript linting.")

    val config = SettingKey[Option[File]]("jshint-config", "The location of a JSHint configuration file.")
    val resolvedConfig = TaskKey[Option[File]]("jshint-resolved-config", "The actual location of a JSHint configuration file if present. If jshint-config is none then the task will seek a .jshintrc in the project folder. If that's not found then .jshintrc will be searched for in the user's home folder. This behaviour is consistent with other JSHint tooling.")

  }

  import WebKeys._
  import SbtJsTaskPlugin.JsTaskKeys._
  import JshintKeys._

  val jshintSettings = inTask(jshint)(

    jsTaskSpecificUnscopedSettings ++ Seq(
      shellFile := "jshint-shell.js",
      fileFilter in Assets := (jsFilter in Assets).value,
      fileFilter in TestAssets := (jsFilter in TestAssets).value,

      config := None,
      resolvedConfig := {
        config.value.orElse {
          val JsHintRc = ".jshintrc"
          val projectRc = baseDirectory.value / JsHintRc
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
        }: Option[File]
      },
      jsOptions := {
        resolvedConfig.value
          .map(IO.read(_))
          .getOrElse("{}"): String
      },

      fileInputHasher := OpInputHasher[File](source => OpInputHash.hashString(source + "|" + (jsOptions in jshint).value)),

      taskMessage in Assets := "JavaScript linting",
      taskMessage in TestAssets := "JavaScript test linting"

    )
  ) ++ addJsSourceFileTasks(jshint)

}
