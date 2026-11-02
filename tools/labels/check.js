#!/usr/bin/env node
/**
 * Checks a label bundle against the plugin's own key list.
 *
 * A translation is easy to get subtly wrong in ways nobody notices until a customer does: a key
 * missing, so one label silently stays English; a `{format}` placeholder dropped, so the screen
 * shows a sentence with a hole in it; a key invented, which simply never applies. None of those
 * are visible by reading the file.
 *
 *   node tools/labels/check.js docs/labels/ar.json
 *   node tools/labels/check.js docs/labels/*.json
 */
const fs = require('fs');
const path = require('path');
const Module = require('module');

const pluginPath = path.join(__dirname, '..', '..', 'www', 'NfcDocumentReader.js');
const stubDir = fs.mkdtempSync(path.join(require('os').tmpdir(), 'labelcheck-'));
fs.mkdirSync(path.join(stubDir, 'cordova'), { recursive: true });
fs.writeFileSync(path.join(stubDir, 'cordova', 'exec.js'), 'module.exports = function () {};');
const resolve = Module._resolveFilename;
Module._resolveFilename = function (request, ...args) {
    if (request === 'cordova/exec') return path.join(stubDir, 'cordova', 'exec.js');
    return resolve.call(this, request, ...args);
};

const plugin = require(pluginPath);
const reference = plugin.labels();
const placeholders = (text) => (String(text).match(/\{[a-zA-Z]+\}/g) || []).sort();

let failed = false;
for (const file of process.argv.slice(2)) {
    const bundle = JSON.parse(fs.readFileSync(file, 'utf8'));
    const problems = [];

    for (const key of Object.keys(reference)) {
        if (!(key in bundle)) { problems.push(`missing key: ${key}`); continue; }
        if (typeof bundle[key] !== 'string' || !bundle[key].length) {
            problems.push(`empty value: ${key}`);
            continue;
        }
        const want = placeholders(reference[key]);
        const got = placeholders(bundle[key]);
        if (want.join(',') !== got.join(',')) {
            problems.push(`placeholders in ${key}: expected ${want.join(' ') || '(none)'}`
                + `, found ${got.join(' ') || '(none)'}`);
        }
    }
    for (const key of Object.keys(bundle)) {
        if (!(key in reference)) problems.push(`unknown key: ${key}`);
    }

    const name = path.basename(file);
    if (problems.length) {
        failed = true;
        console.log(`✗ ${name}`);
        problems.forEach((p) => console.log(`    ${p}`));
    } else {
        console.log(`✓ ${name} — ${Object.keys(bundle).length} keys, placeholders intact`);
    }
}
fs.rmSync(stubDir, { recursive: true, force: true });
process.exit(failed ? 1 : 0);
