/*global arguments, print, readFile, require */

/*
 * Lint files. Direct support is provided for common-node and Rhino.
 *
 * Arguments:
 * 0 - file path of jshint itself
 * 1 - array of file paths to the files to lint
 * 2 - jshint options as a Json array of objects
 *
 * Json array tuples are sent to stdout for each file in error (if any). Each tuple is an array where the
 * element 0 corresponds to the file path of the file linted, and element 1 is JSHINT.errors.
 */

(function () {

    "use strict";

    var args,
        console,
        fs,
        jshint,
        options,
        JSHINT_ARG,
        OPTIONS_ARG,
        SOURCE_FILE_PATHS_ARG;

    // Resolve CommonJS modules along and handle Rhino compatibility where required.

    args = require("system").args;
    if (args === undefined) {
        args = arguments.unshift("");
    }

    console = require("console");
    if (console === undefined) {
        console = {
            log: print
        };
    }

    fs = require("fs-base");
    if (fs === undefined) {
        fs = {
            read: readFile
        };
    }

    // Main processing

    JSHINT_ARG = 2;
    SOURCE_FILE_PATHS_ARG = 3;
    OPTIONS_ARG = 4;

    jshint = require(args[JSHINT_ARG]).JSHINT;

    options = JSON.parse(args[OPTIONS_ARG]);

    JSON.parse(args[SOURCE_FILE_PATHS_ARG]).forEach(function (sourceFilePath) {
        var source = fs.read(sourceFilePath);
        if (!jshint(source, options)) {
            console.log(JSON.stringify([sourceFilePath, jshint.errors]));
        }
    });
}());
