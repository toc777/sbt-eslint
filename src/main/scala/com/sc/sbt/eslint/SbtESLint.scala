package com.sc.sbt.eslint

import sbt._
import sbt.Keys._
import sbt.File
import com.typesafe.sbt.jse.SbtJsTask
import com.typesafe.sbt.web.SbtWeb

object Import {

  object EslintKeys {

    val eslint = TaskKey[Seq[File]]("eslint", "Perform JavaScript linting.")

    val config = SettingKey[Option[File]]("eslint-config", "The location of a ESLint configuration file.")
    val resolvedConfig = TaskKey[Option[File]]("eslint-resolved-config", "The actual location of a ESLint configuration file if present. If eslint-config is none then the task will seek a .eslintrc in the project folder. If that's not found then .eslintrc will be searched for in the user's home folder. This behaviour is consistent with other ESLint tooling.")

  }

}

object SbtESLint extends AutoPlugin {

  override def requires = SbtJsTask

  override def trigger = AllRequirements

  val autoImport = Import

  import SbtWeb.autoImport._
  import WebKeys._
  import SbtJsTask.autoImport.JsTaskKeys._
  import autoImport.EslintKeys._

  override def buildSettings = inTask(eslint)(
    SbtJsTask.jsTaskSpecificUnscopedBuildSettings ++ Seq(
      moduleName := "eslint",
      shellFile := getClass.getClassLoader.getResource("eslint-shell.js")
    )
  )

  override def projectSettings = Seq(
    config := None,
    resolvedConfig := {
      config.value.orElse {
        val ESLintRc = ".eslintrc"
        val projectRc = baseDirectory.value / ESLintRc
        if (projectRc.exists()) {
          Some(projectRc)
        } else {
          val homeRc = file(System.getProperty("user.home")) / ESLintRc
          if (homeRc.exists()) {
            Some(homeRc)
          } else {
            None
          }
        }
      }: Option[File]
    }
  ) ++ inTask(eslint)(
    SbtJsTask.jsTaskSpecificUnscopedProjectSettings ++ Seq(
      includeFilter in Assets := (jsFilter in Assets).value,
      includeFilter in TestAssets := (jsFilter in TestAssets).value,

      jsOptions := resolvedConfig.value.fold("{}")(IO.read(_)),

      taskMessage in Assets := "JavaScript linting",
      taskMessage in TestAssets := "JavaScript test linting"

    )
  ) ++ SbtJsTask.addJsSourceFileTasks(eslint) ++ Seq(
    eslint in Assets := (eslint in Assets).dependsOn(nodeModules in Assets).value,
    eslint in TestAssets := (eslint in TestAssets).dependsOn(nodeModules in TestAssets).value
  )

}
