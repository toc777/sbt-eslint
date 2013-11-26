import sbt._

import com.typesafe.jshint.sbt.JSHintPlugin
import com.typesafe.js.sbt.WebPlugin.WebKeys
import xsbti.Severity

object TestBuild extends Build {

  object TestLogger extends Logger {
    val messages = new StringBuilder()

    def trace(t: => Throwable): Unit = {}

    def success(message: => String): Unit = {}

    def log(level: Level.Value, message: => String): Unit = messages ++= message
  }

  object TestReporter extends LoggerReporter(-1, TestLogger) {
    override def hasErrors(): Boolean = false
  }

  lazy val root = Project(
    id = "test-build",
    base = file("."),
    settings =
      Project.defaultSettings ++
        JSHintPlugin.jshintSettings ++
        Seq(
          WebKeys.reporter := TestReporter,
          TaskKey[Unit]("check") := {
            val errorCount = TestReporter.count.get(Severity.Error)
            if (errorCount != 2) {
              sys.error(s"$errorCount linting errors received when 2 were expected.")
            }
            val messages = TestLogger.messages.toString()
            if (
              !messages.contains("Expected an assignment or function call and instead saw an expression.") &&
                !messages.contains("Missing semicolon.")
            ) {
              sys.error(messages)
            }
          }
        )
  )

}