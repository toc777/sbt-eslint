package com.sc.sbt.eslint

import sbt._
import sbt.Keys._
import sbt.File
import scala.Some
import com.typesafe.sbt.jse.SbtJsTask
import com.typesafe.sbt.web.SbtWeb

object Import {

  object EslintKeys {
    val eslint = TaskKey[Seq[File]]("eslint", "Perform JavaScript linting.")
    val config = TaskKey[Option[File]]("eslint-config")
  }

}

object SbtESLint extends AutoPlugin {

  override def requires = SbtJsTask

  override def trigger = AllRequirements

  val autoImport = Import
  
  private val NodeModules = "node_modules"

  import SbtWeb.autoImport._
  import WebKeys._
  import SbtJsTask.autoImport.JsTaskKeys._
  import autoImport.EslintKeys._

  override def projectSettings = Seq(
    config := Some(baseDirectory.value / ".eslintrc")
  ) ++ inTask(eslint)(
    SbtJsTask.jsTaskSpecificUnscopedSettings ++ Seq(
      moduleName := "eslint",
      shellFile := getClass.getClassLoader.getResource("eslint-shell.js"),
      includeFilter in Assets := (jsFilter in Assets).value,
      includeFilter in TestAssets := (jsFilter in TestAssets).value,

      jsOptions := config.value.fold("{}")(IO.read(_)),

      taskMessage in Assets := "JavaScript linting",
      taskMessage in TestAssets := "JavaScript test linting"
    )
  ) ++ SbtJsTask.addJsSourceFileTasks(eslint) ++ Seq(
    eslint in Assets := (eslint in Assets).dependsOn(nodeModules in Assets).value,
    eslint in TestAssets := (eslint in TestAssets).dependsOn(nodeModules in TestAssets).value,
    nodeModuleDirectories in Plugin += baseDirectory.value / NodeModules
  )

}
