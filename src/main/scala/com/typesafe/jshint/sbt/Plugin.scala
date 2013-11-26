package com.typesafe.jshint.sbt

import sbt._
import sbt.Keys._
import akka.actor.Props
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import spray.json._
import xsbti.{Maybe, Position, Severity}
import java.lang.RuntimeException
import com.typesafe.js.sbt.WebPlugin.WebKeys
import com.typesafe.jse.sbt.JsEnginePlugin.JsEngineKeys
import com.typesafe.jse.sbt.JsEnginePlugin
import scala.Some
import com.typesafe.jse.{Rhino, PhantomJs, Node, CommonNode}
import scala.collection.immutable
import com.typesafe.jshint.Jshinter


/**
 * The sbt plugin plumbing around the JSHint library.
 */
object JSHintPlugin extends sbt.Plugin {

  object JshintKeys {

    object QuotMark extends Enumeration {
      val Off = Value("true")
      val Single, Double = Value
    }

    val jshint = TaskKey[Unit]("jshint", "Perform JavaScript linting.")
    val jshintTest = TaskKey[Unit]("jshint-test", "Perform JavaScript linting for tests.")

    val jshintOptions = TaskKey[JsObject]("jshint-options", "An array of jshint options to pass to the linter.")

    // http://www.jshint.com/docs/options/#enforcing-options

    val bitwise = SettingKey[Option[Boolean]]("jshint-bitwise", "This option prohibits the use of bitwise operators such as ^ (XOR), | (OR) and others. Bitwise operators are very rare in JavaScript programs and quite often & is simply a mistyped &&.")
    val camelcase = SettingKey[Option[Boolean]]("jshint-camelcase", "This option allows you to force all variable names to use either camelCase style or UPPER_CASE with underscores.")
    val curly = SettingKey[Option[Boolean]]("jshint-curly", "This option requires you to always put curly braces around blocks in loops and conditionals.")
    val eqeqeq = SettingKey[Option[Boolean]]("jshint-eqeqeq", "This option prohibits the use of == and != in favor of === and !==.")
    val es3 = SettingKey[Option[Boolean]]("jshint-es3", "This option tells JSHint that your code needs to adhere to ECMAScript 3 specification.")
    val forin = SettingKey[Option[Boolean]]("jshint-forin", "This option requires all for in loops to filter object's items.")
    val freeze = SettingKey[Option[Boolean]]("jshint-freeze", "This options prohibits overwriting prototypes of native objects such as Array, Date and so on.")
    val immed = SettingKey[Option[Boolean]]("jshint-immed", "This option prohibits the use of immediate function invocations without wrapping them in parentheses.")
    val indent = SettingKey[Option[Boolean]]("jshint-indent", "This option enforces specific tab width for your code.")
    val latedef = SettingKey[Option[Boolean]]("jshint-latedef", "This option prohibits the use of a variable before it was defined.")
    val newcap = SettingKey[Option[Boolean]]("jshint-newcap", "This option requires you to capitalize names of constructor functions.")
    val noarg = SettingKey[Option[Boolean]]("jshint-noarg", "This option prohibits the use of arguments.caller and arguments.callee.")
    val noempty = SettingKey[Option[Boolean]]("jshint-noempty", "This option warns when you have an empty block in your code.")
    val nonew = SettingKey[Option[Boolean]]("jshint-nonew", "This option prohibits the use of constructor functions for side-effects.")
    val plusplus = SettingKey[Option[Boolean]]("jshint-plusplus", "This option prohibits the use of unary increment and decrement operators.")
    val quotmark = SettingKey[Option[QuotMark.Value]]("jshint-quotmark", "This option enforces the consistency of quotation marks used throughout your code.")
    val undef = SettingKey[Option[Boolean]]("jshint-undef", "This option prohibits the use of explicitly undeclared variables.")
    val unused = SettingKey[Option[Boolean]]("jshint-unused", "This option warns when you define and never use your variables.")
    val strict = SettingKey[Option[Boolean]]("jshint-strict", "This option requires all functions to run in ECMAScript 5's strict mode.")
    val trailing = SettingKey[Option[Boolean]]("jshint-trailing", "This option makes it an error to leave a trailing whitespace in your code.")
    val maxparams = SettingKey[Option[Int]]("jshint-maxparams", "This option lets you set the max number of formal parameters allowed per function.")
    val maxdepth = SettingKey[Option[Int]]("jshint-maxdepth", "This option lets you control how nested do you want your blocks to be.")
    val maxstatements = SettingKey[Option[Int]]("jshint-maxstatements", "This option lets you set the max number of statements allowed per function.")
    val maxcomplexity = SettingKey[Option[Int]]("jshint-maxstatements", "This option lets you control cyclomatic complexity throughout your code.")
    val maxlen = SettingKey[Option[Int]]("jshint-maxlen", "This option lets you set the maximum length of a line.")

