package com.typesafe.jshint

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification
import akka.util.Timeout
import scala.concurrent.duration._
import org.specs2.time.NoTimeConversions
import spray.json.{JsArray, JsObject}
import java.io.File
import scala.concurrent.{Await, Future}
import scala.util.Success
import scala.collection.immutable
import com.typesafe.jse.Rhino

@RunWith(classOf[JUnitRunner])
class JshinterSpec extends Specification with NoTimeConversions {


  "the jshinter" should {
    "receive source and options" in new TestActorSystem {
      val shellSource = new File(this.getClass.getClassLoader.getResource("shell.js").toURI)
      val jshintSource = new File(this.getClass.getClassLoader.getResource("jshint.js").toURI)

      implicit val timeout = Timeout(5.seconds)
      val engine = system.actorOf(Rhino.props(), "engine")
      val jshinter = Jshinter(engine, shellSource, jshintSource)

      val fileToLint = new File(this.getClass.getResource("some.js").toURI)
      val filesToLint = immutable.Seq(fileToLint)
      val options = JsObject()

      val futureResult: Future[JsArray] = jshinter.lint(filesToLint, options)

      Await.result(futureResult, timeout.duration)
      val Success(result: JsArray) = futureResult.value.get

      result.elements.size must_== 1
      result.elements(0).toString() must_== s"""["${fileToLint.getCanonicalPath}",[{"id":"(error)","raw":"Missing semicolon.","code":"W033","evidence":"var a = 1","line":1,"character":10,"scope":"(main)","reason":"Missing semicolon."}]]"""
    }
  }
}
