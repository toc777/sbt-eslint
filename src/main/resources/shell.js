/*global arguments, print, readFile, require */

/*
 * Lint files. Direct support is provided for common-node and Rhino.
 *
 * Arguments:
 * 0 - name given to the command invoking the script (unused)
 * 1 - filepath of this script (unused)
 * 2 - file path of jshint itself
 * 3 - array of file paths to the files to lint
 * 4 - jshint options as a Json object
 *
 * Json array tuples are sent to stdout for each file in error (if any). Each tuple is an array where the
 * element 0 corresponds to the file path of the file linted, and element 1 is JSHINT.errors.
 */

(function (externalArgs) {

    "use strict";

    // Resolve CommonJS modules along and handle Rhino compatibility where required.

    var args;
    try {
        args = require("system").args;
    } catch (e) {
        // Convert to an array - arguments are not arrays. We also insert 2 elements at the
        // beginning so that it is normalised with CommonJS arguments.
        args = Array.prototype.slice.call(externalArgs);
        args.unshift("", "");
    }

    var console;
    try {
        console = require("console");
    } catch (e) {
        console = {
            log: print
        };
    }

    var fs;
    try {
        fs = require("fs-base");
    } catch (e) {
        fs = {
            read: readFile
        };
    }

    // Main processing

    var JSHINT_ARG = 2;
    var SOURCE_FILE_PATHS_ARG = 3;
    var OPTIONS_ARG = 4;

    var jshint = require(args[JSHINT_ARG]).JSHINT;

    var options = JSON.parse(args[OPTIONS_ARG]);

    var errorReported = false;
    console.log("[");
    JSON.parse(args[SOURCE_FILE_PATHS_ARG]).forEach(function (sourceFilePath) {
        var source = fs.read(sourceFilePath);
        if (!jshint(source, options)) {
            if (errorReported) {
                console.log(",");
            }
            console.log(JSON.stringify([sourceFilePath, jshint.errors]));
            errorReported = true;
        }
    });
    console.log("]");

}(arguments));
