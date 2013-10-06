package com.typesafe.jslint

import akka.actor.{PoisonPill, ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import com.typesafe.webdriver.{Session, LocalBrowser, PhantomJs}
import java.io._
import scala.concurrent.Future
import spray.json._
import org.webjars.WebJarAssetLocator
import scala.collection.mutable.ListBuffer
import scala.StringBuilder
import scala.Some

/**
 * This is the main service that performs the linting functionality of the plugin. The service holds the notion of
 * a lifecycle and will start and stop the related webdriver service accordingly. Linting is of course performed
 * using the webdriver service. There is no dependency on sbt and so there is the potential for this to be factored
 * out into its own library and used with other build tools such as Gradle or Maven.
 */
class JslintEngine(system: ActorSystem, timeout: Timeout, jslintSourceStream: InputStream) {

  implicit private val implicitSystem = system
  implicit private val implicitTimeout = timeout

  private val jslintSource = buildFromString(jslintSourceStream, new StringBuilder) {
    (builder, s) =>
      builder.append(s)
      builder.append('\n')
  }.toString()

  /**
   * Start the engine - a potentially costly exercise that should be minimised.
   * @return The engine's state.
   */
  def start(): ActorRef = {
    val browser = system.actorOf(PhantomJs.props(), "localBrowser")
    browser ! LocalBrowser.Startup
    browser
  }

  /**
   * Declare the start of a sequence of linting to be done.
   * @param engineState the engine state.
   * @return a future of a state to be used for the linting.
   */
  def beginLint(engineState: ActorRef): Future[ActorRef] = {
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
  def lint(lintState: ActorRef, fileToLint: File, options: JsObject): Future[JsArray] = {
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

  /**
   * Stop the engine.
   * @param engineState the engine's state.
   */
  def stop(engineState: ActorRef): Unit = {
    engineState ! PoisonPill
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

object JslintEngine {

  def apply(): JslintEngine = {
    val jslintSourceStream = JslintEngine.getClass.getClassLoader.getResourceAsStream(
      new WebJarAssetLocator().getFullPath("jslint.js")
    )
    new JslintEngine(
      withActorClassloader(ActorSystem("webdriver-system")),
      Timeout(5.seconds),
      jslintSourceStream
    )
  }

  /*
   * Sometimes the class loader associated with the actor system is required e.g. when loading configuration.
   */
  private def withActorClassloader[A](f: => A): A = {
    val newLoader = ActorSystem.getClass.getClassLoader
    val thread = Thread.currentThread
    val oldLoader = thread.getContextClassLoader

    thread.setContextClassLoader(newLoader)
    try {
      f
    } finally {
      thread.setContextClassLoader(oldLoader)
    }
  }
}