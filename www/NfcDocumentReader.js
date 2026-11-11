var exec = require('cordova/exec');

var SERVICE_NAME = 'NfcDocumentReader';

/**
 * ---------------------------------------------------------------------------------------------
 * Business-friendly verdict
 * ---------------------------------------------------------------------------------------------
 * The native payload reports each check separately and precisely, which is right for an audit
 * trail and awkward for application logic: deciding whether to let a customer through should not
 * require reading eight nested booleans and knowing which combinations mean what.
 *
 * `summarise` folds those into one `verification` block: a single outcome, plain-language issues,
 * and flat fields an OutSystems (or any) flow can branch on directly. It is computed in
 * JavaScript on purpose — one implementation for both platforms, rather than the same decision
 * table written twice in Java and Swift, where the two would drift.
 *
 * Two rules it will not bend:
 *
 *   1. Unknown is never treated as good. A check that could not run reports "unknown" and forces
 *      "review" — it never contributes to a "pass". An un-provisioned trust store or a missing
 *      face-match model is not evidence of anything.
 *   2. Only checks that actually ran can produce a pass, and `checksPerformed` says which those
 *      were. A chip-only read that passes says the document is authentic; it says nothing about
 *      who presented it, and `holderPresent` stays "notChecked" to keep that visible.
 *
 * The detailed native blocks are left untouched alongside it, so nothing is lost.
 */

/**
 * ---------------------------------------------------------------------------------------------
 * Labels
 * ---------------------------------------------------------------------------------------------
 * Every string the plugin's screens can show, in one place, overridable by the host app.
 *
 * WHY THE DEFAULTS LIVE HERE AND NOT IN THE NATIVE CODE
 * There is one copy of this text and both platforms are handed it. Two hardcoded English copies
 * would drift, and this project has watched that happen repeatedly in smaller ways — "notPerformed"
 * against "notDone", "I" against "ID", a name split one way on Android and another on iOS. Drifting
 * in a language nobody on the team reads is worse: nothing looks wrong until a customer says so.
 *
 * So the native side holds no defaults at all. `resolveLabels` fills every key before the call
 * crosses the bridge, and a screen that renders an empty label is a missing key here, not a
 * platform difference.
 *
 * WHY NOT ANDROID values-ar/ AND iOS .lproj
 * Native resources follow the handset's locale. An application with its own language switch — which
 * this one has — would show Arabic prompts to a customer whose phone is in English, and English to
 * one who chose Arabic in the app. The copy also belongs to the bank: identity-verification wording
 * is reviewed, and translations compiled into a plugin are translations nobody approved.
 *
 * PLACEHOLDERS
 * `{name}` is substituted by the native layer. They are listed per key below; a translation may
 * reorder them but must not invent or drop one.
 */
var DEFAULT_LABELS = {
    // ---- NFC bottom sheet ----
    "nfc.ready.title":              "Ready to Scan",
    "nfc.ready.description":        "Hold your document against the back of your phone and keep it still.",
    "nfc.status.waiting":           "Waiting for document...",
    "nfc.status.connecting":        "Connecting...",
    "nfc.status.authenticating":    "Authenticating...",
    "nfc.status.reading":           "Reading {dataGroup}...",    // {dataGroup}
    // Apple's own NFC modal, which sits in front of the plugin's sheet for the whole read.
    // iOS draws it; Android has no equivalent and ignores both keys.
    "nfc.system.present":           "Hold your iPhone near the document.",
    "nfc.system.error":            "Error: {detail}",   // {detail}, from the OS, always English
    "nfc.reading.title":            "Reading Document",
    "nfc.reading.description":      "Keep the document still against your phone.\nDo not move it until reading is complete.",
    "nfc.success.title":            "Success",
    "nfc.success.description":      "Document data read successfully.",
    "nfc.success.status":           "Complete",
    "nfc.error.title":              "Error",
    "nfc.error.status":             "Failed",
    "nfc.button.cancel":            "Cancel",
    "nfc.button.close":             "Close",

    // ---- MRZ scanner ----
    "mrz.title":                    "Scan MRZ",
    "mrz.hint.id":                  "Point camera at the MRZ lines on the back of your ID card",
    "mrz.hint.passport":            "Point camera at the MRZ lines on your passport data page",
    "mrz.status.detecting":         "Detecting... ({lines} lines found)",   // {lines}
    "mrz.status.scanning":          "Scanning...",
    "mrz.status.detected":          "MRZ Detected ({format})",   // {format}
    "mrz.status.confirming":        "Hold steady — confirming ({seen}/{required})",  // {seen} {required}
    "mrz.status.cameraUnavailable": "Camera not available",
    "mrz.status.permissionRequired":"Camera permission is required to scan MRZ",
    "mrz.result.title":             "MRZ Detected",
    "mrz.result.documentNumber":    "Document",
    "mrz.result.dateOfBirth":       "Date of Birth",
    "mrz.result.dateOfExpiry":      "Date of Expiry",
    "mrz.button.confirm":           "Confirm",
    "mrz.button.rescan":            "Re-scan",
    "mrz.button.cancel":            "Cancel",
    "mrz.button.close":             "Close",   // the ✕ button; read aloud, not drawn


    // ---- Document capture ----
    "capture.title.id":             "Capture ID card",
    "capture.title.passport":       "Capture passport",
    "capture.title.proofOfAddress": "Proof of address",
    "capture.title.document":       "Capture document",
    "capture.step":                 "STEP {index} OF {total} — {label}",  // {index} {total} {label}
    "capture.stepSingle":           "{label}",   // {label}; shown when there is only one step
    "capture.step.front.label":     "Front of the card",
    "capture.step.front.hint":      "Place the front of the card flat and fill the frame",
    "capture.step.back.label":      "Back of the card",
    "capture.step.back.hint":       "Now turn the card over and photograph the back",
    "capture.step.passport.label":  "Passport photo page",
    "capture.step.passport.hint":   "Open the passport at the photo page and fill the frame",
    "capture.step.document.label":  "Proof of address",
    "capture.step.document.hint":   "Photograph the whole page, including the name and address",
    "capture.step.generic.label":   "Document",
    "capture.step.generic.hint":    "Place the document flat and fill the frame",
    "capture.hint.review":          "Check the photo is sharp and the whole document is visible",
    "capture.hint.reviewConfirmed": "Document recognised — check it is sharp and complete",
    "capture.hint.checking":        "Checking the photo...",
    "capture.hint.saveFailed":      "That photo could not be saved. Please retake it.",
    "capture.hint.captureFailed":   "The photo could not be taken. Please try again.",
    "capture.hint.readFailed":      "That photo could not be read. Please try again.",
    "capture.hint.cameraUnavailable": "The camera could not be started.",
    "capture.hint.rejected":        "This does not look like the document. Please retake it.",
    "capture.hint.unrecognised":    "We could not recognise the document in this photo. Retake it, or use it anyway if it is correct.",
    "capture.permissionRequired":   "Camera permission is required to photograph the document.",
    "capture.button.capture":       "Capture",
    "capture.button.use":           "Use photo",
    "capture.button.retake":        "Retake",
    "capture.button.cancel":        "Cancel",
    "capture.button.close":         "Close",   // the ✕ button; read aloud, not drawn
    "capture.a11y.photo":           "Captured document",   // the review image, read aloud


    // ---- Liveness ----
    "liveness.title":               "Liveness Check",
    "liveness.step":                "STEP {index} OF {total}",   // {index} {total}
    "liveness.findFace":            "Position your face inside the oval",
    "liveness.center":              "Centre your face in the oval",
    "liveness.tooFar":              "Move a little closer",
    "liveness.tooClose":            "Move a little further away",
    "liveness.multipleFaces":       "Only one person should be in frame",
    "liveness.blink":               "Blink slowly",
    "liveness.smile":               "Smile",
    "liveness.turnLeft":            "Slowly turn your head to your left",
    "liveness.turnRight":           "Slowly turn your head to your right",
    "liveness.turnLeftSmile":       "Turn your head to your left and smile",
    "liveness.turnRightSmile":      "Turn your head to your right and smile",
    "liveness.turnLeftBlink":       "Turn your head to your left, then blink",
    "liveness.turnRightBlink":      "Turn your head to your right, then blink",
    "liveness.hold":                "Hold still and look at the camera",
    "liveness.success":             "Done",
    "liveness.failed":              "Liveness check failed",
    "liveness.hint":                "Hold your phone at eye level in even lighting",
    "liveness.permissionRequired":  "Camera permission is required for the liveness check.",
    "liveness.button.cancel":       "Cancel",
    "liveness.button.close":        "Close",   // the ✕ button; read aloud, not drawn


    // ---- Messages delivered to the error callback ----
    // Keyed by the code the native layer reports, so the mapping lives here rather than being
    // translated in two places. A code with no entry is passed through as-is.
    "error.NO_FACE_TIMEOUT":        "We could not see your face. Please try again in better lighting.",
    "error.CHALLENGE_TIMEOUT":      "The on-screen instruction was not completed in time. Please try again.",
    "error.MULTIPLE_FACES":         "More than one face was visible. Please make sure you are alone in frame.",
    "error.FACE_CHANGED":           "The face in front of the camera changed during the check. Please try again.",
    "error.OVERALL_TIMEOUT":        "The liveness check took too long. Please try again.",
    "error.NO_USABLE_PORTRAIT":     "We could not capture a clear photo. Please try again in better lighting.",
    "error.CAMERA_UNAVAILABLE":     "The camera could not be started.",
    "error.CAMERA_PERMISSION_DENIED": "Camera permission is required.",
    "error.MRZ_CANCELLED":          "MRZ scan cancelled",
    "error.CAPTURE_CANCELLED":      "Document capture was cancelled.",
    "error.NFC_CANCELLED":          "NFC reading cancelled",
    "error.NFC_UNAVAILABLE":        "NFC is not available on this device.",
    "error.NFC_DISABLED":           "NFC is turned off. Please enable NFC in your device settings.",
    "error.READ_FAILED":            "Unable to read document data. Please keep the document still and try again.",
    "error.AUTH_FAILED":            "Unable to read this document. Please ensure the document details are correct and try again.",
    "error.TAG_NOT_SUPPORTED":      "This document's chip could not be detected. Please try repositioning it.",
    "error.TAG_LOST":               "Connection lost. Please hold the document steady on your phone and try again.",
    "error.COMM_ERROR":             "A communication error occurred. Please try again and keep the document still.",
    "error.INVALID_MRZ":            "The document details provided are invalid. Please scan the document again.",
    "error.CANCELLED":              "The check was cancelled.",
    "error.LIVENESS_FAILED":        "The liveness check could not be completed. Please try again.",
    "error.RESULT_ENCODING_FAILED": "The check finished but its result could not be prepared. Please try again.",
    "error.NFC_START_FAILED":       "NFC scanning could not be started. Please return to the previous screen and try again.",
    "error.FACE_MATCH_FAILED":      "The document was read but the face comparison failed. Please try again.",
    "error.UNKNOWN":                "An unexpected error occurred. Please try again."
};

