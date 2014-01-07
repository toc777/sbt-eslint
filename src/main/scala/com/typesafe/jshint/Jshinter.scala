package com.typesafe.jshint

import java.io._
import akka.util.Timeout
import scala.concurrent.{ExecutionContext, Future}
import spray.json._
import akka.actor.ActorRef
import akka.pattern.ask
import com.typesafe.jse.Engine
import com.typesafe.jse.Engine.JsExecutionResult
import scala.collection.immutable

/**
 * An error representing an element of JSHINT.errors
 * @param id         The line identifier which includes the severity.
 * @param line       The line (relative to 0) at which the lint was found
 * @param character  The character (relative to 0) at which the lint was found
 * @param reason     The problem
 * @param evidence   The text line in which the problem occurred
 * @param raw        The raw message before the details were inserted
 * @param a          The first detail
 * @param b          The second detail
 * @param c          The third detail
 * @param d          The fourth detail
 */
case class JshintError(
                        id: String,
                        line: Int,
                        character: Int,
                        reason: String,
                        evidence: String,
                        raw: String,
                        a: Option[String],
                        b: Option[String],
                        c: Option[String],
                        d: Option[String]
                        )

/**
 * For automatic transformation of Json structures.
 */
object JsHintProtocol extends DefaultJsonProtocol {
  implicit val jshintErrorFormat = jsonFormat10(JshintError)

  implicit object FileFormat extends RootJsonFormat[File] {
    def write(f: File) = JsString(f.getCanonicalPath)

    def read(value: JsValue) = value match {
      case s: JsString => new File(s.convertTo[String])
      case _ => deserializationError("String expected")
    }
  }

}

/**
 * This is the main service that performs the linting functionality of the plugin.  Linting is performed
 * using the js-engine service. There is no dependency on sbt and so there is the potential for this to be factored
 * out into its own library and used with other build tools such as Gradle or Maven.
 * @param engine the JS engine actor properties.
 * @param shellSource the location of js that will process the arguments provided and output results.
 **/
class Jshinter(engine: ActorRef, shellSource: File) {

  /**
   * Perform Jshint on a sequence of files
   * @param filesToLint the file to lint.
   * @param options The Jshint options as a Json structure as per JSHint's .jshintrc format.
   * @return a sequence of errors as transformed from JSHINT.errors.
   */
  def lint(filesToLint: Seq[File], options: String)(implicit timeout: Timeout): Future[Seq[(File, Seq[JshintError])]] = {

    import ExecutionContext.Implicits.global

    val args = immutable.Seq(
      JsArray(filesToLint.map(x => JsString(x.getCanonicalPath)).toList).toString(),
      options
    )

    (engine ? Engine.ExecuteJs(
      shellSource,
      args
    )).mapTo[JsExecutionResult].map {
      result =>
        if (result.exitValue != 0) {
          throw new JshinterFailure(new String(result.error.toArray, "UTF-8"))
        }

        val p = JsonParser(new String(result.output.toArray, "UTF-8"))
        import JsHintProtocol._
        p.convertTo[Seq[(File, Seq[JshintError])]]
    }
  }
}

/**
 * Thrown when there is an unexpected problem to do with JSHint's execution.
 */
class JshinterFailure(m: String) extends RuntimeException(m)