    // http://www.jshint.com/docs/options/#relaxing-options

    val asi = SettingKey[Option[Boolean]]("jshint-asi", "This option suppresses warnings about missing semicolons.")
    val boss = SettingKey[Option[Boolean]]("jshint-boss", "This option suppresses warnings about the use of assignments in cases where comparisons are expected.")
    val debug = SettingKey[Option[Boolean]]("jshint-debug", "This option suppresses warnings about the debugger statements in your code.")
    val eqnull = SettingKey[Option[Boolean]]("jshint-eqnull", "This option suppresses warnings about == null comparisons.")
    val esnext = SettingKey[Option[Boolean]]("jshint-esnext", "This option tells JSHint that your code uses ECMAScript 6 specific syntax.")
    val evil = SettingKey[Option[Boolean]]("jshint-evil", "This option suppresses warnings about the use of eval.")
    val expr = SettingKey[Option[Boolean]]("jshint-expr", "This option suppresses warnings about the use of expressions where normally you would expect to see assignments or function calls.")
    val funcscope = SettingKey[Option[Boolean]]("jshint-funcscope", "This option suppresses warnings about declaring variables inside of control structures while accessing them later from the outside.")
    val globalstrict = SettingKey[Option[Boolean]]("jshint-globalstrict", "This option suppresses warnings about the use of global strict mode. Global strict mode can break third-party widgets so it is not recommended.")
    val iterator = SettingKey[Option[Boolean]]("jshint-iterator", "This option suppresses warnings about the __iterator__ property.")
    val lastsemic = SettingKey[Option[Boolean]]("jshint-lastsemic", "This option suppresses warnings about missing semicolons, but only when the semicolon is omitted for the last statement in a one-line block.")
    val laxbreak = SettingKey[Option[Boolean]]("jshint-laxbreak", "This option suppresses most of the warnings about possibly unsafe line breakings in your code.")
    val laxcomma = SettingKey[Option[Boolean]]("jshint-laxcomma", "This option suppresses warnings about comma-first coding style.")
    val loopfunc = SettingKey[Option[Boolean]]("jshint-loopfunc", "This option suppresses warnings about functions inside of loops.")
    val moz = SettingKey[Option[Boolean]]("jshint-moz", "This options tells JSHint that your code uses Mozilla JavaScript extensions.")
    val multistr = SettingKey[Option[Boolean]]("jshint-multistr", "This option suppresses warnings about multi-line strings.")
    val notypeof = SettingKey[Option[Boolean]]("jshint-notypeof", "This option suppresses warnings about invalid typeof operator values.")
    val proto = SettingKey[Option[Boolean]]("jshint-proto", "This option suppresses warnings about the __proto__ property.")
    val scripturl = SettingKey[Option[Boolean]]("jshint-scripturl", "This option suppresses warnings about the use of script-targeted URLs—such as javascript:....")
    val smarttabs = SettingKey[Option[Boolean]]("jshint-smarttabs", "This option suppresses warnings about mixed tabs and spaces when the latter are used for alignmnent only.")
    val shadow = SettingKey[Option[Boolean]]("jshint-shadow", "This option suppresses warnings about variable shadowing i.e. declaring a variable that had been already declared somewhere in the outer scope.")
    val sub = SettingKey[Option[Boolean]]("jshint-sub", "This option suppresses warnings about using [] notation when it can be expressed in dot notation.")
    val supernew = SettingKey[Option[Boolean]]("jshint-supernew", "This option suppresses warnings about \"weird\" constructions like new function () { ... } and new Object.")
    val validthis = SettingKey[Option[Boolean]]("jshint-validthis", "This option suppresses warnings about possible strict violations when the code is running in strict mode and you use this in a non-constructor function.")

