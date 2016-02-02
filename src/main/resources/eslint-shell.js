/*global process, require */

/*
 * Lint a number of files.
 *
 * Arguments:
 * 0 - name given to the command invoking the script (unused)
 * 1 - filepath of this script (unused)
 * 2 - array of file paths to the files to lint
 * 3 - the target folder to write to (unused - not required)
 * 4 - eslint options as a Json object
 *
 * Json array tuples are sent to stdout for each file in error (if any). Each tuple is an array where the
 * element 0 corresponds to the file path of the file linted, and element 1 is the errors.
 */

(function () {

    "use strict";
    
    var console = require("console");
    var CLIEngine = require("eslint").CLIEngine;

    var args = process.argv, 
        options = JSON.parse(args[4]), 
        sourceFileMappings = JSON.parse(args[2]);
    
    var results = [], problems = [];
    
    var cli = new CLIEngine(options);
    var report = cli.executeOnFiles(sourceFileMappings.map(m => m[0]));
    
    report.results.forEach(result => {
        result.messages.forEach(message => {
            results.push({
                source: result.filePath,
                result: (result.errorCount === 0 ? {filesRead: [result.filePath], filesWritten: []} : null)
            });
            
            problems.push({
                message: message.message,
                severity: (message.severity === 1 ? "error" : "warning"),
                lineNumber: message.line,
                characterOffset: message.column,
                lineContent: message.source,
                source: result.filePath
            });
        });
    });
    
    console.log("\u0010" + JSON.stringify({results: results, problems: problems}));
}());
