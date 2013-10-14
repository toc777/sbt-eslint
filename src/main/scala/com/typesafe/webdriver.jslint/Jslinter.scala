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
import scala.Some

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
   * @param engineState the engine state.
   * @return a future of a state to be used for the linting.
   */
  def beginLint(engineState: ActorRef)(implicit timeout: Timeout): Future[ActorRef] = {
    (engineState ? LocalBrowser.CreateSession).mapTo[ActorRef]
  }

  /**
   * Perform Jslint on a file
   * @param lintState the linting state.
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
  def lint(lintState: ActorRef, fileToLint: File, options: JsObject)(implicit timeout: Timeout): Future[JsArray] = {
    val targetJs = s"""|$jslintSource
                       |JSLINT(arguments[0], arguments[1]);
                       |return JSLINT.errors;
                       |""".stripMargin

    val fileToLintAsJsArray = JsArray(
      buildFromString(new FileInputStream(fileToLint), new ListBuffer[JsValue]) {
        (builder: ListBuffer[JsValue], s) => builder.append(JsString(s))
      }.toList
    )

    (lintState ? Session.ExecuteJs(targetJs, JsArray(fileToLintAsJsArray, options))).mapTo[JsArray]
  }

  /**
   * Declaring the end of linting and clean up.
   * @param lintState the linting state.
   */
  def endLint(lintState: ActorRef): Unit = {
    lintState ! PoisonPill
  }

  /*
  * Convert a stream to a container of strings.
  */
  private def buildFromString[T](stream: InputStream, builder: T)(build: (T, String) => Unit): T = {
    val in = new BufferedReader(new InputStreamReader(stream))
    try {
      import scala.util.control.Breaks._
      breakable {
        while (true) {
          val maybeLine = Option(in.readLine())
          maybeLine match {
            case Some(line) => build(builder, line)
            case None => break()
          }
        }
      }
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