    // http://www.jshint.com/docs/options/#environments

    val browser = SettingKey[Option[Boolean]]("jshint-browser", "This option defines globals exposed by modern browsers: all the way from good old document and navigator to the HTML5 FileReader and other new developments in the browser world.")
    val couch = SettingKey[Option[Boolean]]("jshint-couch", "This option defines globals exposed by CouchDB. CouchDB is a document-oriented database that can be queried and indexed in a MapReduce fashion using JavaScript.")
    val devel = SettingKey[Option[Boolean]]("jshint-devel", "This option defines globals that are usually used for logging poor-man's debugging: console, alert, etc.")
    val dojo = SettingKey[Option[Boolean]]("jshint-dojo", "This option defines globals exposed by the Dojo Toolkit.")
    val jquery = SettingKey[Option[Boolean]]("jshint-jquery", "This option defines globals exposed by the jQuery JavaScript library.")
    val mootools = SettingKey[Option[Boolean]]("jshint-mootools", "This option defines globals exposed by the MooTools JavaScript framework.")
    val node = SettingKey[Option[Boolean]]("jshint-node", "This option defines globals available when your code is running inside of the Node runtime environment.")
    val nonstandard = SettingKey[Option[Boolean]]("jshint-nonstandard", "This option defines non-standard but widely adopted globals such as escape and unescape.")
    val phantom = SettingKey[Option[Boolean]]("jshint-phantom", "This option defines globals available when your core is running inside of the PhantomJS runtime environment.")
    val prototypejs = SettingKey[Option[Boolean]]("jshint-prototypejs", "This option defines globals exposed by the Prototype JavaScript framework.")
    val rhino = SettingKey[Option[Boolean]]("jshint-rhino", "This option defines globals available when your code is running inside of the Rhino runtime environment.")
    val worker = SettingKey[Option[Boolean]]("jshint-worker", "This option defines globals available when your code is running inside of a Web Worker.")
    val wsh = SettingKey[Option[Boolean]]("jshint-wsh", "This option defines globals available when your code is running as a script for the Windows Script Host.")
    val yui = SettingKey[Option[Boolean]]("jshint-yui", "This option defines globals exposed by the YUI JavaScript framework.")

    // http://www.jshint.com/docs/options/#legacy - DO NOT use them

    val nomen = SettingKey[Option[Boolean]]("jshint-nomen", "This option disallows the use of dangling _ in variables.")
    val onevar = SettingKey[Option[Boolean]]("jshint-onevar", "This option allows only one var statement per function.")
    val passfail = SettingKey[Option[Boolean]]("jshint-passfail", "This option makes JSHint stop on the first error or warning.")
    val white = SettingKey[Option[Boolean]]("jshint-white", "This option make JSHint check your source code against Douglas Crockford's JavaScript coding style.")

    val shellSource = SettingKey[File]("jshint-shelljs-source", "The target location of the js shell script to use.")
    val jshintSource = SettingKey[File]("jshint-jshintjs-source", "The target location of the jshint script to use.")

  }

  import WebKeys._
  import JshintKeys._
  import JsEngineKeys._

