#!/usr/bin/env node

/**
 * Hook script to:
 * 1. Set SWIFT_VERSION build setting in the Xcode project
 * 2. Configure the bridging header with Cordova imports
 * 3. Ensure NFC TAG entitlement is present in all entitlements files
 */

var fs = require('fs');
var path = require('path');
var plist = require('plist');

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
        console.log('ios_setup_hook: No .xcodeproj found, skipping.');
        return;
    }

    var appName = path.basename(xcodeProjectDir, '.xcodeproj');
    var pbxprojPath = path.join(xcodeProjectDir, 'project.pbxproj');

    if (!fs.existsSync(pbxprojPath)) {
        console.log('ios_setup_hook: No project.pbxproj found, skipping.');
        return;
    }

    var pbxproj = fs.readFileSync(pbxprojPath, 'utf8');

    // ========== 1. Set SWIFT_VERSION ==========
    if (pbxproj.indexOf('SWIFT_VERSION') === -1) {
        pbxproj = pbxproj.replace(/buildSettings = \{/g, 'buildSettings = {\n\t\t\t\tSWIFT_VERSION = 5.0;');
        console.log('ios_setup_hook: Set SWIFT_VERSION = 5.0 in all build configurations.');
    } else {
        pbxproj = pbxproj.replace(/SWIFT_VERSION = [^;]*;/g, 'SWIFT_VERSION = 5.0;');
        console.log('ios_setup_hook: Updated SWIFT_VERSION to 5.0.');
    }

    fs.writeFileSync(pbxprojPath, pbxproj, 'utf8');

    // ========== 2. Bridging Header ==========
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

    var needsUpdate = true;
    if (fs.existsSync(bridgingHeaderPath)) {
        var existingContent = fs.readFileSync(bridgingHeaderPath, 'utf8');
        if (existingContent.indexOf('#import <Cordova/CDV.h>') !== -1) {
            needsUpdate = false;
        }
    }

    if (needsUpdate) {
        fs.writeFileSync(bridgingHeaderPath, bridgingHeaderContent, 'utf8');
        console.log('ios_setup_hook: Created/updated bridging header: ' + bridgingHeaderName);
    }

    pbxproj = fs.readFileSync(pbxprojPath, 'utf8');
    if (pbxproj.indexOf('SWIFT_OBJC_BRIDGING_HEADER') === -1) {
        pbxproj = pbxproj.replace(/SWIFT_VERSION = 5\.0;/g,
            'SWIFT_VERSION = 5.0;\n\t\t\t\tSWIFT_OBJC_BRIDGING_HEADER = "' + bridgingHeaderName + '";');
        fs.writeFileSync(pbxprojPath, pbxproj, 'utf8');
        console.log('ios_setup_hook: Added SWIFT_OBJC_BRIDGING_HEADER to build settings.');
    }

    // ========== 3. NFC Entitlements ==========
    ensureNfcEntitlement(platformRoot, appName);
};

/**
 * Find all entitlements plist files and ensure TAG + NDEF are in
 * com.apple.developer.nfc.readersession.formats
 */
function ensureNfcEntitlement(platformRoot, appName) {
    var appDir = path.join(platformRoot, appName);
    var entitlementFiles = [];

    // Search for entitlements in the app directory
    var searchDirs = [appDir, platformRoot];

    for (var d = 0; d < searchDirs.length; d++) {
        var dir = searchDirs[d];
        if (!fs.existsSync(dir)) continue;

        var dirFiles;
        try {
            dirFiles = fs.readdirSync(dir);
        } catch (e) {
            continue;
        }

        for (var i = 0; i < dirFiles.length; i++) {
            var f = dirFiles[i];
            if (f.match(/\.entitlements$/) || f.match(/Entitlements.*\.plist$/i)) {
                var fullPath = path.join(dir, f);
                if (entitlementFiles.indexOf(fullPath) === -1) {
                    entitlementFiles.push(fullPath);
                }
            }
        }
    }

    if (entitlementFiles.length === 0) {
        console.log('ios_setup_hook: No entitlements files found. Creating one.');
        // Create a new entitlements file
        var entPath = path.join(appDir, appName + '.entitlements');
        var entContent = '<?xml version="1.0" encoding="UTF-8"?>\n' +
            '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n' +
            '<plist version="1.0">\n<dict>\n' +
            '\t<key>com.apple.developer.nfc.readersession.formats</key>\n' +
            '\t<array>\n\t\t<string>TAG</string>\n\t\t<string>NDEF</string>\n\t</array>\n' +
            '</dict>\n</plist>\n';
        fs.writeFileSync(entPath, entContent, 'utf8');
        console.log('ios_setup_hook: Created entitlements file with NFC TAG + NDEF: ' + entPath);

        // Add CODE_SIGN_ENTITLEMENTS to pbxproj
        var pbxPath = path.join(path.join(platformRoot, appName + '.xcodeproj'), 'project.pbxproj');
        if (fs.existsSync(pbxPath)) {
            var pbx = fs.readFileSync(pbxPath, 'utf8');
            var relEntPath = appName + '/' + appName + '.entitlements';
            if (pbx.indexOf('CODE_SIGN_ENTITLEMENTS') === -1) {
                pbx = pbx.replace(/SWIFT_VERSION = 5\.0;/g,
                    'SWIFT_VERSION = 5.0;\n\t\t\t\tCODE_SIGN_ENTITLEMENTS = "' + relEntPath + '";');
                fs.writeFileSync(pbxPath, pbx, 'utf8');
                console.log('ios_setup_hook: Added CODE_SIGN_ENTITLEMENTS to build settings.');
            }
        }
        return;
    }

    // Update each entitlements file to ensure TAG is present
    for (var j = 0; j < entitlementFiles.length; j++) {
        var entFile = entitlementFiles[j];
        console.log('ios_setup_hook: Checking entitlements file: ' + entFile);

        try {
            var content = fs.readFileSync(entFile, 'utf8');

            // Simple string-based check and injection
            var nfcKey = 'com.apple.developer.nfc.readersession.formats';

            if (content.indexOf(nfcKey) === -1) {
                // Key doesn't exist - add it before </dict>
                var insertion = '\t<key>' + nfcKey + '</key>\n' +
                    '\t<array>\n\t\t<string>TAG</string>\n\t\t<string>NDEF</string>\n\t</array>\n';
                content = content.replace('</dict>', insertion + '</dict>');
                fs.writeFileSync(entFile, content, 'utf8');
                console.log('ios_setup_hook: Added NFC TAG + NDEF entitlement to: ' + path.basename(entFile));
            } else if (content.indexOf('<string>TAG</string>') === -1) {
                // Key exists but TAG is missing - add TAG
                content = content.replace(
                    '<key>' + nfcKey + '</key>\n\t<array>',
                    '<key>' + nfcKey + '</key>\n\t<array>\n\t\t<string>TAG</string>'
                );
                // Also try alternate formatting
                content = content.replace(
                    '<key>' + nfcKey + '</key>\r\n\t<array>',
                    '<key>' + nfcKey + '</key>\r\n\t<array>\r\n\t\t<string>TAG</string>'
                );
                fs.writeFileSync(entFile, content, 'utf8');
                console.log('ios_setup_hook: Added TAG to existing NFC entitlement in: ' + path.basename(entFile));
            } else {
                console.log('ios_setup_hook: NFC TAG entitlement already present in: ' + path.basename(entFile));
            }
        } catch (e) {
            console.log('ios_setup_hook: Error processing ' + entFile + ': ' + e.message);
        }
    }
}
