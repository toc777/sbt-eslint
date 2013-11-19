sbt-jslint-plugin
=================

Allows jslint to be used from within sbt. Builds on com.typesafe:webdriver in order to execute jslint.js
along with the scripts to verify. WebDriver enables high performance linting given parallelism and native
browser execution.

To use this plugin use the addSbtPlugin command within your project's plugins.sbt (or as a global setting). Then
declare the settings required in your build file. For example, for build.sbt:

    jslintSettings

By default linting occurs as part of your project's `test` task. Both src/main/js and src/test/js sources are linted.

Options can be specified in accordance with the
[jslint website](http://jslint.org) and they share the same set of defaults. To set an option:

    JslintKeys.passfail := Some(true)

By default [HtmlUnit](http://htmlunit.sourceforge.net/) is used as the JavaScript engine (HtmlUnit provides a browser
environment to [Rhino](https://developer.mozilla.org/en/docs/Rhino)) entirely within the JVM requiring no
additional downloads.
[PhantomJS](http://phantomjs.org/) is supported as a native browser option for fast and native JavaScript execution.
To use PhantomJs instead of HtmlUnit declare the following in your build file:

    WebDriverKeys.browserType in Global := WebDriverKeys.BrowserType.PhantomJs

PhantomJs is required to be available on your shell's path in order for it to be used. To check for its availability
simply type `phantomjs`.

&copy; Typesafe Inc., 2013