  def jshintSettings = Seq(
    bitwise := None,
    camelcase := None,
    curly := None,
    eqeqeq := None,
    es3 := None,
    forin := None,
    freeze := None,
    immed := None,
    indent := None,
    latedef := None,
    newcap := None,
    noarg := None,
    noempty := None,
    nonew := None,
    plusplus := None,
    quotmark := None,
    undef := None,
    unused := None,
    strict := None,
    trailing := None,
    maxparams := None,
    maxdepth := None,
    maxstatements := None,
    maxcomplexity := None,
    maxlen := None,
    asi := None,
    boss := None,
    debug := None,
    eqnull := None,
    esnext := None,
    evil := None,
    expr := None,
    funcscope := None,
    globalstrict := None,
    iterator := None,
    lastsemic := None,
    laxbreak := None,
    laxcomma := None,
    loopfunc := None,
    moz := None,
    multistr := None,
    notypeof := None,
    proto := None,
    scripturl := None,
    smarttabs := None,
    shadow := None,
    sub := None,
    supernew := None,
    validthis := None,
    browser := None,
    couch := None,
    devel := None,
    dojo := None,
    jquery := None,
    mootools := None,
    node := None,
    nonstandard := None,
    phantom := None,
    prototypejs := None,
    rhino := None,
    worker := None,
    wsh := None,
    yui := None,
    nomen := None,
    onevar := None,
    passfail := None,
    white := None,

    jshintOptions <<= state map jshintOptionsTask,

    shellSource := getFileInTarget(target.value, "shell.js"),
    // FIXME: This resource will eventually be located from its webjar. For now
    // we use a webjar until the webjar is updated with my fix (waiting for a
    // new release of jshint).
    jshintSource := getFileInTarget(target.value, "jshint.js"),

    jshint <<= (
      shellSource,
      jshintSource,
      unmanagedSources in Assets,
      jsFilter,
      jshintOptions,
      engineType,
      parallelism,
      streams,
      reporter
      ) map (jshintTask(_, _, _, _, _, _, _, _, _, testing = false)),
    jshintTest <<= (
      shellSource,
      jshintSource,
      unmanagedSources in TestAssets,
      jsFilter,
      jshintOptions,
      engineType,
      parallelism,
      streams,
      reporter
      ) map (jshintTask(_, _, _, _, _, _, _, _, _, testing = true)),

    test <<= (test in Test).dependsOn(jshint, jshintTest)
  )