/** Overrides set by the host app, applied to every subsequent call. */
var sessionLabels = {};
/** "ltr" or "rtl". Explicit rather than inferred: see setDirection. */
var sessionDirection = "ltr";

/**
 * Replaces one or more labels for every subsequent call.
 *
 * Call it once at startup and again whenever the application's language changes. Keys not listed
 * keep their current value, so a partial bundle is fine — and a partially translated one shows the
 * untranslated keys in English rather than blank.
 *
 * An unknown key is reported rather than ignored. The previous `prompts` option dropped them
 * silently, which turns a typo into a label that simply never changes and no sign of why.
 */
function setLabels(overrides) {
    if (!overrides || typeof overrides !== "object") return;
    var unknown = [];
    for (var key in overrides) {
        if (!Object.prototype.hasOwnProperty.call(overrides, key)) continue;
        if (!Object.prototype.hasOwnProperty.call(DEFAULT_LABELS, key)) {
            unknown.push(key);
            continue;
        }
        if (typeof overrides[key] === "string") sessionLabels[key] = overrides[key];
    }
    if (unknown.length && typeof console !== "undefined" && console.warn) {
        console.warn("[NfcDocumentReader] Unknown label key(s) ignored: " + unknown.join(", ")
            + ". See NfcDocumentReader.labelKeys().");
    }
}

/**
 * Lays the plugin's screens out left-to-right or right-to-left.
 *
 * Separate from the labels on purpose. Arabic text in a left-to-right layout is still wrong — the
 * back arrow, the step counter and the buttons all sit on the side the reader finishes at, not the
 * side they start from. Translating without this produces a screen that reads correctly and
 * behaves backwards.
 *
 * Explicit rather than inferred from the characters in the bundle: a guess about which way a
 * customer's screen should flow is not the plugin's to make.
 */
function setDirection(direction) {
    sessionDirection = direction === "rtl" ? "rtl" : "ltr";
}

/** Every label, with session overrides and then this call's overrides applied. */
function resolveLabels(perCall) {
    var resolved = {};
    for (var key in DEFAULT_LABELS) {
        if (Object.prototype.hasOwnProperty.call(DEFAULT_LABELS, key)) {
            resolved[key] = sessionLabels[key] !== undefined ? sessionLabels[key] : DEFAULT_LABELS[key];
        }
    }
    if (perCall && typeof perCall === "object") {
        for (var k in perCall) {
            if (Object.prototype.hasOwnProperty.call(resolved, k)
                && typeof perCall[k] === "string") {
                resolved[k] = perCall[k];
            }
        }
    }
    return resolved;
}

/** Adds the resolved labels and direction to a call's options, without mutating the caller's. */
function withLabels(options) {
    var out = {};
    var source = options || {};
    for (var k in source) {
        if (Object.prototype.hasOwnProperty.call(source, k)) out[k] = source[k];
    }
    out.labels = resolveLabels(source.labels);
    out.direction = source.direction === "rtl" || source.direction === "ltr"
        ? source.direction : sessionDirection;
    return out;
}

