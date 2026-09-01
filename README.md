# cordova-plugin-nfc-document-reader

Reads NFC-enabled identity documents — passports and national ID cards — over ICAO 9303 / MRTD,
on Android and iOS. Scans the MRZ with the camera to derive the chip access key, reads the data
groups, verifies the chip data against the issuing state's signature, optionally runs a liveness
check and compares the holder's face against the portrait stored on the chip.

Everything runs on the device. No document data, portrait or biometric template leaves the phone.

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [API](#api) — `isNFCAvailable`, `scanMRZ`, `readNFC`, `checkLiveness`, `captureDocument`, `captureAndReadNFC`, `captureProofOfAddress`, `cancelRead`
- [Typical flow](#typical-flow)
- [Result payload](#result-payload)
- [Provisioning](#provisioning) — face-match model, CSCA trust store, threshold
- [Tools](#tools)
- [Troubleshooting](#troubleshooting)
- [What this proves, and what it does not](#what-this-proves-and-what-it-does-not)

## Requirements

| | |
|---|---|
| **Android** | NFC hardware (declared `required="true"`), camera for MRZ and liveness |
| **iOS** | iPhone 7 or later for NFC; **iOS 15.5+**, set by ML Kit 7.0 (`plugin.xml` raises the deployment target) |
| **iOS entitlement** | The App ID needs the **NFC Tag Reading** capability in the Apple Developer portal, and the provisioning profile regenerated. The entitlement in `plugin.xml` alone is not enough. |
| **Cordova** | cordova >= 10, cordova-android >= 10, cordova-ios >= 6 |

## Installation

```bash
cordova plugin add https://github.com/MohamedSaeedASBB/cordova-plugin-nfc-document-reader
```

Permissions, activities and entitlements are added by `plugin.xml`; nothing needs adding to the
host app's manifest.

### OutSystems / MABS

Add it in the module's **Extensibility Configuration**:

```json
{
    "plugin": {
        "url": "https://github.com/MohamedSaeedASBB/cordova-plugin-nfc-document-reader"
    }
}
```

Append `#branch-or-tag` to the URL to pin a specific version. The plugin is fetched at build time,
so changes need a **fresh MABS build**, not a republish. Custom Cordova plugins do not exist in
"Preview in Browser" or the OutSystems Now app — test on a generated build, and reference the
global as `window.NfcDocumentReader` inside a JavaScript node.

> Reinstalling matters after any plugin change: `cordova build` does not re-sync a plugin's
> `<source-file>` and `<resource-file>` entries. Use `cordova plugin remove` then `add`.

## API

All five functions take `success` and `error` callbacks. `error` always receives a
**user-facing message string**, never a code — the machine-readable codes (`TAG_NOT_SUPPORTED`,
`TAG_LOST`, `AUTH_FAILED`, `READ_FAILED`, `COMM_ERROR`) appear in the device log and in
diagnostics, so quote the message when reporting an issue.

### `isNFCAvailable(success, error)`

Whether the device has NFC hardware and whether it is switched on.

```js
window.NfcDocumentReader.isNFCAvailable(function (status) {
    // { available: true, enabled: true }
    if (!status.available) return show("This device does not support NFC.");
    if (!status.enabled)   return show("Please turn on NFC in your device settings.");
    startScan();
}, function (error) {
    console.log(error);
});
```

Useful as a reachability check too: if `window.NfcDocumentReader` is `undefined`, the plugin is
not in the build.

### `scanMRZ(success, error, [options])`

Opens the camera and reads the Machine Readable Zone. The MRZ carries the key material that
unlocks the chip, so this runs before `readNFC`.

```js
window.NfcDocumentReader.scanMRZ(function (mrz) {
    // { documentNumber, dateOfBirth: "YYMMDD", dateOfExpiry: "YYMMDD",
    //   rawMrzLines: [...], format: "TD1" | "TD2" | "TD3" }
}, function (error) {
    console.log(error);
}, { documentType: "id" });      // "id" or "passport" — changes the on-screen guidance only
```

### `readNFC(success, error, mrzData, [options])`

Reads the chip. `success` is called **several times**: once per progress event, then once with the
final result. Check for `event` to tell them apart.

```js
window.NfcDocumentReader.readNFC(function (data) {
    if (data.event) {
        console.log("progress:", data.state);
        return;
    }
    console.log("document:", data);
}, function (error) {
    console.log(error);
}, mrz, { liveness: true });
```

**Progress states**

| `state` | Meaning |
|---|---|
| `waitingForTag` | the scan sheet is up |
| `readerArmed` | *(Android)* the platform accepted tag detection — a tap can now be seen |
| `readerArmFailed` | tag detection could not be started; the error callback follows |
| `connecting` | a tag was found and is being connected |
| `authenticating` | PACE/BAC in progress |
| `readingDataGroup` | also carries `dgNumber` and `dgName` |

`readerArmed` is the one to watch: without it, nothing is listening for the document however
normal the sheet looks.

**`mrzData`** — `{ documentNumber, dateOfBirth, dateOfExpiry }`, dates as `YYMMDD`. Pass the
object `scanMRZ` returned.

**`options`**

| Option | Default | Purpose |
|---|---|---|
| `liveness` | *(off)* | `true`, or a `checkLiveness` options object. Chains liveness + face match onto the read. |
| `passiveAuth.trustStoreAsset` | `"csca_master_list.pem"` | CSCA bundle for the issuer check. `null` skips it. |
| `faceMatch.modelAsset` | `"mobilefacenet.tflite"` | Embedding model in app assets. `null` disables matching. |
| `faceMatch.inputSize` | `112` | Model input edge |
| `faceMatch.embeddingSize` | `192` | Model output vector length |

Safe to call directly from the `scanMRZ` callback.

### `checkLiveness(success, error, [options])`

A standalone liveness check on the front camera: a randomised challenge-response sequence (blink,
smile, turn head) driven by ML Kit face detection, returning a portrait captured from the same
verified frame stream.

```js
window.NfcDocumentReader.checkLiveness(function (result) {
    // { passed, faceImageBase64, challenges: [{ type, passed, durationMs }],
    //   signals: { framesAnalysed, durationMs, multiFaceFrames, trackingIdChanges },
    //   sdk: { provider, feature, platform, presentationAttackDetection }, capturedAt }
}, function (error) {
    console.log(error);
}, { challengeCount: 2 });
```

Options: `challenges[]`, `challengeCount` (2), `overallTimeoutMs` (45000), `perChallengeTimeoutMs`
(15000), `faceSearchTimeoutMs` (20000), `maxImageDimension` (720), `maxImageBytes` (204800),
`jpegQuality` (85), `cropToFace` (true), `includeFullFrame`, `includeChallengeFrames`, `prompts`.

Omit `challenges` so the sequence is random — a fixed order is replayable.

### `captureDocument(success, error, [options])`  *(Android only)*

Photographs the document itself. **An ID card is captured front and back; a passport is captured
once, at the photo page** — the step list follows from `documentType`, so the caller does not
describe the sides.

```js
window.NfcDocumentReader.captureDocument(function (result) {
    // result.sides.front.imageBase64, result.sides.back.imageBase64
}, function (error) {
    console.log(error);            // also called when the user cancels
}, { documentType: "id" });
```

```json
{
  "captureType": "document",
  "documentType": "id",
  "sides": {
    "front": { "key": "front", "label": "Front of the card", "imageBase64": "…",
               "imageMimeType": "image/jpeg", "imageBytes": 106842,
               "imageWidth": 900, "imageHeight": 1200, "jpegQuality": 80 },
    "back":  { … }
  },
  "order": ["front", "back"],
  "capturedAt": 1756704000000
}
```

Entries appear once, under `sides`. `order` gives the sequence without repeating the images —
carrying each base64 twice doubled the payload for no benefit.

Each shot is reviewed on screen before it is kept — nothing downstream can tell the operator that
a photo is too blurry to read while they can still retake it.

**There is no OCR here, by design.** The chip already carries these fields — including the Arabic —
covered by the issuer's signature and hash-verified. Reading them off a photograph instead would
replace proven data with a camera-dependent guess. Use `readNFC` for the data and this for the
picture. An `ocr: true` passed here is ignored.

| Option | Default | |
|---|---|---|
| `documentType` | `"id"` | `"id"` captures front and back, `"passport"` front only |
| `maxImageDimension` | `1200` | Long edge in pixels |
| `maxImageBytes` | `256000` | Quality steps down until the JPEG fits |
| `jpegQuality` | `80` | Starting quality |
| `title` | per type | Screen title |

### `captureAndReadNFC(success, error, [options])`  *(Android only)*

The whole document check in one call, in this order:

1. **Scan the MRZ** — it derives the chip access key, so it has to come first
2. **Read the chip** — with progress events, liveness and face match, exactly as `readNFC`
3. **Compare** what is printed against what the chip holds
4. **Photograph the card** — front and back for an ID, photo page for a passport

The photographs come last on purpose: the card is only photographed once there is reason to
believe it is genuine, and the customer is still holding it either way. **There is no `mrzData`
argument** — this function performs the scan itself.

```js
window.NfcDocumentReader.captureAndReadNFC(function (data) {
    if (data.event) { return show(data.state); }
    // data.mrzComparison.status        — "matched" | "mismatch" | "notCompared"
    // data.capture.sides.front.imageBase64
    // data.authentication, data.faceComparison, data.verification
}, function (error) {
    show(error);
}, { documentType: "id", liveness: true });
```

The result is the `readNFC` payload plus:

```json
"mrzComparison": { "status": "matched", "fieldsCompared": ["documentNumber", "…"],
                   "mismatches": [], "note": "…" },
"capture": { "captureType": "document", "documentType": "id",
             "sides": { "front": { … }, "back": { … } },
             "order": ["front", "back"], "capturedAt": 1756704000000 }
```

**What the comparison is worth.** `documentNumber`, `dateOfBirth` and `dateOfExpiry` derive the
chip access key, so a chip that opened at all already agreed with them — they are reported as
evidence, not as a test. The fields that can genuinely disagree are the names, nationality,
issuing state and document code. A disagreement there is what an altered card or a transplanted
chip looks like — **and also what a smudged character on worn print looks like to OCR**, so treat
`mismatch` as a finding for a human rather than as proof of fraud.

`mrzComparison` is added to any `readNFC` result whose `mrzData` included `rawMrzLines`, not only
to this flow.

> **If the photographs are abandoned**, the chip result is still delivered with `capture` absent
> and `captureCancelled: true`. A completed read cost the customer a tap and possibly a liveness
> check; discarding it over a cancelled camera screen would be worse than returning it incomplete.
> A failed chip read, by contrast, ends on the error callback.

### `captureProofOfAddress(success, error, [options])`  *(Android only)*

One page of whatever the customer brought — a utility bill, a bank statement, a tenancy contract.
Same options minus `documentType`, and the single entry is keyed `"document"`.

```js
window.NfcDocumentReader.captureProofOfAddress(function (result) {
    var page = result.sides.document;
    // page.imageBase64, page.ocr.lines
}, function (error) { console.log(error); });
```

Defaults here are larger than `captureDocument`'s — `maxImageDimension` 1800, `maxImageBytes`
600KB, `jpegQuality` 88 — because a bill's print is small and a backend re-reading the image for
Arabic is limited by what was sent, not by what the camera saw. Raise them for dense pages.

Note the on-device OCR runs on the **full-resolution frame**, before compression, so `ocr` is not
affected by these settings. Only the image you forward is.

**OCR is on by default here, and available nowhere else.** Reading the page is the reason this
capture exists, and unlike an ID card there is no chip behind a utility bill to take the text from
instead. Pass `ocr: false` for the image alone.

The plugin does not judge whether a document *is* valid proof of address — it has no idea what
counts in a given country. It returns the picture and, optionally, the text on it.

### OCR and script coverage

OCR runs on `captureProofOfAddress` only. It returns **raw recognised lines, never named fields.** Deciding which line is the
customer's address rather than the biller's is issuer-specific, and getting it wrong writes a
stranger's address onto a customer record.

**Coverage differs by platform, and this matters for Arabic documents:**

| Platform | Engine | Scripts | Arabic |
|---|---|---|---|
| Android | ML Kit Text Recognition v2 | Latin only (separate models exist for Chinese, Devanagari, Japanese, Korean) | **No** |
| iOS | Apple Vision | Many, version-dependent | **Yes**, on recent iOS |

On a bilingual Algerian document, Android returns the Latin half and simply omits the Arabic. Since
"no Arabic in the output" and "no Arabic on the page" look identical downstream, the result reports
what ran:

```json
"ocr": { "text": "…", "lines": ["…"], "lineCount": 12,
         "engine": "mlkit-text-recognition-v2", "scripts": ["Latin"],
         "arabicSupported": false }
```

If you need the Arabic, OCR the returned image in the backend — `arabicSupported: false` is the
flag to branch on. That is the recommended route in any case: OCR quality on Arabic is the hard
part, and a server-side engine can be tuned and replaced without an app release. Note that AWS
Textract's structured extraction covers six Latin languages only, so it will not do field
extraction on an Arabic bill.

Both on-device engines run with no network call and no extra dependency — ML Kit's text model is
already bundled for MRZ scanning, and Vision is a system framework.

For an ID card none of this applies: the chip carries the same fields, in Arabic, signed.

### `cancelRead(success, error)`

Cancels an in-flight read and dismisses the sheet.

```js
window.NfcDocumentReader.cancelRead(function () {}, function (error) { console.log(error); });
```

## Typical flow

```js
var NFC = window.NfcDocumentReader;

NFC.isNFCAvailable(function (status) {
    if (!status.available || !status.enabled) {
        return show("Please enable NFC and try again.");
    }

    NFC.scanMRZ(function (mrz) {
        NFC.readNFC(function (data) {
            if (data.event) { return show(data.state); }

            var auth = data.authentication.passiveAuthentication;
            var match = data.faceComparison && data.faceComparison.match;

            console.log("chip data authentic:", auth.status);          // passed/failed/notVerified
            console.log("face match:", match && match.status);         // matched/notMatched/review/deferred
            submitToBackOffice(data);
        }, function (error) {
            show(error);
        }, mrz, { liveness: true });
    }, function (error) {
        show(error);
    }, { documentType: "id" });
}, function (error) {
    show(error);
});
```

## `verification` — the block to build logic on

The native payload reports every check separately and precisely, which is right for an audit trail
and awkward for application logic. `readNFC` therefore adds a `verification` block: one outcome,
flat fields, and plain-language issues.

```json
{
  "outcome": "pass",                    // "pass" | "review" | "fail"
  "requiresManualReview": false,
  "checksPerformed": ["chipAccess", "documentAuthenticity", "liveness", "faceMatch"],
  "documentAuthentic": "yes",           // "yes" | "no" | "unknown"
  "documentTampered": false,
  "chipUnlocked": true,
  "holderPresent": "yes",               // "yes" | "no" | "notChecked"
  "faceMatch": "review",                // "review" (a score was produced) | "notAvailable"
  "faceMatchScore": 0.7474,             // the backend applies its threshold to this
  "issues": [],
  "blockingIssueCount": 0,
  "warningCount": 0,
  "summary": "Document is genuine and issued by a trusted authority. A live person was present. Face match score returned for a decision. Decide in the backend or send to manual review."
}
```

Branch on `outcome` alone if you want one decision:

| `outcome` | Meaning | Suggested handling |
|---|---|---|
| `pass` | every check that ran succeeded | proceed |
| `review` | includes every successful face match, since the device never decides one | apply your threshold to `faceMatchScore` |
| `review` | nothing was contradicted, but something could not be established | queue for an officer |
| `fail` | a check was contradicted | stop; `summary` says why |

```js
switch (data.verification.outcome) {
    case "pass":   return proceed();
    case "review": return sendToManualReview(data.verification.summary);
    case "fail":   return reject(data.verification.summary);
}
```

**Two rules it will not bend.** *Unknown is never treated as good* — a check that could not run
reports `unknown` and forces `review`, never contributing to a `pass`; an un-provisioned trust
store or missing face-match model is not evidence of anything. And *only checks that actually ran
can produce a pass* — `checksPerformed` says which those were, so a chip-only read cannot imply
anything about who presented the document.

`issues[]` entries carry `{ code, severity, message }`, with `severity` either `blocking` or
`warning`. `code` is the machine-readable value to branch on; `message` is safe to show a person.

> **While the 0.90 threshold is uncalibrated**, a genuine customer will often score below it and
> produce `outcome: "fail"` with `faceMatch: "notMatched"`. Until the threshold is measured,
> consider routing that specific combination to manual review rather than to a rejection — the
> score and the bar it missed are both in the payload.

The same function is exposed as `NfcDocumentReader.summarise(result)`, so a stored payload can be
re-summarised without another read. The detailed native blocks below are left untouched alongside
it.

## Result payload

```
{
  documentType, issuingState, primaryIdentifier, secondaryIdentifier,
  documentNumber, nationality, dateOfBirth, gender, dateOfExpiry, personalNumber,
  faceImageBase64, signatureImageBase64,
  fullNameOfHolder, otherNames, personalSummary, placeOfBirth, permanentAddress, telephone,
  issuingAuthority, dateOfIssue, endorsementsAndObservations,
  dataGroupsRead, authentication, readErrors,

  liveness,          // when options.liveness was set
  faceComparison     // when options.liveness was set
}
```

### `authentication`

```
{
  chipAccessEstablished,   // BAC or PACE unlocked the chip. Says nothing about the data.
  accessProtocol,          // "PACE" | "BAC" | null
  chipAuthentication,      // "success" | "failed" | "notDone" | "notPerformed"
  passiveAuthentication: {
    status,                // "passed" | "failed" | "notVerified"
    sodSignatureVerified,  // the SOD is validly signed by the certificate it carries
    dataIntegrityVerified, // every data group read hashes to the value in the SOD
    issuerTrusted,         // that certificate chains to a CSCA in the trust store
    digestAlgorithm, signatureAlgorithm, documentSignerSubject,
    trustStore,            // "none" | "unreadable" | "loaded" | "loaded:<count>"
    dataGroupHashes,       // { "1": true, "2": true, ... }
    reasons                // e.g. ["NO_TRUST_ANCHORS"], ["DG_HASH_MISMATCH:2"]
  }
}
```

If you read one field, read `passiveAuthentication.status`:

- **`passed`** — the data is what the issuing state signed, and the signer is trusted
- **`failed`** — something was contradicted. Do not treat the data as authentic.
- **`notVerified`** — nothing contradicted, but authenticity was not established. Usually
  `NO_TRUST_ANCHORS`: no CSCA bundle installed, so the chip is only *self-consistent* — which a
  forger can also achieve.

> `bacSucceeded` and `chipAuthSucceeded` were removed in favour of this block. On Android the old
> flag was set from the mere presence of a certificate — nothing was verified — and on iOS it
> carried a different protocol's status.

### `faceComparison`

```
{
  documentPortrait: { faceDetected, faceCount, faceAreaRatio, yaw, pitch, roll,
                      frontal, largeEnough, imageWidth, imageHeight },
  livenessPortrait: { ...same shape... },
  screening: { passed, reasons[], note },        // quality gate, NOT an identity match

  // the two detected faces, cropped as the matcher consumed them
  documentFaceImageBase64, documentFaceImageBytes,
  documentFaceImageWidth, documentFaceImageHeight,
  livenessFaceImageBase64, livenessFaceImageBytes,
  livenessFaceImageWidth, livenessFaceImageHeight,

  match: { status, similarity, threshold, reason, onDevice }
}
```

### Multi-part fields: `placeOfBirthLines`, `permanentAddressLines`

DG11 separates a field's components with `<`, and issuers use that freely. On an Algerian ID the
"permanent address" field carries **three** values — marital status, an Arabic value, and a blood
group — and the place of birth is stated twice, Latin then Arabic.

Joined for display, those read as `"M, الجزائر, AB+"` and `"BORDJ EL KIFFAN, برج الكيفان"`: one
looks like a nonsensical address, the other like "city, region". Neither is what it appears to be.

So the components are returned as arrays too:

```json
"permanentAddress":      "M, الجزائر, AB+",
"permanentAddressLines": ["M", "الجزائر", "AB+"],
"placeOfBirth":          "BORDJ EL KIFFAN, برج الكيفان",
"placeOfBirthLines":     ["BORDJ EL KIFFAN", "برج الكيفان"]
```

**Build logic on the arrays and treat the joined strings as display text.** What each component
means is the issuer's convention, not something the plugin can tell you — confirm the mapping
against the physical document per issuing country before storing it in named fields.

### Raw data groups

Pass `includeRawDataGroups: true` to `readNFC` to get each data group exactly as read from the
chip (both platforms):

```json
"rawDataGroups": { "1": "<base64>", "2": "<base64>", "11": "<base64>",
                   "12": "<base64>", "sod": "<base64>" }
```

These are the bytes passive authentication hashes, so a backend can **re-verify the issuer's
signature itself** rather than trusting the handset's verdict — worth doing for a decision that
matters, since anything a phone reports about its own integrity is only as trustworthy as the
phone. They also let a backend re-decode text this plugin got wrong.

Off by default: it is a second full copy of every field including the portrait, in the rawest form
the holder's data takes, and it roughly doubles the payload.

### Non-Latin text and `textEncoding`

ICAO 9303 specifies UTF-8 for the DG11/DG12 text fields, and most documents comply. Some do not:
an Algerian ID in testing stored its Arabic fields in a single-byte Arabic code page, so UTF-8
decoding turned every Arabic letter into `U+FFFD` and the holder's Arabic name arrived as a row of
boxes.

The plugin detects that and decodes the affected fields again from the chip's raw bytes — the same
bytes passive authentication hashes, so they are known to be exactly what the issuer signed. Only
fields that actually failed are touched, so a conformant document is unaffected.

`textEncoding` reports the outcome:

| Value | Meaning |
|---|---|
| `null` | the document was conformant; text decoded as UTF-8 |
| `"windows-1256"` / `"ISO-8859-6"` | text was recovered using this code page |

**A non-null value means the encoding was inferred, not declared.** The candidate code pages agree
on the core Arabic letters and differ elsewhere, so recovered names should be checked against the
physical document before being trusted as a customer record. The encoding is chosen once per
document from all its damaged fields together, so fields cannot disagree with each other.

Both platforms do this, but the failure they recover from looks different and is easy to misread.
Android's decoder substitutes `U+FFFD` per unreadable byte, so damage shows as boxes. iOS returns
nil for the whole field, so the same document arrives with **empty fields** — which reads as "this
document has no place of birth" rather than as an error. Detection therefore works from the raw
bytes on both, not from the decoded string.

### Images in the payload

Every image is base64 JPEG or PNG, ready to drop into an `<img src="data:image/jpeg;base64,…">`.

| Path | What it is |
|---|---|
| `faceImageBase64` | full portrait from the chip (DG2) |
| `signatureImageBase64` | holder's signature from the chip (DG7), when present |
| `liveness.faceImageBase64` | portrait captured during the liveness check |
| `faceComparison.documentFaceImageBase64` | **detected face** cropped from the chip portrait |
| `faceComparison.livenessFaceImageBase64` | **detected face** cropped from the live capture |

The last two are the pair the similarity score was computed from — show those side by side on a
review screen, not the full frames. Each is accompanied by `…Bytes`, `…Width` and `…Height`, and
each is present only when a face was actually detected on that side; `faceComparison.screening`
and the `documentPortrait` / `livenessPortrait` metrics say why if one is missing.

`match.status`:

| Status | Meaning |
|---|---|
| `matched` / `notMatched` | the score was compared against the threshold |
| `review` | the score is real but the threshold was cleared — a human decides |
| `deferred` | no comparison ran, nothing is broken: `MODEL_NOT_INSTALLED` or `NO_MODEL_CONFIGURED` |
| `error` | never a pass: `MODEL_NOT_FOUND`, `EMBEDDING_LENGTH_MISMATCH`, `MISSING_PORTRAIT`, `MATCHER_FAILED` |

## Provisioning

Two files are not committed, because each is a governance decision rather than a coding one. The
plugin works without them and says so in the payload instead of pretending otherwise.

### Face-match model

On-device face verification needs a TensorFlow Lite embedding model — ML Kit does face
*detection* only, and iOS exposes no public face-recognition API. Defaults target the
MobileFaceNet family: 112×112 input, 192-d embedding, `(x - 127.5) / 128`.

```bash
tools/install-face-model.sh /path/to/approved/mobilefacenet.tflite
cordova plugin remove cordova-plugin-nfc-document-reader && cordova plugin add <path>
```

Without it: `match.status = "deferred"`, `reason = "MODEL_NOT_INSTALLED"`. See
[`src/models/README.md`](src/models/README.md).

### CSCA trust store

Passive authentication can only confirm the *issuer* if it has a bundle of Country Signing CA
certificates to check against. Put the PEM bundle at `src/csca/csca_master_list.pem`, uncomment
the two `<!-- CSCA trust store -->` blocks in `plugin.xml`, and reinstall.

Without it: `passiveAuthentication.status = "notVerified"`, `reasons = ["NO_TRUST_ANCHORS"]`. See
[`src/csca/README.md`](src/csca/README.md).

### Threshold — there isn't one

**The plugin has no threshold and no option to set one.** It computes the cosine similarity and
returns it; the backend decides what the number means:

```json
"match": { "status": "review", "similarity": 0.7474, "reason": null, "onDevice": true }
```

`verification.outcome` is `review` with `faceMatchScore` populated. `"review"` is the only
successful match status — the device measures, it never decides.

A decision boundary on the handset cannot be changed without an app release, cannot be audited
centrally, and sits on a device an attacker controls. In the backend it can be tuned, versioned and
recalibrated as `tools/face-match-calibration` produces better FAR/FRR numbers — and one value
governs every channel rather than every installed build.

A genuine read observed in testing scored **0.7474**, which is a useful reminder that thresholds
copied from public benchmarks do not transfer to chip-portrait-versus-selfie pairs.

## Tools

| Tool | Purpose |
|---|---|
| `tools/install-face-model.sh` | Installs an approved `.tflite`, verifies the FlatBuffer identifier, prints the SHA-256, uncomments the staging blocks |
| `tools/face-match-calibration/calibrate.py --inspect` | Reports a model's real input/output shapes and the config they imply |
| `tools/face-match-calibration/calibrate.py` | Sweeps thresholds over labelled pairs and reports FAR/FRR per operating point |

The calibration input is customer biometric data: it makes no network calls, copies no images into
its output, and offers `--anonymise-ids`. Run it inside the bank's environment. See
[`tools/face-match-calibration/README.md`](tools/face-match-calibration/README.md).

## Troubleshooting

| Symptom | Cause |
|---|---|
| `window.NfcDocumentReader` is `undefined` | Plugin not in the build — Preview/OutSystems Now, or the extensibility config did not fetch it |
| Sheet sits on "Ready to scan", no `readerArmed` event | Tag detection never started; the error callback carries the reason. Check logcat tag `NfcDocReaderPlugin` |
| Sheet armed but taps do nothing | Antenna position (upper back on most phones), or a document that is not ISO-DEP |
| "Unable to read this document. Please ensure the document details are correct…" | MRZ key material wrong — re-scan the MRZ (logged as `AUTH_FAILED`) |
| `match.reason = "MODEL_NOT_INSTALLED"` | Expected until a model is installed; the chip read itself succeeded |
| `passiveAuthentication.reasons = ["NO_TRUST_ANCHORS"]` | Expected until a CSCA bundle is installed |
| Plugin changes not taking effect | `cordova build` does not re-sync plugin sources — remove and re-add the plugin |

Android log tags: `NfcDocReaderPlugin`, `NfcDocumentReader`, `PassiveAuth`, `FaceMatcher`.
iOS: the same names in Console.

## What this proves, and what it does not

**Proves**, when `passiveAuthentication.status` is `passed`: the data groups read off the chip are
what the issuing state signed, and the signer chains to a CSCA you trust.

**Does not prove:**

- **That the chip is not a clone.** Passive authentication verifies data, not the medium. A
  bit-perfect copy passes. Chip Authentication detects cloning; `chipAuthentication` reports
  whether it ran — `notPerformed` on Android today.
- **That the holder is the rightful holder.** That is the face match, reported separately, and it
  is only as good as the model and threshold behind it.
- **That a live person was present.** `liveness.sdk.presentationAttackDetection` is `false` in
  every payload. ML Kit has no presentation-attack detection: challenge-response defeats a printed
  or displayed photo, but not a replayed video, an injected camera feed, or a 3D mask.
- **Revocation.** Neither platform consults CRLs or PKD deltas, so a document signer certificate
  revoked after issuance still verifies.

A high similarity score against a chip portrait plus a liveness pass is strong evidence, not
proof.

## Licence

MIT
