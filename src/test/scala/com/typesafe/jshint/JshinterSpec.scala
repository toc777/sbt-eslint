package com.typesafe.jshint

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification
import akka.util.Timeout
import scala.concurrent.duration._
import org.specs2.time.NoTimeConversions
import spray.json.{JsBoolean, JsArray, JsObject}
import java.io.File
import scala.concurrent.{Await, Future}
import scala.util.Success
import com.typesafe.jse.Trireme
import akka.actor.ActorSystem
import scala.collection.immutable

@RunWith(classOf[JUnitRunner])
class JshinterSpec extends Specification with NoTimeConversions {

  def jshinterTester(implicit system: ActorSystem): Jshinter = {
    val shellSource = new File(this.getClass.getClassLoader.getResource("shell.js").toURI)
    val nodeModules = new File(this.getClass.getResource("node_modules").toURI)
    val engine = system.actorOf(Trireme.props(stdModulePaths = immutable.Seq(nodeModules.getCanonicalPath)), "engine")
    new Jshinter(engine, shellSource)
  }

  implicit val timeout = Timeout(15.seconds)

  sequential

  "the jshinter" should {
    "receive source with no options and find an error" in new TestActorSystem {
      val fileToLint = new File(this.getClass.getResource("some.js").toURI)
      val filesToLint = Seq(fileToLint)
      val options = JsObject()

      val futureResult: Future[JsArray] = jshinterTester.lint(filesToLint, options)

      Await.result(futureResult, timeout.duration)
      val Success(result: JsArray) = futureResult.value.get

      result.elements.size must_== 1
      result.elements(0).toString() must_== s"""["${fileToLint.getCanonicalPath}",[{"id":"(error)","raw":"Missing semicolon.","code":"W033","evidence":"var a = 1","line":1,"character":10,"scope":"(main)","reason":"Missing semicolon."}]]"""
    }

    "receive source and options and not find an error" in new TestActorSystem {
      val fileToLint = new File(this.getClass.getResource("some.js").toURI)
      val filesToLint = Seq(fileToLint)
      val options = JsObject("asi" -> JsBoolean(true))

      val futureResult: Future[JsArray] = jshinterTester.lint(filesToLint, options)

      Await.result(futureResult, timeout.duration)
      val Success(result: JsArray) = futureResult.value.get

      result.elements.size must_== 0
    }
  }
}
