
resolvers += Resolver.typesafeRepo("releases")

addSbtJsEngine("1.2.2")

organization := "com.sc.sbt"

lazy val root = (project in file(".")).
  settings(
    sbtPlugin := true,
    name := "sbt-eslint",
    description := "Run eslint on js assets",
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    publishMavenStyle := false,
    bintrayRepository := "sbt-plugins"
  )
