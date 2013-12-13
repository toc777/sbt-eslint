resolvers ++= Seq(
    Resolver.url("sbt snapshot plugins", url("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots"))(Resolver.ivyStylePatterns),
    Resolver.sonatypeRepo("snapshots"),
    "Typesafe Snapshots Repository" at "http://repo.typesafe.com/typesafe/snapshots/",
    "Spray Releases" at "http://repo.spray.io/"
    )

addSbtPlugin("com.typesafe" % "sbt-jshint-plugin" % sys.props("project.version"))