  private def jshintOptionsTask(state: State): JsObject = {
    val extracted = Project.extract(state)
    JsObject(List(
      extracted.get(bitwise).map(v => "bitwise" -> JsBoolean(v)),
      extracted.get(camelcase).map(v => "camelcase" -> JsBoolean(v)),
      extracted.get(curly).map(v => "curly" -> JsBoolean(v)),
      extracted.get(eqeqeq).map(v => "eqeqeq" -> JsBoolean(v)),
      extracted.get(es3).map(v => "eqeqeq" -> JsBoolean(v)),
      extracted.get(forin).map(v => "forin" -> JsBoolean(v)),
      extracted.get(freeze).map(v => "freeze" -> JsBoolean(v)),
      extracted.get(immed).map(v => "immed" -> JsBoolean(v)),
      extracted.get(indent).map(v => "indent" -> JsBoolean(v)),
      extracted.get(latedef).map(v => "latedef" -> JsBoolean(v)),
      extracted.get(newcap).map(v => "newcap" -> JsBoolean(v)),
      extracted.get(noarg).map(v => "noarg" -> JsBoolean(v)),
      extracted.get(noempty).map(v => "noempty" -> JsBoolean(v)),
      extracted.get(nonew).map(v => "nonew" -> JsBoolean(v)),
      extracted.get(plusplus).map(v => "plusplus" -> JsBoolean(v)),
      extracted.get(quotmark).map(v => "quotmark" -> JsString(v.toString)),
      extracted.get(undef).map(v => "undef" -> JsBoolean(v)),
      extracted.get(unused).map(v => "unused" -> JsBoolean(v)),
      extracted.get(strict).map(v => "strict" -> JsBoolean(v)),
      extracted.get(trailing).map(v => "trailing" -> JsBoolean(v)),
      extracted.get(maxparams).map(v => "maxparams" -> JsNumber(v)),
      extracted.get(maxdepth).map(v => "maxdepth" -> JsNumber(v)),
      extracted.get(maxstatements).map(v => "maxstatements" -> JsNumber(v)),
      extracted.get(maxcomplexity).map(v => "maxcomplexity" -> JsNumber(v)),
      extracted.get(maxlen).map(v => "maxlen" -> JsNumber(v)),
      extracted.get(asi).map(v => "asi" -> JsBoolean(v)),
      extracted.get(boss).map(v => "boss" -> JsBoolean(v)),
      extracted.get(debug).map(v => "debug" -> JsBoolean(v)),
      extracted.get(eqnull).map(v => "eqnull" -> JsBoolean(v)),
      extracted.get(esnext).map(v => "esnext" -> JsBoolean(v)),
      extracted.get(evil).map(v => "evil" -> JsBoolean(v)),
      extracted.get(expr).map(v => "expr" -> JsBoolean(v)),
      extracted.get(funcscope).map(v => "funcscope" -> JsBoolean(v)),
      extracted.get(globalstrict).map(v => "globalstrict" -> JsBoolean(v)),
      extracted.get(iterator).map(v => "iterator" -> JsBoolean(v)),
      extracted.get(lastsemic).map(v => "lastsemic" -> JsBoolean(v)),
      extracted.get(laxbreak).map(v => "laxbreak" -> JsBoolean(v)),
      extracted.get(laxcomma).map(v => "laxcomma" -> JsBoolean(v)),
      extracted.get(loopfunc).map(v => "loopfunc" -> JsBoolean(v)),
      extracted.get(moz).map(v => "moz" -> JsBoolean(v)),
      extracted.get(multistr).map(v => "multistr" -> JsBoolean(v)),
      extracted.get(notypeof).map(v => "notypeof" -> JsBoolean(v)),
      extracted.get(proto).map(v => "proto" -> JsBoolean(v)),
      extracted.get(scripturl).map(v => "scripturl" -> JsBoolean(v)),
      extracted.get(smarttabs).map(v => "smarttabs" -> JsBoolean(v)),
      extracted.get(shadow).map(v => "shadow" -> JsBoolean(v)),
      extracted.get(sub).map(v => "sub" -> JsBoolean(v)),
      extracted.get(supernew).map(v => "supernew" -> JsBoolean(v)),
      extracted.get(validthis).map(v => "validthis" -> JsBoolean(v)),
      extracted.get(browser).map(v => "browser" -> JsBoolean(v)),
      extracted.get(couch).map(v => "couch" -> JsBoolean(v)),
      extracted.get(devel).map(v => "devel" -> JsBoolean(v)),
      extracted.get(dojo).map(v => "dojo" -> JsBoolean(v)),
      extracted.get(jquery).map(v => "jquery" -> JsBoolean(v)),
      extracted.get(mootools).map(v => "mootools" -> JsBoolean(v)),
      extracted.get(node).map(v => "node" -> JsBoolean(v)),
      extracted.get(nonstandard).map(v => "nonstandard" -> JsBoolean(v)),
      extracted.get(phantom).map(v => "phantom" -> JsBoolean(v)),
      extracted.get(prototypejs).map(v => "prototypejs" -> JsBoolean(v)),
      extracted.get(rhino).map(v => "rhino" -> JsBoolean(v)),
      extracted.get(worker).map(v => "worker" -> JsBoolean(v)),
      extracted.get(wsh).map(v => "wsh" -> JsBoolean(v)),
      extracted.get(yui).map(v => "yui" -> JsBoolean(v)),
      extracted.get(nomen).map(v => "nomen" -> JsBoolean(v)),
      extracted.get(onevar).map(v => "onevar" -> JsBoolean(v)),
      extracted.get(passfail).map(v => "passfail" -> JsBoolean(v)),
      extracted.get(white).map(v => "white" -> JsBoolean(v))

    ).flatten)
  }

  def getFileInTarget(target: File, name: String): File = {
    val is = this.getClass.getClassLoader.getResourceAsStream(name)
    try {
      val f = target / this.getClass.getSimpleName / name
      IO.transfer(is, f)
      f
    } finally {
      is.close()
    }
  }

