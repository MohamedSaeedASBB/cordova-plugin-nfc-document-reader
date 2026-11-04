# Labels

Every string the plugin's screens show, in three bundles. 113 keys each.

| | |
|---|---|
| [`en.json`](en.json) | Generated from the plugin's own defaults. **Do not edit** — regenerate it instead. |
| [`ar.json`](ar.json) | Arabic. **Draft — needs review.** |
| [`fr.json`](fr.json) | French. **Draft — needs review.** |

## The Arabic and French are drafts

They were written to give you a complete, structurally correct starting point, not to be shipped
as they stand. **Nobody at the bank has reviewed them**, and identity-verification wording is
read by customers under pressure and by regulators afterwards — the terminology, the register and
the instructions deserve the same review as any other customer-facing copy.

They are deliberately not compiled into the plugin for the same reason. Pass them from the app,
where you control them, and they change without a new plugin release.

## Using one

```javascript
var ar = /* docs/labels/ar.json, loaded or inlined */;
NfcDocumentReader.setLabels(ar);
NfcDocumentReader.setDirection("rtl");
```

Call both again whenever the application's language changes. A partial bundle is fine: keys you
leave out keep their current value, so a half-finished translation shows the rest in English
rather than blank.

## Checking a bundle

```
node tools/labels/check.js docs/labels/ar.json
```

It reports three things that are invisible when reading the file and obvious to a customer:

- **a missing key** — that one label silently stays English
- **a dropped placeholder** — `{format}`, `{index}`, `{seen}`; the screen shows a sentence with a
  hole in it
- **an unknown key** — a typo, which simply never applies

Placeholders are matched by name, not position, so a translation may reorder them. Arabic
frequently needs to.

## Checking the screens

```
node tools/labels/check-native.js
```

A correct bundle is not enough on its own. This one checks the other half — that each screen
actually asks for the keys the bundle defines:

- **a key set on a view no layout defines.** The Android screens replace their English by looking
  a view up by name, and a name nothing answers to used to be skipped in silence. That is how
  "Scan MRZ", "MRZ Detected" and "Liveness Check" stayed English on an Arabic screen while every
  other string beside them translated. Nothing in the code or in the bundle showed it; it took a
  screenshot from a device.
- **a key the native code asks for that the bundle has no entry for.** It reaches the customer as
  `capture.hint.rejected` in the middle of a sentence.
- **a failure code with no message.** `CANCELLED`, `NO_USABLE_PORTRAIT` and
  `CAMERA_PERMISSION_DENIED` were all reported to the caller with no translation behind them.
- **a key nothing asks for**, which is how a label goes stale after a screen is reworked.

Run both before a release. Between them they cover the bundle and the screens; either alone
leaves the gap the other catches.

Neither reads the code as a compiler does, so follow them with `bash tools/ios-compile/check.sh`
and `bash tools/android-compile/check.sh`. A label change touches every screen on both platforms,
which is a wide surface for an ordinary compile error, and neither platform builds locally.

## Direction is not optional for Arabic

`setDirection("rtl")` is separate from the bundle and has to be set as well. Arabic strings in a
left-to-right layout still read wrong: the close button, the step counter and the action buttons
sit where the reader finishes rather than where they start. Translating without it produces a
screen that reads correctly and behaves backwards.

It is explicit rather than inferred from the characters in the bundle, because which way a
customer's screen should flow is the application's decision, not a guess the plugin should make.

## Adding a key

The canonical list lives in `DEFAULT_LABELS` in `www/NfcDocumentReader.js` — one copy, which both
platforms are handed at call time. The native code holds no defaults at all, so a new key is added
in one place and a key that is missing renders as its own name rather than as English. Silent
English in an Arabic screen is the failure that reaches production; a visible `mrz.title` is the
one that gets fixed on the first run.

After adding one, regenerate `en.json` and re-run both checkers — between them they will tell you
which bundles now have a gap and whether the screen can actually reach the new key.

A key for text that is drawn by a layout rather than built in code needs an `android:id` on that
view, because that id is the only thing connecting the two. `check-native.js` fails if it is
missing.

## What is not in here

Messages the plugin hands to your `error` callback **are** in here, keyed by the failure code
(`error.AUTH_FAILED`, `error.TAG_LOST`, …), so the NFC sheet and the callback cannot disagree.

Two things are deliberately left in English:

- **Integration errors** — "Invalid capture options", "Invalid MRZ data. Required: …", and any
  message carrying an exception's text. They are for whoever is wiring the plugin up, and they go
  to a log, not to a customer.
- **`ISSUE_TEXT` in `www/NfcDocumentReader.js`** — the plain-language descriptions of passive
  authentication findings that travel in the result payload. They describe the document, not the
  screen, and your backend decides whether a customer ever sees one.
