#!/usr/bin/env node

/**
 * Hook script to set SWIFT_VERSION build setting in the Xcode project
 * and configure the bridging header with Cordova imports.
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

    var appName = path.basename(xcodeProjectDir, '.xcodeproj');
    var pbxprojPath = path.join(xcodeProjectDir, 'project.pbxproj');

    if (!fs.existsSync(pbxprojPath)) {
        console.log('set_swift_version: No project.pbxproj found, skipping.');
        return;
    }

    var pbxproj = fs.readFileSync(pbxprojPath, 'utf8');

    // Set SWIFT_VERSION in all build configurations
    if (pbxproj.indexOf('SWIFT_VERSION') === -1) {
        pbxproj = pbxproj.replace(/buildSettings = \{/g, 'buildSettings = {\n\t\t\t\tSWIFT_VERSION = 5.0;');
        console.log('set_swift_version: Set SWIFT_VERSION = 5.0 in all build configurations.');
    } else {
        pbxproj = pbxproj.replace(/SWIFT_VERSION = [^;]*;/g, 'SWIFT_VERSION = 5.0;');
        console.log('set_swift_version: Updated SWIFT_VERSION to 5.0.');
    }

    fs.writeFileSync(pbxprojPath, pbxproj, 'utf8');

    // Create or update bridging header with Cordova imports
    var bridgingHeaderName = appName + '-Bridging-Header.h';
    var bridgingHeaderPath = path.join(platformRoot, bridgingHeaderName);

    var bridgingHeaderContent = [
        '//',
        '//  ' + bridgingHeaderName,
        '//  Bridging header for Swift/Cordova support',
        '//',
        '',
        '#import <Cordova/CDV.h>',
        '#import <Cordova/CDVPlugin.h>',
        '#import <Cordova/CDVInvokedUrlCommand.h>',
        '#import <Cordova/CDVPluginResult.h>',
        '#import <Cordova/CDVCommandDelegate.h>',
        '#import <Cordova/CDVViewController.h>',
        ''
    ].join('\n');

    // Check if bridging header exists and has Cordova imports
    var needsUpdate = true;
    if (fs.existsSync(bridgingHeaderPath)) {
        var existingContent = fs.readFileSync(bridgingHeaderPath, 'utf8');
        if (existingContent.indexOf('#import <Cordova/CDV.h>') !== -1) {
            needsUpdate = false;
            console.log('set_swift_version: Bridging header already has Cordova imports.');
        }
    }

    if (needsUpdate) {
        fs.writeFileSync(bridgingHeaderPath, bridgingHeaderContent, 'utf8');
        console.log('set_swift_version: Created/updated bridging header with Cordova imports: ' + bridgingHeaderName);
    }

    // Ensure SWIFT_OBJC_BRIDGING_HEADER is in build settings
    pbxproj = fs.readFileSync(pbxprojPath, 'utf8');
    if (pbxproj.indexOf('SWIFT_OBJC_BRIDGING_HEADER') === -1) {
        pbxproj = pbxproj.replace(/SWIFT_VERSION = 5\.0;/g,
            'SWIFT_VERSION = 5.0;\n\t\t\t\tSWIFT_OBJC_BRIDGING_HEADER = "' + bridgingHeaderName + '";');
        fs.writeFileSync(pbxprojPath, pbxproj, 'utf8');
        console.log('set_swift_version: Added SWIFT_OBJC_BRIDGING_HEADER to build settings.');
    }
};