/** code -> [severity, plain-language message]. Severity: "blocking" | "warning". */
var ISSUE_TEXT = {
    // Passive authentication
    SOD_SIGNATURE_INVALID:       ["blocking", "The chip's signature is not valid. The document data cannot be trusted."],
    SOD_CONTENT_DIGEST_MISMATCH: ["blocking", "The chip's signed contents do not match the signature. Possible tampering."],
    SOD_SIGNATURE_UNCHECKABLE:   ["warning",  "The chip's signature could not be checked."],
    DG_HASHES_UNCHECKABLE:       ["warning",  "The chip data could not be checked against the issuer's signature."],
    NO_DATA_GROUPS_TO_VERIFY:    ["warning",  "No chip data was available to verify."],
    NO_DOC_SIGNING_CERTIFICATE:  ["blocking", "The chip carries no signing certificate, so its data cannot be verified."],
    ISSUER_NOT_TRUSTED:          ["blocking", "The certificate that signed this document is not from a trusted issuing authority."],
    NO_TRUST_ANCHORS:            ["warning",  "The list of trusted issuing authorities is not installed, so the document's issuer could not be confirmed."],
    TRUST_STORE_UNREADABLE:      ["warning",  "The list of trusted issuing authorities could not be read."],
    SOD_NOT_READ:                ["warning",  "The chip's security data could not be read."],
    DOC_SIGNER_CERTIFICATE_EXPIRED: ["warning", "The certificate that signed this document has expired. Normal for older documents."],
    NOT_RUN:                     ["warning",  "The document authenticity check did not run."],
    // Face match
    MODEL_NOT_INSTALLED:         ["warning",  "Face matching is not enabled in this build."],
    NO_MODEL_CONFIGURED:         ["warning",  "Face matching is switched off."],
    MODEL_NOT_FOUND:             ["warning",  "The face matching model is missing from this build."],
    EMBEDDING_LENGTH_MISMATCH:   ["warning",  "The face matching model is misconfigured."],
    MISSING_PORTRAIT:            ["warning",  "One of the two photos was missing, so no face comparison was made."],
    NO_FACE_DETECTED:            ["warning",  "No face could be found in the chip photo or the selfie, so no face comparison was made."],
    MATCHER_FAILED:              ["warning",  "The face comparison could not be completed."],
    FACE_SCORE_RETURNED:         ["info",     "Face match score returned for the backend to decide on."],
    // Raised by summarise itself rather than by the native layer, so that every failure carries a
    // reason a person can act on.
    LIVENESS_FAILED:             ["blocking", "The liveness check did not pass — no live person was confirmed in front of the camera."],
    CHIP_NOT_UNLOCKED:           ["blocking", "The document's chip could not be unlocked."],
    NO_CHECKS_PERFORMED:         ["warning",  "No verification checks were completed on this result."]
};

function describeIssue(code) {
    // A per-data-group code arrives as "DG_HASH_MISMATCH:2".
    var base = String(code).split(":")[0];
    if (base === "DG_HASH_MISMATCH" || base === "DG_NOT_COVERED_BY_SOD") {
        return {
            code: code,
            severity: "blocking",
            message: "Part of the chip data does not match the issuer's signature. Possible tampering."
        };
    }
    var known = ISSUE_TEXT[base];
    return {
        code: code,
        severity: known ? known[0] : "warning",
        message: known ? known[1] : "Unrecognised check result: " + code
    };
}

/**
 * ---------------------------------------------------------------------------------------------
 * One envelope on every result
 * ---------------------------------------------------------------------------------------------
 * The nine functions grew separately and returned five different top-level shapes: chip fields at
 * the root of one, the same data nested in another, `capturedAt` on three of five, `verification`
 * on three of seven. A backend consuming more than one of them had to write a mapping per
 * function and know which was which.
 *
 * Every result now carries the same five keys, whichever function produced it:
 *
 *   schemaVersion   so this can change again without guessing
 *   captureType     which function produced this payload
 *   capturedAt      ISO 8601, UTC
 *   completed       did the flow finish every step it set out to
 *   verification    always present, always the same shape
 *
 * plus `document` — the holder's identity as best it is known, from the chip where there was one
 * and from the MRZ otherwise, with `source` saying which. That is the block application logic
 * actually wants, and it was previously spelled three different ways.
 *
 * WHAT DELIBERATELY DID NOT MOVE
 * Nothing was relocated and nothing was removed. The existing fields are all still exactly where
 * they were, because a bank backend is already mapping them and a silent restructure would break
 * it in production rather than at build time. `document` duplicates about twenty short strings,
 * which is cheap; the images are large, so they are referenced where they already live and never
 * copied. Requirement 4 of the bank's letter was raised because card images appeared twice.
 *
 * When the backend has moved onto `document`, the root-level copies can go in a schemaVersion 2.
 */
function unify(result, captureType) {
    if (!result || typeof result !== "object" || result.event) return result;

    result.schemaVersion = 1;
    // The function that produced this payload, always, spelled the same way it is called.
    //
    // `captureType` is not reused for it. The native layer already sets that on three of the
    // flows, to "document", "proofOfAddress" and "documentAndLiveness" — names that match neither
    // each other nor the functions — and a backend is already reading them. Overwriting would fix
    // the spelling by breaking production, so the inconsistent field is left exactly as it is and
    // a predictable one is added beside it. `captureType` can go in a schemaVersion 2.
    result.producedBy = captureType;
    if (!result.captureType) result.captureType = captureType;
    if (!result.capturedAt) result.capturedAt = new Date().toISOString();
    if (!result.hasOwnProperty("completed")) result.completed = true;
    if (!result.verification) result.verification = summarise(result);

    var document = identityOf(result);
    if (document) result.document = document;

    return result;
}

/**
 * The holder's identity, from wherever this payload knows it.
 *
 * Chip data wins over the MRZ because it is signed and carries fields the MRZ has no room for.
 * `source` is reported rather than inferred, so a backend can hold MRZ-only identity to a
 * different standard than chip-derived identity — they are not equally trustworthy, and a payload
 * that presented them identically would invite treating them as though they were.
 */
function identityOf(result) {
    var mrz = result.mrz || null;
    var fromChip = typeof result.documentNumber === "string" && result.documentNumber.length > 0;
    var fromMrz = mrz && typeof mrz.documentNumber === "string" && mrz.documentNumber.length > 0;
    if (!fromChip && !fromMrz) return null;

    var src = fromChip ? result : mrz;
    // An MRZ scan returns only the three fields needed to open the chip, but the raw lines it also
    // returns carry the name, nationality and the rest. Without this, a chip-less flow reports an
    // identity with a document number and no holder — uniform in shape and useless in practice.
    var printed = fromChip ? null : parseMrzLines(mrz.rawMrzLines);

    function value(key) {
        var v = src[key];
        if (typeof v === "string" && v.length) return v;
        if (printed && typeof printed[key] === "string" && printed[key].length) return printed[key];
        return null;
    }

    return {
        source: fromChip ? "chip" : "mrz",
        documentType:   value("documentType"),
        documentNumber: value("documentNumber"),
        issuingState:   value("issuingState"),
        nationality:    value("nationality"),
        dateOfBirth:    value("dateOfBirth"),
        dateOfExpiry:   value("dateOfExpiry"),
        dateOfIssue:    value("dateOfIssue"),
        personalNumber: value("personalNumber"),
        // Named for what they are. "primaryIdentifier" is ICAO's wording, not a bank's.
        surname:        value("primaryIdentifier"),
        givenNames:     value("secondaryIdentifier"),
        fullName:       value("fullNameOfHolder"),
        gender:         value("gender")
    };
}

