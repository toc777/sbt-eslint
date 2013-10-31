package com.typesafe.jslint

import akka.actor.{PoisonPill, ActorRef}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.ExecutionContext.Implicits.global

import com.typesafe.webdriver.{Session, LocalBrowser}
import java.io._
import scala.concurrent.Future
import spray.json._
import org.webjars.WebJarAssetLocator
import scala.collection.mutable.ListBuffer
import scala.StringBuilder
import scala.annotation.tailrec

/**
 * This is the main service that performs the linting functionality of the plugin.  Linting is performed
 * using the webdriver service. There is no dependency on sbt and so there is the potential for this to be factored
 * out into its own library and used with other build tools such as Gradle or Maven.
 */
class Jslinter(jslintSourceStream: InputStream) {

  private val jslintSource = buildFromString(jslintSourceStream, new StringBuilder) {
    (builder, s) =>
      builder.append(s)
      builder.append('\n')
  }.toString()

  /**
   * Declare the start of a sequence of linting to be done.
   * @param browser the browser handling our requests.
   * @return a future of a session to be used for the linting.
   */
  def beginLint(browser: ActorRef)(implicit timeout: Timeout): Future[ActorRef] = {
    (browser ? LocalBrowser.CreateSession).mapTo[ActorRef]
  }

  /**
   * Perform Jslint on a file
   * @param session the linting state.
   * @param fileToLint the file to lint.
   * @param options The Jslint options as a JSONObject structure.
   * @return a Json array of objects structure as per JSLINT.errors i.e.:
   *         line      : The line (relative to 0) at which the lint was found
   *         character : The character (relative to 0) at which the lint was found
   *         reason    : The problem
   *         evidence  : The text line in which the problem occurred
   *         raw       : The raw message before the details were inserted
   *         a         : The first detail
   *         b         : The second detail
   *         c         : The third detail
   *         d         : The fourth detail
   */
  def lint(session: ActorRef, fileToLint: File, options: JsObject)(implicit timeout: Timeout): Future[JsArray] = {
    val targetJs = s"""|$jslintSource
                       |JSLINT(arguments[0], arguments[1]);
                       |var result = JSLINT.errors;
                       |""".stripMargin

    val fileToLintAsJsArray = JsArray(
      buildFromString(new FileInputStream(fileToLint), new ListBuffer[JsValue]) {
        (builder: ListBuffer[JsValue], s) => builder.append(JsString(s))
      }.toList
    )

    (session ? Session.ExecuteJs(targetJs, JsArray(fileToLintAsJsArray, options))).mapTo[JsArray]
  }

  /**
   * Declaring the end of linting and clean up.
   * @param session the linting session.
   */
  def endLint(session: ActorRef): Unit = {
    session ! PoisonPill
  }

  /*
  * Convert a stream to a container of strings.
  */
  private def buildFromString[T](stream: InputStream, builder: T)(build: (T, String) => Unit): T = {
    val in = new BufferedReader(new InputStreamReader(stream))
    try {
      @tailrec
      def consumeLines(): Unit = {
        in.readLine() match {
          case null =>
          case line =>
            build(builder, line)
            consumeLines()
        }
      }
      consumeLines()
    } finally {
      in.close()
    }
    builder
  }
}

object Jslinter {

  def apply(): Jslinter = {
    val jslintSourceStream = Jslinter.getClass.getClassLoader.getResourceAsStream(
      new WebJarAssetLocator().getFullPath("jslint.js")
    )
    new Jslinter(jslintSourceStream)
  }
}