  // TODO: This can be abstracted further so that source batches can be determined generally?
  private def jshintTask(shellSource: File,
                         jshintSource: File,
                         unmanagedSources: Seq[File],
                         jsFilter: FileFilter,
                         jshintOptions: JsObject,
                         engineType: EngineType.Value,
                         parallelism: Int,
                         s: TaskStreams,
                         reporter: LoggerReporter,
                         testing: Boolean
                          ): Unit = {

    import DefaultJsonProtocol._

    reporter.reset()

    val sources = (unmanagedSources ** jsFilter).get.to[immutable.Seq]

    val engineProps = engineType match {
      case EngineType.CommonNode => CommonNode.props()
      case EngineType.Node => Node.props()
      case EngineType.PhantomJs => PhantomJs.props()
      case EngineType.Rhino => Rhino.props()
    }

    val testKeyword = if (testing) "test " else ""
    if (sources.size > 0) {
      s.log.info(s"JavaScript linting on ${sources.size} ${testKeyword}source(s)")
    }

    val resultBatches: immutable.Seq[Future[JsArray]] =
      try {
        val sourceBatches = (sources grouped Math.max(sources.size / parallelism, 1)).to[immutable.Seq]
        sourceBatches.map(sourceBatch => lintForSources(engineProps, shellSource, jshintSource, sourceBatch, jshintOptions))
      }

    val pendingResults = Future.sequence(resultBatches)
    for {
      allResults <- Await.result(pendingResults, 10.seconds)
      result <- allResults.elements
    } {
      val resultTuple = result.convertTo[JsArray]
      logErrors(
        reporter,
        s.log,
        file(resultTuple.elements(0).toString()),
        resultTuple.elements(1).convertTo[JsArray]
      )
    }

    reporter.printSummary()
    if (reporter.hasErrors()) {
      throw new LintingFailedException
    }
  }

  implicit val jseSystem = JsEnginePlugin.jseSystem
  implicit val jseTimeout = JsEnginePlugin.jseTimeout

  /*
   * lints a sequence of sources and returns a future representing the results of all.
   */
  private def lintForSources(
                              engineProps: Props,
                              shellSource: File,
                              jshintSource: File,
                              sources: immutable.Seq[File],
                              options: JsObject
                              ): Future[JsArray] = {
    val engine = jseSystem.actorOf(engineProps)
    val jshinter = Jshinter(engine, shellSource, jshintSource)
    jshinter.lint(sources, options)
  }

  private def logErrors(reporter: LoggerReporter, log: Logger, source: File, jshintErrors: JsArray): Unit = {

    jshintErrors.elements.map {
      case o: JsObject =>

        def getReason(o: JsObject): String = o.fields.get("reason").get.toString()

        def logWithSeverity(o: JsObject, s: Severity): Unit = {
          val p = new Position {
            def line(): Maybe[Integer] =
              Maybe.just(java.lang.Double.parseDouble(o.fields.get("line").get.toString()).toInt)

            def lineContent(): String = o.fields.get("evidence") match {
              case Some(JsString(line)) => line
              case _ => ""
            }

            def offset(): Maybe[Integer] =
              Maybe.just(java.lang.Double.parseDouble(o.fields.get("character").get.toString()).toInt - 1)

            def pointer(): Maybe[Integer] = offset()

            def pointerSpace(): Maybe[String] = Maybe.just(
              lineContent().take(pointer().get).map {
                case '\t' => '\t'
                case x => ' '
              })

            def sourcePath(): Maybe[String] = Maybe.just(source.getPath)

            def sourceFile(): Maybe[File] = Maybe.just(source)
          }
          val r = getReason(o)
          reporter.log(p, r, s)
        }

        o.fields.get("id") match {
          case Some(JsString("(error)")) => logWithSeverity(o, Severity.Error)
          case Some(JsString("(info)")) => logWithSeverity(o, Severity.Info)
          case Some(JsString("(warn)")) => logWithSeverity(o, Severity.Warn)
          case Some(id@_) => log.error(s"Unknown type of error: $id with reason: ${getReason(o)}")
          case _ => log.error(s"Malformed error with reason: ${getReason(o)}")
        }
      case x@_ => log.error(s"Malformed result: $x")
    }
  }
}

class LintingFailedException extends RuntimeException("JavaScript linting failed") with FeedbackProvidedException {
  override def toString = getMessage
}
