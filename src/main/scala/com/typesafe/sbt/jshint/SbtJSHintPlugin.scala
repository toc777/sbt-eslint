package com.typesafe.sbt.jshint

import sbt._
import sbt.Keys._
import com.typesafe.sbt.web.SbtWebPlugin._
import com.typesafe.sbt.web.incremental._
import sbt.File
import scala.Some
import com.typesafe.sbt.jse.SbtJsTaskPlugin

/**
 * The sbt plugin plumbing around the JSHint library.
 */
object SbtJSHintPlugin extends SbtJsTaskPlugin {

  object JshintKeys {

    val jshint = TaskKey[Int]("jshint", "Perform JavaScript linting.")

    val jshintOptions = TaskKey[String]("jshint-options", "An array of jshint options to pass to the linter. This options are found via jshint-resolved-config. If there is no config then the options will be specified such that the JSHint defaults are used.")

    val config = SettingKey[Option[File]]("jshint-config", "The location of a JSHint configuration file.")
    val resolvedConfig = TaskKey[Option[File]]("jshint-resolved-config", "The actual location of a JSHint configuration file if present. If jshint-config is none then the task will seek a .jshintrc in the project folder. If that's not found then .jshintrc will be searched for in the user's home folder. This behaviour is consistent with other JSHint tooling.")

  }

  import WebKeys._
  import SbtJsTaskPlugin.JsTaskKeys._
  import JshintKeys._

  def jshintSettings = inTask(jshint)(jsTaskSpecificUnscopedSettings) ++ Seq(

    shellFile in jshint := "jshint-shell.js",
    fileInputHasher in jshint := OpInputHasher[File](source => OpInputHash.hashString(source + "|" + jshintOptions.value)),

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
    jshintOptions := {
      resolvedConfig.value
        .map(IO.read(_))
        .getOrElse("{}"): String
    },

    jshint in Assets := jsTask(jshint, Assets, jsFilter, jshintOptions, fileInputHasher, "JavaScript linting").value,
    jshint in TestAssets := jsTask(jshint, TestAssets, jsFilter, jshintOptions, fileInputHasher, "JavaScript test linting").value,
    jshint := (jshint in Assets).value,

    jsTasks in Assets <+= (jshint in Assets),
    jsTasks in TestAssets <+= (jshint in TestAssets)

  )

}
