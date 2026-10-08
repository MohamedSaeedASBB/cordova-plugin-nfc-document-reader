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
    /**
     * The issuer's own components for the two fields above, before they are flattened.
     *
     * DG11 separates components with '<', and jmrtd returns them as a list. Joining that list
     * hides its structure: an Algerian ID puts marital status, an Arabic value and a blood group
     * in the address field, which reads as one nonsensical address once joined, and states the
     * place of birth twice — Latin then Arabic — which reads as "city, region". Application logic
     * should use these arrays and treat the joined strings above as display text.
     */
    public List<String> placeOfBirthLines = new ArrayList<>();
    public List<String> permanentAddressLines = new ArrayList<>();
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
    /**
     * Raw data groups exactly as read from the chip, keyed by data group number plus "sod",
     * populated only when the caller asks for them. These are what passive authentication hashes,
     * so a backend can re-verify the issuer's signature itself rather than trusting the handset's
     * verdict — and can re-decode any text this plugin got wrong.
     *
     * Off by default: they are a second full copy of every field and the portrait, and they are
     * holder data in its rawest form.
     */
    public Map<Integer, byte[]> rawDataGroups = null;
    public byte[] rawSod = null;

    /**
     * Encoding used to recover DG11/DG12 text, or null when the document was conformant and the
     * fields decoded as UTF-8. A non-null value means the issuer did not use UTF-8 and the text
     * was decoded again with a code page chosen by inspection — see MrtdTextDecoder.
     */
    public String textEncoding = null;
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
        // The holder's name, split into its components.
        //
        // ICAO separates the parts of a DG11 text field with '<', and an Algerian card uses that
        // to carry the Latin and Arabic forms of the same name: "TOUATI<<تواتي". Handing a backend
        // one string with a separator buried in it makes every consumer re-split it, and each one
        // has to know the convention. placeOfBirthLines and permanentAddressLines have been doing
        // this all along; the names were the odd ones out.
        json.put("fullNameOfHolderLines", toJsonArray(MrtdTextDecoder.splitComponents(fullNameOfHolder)));

        // Each entry split the same way, then flattened. Android previously returned jmrtd's list
        // untouched — one element still containing "<<" — while iOS split it, so the same card
        // produced a one-item array on one platform and a two-item array on the other.
        JSONArray namesArray = new JSONArray();
        for (String name : otherNames) {
            for (String part : MrtdTextDecoder.splitComponents(name)) {
                namesArray.put(part);
            }
        }
        json.put("otherNames", namesArray);
        json.put("personalSummary", personalSummary);
        json.put("placeOfBirth", placeOfBirth);
        json.put("permanentAddress", permanentAddress);
        json.put("placeOfBirthLines", toJsonArray(placeOfBirthLines));
        json.put("permanentAddressLines", toJsonArray(permanentAddressLines));
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
        // Null for a conformant document. Non-null names the code page the text had to be
        // recovered with, so a reviewer can tell inspected text from text the issuer's own
        // encoding produced.
        json.put("textEncoding", textEncoding != null ? textEncoding : JSONObject.NULL);

        // How much non-ASCII text this plugin put into the payload, counted as it was written.
        //
        // Arabic from DG11 kept arriving at the back office as question marks, and establishing
        // where it was being lost took three rounds of device testing because nothing in the
        // payload said what had left the handset. This does: if a stored payload reports a count
        // here but its own name and address fields hold no non-ASCII characters, they were
        // destroyed after this plugin returned — a lossy conversion somewhere downstream, which is
        // what substitutes '?'. Nothing here converts: the plugin is UTF-8 throughout.
        JSONObject textRecovery = new JSONObject();
        textRecovery.put("encoding", textEncoding != null ? textEncoding : JSONObject.NULL);
        textRecovery.put("nonAsciiCharacters", countNonAscii(
                fullNameOfHolder, personalSummary, placeOfBirth, permanentAddress,
                issuingAuthority, endorsementsAndObservations)
                + countNonAscii(otherNames.toArray(new String[0])));
        json.put("textRecovery", textRecovery);

        // Raw data groups, base64, only when requested. Keyed by data group number, plus "sod",
        // which is the one a backend needs to re-run passive authentication independently.
        if (rawDataGroups != null || rawSod != null) {
            JSONObject raw = new JSONObject();
            if (rawDataGroups != null) {
                for (Map.Entry<Integer, byte[]> entry : rawDataGroups.entrySet()) {
                    raw.put(String.valueOf(entry.getKey()),
                            Base64.encodeToString(entry.getValue(), Base64.NO_WRAP));
                }
            }
            if (rawSod != null) {
                raw.put("sod", Base64.encodeToString(rawSod, Base64.NO_WRAP));
            }
            json.put("rawDataGroups", raw);
        }

        // ---- Authentication ----
        // The old payload reported bacSucceeded plus a chipAuthSucceeded that was set from the
        // mere presence of a signer certificate in the SOD — no signature was ever checked. Both
        // names are gone; each field below states exactly what was verified.
        JSONObject auth = new JSONObject();
        auth.put("chipAccessEstablished", chipAccessEstablished);
        auth.put("accessProtocol", accessProtocol != null ? accessProtocol : JSONObject.NULL);
        // Chip Authentication (anti-cloning) is a separate EAC protocol this reader does not
        // perform on Android. Reported as not performed rather than inferred from something else.
        //
        // "notDone" rather than "notPerformed" so the two platforms agree on the vocabulary: iOS
        // reports the library's own states, which are "success", "failed" and "notDone". A backend
        // branching on this was comparing a real result on one platform against a different word
        // meaning the same thing on the other.
        auth.put("chipAuthentication", "notDone");
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

    /**
     * Characters outside printable ASCII across the values given. A plain count, deliberately: it
     * is a tripwire for text being mangled in transit, not an analysis of what the text says.
     * Mirrors NfcDocumentReader.countNonAscii on iOS.
     */
    private static int countNonAscii(String... values) {
        int total = 0;
        for (String value : values) {
            if (value == null) continue;
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) > 0x7E) total++;
            }
        }
        return total;
    }

    private static JSONArray toJsonArray(List<String> values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (String value : values) array.put(value);
        }
        return array;
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
