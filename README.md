sbt-jshint-plugin
=================

Allows JSHint to be used from within sbt. Builds on com.typesafe:js-engine in order to execute jshint.js
along with the scripts to verify. js-engine enables high performance linting given parallelism and native
JS engine execution.

To use this plugin use the addSbtPlugin command within your project's plugins.sbt (or as a global setting). Then
declare the settings required in your build file. For example, for build.sbt:

    import com.typesafe.sbt.jse.JsEnginePlugin

    JsEnginePlugin.jshintSettings

By default linting occurs as part of your project's `test` task. Both src/main/assets/\*\*/*.js and
src/test/assets/\**/*.js sources are linted.

Options can be specified in accordance with the
[JSHint website](http://www.jshint.com/docs) and they share the same set of defaults. To set an option you can
provide a `.jshintrc` file within your project's base directory. If there is no such file then a `.jshintrc` file will
be search for in your home directory. This behaviour can be overridden by using a `JsEngineKeys.config` setting for the plugin.
`JsEngineKeys.config` is used to specify the location of a configuration file.

By default [Rhino](https://developer.mozilla.org/en/docs/Rhino) is used as the JavaScript engine and runs entirely within
the JVM requiring no additional downloads.
[common-node](http://olegp.github.io/common-node//) is supported as a native engine option for fast and native JavaScript execution.
common-node is [Node](http://nodejs.org/) with library support for [CommonJS](http://wiki.commonjs.org/wiki/CommonJS).
To use common-node instead of Rhino declare the following in your build file:

    import com.typesafe.sbt.jse.JsEnginePlugin.JsEngineKeys

    JsEngineKeys.engineType := JsEngineKeys.EngineType.CommonNode

common-node is required to be available on your shell's path in order for it to be used. To check for its availability
simply type `common-node`.

&copy; Typesafe Inc., 2013  