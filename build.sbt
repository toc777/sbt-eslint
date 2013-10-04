sbtPlugin := true

organization := "com.typesafe"

name := "jslint-sbt-plugin"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.10.2"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.1",
  "io.spray" %% "spray-json" % "1.2.5",
  "com.typesafe" %% "webdriver" % "1.0.0-SNAPSHOT",
  "org.webjars" % "jslint" % "c657984cd7",
  "org.webjars" % "webjars-locator" % "0.5",
  "org.specs2" %% "specs2" % "2.2.2" % "test",
  "junit" % "junit" % "4.11" % "test"
)

scriptedSettings

scriptedLaunchOpts <+= version apply { v => s"-Dproject.version=$v" }
