import sbt._
import sbt.Keys._

import com.typesafe.sbt.web.SbtWebPlugin
import com.typesafe.sbt.jse.SbtJsTaskPlugin
import com.typesafe.sbt.jshint.SbtJSHintPlugin

object TestBuild extends Build {

  class TestLogger(target: File) extends Logger {
    def trace(t: => Throwable): Unit = {}

    def success(message: => String): Unit = {}

    def log(level: Level.Value, message: => String): Unit = {
      if (level == Level.Error) {
        if (message.contains("Expected an assignment or function call and instead saw an expression.")) {
          IO.touch(target / "expected-assignment-error")
        } else if (message.contains("Missing semicolon.")) {
          IO.touch(target / "missing-semi-error")
        }
      }
    }
  }

  class TestReporter(target: File) extends LoggerReporter(-1, new TestLogger(target))

  lazy val root = Project(
    id = "test-build",
    base = file("."),
    settings =
      Project.defaultSettings ++
        SbtWebPlugin.webSettings ++
        SbtJsTaskPlugin.jsEngineAndTaskSettings ++
        SbtJSHintPlugin.jshintSettings ++
        Seq(
          SbtWebPlugin.WebKeys.reporter := new TestReporter(target.value)
        )
  )

}