#!/usr/bin/env node

/**
 * Hook script to set SWIFT_VERSION build setting in the Xcode project.
 * This replaces cordova-plugin-add-swift-support to avoid version conflicts with MABS.
 */

var fs = require('fs');
var path = require('path');

module.exports = function (context) {
    var platformRoot = path.join(context.opts.projectRoot, 'platforms', 'ios');

    if (!fs.existsSync(platformRoot)) {
        return;
    }

    // Find the .xcodeproj directory
    var xcodeProjectDir;
    var files = fs.readdirSync(platformRoot);
    for (var i = 0; i < files.length; i++) {
        if (files[i].match(/\.xcodeproj$/)) {
            xcodeProjectDir = path.join(platformRoot, files[i]);
            break;
        }
    }

    if (!xcodeProjectDir) {
        console.log('set_swift_version: No .xcodeproj found, skipping.');
        return;
    }

    var pbxprojPath = path.join(xcodeProjectDir, 'project.pbxproj');

    if (!fs.existsSync(pbxprojPath)) {
        console.log('set_swift_version: No project.pbxproj found, skipping.');
        return;
    }

    var pbxproj = fs.readFileSync(pbxprojPath, 'utf8');

    // Check if SWIFT_VERSION is already set
    if (pbxproj.indexOf('SWIFT_VERSION') === -1) {
        // Add SWIFT_VERSION to all build configurations
        pbxproj = pbxproj.replace(/buildSettings = \{/g, 'buildSettings = {\n\t\t\t\tSWIFT_VERSION = 5.0;');
        fs.writeFileSync(pbxprojPath, pbxproj, 'utf8');
        console.log('set_swift_version: Set SWIFT_VERSION = 5.0 in all build configurations.');
    } else {
        // Update existing SWIFT_VERSION to 5.0
        pbxproj = pbxproj.replace(/SWIFT_VERSION = [^;]*;/g, 'SWIFT_VERSION = 5.0;');
        fs.writeFileSync(pbxprojPath, pbxproj, 'utf8');
        console.log('set_swift_version: Updated SWIFT_VERSION to 5.0.');
    }

    // Ensure we have an empty bridging header if one doesn't exist
    var bridgingHeaderName;
    files = fs.readdirSync(platformRoot);
    for (var j = 0; j < files.length; j++) {
        if (files[j].match(/^.*-Bridging-Header\.h$/)) {
            bridgingHeaderName = files[j];
            break;
        }
    }

    if (!bridgingHeaderName) {
        // Find the app name from the xcodeproj
        var appName = path.basename(xcodeProjectDir, '.xcodeproj');
        bridgingHeaderName = appName + '-Bridging-Header.h';
        var bridgingHeaderPath = path.join(platformRoot, bridgingHeaderName);

        if (!fs.existsSync(bridgingHeaderPath)) {
            fs.writeFileSync(bridgingHeaderPath, '//\n//  Bridging header for Swift support\n//\n', 'utf8');
            console.log('set_swift_version: Created bridging header: ' + bridgingHeaderName);

            // Add bridging header to build settings
            var updatedPbxproj = fs.readFileSync(pbxprojPath, 'utf8');
            if (updatedPbxproj.indexOf('SWIFT_OBJC_BRIDGING_HEADER') === -1) {
                updatedPbxproj = updatedPbxproj.replace(/SWIFT_VERSION = 5\.0;/g,
                    'SWIFT_VERSION = 5.0;\n\t\t\t\tSWIFT_OBJC_BRIDGING_HEADER = "' + bridgingHeaderName + '";');
                fs.writeFileSync(pbxprojPath, updatedPbxproj, 'utf8');
                console.log('set_swift_version: Added SWIFT_OBJC_BRIDGING_HEADER to build settings.');
            }
        }
    }
};