/**
 * Wraps a success callback so every payload leaves through `unify`.
 *
 * Progress events pass through untouched: readNFC and captureAndReadNFC keep the callback open and
 * push `{ event: "stateChanged", ... }` through it many times before the result, and stamping an
 * envelope onto one would make it look like a finished payload to anything checking `captureType`.
 */
function wrap(success, captureType) {
    return function(data) {
        success(unify(data, captureType));
    };
}

/**
 * Reads the ICAO 9303 fields out of scanned MRZ lines.
 *
 * The offsets are the standard's, and the same ones MrzChipComparison uses natively on both
 * platforms. This exists in JavaScript as well because the MRZ-only flows never go near that code:
 * their payload has the lines but nothing has parsed them.
 *
 * Returns null for anything it cannot read rather than guessing. A half-read MRZ producing a
 * confident-looking name is worse than an absent one.
 */
function parseMrzLines(lines) {
    if (!Array.isArray(lines) || !lines.length) return null;
    var joined = lines.join("").replace(/[\s|]/g, "").toUpperCase();

    function field(from, to) { return joined.slice(from, to); }
    function trimmed(from, to) { return field(from, to).replace(/</g, " ").trim() || null; }
    function names(raw) {
        var parts = raw.split("<<");
        var clean = function(v) { return v.replace(/</g, " ").trim() || null; };
        return { surname: clean(parts[0] || ""), givenNames: clean(parts.slice(1).join("<<")) };
    }

    var out = {};
    if (joined.length === 90) {                       // TD1, 3 x 30
        out.documentType = trimmed(0, 2);
        out.issuingState = trimmed(2, 5);
        out.documentNumber = trimmed(5, 14);
        out.dateOfBirth = field(30, 36);
        out.gender = trimmed(37, 38);
        out.dateOfExpiry = field(38, 44);
        out.nationality = trimmed(45, 48);
        var td1 = names(field(60, 90));
        out.primaryIdentifier = td1.surname;
        out.secondaryIdentifier = td1.givenNames;
    } else if (joined.length === 72) {                // TD2, 2 x 36
        out.documentType = trimmed(0, 2);
        out.issuingState = trimmed(2, 5);
        var td2 = names(field(5, 36));
        out.primaryIdentifier = td2.surname;
        out.secondaryIdentifier = td2.givenNames;
        out.documentNumber = trimmed(36, 45);
        out.nationality = trimmed(46, 49);
        out.dateOfBirth = field(49, 55);
        out.gender = trimmed(56, 57);
        out.dateOfExpiry = field(57, 63);
    } else if (joined.length === 88) {                // TD3, 2 x 44
        out.documentType = trimmed(0, 2);
        out.issuingState = trimmed(2, 5);
        var td3 = names(field(5, 44));
        out.primaryIdentifier = td3.surname;
        out.secondaryIdentifier = td3.givenNames;
        out.documentNumber = trimmed(44, 53);
        out.nationality = trimmed(54, 57);
        out.dateOfBirth = field(57, 63);
        out.gender = trimmed(64, 65);
        out.dateOfExpiry = field(65, 71);
    } else {
        return null;                                  // a partial read is not something to parse
    }
    return out;
}

/**
 * Builds the `verification` block from a readNFC result. Exposed as
 * NfcDocumentReader.summarise(result) so it can also be run over a stored payload.
 */
function summarise(result) {
    result = result || {};
    var auth = result.authentication || {};
    var passive = auth.passiveAuthentication || {};
    var comparison = result.faceComparison || null;
    var match = comparison ? (comparison.match || {}) : null;
    var liveness = result.liveness || null;

    var checksPerformed = [];
    var issues = [];

    // ---- Chip access ----
    var chipUnlocked = auth.chipAccessEstablished === true;
    if (auth.hasOwnProperty("chipAccessEstablished")) checksPerformed.push("chipAccess");

    // ---- Document authenticity ----
    var documentAuthentic = "unknown";
    if (passive.status === "passed") documentAuthentic = "yes";
    else if (passive.status === "failed") documentAuthentic = "no";
    if (passive.status) checksPerformed.push("documentAuthenticity");
    (passive.reasons || []).forEach(function(code) { issues.push(describeIssue(code)); });

    // Tampering is a stronger, narrower claim than "not authentic": it means a hash or the signed
    // content was contradicted, not merely that the issuer is unconfirmed.
    var documentTampered = passive.dataIntegrityVerified === false
        || passive.sodSignatureVerified === false;

    // ---- Holder present (liveness) ----
    var holderPresent = "notChecked";
    if (liveness) {
        checksPerformed.push("liveness");
        holderPresent = liveness.passed === true ? "yes" : "no";
        if (holderPresent === "no") issues.push(describeIssue("LIVENESS_FAILED"));
    }

    // ---- Face match ----
    var faceMatch = "notAvailable";
    var faceMatchScore = null;
    if (match) {
        checksPerformed.push("faceMatch");
        faceMatchScore = (typeof match.similarity === "number") ? match.similarity : null;
        // The device no longer decides: a completed comparison reports "review" and the score.
        // "matched"/"notMatched" are still handled so payloads stored by older builds still read.
        if (match.status === "matched") faceMatch = "matched";
        else if (match.status === "notMatched") faceMatch = "notMatched";
        else if (match.status === "review") faceMatch = "review";
        if (match.reason) issues.push(describeIssue(match.reason));
        if (faceMatch === "review" && faceMatchScore !== null) {
            issues.push(describeIssue("FACE_SCORE_RETURNED"));
        }
        if (faceMatch === "notMatched") {
            issues.push({
                code: "FACE_NOT_MATCHED",
                severity: "blocking",
                message: "The selfie did not match the chip photo closely enough"
                    + (faceMatchScore !== null ? " (score " + faceMatchScore + ")." : ".")
            });
        }
    }

    // ---- Outcome ----
    // Fail only on a contradiction. Anything merely unestablished is "review", because a check
    // that could not run is not evidence against the customer either.
    var outcome;
    var chipCheckRan = auth.hasOwnProperty("chipAccessEstablished");
    if (chipCheckRan && !chipUnlocked) issues.push(describeIssue("CHIP_NOT_UNLOCKED"));

    if (!checksPerformed.length) {
        // Nothing was established either way. "review" rather than "fail": an empty or malformed
        // payload is a defect on our side, not evidence against the customer.
        issues.push(describeIssue("NO_CHECKS_PERFORMED"));
        outcome = "review";
    } else if (documentAuthentic === "no" || documentTampered
            || holderPresent === "no" || faceMatch === "notMatched"
            || (chipCheckRan && !chipUnlocked)) {
        outcome = "fail";
    } else if (documentAuthentic === "yes"
            && (holderPresent === "yes" || holderPresent === "notChecked")
            && (faceMatch === "matched" || faceMatch === "notAvailable")) {
        // "notAvailable"/"notChecked" only reach here with their warnings already in `issues`,
        // and checksPerformed records what was actually established.
        outcome = (faceMatch === "notAvailable" || holderPresent === "notChecked")
            ? "review"
            : "pass";
    } else {
        outcome = "review";
    }

    var blocking = issues.filter(function(i) { return i.severity === "blocking"; });
    var warnings = issues.filter(function(i) { return i.severity === "warning"; });

    return {
        outcome: outcome,                       // "pass" | "review" | "fail"
        requiresManualReview: outcome === "review",
        checksPerformed: checksPerformed,
        documentAuthentic: documentAuthentic,   // "yes" | "no" | "unknown"
        documentTampered: documentTampered,
        chipUnlocked: chipUnlocked,
        holderPresent: holderPresent,           // "yes" | "no" | "notChecked"
        faceMatch: faceMatch,                   // "matched" | "notMatched" | "review" | "notAvailable"
        faceMatchScore: faceMatchScore,
        issues: issues,
        blockingIssueCount: blocking.length,
        warningCount: warnings.length,
        summary: buildSummary(outcome, documentAuthentic, holderPresent, faceMatch, blocking)
    };
}

