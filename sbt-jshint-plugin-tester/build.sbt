import com.typesafe.sbt.jse.SbtJsEnginePlugin._
import com.typesafe.sbt.web.SbtWeb

lazy val root = project.in(file(".")).addPlugins(SbtWeb)

//JsEngineKeys.engineType := JsEngineKeys.EngineType.Node
