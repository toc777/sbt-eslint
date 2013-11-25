sbt-jshint-plugin
=================

Allows JSHint to be used from within sbt. Builds on com.typesafe:js-engine in order to execute jshint.js
along with the scripts to verify. js-engine enables high performance linting given parallelism and native
JS engine execution.

To use this plugin use the addSbtPlugin command within your project's plugins.sbt (or as a global setting). Then
declare the settings required in your build file. For example, for build.sbt:

    jshintSettings

By default linting occurs as part of your project's `test` task. Both src/main/assets/**/*.js and
src/test/assets/**/*.js sources are linted.

Options can be specified in accordance with the
[JSHint website](http://www.jshint.com/) and they share the same set of defaults. To set an option:

    JshintKeys.asi := Some(true)

By default [Rhino](https://developer.mozilla.org/en/docs/Rhino) is used as the JavaScript engine entirely within
the JVM requiring no additional downloads.
[common-node](http://olegp.github.io/common-node//) is supported as a native engine option for fast and native JavaScript execution.
common-node is [Node](http://nodejs.org/) with library support for [CommonJS](http://wiki.commonjs.org/wiki/CommonJS).
To use common-node instead of Rhino declare the following in your build file:

    JsEngineKeys.engineType := JsEngineKeys.EngineType.CommonNode

common-node is required to be available on your shell's path in order for it to be used. To check for its availability
simply type `common-node`.

&copy; Typesafe Inc., 2013