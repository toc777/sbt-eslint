sbtPlugin := true

organization := "com.sc.sbt"

name := "sbt-eslint"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.10.4"

resolvers += Resolver.typesafeRepo("releases")

addSbtPlugin("com.typesafe.sbt" % "sbt-js-engine" % "1.1.3")

scriptedSettings

scriptedLaunchOpts <+= version apply { v => s"-Dproject.version=$v" }
