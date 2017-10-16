sbt-eslint
==========

[![Build Status](https://api.travis-ci.org/toc777/sbt-eslint.png?branch=master)](https://travis-ci.org/toc777/sbt-eslint)

Allows ESLint to be used from within sbt. Builds on com.typesafe:js-engine in order to execute eslint.js
along with the scripts to verify. js-engine enables high performance linting given parallelism and native
JS engine execution.

To use this plugin use the addSbtPlugin command within your project's plugins.sbt (or as a global setting) i.e.:

    addSbtPlugin("com.sc.sbt" % "sbt-eslint" % "1.0.2")

Your project's build file also needs to enable sbt-web plugins. For example with build.sbt:

    lazy val root = (project in file(".")).enablePlugins(SbtWeb)
    
Install eslint, either globally with npm:

```shell
npm install eslint -g
```

Or locally in your project with a `package.json` file:

```json
{
  "devDependencies": {
    "eslint": "4.9.0"
  }
}
```

By default linting occurs at compile time as part of your project's `eslint` task. Both src/main/assets/\*\*/\*.js and
src/test/assets/\*\*/\*.js sources are linted.

Options can be specified in accordance with the
[ESLint website](http://eslint.org/) and they share the same set of defaults. To set an option you can
provide a `.eslintrc` file within your project's base directory.
