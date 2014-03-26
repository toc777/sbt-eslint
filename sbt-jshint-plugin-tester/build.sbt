import com.typesafe.sbt.jse.SbtJsEnginePlugin._
import com.typesafe.sbt.web.SbtWebPlugin

lazy val root = project.in(file(".")).addPlugins(SbtWebPlugin)

//JsEngineKeys.engineType := JsEngineKeys.EngineType.Node
