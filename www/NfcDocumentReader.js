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
    MISSING_PORTRAIT:            ["warning",  "A face could not be found in the chip photo or the selfie."],
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
     */
    scanMRZ: function(success, error, options) {
        exec(success, error, SERVICE_NAME, 'scanMRZ', [options || {}]);
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
     * @param {string[]} [options.challenges] - Explicit sequence: "blink", "smile", "turnLeft", "turnRight".
     *                                          Omit to get a random subset (recommended — a fixed
     *                                          order is replayable).
     * @param {number} [options.challengeCount=2] - How many random challenges when none are listed
     * @param {number} [options.overallTimeoutMs=45000]
     * @param {number} [options.perChallengeTimeoutMs=15000]
     * @param {number} [options.faceSearchTimeoutMs=20000]
     * @param {number} [options.maxImageDimension=720] - Long edge of the returned image, in pixels
     * @param {number} [options.maxImageBytes=204800] - JPEG quality steps down until it fits
     * @param {number} [options.jpegQuality=85] - Starting quality, 1-100
     * @param {boolean} [options.cropToFace=true] - Crop to the face (with padding) rather than the full frame
     * @param {boolean} [options.includeFullFrame=false] - Also return the uncropped frame
     * @param {boolean} [options.includeChallengeFrames=false] - Also return one frame per challenge
     * @param {Object} [options.prompts] - Override on-screen copy, e.g. { blink: "...", smile: "..." }
     */
    checkLiveness: function(success, error, options) {
        exec(success, error, SERVICE_NAME, 'checkLiveness', [options || {}]);
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
     *     faceImageBase64, signatureImageBase64,
     *     fullNameOfHolder, otherNames, personalSummary, placeOfBirth, permanentAddress, telephone,
     *     placeOfBirthLines[], permanentAddressLines[],   // the issuer's own components
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
     *     chipAuthentication,      // "success" | "failed" | "notDone" | "notPerformed"
     *                              // Anti-cloning (EAC). "notPerformed" on Android — not implemented.
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
     *                              MISSING_PORTRAIT          - a face was not detected in one image
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
        exec(function(data) {
            // Progress events pass straight through; the final result gains `verification`.
            if (data && !data.event) {
                data.verification = summarise(data);
            }
            success(data);
        }, error, SERVICE_NAME, 'readNFC', [mrzData, options || {}]);
    },

    /**
     * Recomputes the `verification` block for a stored readNFC result. Same function readNFC
     * applies, exposed so a payload saved earlier can be re-summarised without another read.
     * @param {Object} result - a readNFC final result
     * @returns {Object} the verification block
     */
    summarise: summarise,

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
     *     images: [ { key, label, imageBase64, imageMimeType, imageBytes,
     *                 imageWidth, imageHeight, jpegQuality, ocr? } ],
     *     sides: { front: {...}, back: {...} },   // the same entries, keyed
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
     * @param {number} [options.maxImageDimension=1600] - Long edge in pixels. Larger than the
     *                 liveness portrait on purpose: this image has to stay readable to a person.
     * @param {number} [options.maxImageBytes=512000] - JPEG quality steps down until it fits
     * @param {number} [options.jpegQuality=90] - Starting quality, 1-100
     */
    captureDocument: function(success, error, options) {
        exec(success, error, SERVICE_NAME, 'captureDocument', [options || {}]);
    },

    /**
     * Photograph a proof of address — a utility bill, a bank statement, a tenancy contract.
     *
     * One page, same review step, same options as captureDocument except that there is no
     * document type: the plugin has no idea what a valid proof of address looks like in a given
     * country, and does not pretend to. It returns the picture and, optionally, the text on it.
     *
     * Result: as captureDocument, with `captureType: "proofOfAddress"` and a single entry keyed
     * "document".
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
     */
    captureProofOfAddress: function(success, error, options) {
        exec(success, error, SERVICE_NAME, 'captureProofOfAddress', [options || {}]);
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
