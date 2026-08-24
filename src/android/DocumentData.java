package com.nfcdocumentreader;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import android.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete parsed data from an MRTD NFC chip.
 */
public class DocumentData {

    // DG1 - MRZ Info
    public String documentType = "";
    public String issuingState = "";
    public String primaryIdentifier = "";   // Surname
    public String secondaryIdentifier = ""; // Given names
    public String documentNumber = "";
    public String nationality = "";
    public String dateOfBirth = "";         // YYMMDD
    public String gender = "";
    public String dateOfExpiry = "";        // YYMMDD
    public String personalNumber = "";

    // DG2 - Facial Image
    public Bitmap faceImage = null;

    // DG7 - Signature/Handwriting
    public Bitmap signatureImage = null;

    // DG11 - Additional Personal Details
    public String fullNameOfHolder = "";
    public List<String> otherNames = new ArrayList<>();
    public String personalSummary = "";
    public String placeOfBirth = "";
    public String permanentAddress = "";
    public String telephone = "";

    // DG12 - Additional Document Details
    public String issuingAuthority = "";
    public String dateOfIssue = "";
    public String endorsementsAndObservations = "";

    // Reading metadata
    public List<Integer> dataGroupsRead = new ArrayList<>();
    /** True once BAC or PACE unlocked the chip. Says nothing about whether the data is genuine. */
    public boolean chipAccessEstablished = false;
    /** "PACE", "BAC", or null if the chip was never unlocked. */
    public String accessProtocol = null;
    /** Result of the ICAO 9303 passive-authentication checks, or null if they did not run. */
    public PassiveAuthenticator.Result passiveAuthentication = null;
    public Map<Integer, String> readErrors = new HashMap<>();

    /**
     * Convert to JSON for the JavaScript bridge.
     * Images are converted to Base64-encoded PNG strings.
     */
    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();

        // DG1
        json.put("documentType", documentType);
        json.put("issuingState", issuingState);
        json.put("primaryIdentifier", primaryIdentifier);
        json.put("secondaryIdentifier", secondaryIdentifier);
        json.put("documentNumber", documentNumber);
        json.put("nationality", nationality);
        json.put("dateOfBirth", dateOfBirth);
        json.put("gender", gender);
        json.put("dateOfExpiry", dateOfExpiry);
        json.put("personalNumber", personalNumber);

        // DG2 - Face Image as Base64
        if (faceImage != null) {
            json.put("faceImageBase64", bitmapToBase64(faceImage));
        } else {
            json.put("faceImageBase64", JSONObject.NULL);
        }

        // DG7 - Signature Image as Base64
        if (signatureImage != null) {
            json.put("signatureImageBase64", bitmapToBase64(signatureImage));
        } else {
            json.put("signatureImageBase64", JSONObject.NULL);
        }

        // DG11
        json.put("fullNameOfHolder", fullNameOfHolder);
        JSONArray namesArray = new JSONArray();
        for (String name : otherNames) {
            namesArray.put(name);
        }
        json.put("otherNames", namesArray);
        json.put("personalSummary", personalSummary);
        json.put("placeOfBirth", placeOfBirth);
        json.put("permanentAddress", permanentAddress);
        json.put("telephone", telephone);

        // DG12
        json.put("issuingAuthority", issuingAuthority);
        json.put("dateOfIssue", dateOfIssue);
        json.put("endorsementsAndObservations", endorsementsAndObservations);

        // Metadata
        JSONArray dgArray = new JSONArray();
        for (int dg : dataGroupsRead) {
            dgArray.put(dg);
        }
        json.put("dataGroupsRead", dgArray);

        // ---- Authentication ----
        // The old payload reported bacSucceeded plus a chipAuthSucceeded that was set from the
        // mere presence of a signer certificate in the SOD — no signature was ever checked. Both
        // names are gone; each field below states exactly what was verified.
        JSONObject auth = new JSONObject();
        auth.put("chipAccessEstablished", chipAccessEstablished);
        auth.put("accessProtocol", accessProtocol != null ? accessProtocol : JSONObject.NULL);
        // Chip Authentication (anti-cloning) is a separate EAC protocol this reader does not
        // perform on Android. Reported as not performed rather than inferred from something else.
        auth.put("chipAuthentication", "notPerformed");
        auth.put("passiveAuthentication", passiveAuthentication != null
                ? passiveAuthentication.toJson()
                : notRunPassiveAuth());
        json.put("authentication", auth);

        JSONObject errorsObj = new JSONObject();
        for (Map.Entry<Integer, String> entry : readErrors.entrySet()) {
            errorsObj.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        json.put("readErrors", errorsObj);

        return json;
    }

    /** Shape-compatible placeholder so consumers never have to handle a missing block. */
    private static JSONObject notRunPassiveAuth() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("status", "notVerified");
        json.put("sodSignatureVerified", false);
        json.put("dataIntegrityVerified", false);
        json.put("issuerTrusted", false);
        json.put("digestAlgorithm", JSONObject.NULL);
        json.put("signatureAlgorithm", JSONObject.NULL);
        json.put("documentSignerSubject", JSONObject.NULL);
        json.put("trustStore", "none");
        json.put("dataGroupHashes", new JSONObject());
        json.put("reasons", new JSONArray().put("NOT_RUN"));
        return json;
    }

    private static String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        byte[] bytes = baos.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
