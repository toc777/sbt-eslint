package com.typesafe.sbt.jslint

import akka.actor.{PoisonPill, ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import com.typesafe.webdriver.{Session, LocalBrowser, PhantomJs}
import java.io.File
import scala.concurrent.Future
import spray.json.{JsValue, JsObject, JsNumber, JsArray}

/**
 * This is the main service that performs the linting functionality of the plugin. The service holds the notion of
 * a lifecycle and will start and stop the related webdriver service accordingly. Linting is of course performed
 * using the webdriver service. There is no dependency on sbt and so there is the potential for this to be factored
 * out into its own library and used with other build tools such as Gradle or Maven.
 */
object JslintEngine {

  implicit lazy val system = withActorClassloader(ActorSystem("webdriver-system"))
  implicit lazy val timeout = Timeout(5.seconds)

  /**
   * Describes the results of a jslint execution.
   * @param success if the linting was successful overall
   * @param errors if unsuccessful then it reports the errors.
   */
  case class LintResult(val success: Boolean, errors: JsValue)

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
   * @param options The Jslint options as a JSONObject structure.
   * @return a future of a state to be used for the linting.
   */
  def beginLint(engineState: ActorRef, options: JsObject): Future[ActorRef] = {
    (engineState ? LocalBrowser.CreateSession).mapTo[ActorRef]
  }

  /**
   * Perform Jslint on a file
   * @param lintState the linting state.
   * @param file the file to lint.
   * @return a tuple of a boolean indicating success (true) and a Json object structure as per JSLINT.errors
   *         if the boolean is false.
   */
  def lint(lintState: ActorRef, file: File): Future[LintResult] = {
    // FIXME: Just to demo things are working...
    for {
      result <- (lintState ? Session.ExecuteJs("return arguments[0]", JsArray(JsNumber(999)))).mapTo[JsNumber]
    } yield LintResult(true, result)
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