function buildSummary(outcome, documentAuthentic, holderPresent, faceMatch, blocking) {
    if (outcome === "fail") {
        if (!blocking.length) return "Rejected: a verification check did not pass.";
        return "Rejected: " + blocking.map(function(i) { return i.message; }).join(" ");
    }
    var parts = [];
    parts.push(documentAuthentic === "yes"
        ? "Document is genuine and issued by a trusted authority."
        : (documentAuthentic === "no"
            ? "Document could not be confirmed as genuine."
            : "Document authenticity could not be established."));
    if (holderPresent === "yes") parts.push("A live person was present.");
    else if (holderPresent === "notChecked") parts.push("Presence of the holder was not checked.");
    if (faceMatch === "matched") parts.push("Their face matches the chip photo.");
    else if (faceMatch === "review") parts.push("Face match score returned for a decision.");
    else if (faceMatch === "notAvailable") parts.push("Face comparison was not available.");
    if (outcome === "review") parts.push("Decide in the backend or send to manual review.");
    return parts.join(" ");
}

var NfcDocumentReader = {

    /**
     * Check if NFC is available and enabled on this device.
     * @param {Function} success - Called with { available: boolean, enabled: boolean }
     * @param {Function} error - Called with error message string
     */
    isNFCAvailable: function(success, error) {
        exec(success, error, SERVICE_NAME, 'isNFCAvailable', []);
    },

    /**
     * Launch camera to scan MRZ (Machine Readable Zone) from a document.
     * @param {Function} success - Called with { documentNumber: string, dateOfBirth: string, dateOfExpiry: string, rawMrzLines: string[], format: string }
     * @param {Function} error - Called with error message string
     * @param {Object} [options] - Optional settings
     * @param {string} [options.documentType] - "id" or "passport" for scan guidance
     * @param {number} [options.requiredMatches=2] - How many frames must read the same MRZ before
     *                 it is accepted. The scanner used to take the first frame that parsed, which
     *                 let a single misread character through — one card returned a name whose "<<"
     *                 separator had been read as a letter. Raise it for worn or glossy documents;
     *                 1 restores the old behaviour.
     * @param {number} [options.frameIntervalMs=250] - Minimum gap between frames the scanner looks
     *                 at. The camera produces frames far faster than a document changes, so this
     *                 spends less battery and lets autofocus settle between looks. Raising it
     *                 reduces heat on long scans; lowering it makes detection feel more immediate.
     */
    scanMRZ: function(success, error, options) {
        exec(wrap(success, 'scanMRZ'), error, SERVICE_NAME, 'scanMRZ', [withLabels(options)]);
    },

    /**
     * Run a standalone liveness check on the front camera.
     *
     * Uses Google ML Kit face detection to drive a randomised challenge-response sequence
     * (blink / smile / turn head), then returns a compressed portrait captured from the same
     * verified frame stream.
     *
     * IMPORTANT — what a pass does and does not mean:
     * ML Kit face detection has no presentation-attack detection. Challenge-response defeats a
     * held-up photo or print, but NOT a replayed video, an injected camera feed or a 3D mask.
     * The result carries sdk.presentationAttackDetection = false to make that explicit.
     *
     * Result:
     *   {
     *     passed: true,
     *     faceImageBase64, faceImageMimeType, faceImageWidth, faceImageHeight,
     *     faceImageBytes, faceImageJpegQuality,
     *     fullFrameImageBase64?, challengeFrames?,
     *     challenges: [{ type, passed, durationMs }],
     *     signals: { framesAnalysed, durationMs, multiFaceFrames, trackingIdChanges },
     *     sdk: { provider, feature, platform, presentationAttackDetection },
     *     capturedAt
     *   }
     *
     * @param {Function} success - Called with the liveness result
     * @param {Function} error - Called with a user-facing failure message
     * @param {Object} [options]
     * @param {string[]} [options.challenges] - Explicit sequence. Single-action: "blink", "smile",
     *   "turnLeft", "turnRight". Compound (a turn held together with a facial action):
     *   "turnLeftSmile", "turnRightSmile", "turnLeftBlink", "turnRightBlink".
     *                                          Omit to get a random subset (recommended — a fixed
     *                                          order is replayable).
     * @param {number} [options.challengeCount=4] - How many random challenges when none are listed.
     *   With compounds off the pool is four, so the default draws all of them and it is the order
     *   that varies between sessions.
     * @param {boolean} [options.includeCompoundChallenges=false] - Widen the random pool to the four
     *   compound challenges as well. Off because their thresholds are not calibrated against real
     *   faces: a challenge a genuine customer cannot pass is a failed onboarding, not a caught fraud.
     * @param {number} [options.poseHoldMs=600] - How long a pose must be held before it counts, on
     *   top of two consecutive qualifying frames. A duration rather than a frame count, because a
     *   frame count is frame-rate dependent — the same smile was accepted after 66ms at 15fps and
     *   16ms at 60fps before this existed. Set 0 to restore that, which is not recommended.
     * @param {number} [options.overallTimeoutMs] - Session ceiling. Derived when omitted, from
     *   faceSearchTimeoutMs + challenges x perChallengeTimeoutMs + 5s, so it tracks the challenge
     *   count instead of firing mid-sequence. An explicit value always wins.
     * @param {number} [options.perChallengeTimeoutMs=15000]
     * @param {number} [options.faceSearchTimeoutMs=20000]
     * @param {number} [options.maxImageDimension=720] - Long edge of the returned image, in pixels
     * @param {number} [options.maxImageBytes=204800] - JPEG quality steps down until it fits
     * @param {number} [options.jpegQuality=85] - Starting quality, 1-100
     * @param {boolean} [options.cropToFace=true] - Crop to the face (with padding) rather than the full frame
     * @param {boolean} [options.includeFullFrame=false] - Also return the uncropped frame
     * @param {boolean} [options.includeChallengeFrames=false] - Also return one frame per challenge
     * @param {boolean} [options.recordVideo=true] - Record the session and return it in result.video
     * @param {boolean} [options.videoTrimToChallenges=true] - Record only the challenge windows. Most
     *   of a session is the customer reading a prompt: the bulk of the file, none of the evidence.
     * @param {number} [options.videoBitrate=900000] - Target bitrate in bits per second
     * @param {number} [options.videoMaxDimension=854] - iOS only; Android uses CameraX Quality.SD
     * @param {number} [options.videoFrameRate=15] - iOS only
     * @param {boolean} [options.videoPreferHEVC=true] - iOS only. Android records H.264 regardless,
     *   because CameraX's Recorder does not expose the codec; result.video.codec says which you got.
     * @param {Object} [options.prompts] - Override on-screen copy, e.g. { blink: "...", smile: "..." }.
     *   Keys: findFace, center, tooFar, tooClose, multipleFaces, blink, smile, turnLeft, turnRight,
     *   turnLeftSmile, turnRightSmile, turnLeftBlink, turnRightBlink, hold, success, failed, hint.
     */
    checkLiveness: function(success, error, options) {
        exec(wrap(success, 'checkLiveness'), error, SERVICE_NAME, 'checkLiveness', [withLabels(options)]);
    },

    /**
     * Read NFC chip from an identity document.
     * Requires MRZ data (document number, date of birth, date of expiry) for BAC authentication.
     *
     * Progress events are sent via the success callback with keepCallback=true:
     *   { event: "stateChanged", state: "waitingForTag" }    // the sheet is up
     *   { event: "stateChanged", state: "readerArmed" }      // the platform accepted the binding
     *                                                        // and a tap can now be detected
     *   { event: "stateChanged", state: "readerArmFailed" }  // tag detection could not be
     *                                                        // started; the error callback
     *                                                        // follows immediately
     *   { event: "stateChanged", state: "connecting" }
     *   { event: "stateChanged", state: "authenticating" }
     *   { event: "stateChanged", state: "readingDataGroup", dgNumber: 1, dgName: "MRZ Information" }
     *   ...
     *
     * Final result (last callback, keepCallback=false). `verification` is added by this plugin's
     * JavaScript layer and is the block to build application logic on — one outcome
     * ("pass" | "review" | "fail"), flat fields, and plain-language issues. See README.md.
     *   {
     *     verification: { outcome, requiresManualReview, checksPerformed, documentAuthentic,
     *                     documentTampered, chipUnlocked, holderPresent, faceMatch,
     *                     faceMatchScore, issues, blockingIssueCount, warningCount,
     *                     summary },
     *     documentType, issuingState, primaryIdentifier, secondaryIdentifier,
     *     documentNumber, nationality, dateOfBirth, gender, dateOfExpiry, personalNumber,
     *     personalNumberFromMrz,
     *     faceImageBase64, signatureImageBase64,
     *     fullNameOfHolder, otherNames, personalSummary, placeOfBirth, permanentAddress, telephone,
     *     placeOfBirthLines[], permanentAddressLines[],   // the issuer's own components
     *     dg11Fields, dg12Fields,                        // every text tag on the chip, by tag
     *     rawDataGroups,                                  // only with includeRawDataGroups: true
     *     issuingAuthority, dateOfIssue, endorsementsAndObservations,
     *     dataGroupsRead, authentication, textEncoding, readErrors
     *   }
     *
     * BREAKING CHANGE: `bacSucceeded` and `chipAuthSucceeded` are gone, replaced by
     * `authentication`. They were removed rather than renamed because `chipAuthSucceeded` was
     * misleading on both platforms: on Android it was set from the mere presence of a signer
     * certificate in the SOD — no signature was ever verified — and on iOS it carried Chip
     * Authentication status, a different protocol. Code reading either field must move to the
     * fields below, which state exactly what was checked.
     *
     *   authentication: {
     *     chipAccessEstablished,   // BAC or PACE unlocked the chip. Says nothing about the data.
     *     accessProtocol,          // "PACE" | "BAC" | null
     *     chipAuthentication,      // "success" | "failed" | "notDone"
     *                              // Anti-cloning (EAC). "notDone" on Android, which does not
     *                              // perform it at all; on iOS it is the chip's real status.
     *     passiveAuthentication: {
     *       status,                // "passed" | "failed" | "notVerified"
     *       sodSignatureVerified,  // the SOD is validly signed by the signer certificate it carries
     *       dataIntegrityVerified, // every data group read hashes to the value recorded in the SOD
     *       issuerTrusted,         // that signer chains to a CSCA in the installed trust store
     *       digestAlgorithm,       // e.g. "SHA-256" (null on iOS — the library does not expose it)
     *       signatureAlgorithm,    // e.g. "SHA256withRSA" (null on iOS, same reason)
     *       documentSignerSubject, // issuer-side identity of the signer. Never holder data.
     *       trustStore,            // "none" | "unreadable" | "loaded" | "loaded:<count>"
     *       dataGroupHashes,       // { "1": true, "2": true, ... } per-group hash match
     *       reasons                // codes explaining a non-"passed" status, e.g.
     *                              // ["NO_TRUST_ANCHORS"], ["DG_HASH_MISMATCH:2"],
     *                              // ["SOD_SIGNATURE_INVALID"], ["ISSUER_NOT_TRUSTED"],
     *                              // ["SOD_CONTENT_DIGEST_MISMATCH"], ["SOD_NOT_READ"],
     *                              // ["TRUST_STORE_UNREADABLE"], ["NOT_RUN"]
     *     }
     *   }
     *
     * Read `status` and nothing else if you only want one signal:
     *   "passed"      - the chip data is what the issuing state signed, and the signer is trusted
     *   "failed"      - something was contradicted. Do not treat the data as authentic.
     *   "notVerified" - no contradiction, but authenticity was not established. The usual cause is
     *                   no CSCA trust store installed (reasons: ["NO_TRUST_ANCHORS"]), which means
     *                   the SOD and hashes are self-consistent — something a forger can also
     *                   produce. See src/csca/README.md.
     *
     * Passive authentication proves the *data* is genuine. It does not prove the chip is not a
     * clone (that is Chip Authentication) and does not prove the holder is the rightful holder
     * (that is the face match). Revocation is not checked on either platform.
     *
     * Safe to call straight from the scanMRZ callback: Android needs a resumed activity to start
     * listening for a tag, so if the MRZ camera is still closing, arming is retried until the
     * activity is back. If tag detection cannot be started at all, the error callback fires — the
     * read never sits on "Ready to scan" with nothing listening.
     *
     * Watch for state "readerArmed" to tell the two apart without a device log: no such event
     * means nothing is listening, however normal the sheet looks.
     *
     * @param {Function} success - Called with progress events and final result
     * @param {Function} error - Called with error message string
     * @param {Object} mrzData - BAC key material
     * @param {string} mrzData.documentNumber - Document number from MRZ
     * @param {string} mrzData.dateOfBirth - Date of birth in YYMMDD format
     * @param {string} mrzData.dateOfExpiry - Date of expiry in YYMMDD format
     *
     * Pass `options.liveness` to chain a liveness check onto the chip read. The chip is read
     * first, then the holder is verified in front of the camera, then their face is compared
     * on-device against the DG2 portrait from the chip. The result gains:
     *
     *   liveness: { ...same shape as checkLiveness... }
     *   faceComparison: {
     *     documentPortrait: { faceDetected, faceCount, faceAreaRatio, yaw, pitch, roll,
     *                         frontal, largeEnough, imageWidth, imageHeight },
     *     livenessPortrait: { ...same shape... },
     *     screening: { passed, reasons[], note },   // quality gate, NOT an identity match
     *
     *     // The two detected faces, cropped exactly as the matcher consumed them. Present
     *     // whenever a face was found on that side; a reviewer settling a borderline score needs
     *     // to see the same pair the score came from.
     *     documentFaceImageBase64, documentFaceImageBytes,
     *     documentFaceImageWidth, documentFaceImageHeight,
     *     livenessFaceImageBase64, livenessFaceImageBytes,
     *     livenessFaceImageWidth, livenessFaceImageHeight,
     *
     *     match: { status, similarity, reason, onDevice }   // no threshold: the backend decides
     *   }
     *
     * The match runs entirely on-device. `options.faceMatch` is optional: the model asset
     * defaults to "mobilefacenet.tflite", which plugin.xml installs into Android assets and the
     * iOS bundle once the file is placed in src/models/ (see src/models/README.md).
     *
     * `match.status`:
     *   "review"                 - the comparison ran and `similarity` is a real score. This is
     *                              the only successful status: the device measures, the backend
     *                              decides. There is no threshold in the plugin to configure.
     *   "deferred"               - no comparison ran and nothing is broken. `reason` says which:
     *                              MODEL_NOT_INSTALLED  - no model at the default asset path, so
     *                                                     on-device matching is not provisioned
     *                                                     yet (see src/models/README.md)
     *                              NO_MODEL_CONFIGURED  - matching disabled (modelAsset was
     *                                                     explicitly passed as null or "")
     *   "error"                  - never reported as a pass. `reason` says which:
     *                              MODEL_NOT_FOUND           - a modelAsset was passed explicitly
     *                                                          but is not in app assets
     *                              EMBEDDING_LENGTH_MISMATCH - model output != embeddingSize
     *                              NO_FACE_DETECTED          - the detector found no face in the
     *                                                          chip portrait or the selfie, so no
     *                                                          comparison was possible
     *                              MISSING_PORTRAIT          - one of the two images was absent
     *                              MATCHER_FAILED            - anything else; check logcat/Console
     *                                                          for tag "FaceMatcher"
     *
     * The plugin has no threshold and no option to set one. It returns the similarity and the
     * backend decides: `verification.outcome` is "review" with `faceMatchScore` populated. A
     * decision boundary on the handset cannot be changed without an app release, cannot be
     * audited centrally, and sits on a device an attacker controls.
     *
     * Everything needed by the back office is in this one object: the chip data groups, the chip
     * portrait (faceImageBase64), the liveness portrait (liveness.faceImageBase64), the aligned
     * pair, and the on-device match verdict.
     *
     * @param {Object} [options]
     * @param {boolean|Object} [options.liveness] - true for defaults, or a checkLiveness options object
     * @param {boolean} [options.includeRawDataGroups=false] - Also return each data group's raw
     *                 bytes, base64, keyed by number plus "sod". Lets a backend re-verify the
     *                 issuer's signature itself instead of trusting the handset, and re-decode any
     *                 text this plugin got wrong. Off by default: it is a second full copy of every
     *                 field and the portrait, in the rawest form the holder's data takes.
     * @param {Object} [options.passiveAuth] - Passive-authentication overrides; all optional
     * @param {string|null} [options.passiveAuth.trustStoreAsset="csca_master_list.pem"] - PEM bundle
     *                 of CSCA certificates in app assets. Pass null to skip the issuer check, which
     *                 caps `passiveAuthentication.status` at "notVerified".
     * @param {Object} [options.faceMatch] - On-device matcher overrides; all optional
     * @param {string} [options.faceMatch.modelAsset="mobilefacenet.tflite"] - .tflite model in app assets
     * @param {number} [options.faceMatch.inputSize=112] - Model input edge (112 MobileFaceNet, 160 FaceNet)
     * @param {number} [options.faceMatch.embeddingSize=192] - Model output vector length
     */
    readNFC: function(success, error, mrzData, options) {
        exec(wrap(success, 'readNFC'), error, SERVICE_NAME, 'readNFC', [mrzData, withLabels(options)]);
    },

    /**
     * Recomputes the `verification` block for a stored readNFC result. Same function readNFC
     * applies, exposed so a payload saved earlier can be re-summarised without another read.
     * @param {Object} result - a readNFC final result
     * @returns {Object} the verification block
     */
    /**
     * Replaces plugin labels for every subsequent call. Partial bundles are fine.
     * See docs/labels/ for en, ar and fr starting points.
     */
    setLabels: setLabels,

    /**
     * "ltr" or "rtl". Set alongside an Arabic bundle — translating without it gives a screen that
     * reads correctly and behaves backwards.
     */
    setDirection: setDirection,

    /** Every label key the plugin understands, for checking a bundle against. */
    labelKeys: function() { return Object.keys(DEFAULT_LABELS); },

    /** The labels as they would be sent right now, defaults plus session overrides. */
    labels: function() { return resolveLabels(null); },

    summarise: summarise,

    /**
     * Applies the envelope to a payload, exposed for the same reason as `summarise`: a result
     * stored before this existed can be brought up to the current shape without another capture.
     *
     * @param {Object} result - a payload from any of the capture functions
     * @param {string} [captureType] - used only when the payload does not already say
     */
    unify: unify,

    /**
     * Photograph the document itself, one side at a time.
     *
     * An ID card is captured front and back; a passport is captured once, at the photo page. The
     * step list follows from `documentType` — the caller does not describe the sides.
     *
     * Each shot is reviewed on screen before it is kept, because nothing downstream can tell the
     * operator that a photo is too blurry to read while they can still retake it.
     *
     * Result:
     *   {
     *     captureType: "document",
     *     documentType: "id" | "passport",
     *     sides: { front: { key, label, imageBase64, imageMimeType, imageBytes,
     *                        imageWidth, imageHeight, jpegQuality, ocr?, documentCheck? },
     *              back: {...} },
     *     order: ["front", "back"],               // the sequence, without repeating the images
     *     capturedAt
     *   }
     *
     * There is deliberately no OCR here. The chip already carries these fields — including the
     * Arabic — covered by the issuer's signature and hash-verified, so reading them off a
     * photograph would replace proven data with a camera-dependent guess. Use readNFC for the
     * data and this only for the picture. OCR lives on captureProofOfAddress, where there is no
     * chip to read instead.
     *
     * @param {Function} success - Called with the capture result
     * @param {Function} error - Called with a user-facing message, including on cancellation
     * @param {Object} [options]
     * @param {string} [options.documentType="id"] - "id" captures front and back, "passport" front only
     * @param {string} [options.title] - Override the screen title
     * @param {boolean} [options.verifyDocument=true] - Check each shot actually shows a document
     *                 before it is kept, and say so on the review screen. See the note below.
     * @param {boolean} [options.requireDocument=false] - Refuse to keep a shot that failed the
     *                 check. Off by default: the check confirms a photograph, it cannot refute
     *                 one, and the person holding the phone can see what a server cannot.
     * @param {string[]} [options.expectedIdentifiers] - "label:value" pairs the photograph should
     *                 contain, e.g. "documentNumber:C26077133". captureAndReadNFC supplies these
     *                 from the chip automatically; pass them here if you already have them.
     * @param {number} [options.maxImageDimension=1200] - Long edge in pixels
     * @param {number} [options.maxImageBytes=256000] - Quality steps down until the JPEG fits
     * @param {number} [options.jpegQuality=80] - Starting quality, 1-100
     *
     * These are tuned for a record rather than for reading: the chip supplies the fields, so the
     * photograph only has to be legible to a person. captureProofOfAddress defaults higher, since
     * there the picture is the data.
     */
    captureDocument: function(success, error, options) {
        exec(wrap(success, 'captureDocument'), error, SERVICE_NAME, 'captureDocument', [withLabels(options)]);
    },

    /**
     * The whole document check in one call: scan the MRZ, read the chip, confirm the two agree,
     * then photograph the card.
     *
     * The order matters. The MRZ is read first because it derives the chip access key. The chip is
     * read next, and what it holds is compared against what is printed. The photographs come last,
     * so the card is only photographed once there is a reason to believe it is genuine — and the
     * customer is still holding it either way.
     *
     * No mrzData argument: this function performs the scan itself, unlike readNFC.
     *
     * The result is the readNFC payload plus:
     *
     *   mrzComparison: { status, fieldsCompared[], mismatches[], note }
     *   capture:       { captureType, documentType, sides: { front, back }, order[], capturedAt }
     *
     * `mrzComparison.status` is "matched", "mismatch", or "notCompared" when the scanned lines
     * could not be parsed. Note that documentNumber, dateOfBirth and dateOfExpiry derive the chip
     * access key, so a chip that opened at all already agreed with them — the fields that can
     * genuinely disagree are the names, nationality, issuing state and document code, and those
     * are also the ones an OCR misread can corrupt. Treat a mismatch as a finding for a human.
     *
     * If the photographs are abandoned, the chip result is still delivered with `capture` absent
     * and `captureCancelled: true`. A completed read cost the customer a tap and possibly a
     * liveness check; discarding it over a cancelled camera screen would be worse than returning
     * it incomplete. A failed chip read, by contrast, ends on the error callback.
     *
     * No OCR: the chip carries these fields signed, so photographing them is for the record.
     *
     * @param {Function} success - Called with progress events, then the merged result
     * @param {Function} error - Called with a user-facing message
     * @param {Object} [options] - readNFC options (liveness, faceMatch, passiveAuth,
     *                 includeRawDataGroups) plus documentType and the captureDocument image
     *                 options (maxImageDimension, maxImageBytes, jpegQuality)
     */
    captureAndReadNFC: function(success, error, options) {
        exec(wrap(success, 'captureAndReadNFC'), error, SERVICE_NAME,
             'captureAndReadNFC', [withLabels(options)]);
    },

    /**
     * MRZ, both sides of the card, then the holder's face — for a document with no chip, or as the
     * fallback when a chip read is not possible.
     *
     * Steps, in order: scan the MRZ, photograph the card (front and back for an ID, photo page for
     * a passport), then run the liveness check.
     *
     * WHAT THIS DOES NOT DO
     * It verifies nothing. Nothing collected here is signed by an issuer and nothing is compared
     * against a chip, so `verification.documentAuthentic` is "unknown" in every result and the
     * outcome is "review". This gathers evidence for a decision made elsewhere; readNFC and
     * captureAndReadNFC are what produce evidence a decision can rest on.
     *
     * Result:
     *   {
     *     captureType: "documentAndLiveness",
     *     documentType,
     *     mrz:      { documentNumber, dateOfBirth, dateOfExpiry, format, rawMrzLines },
     *     capture:  { sides: { front, back }, order, capturedAt },
     *     liveness: { ...same shape as checkLiveness... },
     *     completed, cancelledAt?, cancelReason?, capturedAt
     *   }
     *
     * A step the user abandons is named in `cancelledAt` and the flow stops there, returning
     * everything collected before it — an MRZ scan and two photographs are worth keeping even when
     * the selfie was refused. `completed` is false in that case. Only a cancelled MRZ scan, where
     * nothing was collected at all, reaches the error callback.
     *
     * @param {Function} success - Called with the result
     * @param {Function} error - Called with a user-facing message
     * @param {Object} [options]
     * @param {string} [options.documentType="id"] - "id" photographs both sides, "passport" one
     * @param {Object} [options.liveness] - checkLiveness options
     * @param {number} [options.maxImageDimension] - As captureDocument
     * @param {number} [options.maxImageBytes]
     * @param {number} [options.jpegQuality]
     */
    captureDocumentAndLiveness: function(success, error, options) {
        exec(wrap(success, 'captureDocumentAndLiveness'), error, SERVICE_NAME,
             'captureDocumentAndLiveness', [withLabels(options)]);
    },

    /**
     * Photograph a proof of address — a utility bill, a bank statement, a tenancy contract.
     *
     * One page, same review step, same options as captureDocument except that there is no
     * document type: the plugin has no idea what a valid proof of address looks like in a given
     * country, and does not pretend to. It returns the picture and, optionally, the text on it.
     *
     * Result: as captureDocument, with `captureType: "proofOfAddress"` and a single entry keyed
     * "document" — `result.sides.document.imageBase64` is the compressed JPEG.
     *
     * ON OCR AND SCRIPT COVERAGE
     * OCR is on by default here and available nowhere else: reading the page is the reason this
     * capture exists, and unlike an ID card there is no chip behind a utility bill to take the
     * text from instead. Pass `ocr: false` to skip it and get the image alone.
     *
     * It returns raw recognised lines, never named fields: deciding which line is the customer's
     * address rather than the biller's is issuer-specific and not something this plugin can do
     * safely.
     *
     * Coverage differs by platform, and the result says which engine ran and what it covers:
     *   Android - ML Kit Text Recognition v2. Latin script only; there is no Arabic model, so on a
     *             bilingual document the Arabic is simply absent from the output.
     *   iOS     - Apple Vision, which does recognise Arabic on recent iOS versions.
     * "No Arabic in the output" and "no Arabic on the page" look identical downstream, which is
     * why `ocr.arabicSupported` is reported rather than left to be inferred. A backend that needs
     * the Arabic can OCR the returned image itself.
     *
     * @param {Function} success - Called with the capture result
     * @param {Function} error - Called with a user-facing message, including on cancellation
     * @param {Object} [options] - As captureDocument, minus documentType
     * @param {boolean} [options.ocr=true] - Return recognised text for the page
     * @param {number} [options.maxImageDimension=1800] - Long edge in pixels
     * @param {number} [options.maxImageBytes=614400] - Quality steps down until the JPEG fits
     * @param {number} [options.jpegQuality=88] - Starting quality, 1-100
     *
     * Larger than captureDocument's, because a bill's print is small and a backend re-reading the
     * image for Arabic is limited by what was sent, not by what the camera saw. Raise them further
     * for dense pages; lower them if payload size matters more than legibility.
     */
    captureProofOfAddress: function(success, error, options) {
        exec(wrap(success, 'captureProofOfAddress'), error, SERVICE_NAME, 'captureProofOfAddress', [withLabels(options)]);
    },

    /**
     * Cancel an ongoing NFC reading operation.
     * @param {Function} success - Called on successful cancellation
     * @param {Function} error - Called with error message string
     */
    cancelRead: function(success, error) {
        exec(success, error, SERVICE_NAME, 'cancelRead', []);
    }
};

module.exports = NfcDocumentReader;
