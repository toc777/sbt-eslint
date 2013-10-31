package com.typesafe.webdriver.jslint

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification
import org.webjars.WebJarAssetLocator
import com.typesafe.jslint.Jslinter
import akka.util.Timeout
import scala.concurrent.duration._
import org.specs2.time.NoTimeConversions
import spray.json.{JsString, JsArray, JsObject}
import java.io.File
import akka.testkit.TestActorRef
import akka.actor.Actor
import com.typesafe.webdriver.Session
import scala.concurrent.Future
import scala.util.Success

@RunWith(classOf[JUnitRunner])
class JslinterSpec extends Specification with NoTimeConversions {

  val jslintSourceStream = Jslinter.getClass.getClassLoader.getResourceAsStream(
    new WebJarAssetLocator().getFullPath("jslint.js")
  )

  "the jslinter" should {
    "receive source and options" in new TestActorSystem {
      implicit val timeout = Timeout(5.seconds)
      val jslinter = new Jslinter(jslintSourceStream)

      val lintState = TestActorRef[StubbedSessionActor]
      val fileToLint = new File(this.getClass.getResource("some.js").toURI)
      val options = JsObject()

      val futureResult: Future[JsArray] = jslinter.lint(lintState, fileToLint, options)

      val Success(result: JsArray) = futureResult.value.get

      result.elements.size must_== 2
      result.elements(0).toString() must_== """[["// Nothing to see here.","// ...or here."],{}]"""
      result.elements(1).toString() must startWith( """"// jslint.js""")
    }
  }
}

class StubbedSessionActor extends Actor {
  def receive = {
    case ejs: Session.ExecuteJs => sender ! JsArray(List(ejs.args, JsString(ejs.script)))
  }
}
