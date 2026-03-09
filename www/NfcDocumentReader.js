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
     */
    readNFC: function(success, error, mrzData) {
        exec(success, error, SERVICE_NAME, 'readNFC', [mrzData]);
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
