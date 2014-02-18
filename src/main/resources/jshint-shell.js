/*global process, require */

/*
 * Lint a number of files.
 *
 * Arguments:
 * 0 - name given to the command invoking the script (unused)
 * 1 - filepath of this script (unused)
 * 2 - array of file paths to the files to lint
 * 3 - jshint options as a Json object
 *
 * Json array tuples are sent to stdout for each file in error (if any). Each tuple is an array where the
 * element 0 corresponds to the file path of the file linted, and element 1 is JSHINT.errors.
 */

(function () {

    "use strict";

    var args = process.argv;
    var console = require("console");
    var fs = require("fs");
    var jshint = require("jshint");

    var SOURCE_FILE_PATHS_ARG = 2;
    var OPTIONS_ARG = 3;

    var options = JSON.parse(args[OPTIONS_ARG]);

    var sourceFilePaths = JSON.parse(args[SOURCE_FILE_PATHS_ARG]);
    var sourceFilesToProcess = sourceFilePaths.length;
    var results = [];
    var problems = [];
    sourceFilePaths.forEach(function (sourceFilePath) {
        fs.readFile(sourceFilePath, "utf8", function (e, source) {
            if (e) {
                console.error("Error while trying to read " + source, e);
            } else {
                jshint.JSHINT(source, options);
                results.push({
                    source: sourceFilePath,
                    result: (jshint.JSHINT.errors.length === 0 ? {filesRead: [sourceFilePath], filesWritten: []} : null)
                });
                jshint.JSHINT.errors.forEach(function (e) {
                    problems.push({
                        message: e.reason,
                        severity: e.id.substring(1, e.id.length - 1),
                        lineNumber: e.line,
                        characterOffset: e.character - 1,
                        lineContent: e.evidence,
                        source: sourceFilePath
                    });
                });
            }
            if (--sourceFilesToProcess === 0) {
                console.log("\u0010" + JSON.stringify({results: results, problems: problems}));
            }
        });
    });
}());
