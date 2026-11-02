#!/usr/bin/env node
/**
 * Checks the native screens against the key list in www/NfcDocumentReader.js.
 *
 * Two failures this catches, both of which shipped:
 *
 *   A key addressed to a view that does not exist. The Android activities replace a layout's
 *   English by looking the view up by name, and a name no view answers to was skipped in silence
 *   — so "Scan MRZ", "MRZ Detected" and "Liveness Check" stayed English on an Arabic screen while
 *   every other string on the same screen translated. Nothing in the code or the bundle showed it;
 *   it took a screenshot from a device.
 *
 *   A key the native code asks for that the bundle does not define. Labels renders a missing key
 *   as the key itself, so this reaches the customer as `capture.hint.rejected` in the middle of a
 *   sentence.
 *
 * It also lists keys nothing asks for, which is how a label goes stale after a screen is reworked.
 *
 *   node tools/labels/check-native.js
 */
const fs = require('fs');
const path = require('path');
const Module = require('module');

const root = path.join(__dirname, '..', '..');
const stubDir = fs.mkdtempSync(path.join(require('os').tmpdir(), 'labelcheck-'));
fs.mkdirSync(path.join(stubDir, 'cordova'), { recursive: true });
fs.writeFileSync(path.join(stubDir, 'cordova', 'exec.js'), 'module.exports = function () {};');
const resolve = Module._resolveFilename;
Module._resolveFilename = function (request, ...args) {
    if (request === 'cordova/exec') return path.join(stubDir, 'cordova', 'exec.js');
    return resolve.call(this, request, ...args);
};
const reference = require(path.join(root, 'www', 'NfcDocumentReader.js')).labels();

const read = (dir, ext) => fs.readdirSync(path.join(root, dir))
    .filter((f) => f.endsWith(ext))
    .map((f) => ({ name: path.join(dir, f), body: fs.readFileSync(path.join(root, dir, f), 'utf8') }));

const native = [...read('src/android', '.java'), ...read('src/ios', '.swift')];
const layouts = read('src/android', '.xml');

// Every id any layout defines, so a lookup by name can be checked against it.
const ids = new Set();
for (const layout of layouts) {
    for (const m of layout.body.matchAll(/android:id="@\+id\/([A-Za-z0-9_]+)"/g)) ids.add(m[1]);
}

const problems = [];
const used = new Set();

for (const file of native) {
    // A key may be written out whole, chosen by a ternary, or built from a prefix and a variable
    // (labels.text("liveness." + key)), so both forms are collected.
    for (const m of file.body.matchAll(/labels\.text\(([\s\S]{0,200}?)[,)]/g)) {
        for (const lit of m[1].matchAll(/"([^"]+)"/g)) {
            const key = lit[1];
            // A key always carries a namespace; anything else in the call is another argument.
            if (!key.includes('.')) continue;
            if (key.endsWith('.')) {
                // A prefix completed at runtime: every key under it is reachable.
                Object.keys(reference).filter((k) => k.startsWith(key)).forEach((k) => used.add(k));
                continue;
            }
            used.add(key);
            if (!(key in reference)) problems.push(`${file.name}: no such key "${key}"`);
        }
    }
    // applyLabels("viewName", "some.key", ...) and setLabel("viewName", "some.key") pair a layout
    // view with a key. Only Android looks views up by name; iOS builds its views in code.
    if (!file.name.endsWith('.java')) continue;
    for (const m of file.body.matchAll(/(?:applyLabels|setLabel)\(([\s\S]*?)\);/g)) {
        const args = [...m[1].matchAll(/"([^"]+)"/g)].map((a) => a[1]);
        for (let i = 0; i + 1 < args.length; i += 2) {
            const [view, key] = [args[i], args[i + 1]];
            if (view.includes('.')) continue;   // a parameter name, not a literal pair
            used.add(key);
            if (!(key in reference)) problems.push(`${file.name}: no such key "${key}"`);
            if (!ids.has(view)) {
                problems.push(`${file.name}: "${key}" is set on a view named ${view}, `
                    + `which no layout defines — the label would never be applied`);
            }
        }
    }
}

// error.* is looked up by the code the native layer reports, never written out as a literal, so
// those keys are checked against the codes instead of against the call sites.
const codes = new Set();
for (const file of native) {
    for (const m of file.body.matchAll(/"error\." \+ \(?(?:code|errorCode)/g)) codes.add('dynamic');
    for (const m of file.body.matchAll(/errorCode = "([A-Z_]+)"|errorCode: "([A-Z_]+)"|failure: "([A-Z_]+)"|fail\("([A-Z_]+)"/g)) {
        codes.add(m[1] || m[2] || m[3] || m[4]);
    }
}
codes.delete('dynamic');
for (const code of codes) {
    if (!(`error.${code}` in reference)) {
        problems.push(`a native failure reports code ${code}, but there is no "error.${code}" key`);
    }
    used.add(`error.${code}`);
}

const unused = Object.keys(reference).filter((k) => !used.has(k) && !k.startsWith('error.'));

if (problems.length) {
    console.log('✗ native screens');
    problems.forEach((p) => console.log(`    ${p}`));
} else {
    console.log(`✓ native screens — ${used.size} keys reached, every view resolves`);
}
if (unused.length) {
    console.log(`  ${unused.length} key(s) no native screen asks for:`);
    unused.forEach((k) => console.log(`    ${k}`));
}
process.exit(problems.length ? 1 : 0);
