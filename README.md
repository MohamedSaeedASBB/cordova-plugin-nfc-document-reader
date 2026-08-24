# cordova-plugin-nfc-document-reader

Reads NFC-enabled identity documents — passports and national ID cards — over ICAO 9303 / MRTD,
on Android and iOS. Scans the MRZ with the camera to derive the chip access key, reads the data
groups, verifies the chip data against the issuing state's signature, optionally runs a liveness
check and compares the holder's face against the portrait stored on the chip.

Everything runs on the device. No document data, portrait or biometric template leaves the phone.

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [API](#api) — `isNFCAvailable`, `scanMRZ`, `readNFC`, `checkLiveness`, `cancelRead`
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
| `faceMatch.threshold` | `0.9` | Built in — see [Threshold](#threshold). `null` returns the score without a pass/fail. |

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
  "faceMatch": "matched",               // "matched" | "notMatched" | "review" | "notAvailable"
  "faceMatchScore": 0.94,
  "faceMatchThreshold": 0.9,
  "issues": [],
  "blockingIssueCount": 0,
  "summary": "Document is genuine and issued by a trusted authority. A live person was present. Their face matches the chip photo."
}
```

Branch on `outcome` alone if you want one decision:

| `outcome` | Meaning | Suggested handling |
|---|---|---|
| `pass` | every check that ran succeeded | proceed |
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
  documentFaceImageBase64, documentFaceImageBytes,
  documentFaceImageWidth, documentFaceImageHeight,
  match: { status, similarity, threshold, reason, onDevice }
}
```

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

### Threshold

Built into the plugin at **0.90** cosine similarity, so nothing is passed from JavaScript.

**It is a policy floor, not a measured operating point.** The false-accept and false-reject rates
it produces on this customer population have not been established. Cosine similarity runs from -1
to 1, so 0.90 requires the two embeddings to be nearly identical — and a chip portrait is
low-resolution and often years old next to a live selfie. Expect genuine pairs to fall below it
and be reported as `notMatched` until the number is calibrated; route those to human review rather
than to a rejection.

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
