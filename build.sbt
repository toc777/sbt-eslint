sbtPlugin := true

organization := "com.typesafe"

name := "sbt-jshint-plugin"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.10.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3",
  "io.spray" %% "spray-json" % "1.2.5",
  "org.webjars" % "jshint" % "2.3.0",
  "org.webjars" % "webjars-locator" % "0.6",
  "org.specs2" %% "specs2" % "2.2.2" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3" % "test"
)

addSbtPlugin("com.typesafe" %% "sbt-js-engine" % "1.0.0-SNAPSHOT")

scriptedSettings

scriptedLaunchOpts <+= version apply { v => s"-Dproject.version=$v" }
