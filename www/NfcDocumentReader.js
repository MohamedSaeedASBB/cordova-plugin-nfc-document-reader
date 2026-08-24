var exec = require('cordova/exec');

var SERVICE_NAME = 'NfcDocumentReader';

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
     *   { event: "stateChanged", state: "connecting" }
     *   { event: "stateChanged", state: "authenticating" }
     *   { event: "stateChanged", state: "readingDataGroup", dgNumber: 1, dgName: "MRZ Information" }
     *   ...
     *
     * Final result (last callback, keepCallback=false):
     *   {
     *     documentType, issuingState, primaryIdentifier, secondaryIdentifier,
     *     documentNumber, nationality, dateOfBirth, gender, dateOfExpiry, personalNumber,
     *     faceImageBase64, signatureImageBase64,
     *     fullNameOfHolder, otherNames, personalSummary, placeOfBirth, permanentAddress, telephone,
     *     issuingAuthority, dateOfIssue, endorsementsAndObservations,
     *     dataGroupsRead, bacSucceeded, chipAuthSucceeded, readErrors
     *   }
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
     *     documentFaceImageBase64, documentFaceImageBytes,
     *     documentFaceImageWidth, documentFaceImageHeight,
     *     match: { status, similarity, threshold, reason, onDevice }
     *   }
     *
     * The match runs entirely on-device. `options.faceMatch` is optional: the model asset
     * defaults to "mobilefacenet.tflite", which plugin.xml installs into Android assets and the
     * iOS bundle once the file is placed in src/models/ (see src/models/README.md).
     *
     * `match.status`:
     *   "matched" / "notMatched" - a threshold is configured and the score was compared to it
     *   "review"                 - the comparison ran and `similarity` is real, but no threshold
     *                              is configured, so the decision is left to a human
     *   "deferred"               - matching disabled (modelAsset explicitly cleared)
     *   "error"                  - never reported as a pass. `reason` says which:
     *                              MODEL_NOT_FOUND           - the .tflite is not in app assets
     *                              EMBEDDING_LENGTH_MISMATCH - model output != embeddingSize
     *                              MISSING_PORTRAIT          - a face was not detected in one image
     *                              MATCHER_FAILED            - anything else; check logcat/Console
     *                                                          for tag "FaceMatcher"
     *
     * A threshold is deliberately not defaulted: it fixes the false-accept rate of an identity
     * check and has to be measured on this bank's population. See src/models/README.md.
     *
     * Everything needed by the back office is in this one object: the chip data groups, the chip
     * portrait (faceImageBase64), the liveness portrait (liveness.faceImageBase64), the aligned
     * pair, and the on-device match verdict.
     *
     * @param {Object} [options]
     * @param {boolean|Object} [options.liveness] - true for defaults, or a checkLiveness options object
     * @param {Object} [options.faceMatch] - On-device matcher overrides; all optional
     * @param {string} [options.faceMatch.modelAsset="mobilefacenet.tflite"] - .tflite model in app assets
     * @param {number} [options.faceMatch.inputSize=112] - Model input edge (112 MobileFaceNet, 160 FaceNet)
     * @param {number} [options.faceMatch.embeddingSize=192] - Model output vector length
     * @param {number} [options.faceMatch.threshold] - Cosine-similarity threshold. Omit and the
     *                 score is still returned, with status "review" instead of a pass/fail.
     */
    readNFC: function(success, error, mrzData, options) {
        exec(success, error, SERVICE_NAME, 'readNFC', [mrzData, options || {}]);
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
