package com.nfcdocumentreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;

import net.sf.scuba.smartcards.CardService;

import org.jmrtd.BACKey;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PACEKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SODFile;
import org.jmrtd.lds.icao.COMFile;
import org.jmrtd.lds.icao.DG11File;
import org.jmrtd.lds.icao.DG12File;
import org.jmrtd.lds.icao.DG1File;
import org.jmrtd.lds.icao.DG2File;
import org.jmrtd.lds.icao.DG7File;
import org.jmrtd.lds.iso19794.FaceImageInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Core NFC document reader ported from Kotlin.
 * Handles PACE/BAC authentication and MRTD data group reading.
 */
public class NfcDocumentReader {

    private static final String TAG = "NfcDocumentReader";
    private static final int MAX_TAG_TIMEOUT = 20000; // 20 seconds

    private DocumentData result;
    private String error;           // Friendly user-facing message
    private String errorCode;       // Machine-readable error code
    private String technicalError;  // Full technical details for diagnostics
    private String paceDebugInfo;   // PACE debug info for diagnostics
    private String nfcTechList;     // NFC technologies for diagnostics

    private Context passiveAuthContext;                 // needed to read the CSCA trust store
    private PassiveAuthenticator.Config passiveAuthConfig;
    private boolean includeRawDataGroups = false;

    /**
     * Returns each data group's raw bytes in the result. Off by default: they duplicate every
     * field and the portrait, and a backend only needs them to verify the chip independently or
     * to decode text this plugin could not.
     */
    public void setIncludeRawDataGroups(boolean include) {
        this.includeRawDataGroups = include;
    }

    /**
     * Enables passive authentication for the next read. Without it the chip data is returned
     * unverified — see {@link PassiveAuthenticator}. The context is only used to read the CSCA
     * trust store from app assets.
     */
    public void setPassiveAuthentication(Context context, PassiveAuthenticator.Config config) {
        this.passiveAuthContext = context;
        this.passiveAuthConfig = config;
    }

    /**
     * Callback interface for reading progress updates.
     */
    public interface ProgressListener {
        void onStateChanged(String state);
        void onReadingDataGroup(int dgNumber, String dgName);
    }

    public DocumentData getResult() {
        return result;
    }

    /** Returns the friendly user-facing error message. */
    public String getError() {
        return error;
    }

    /** Returns the machine-readable error code (e.g. "AUTH_FAILED"). */
    public String getErrorCode() {
        return errorCode;
    }

    /** Returns the full technical error details for diagnostics logging. */
    public String getTechnicalError() {
        return technicalError;
    }

    /** Returns PACE debug info for diagnostics. */
    public String getPaceDebugInfo() {
        return paceDebugInfo;
    }

    /** Returns NFC technology list string for diagnostics. */
    public String getNfcTechList() {
        return nfcTechList;
    }

    /**
     * Read all available data groups from the NFC chip.
     */
    public void readDocument(Tag tag, String documentNumber, String dateOfBirth,
                             String dateOfExpiry, ProgressListener listener) {
        result = null;
        error = null;
        errorCode = null;
        technicalError = null;
        paceDebugInfo = null;
        nfcTechList = null;

        DocumentData documentData = new DocumentData();
        List<Integer> dataGroupsRead = new ArrayList<>();
        java.util.Map<Integer, String> readErrors = new java.util.HashMap<>();
        // Exact bytes as read from the chip, kept for the passive-authentication hash comparison.
        // Parsing and re-encoding a data group does not reproduce these bytes, so hashing a
        // re-encoded group would fail on genuine documents. Cleared as soon as PA has run.
        java.util.Map<Integer, byte[]> rawDgBytes = new LinkedHashMap<>();

        try {
            if (listener != null) listener.onStateChanged("connecting");

            IsoDep isoDep = IsoDep.get(tag);
            if (isoDep == null) {
                this.errorCode = "TAG_NOT_SUPPORTED";
                this.error = "This document's chip could not be detected. Please try repositioning it.";
                this.technicalError = "Tag does not support IsoDep. Tech list: " + java.util.Arrays.toString(tag.getTechList());
                return;
            }

            this.nfcTechList = java.util.Arrays.toString(tag.getTechList());

            isoDep.setTimeout(MAX_TAG_TIMEOUT);
            isoDep.connect();

            Log.d(TAG, "IsoDep connected, maxTransceiveLength=" + isoDep.getMaxTransceiveLength() +
                ", timeout=" + isoDep.getTimeout());

            CardService cardService = CardService.getInstance(isoDep);
            cardService.open();

            // Use extended max transceive length for better compatibility with PACE cards
            int maxTrLength = Math.max(
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                isoDep.getMaxTransceiveLength()
            );

            PassportService passportService = new PassportService(
                cardService,
                maxTrLength,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false
            );
            passportService.open();

            // Authenticate with the chip
            if (listener != null) listener.onStateChanged("authenticating");
            boolean bacSucceeded = false;
            boolean paceSucceeded = false;
            boolean noAuthRequired = false;

            // Some cards don't require BAC/PACE — try reading EF.COM without auth first
            try {
                InputStream testComIn = passportService.getInputStream(PassportService.EF_COM);
                COMFile testComFile = new COMFile(testComIn);
                if (testComFile.getTagList() != null && testComFile.getTagList().length > 0) {
                    noAuthRequired = true;
                    Log.d(TAG, "EF.COM readable without authentication — skipping BAC/PACE");
                }
            } catch (Exception e) {
                Log.d(TAG, "EF.COM not readable without auth (expected): " + e.getMessage());
                // Re-select MRTD applet to reset card state after failed EF.COM read
                try {
                    passportService.sendSelectApplet(false);
                } catch (Exception ignored) {}
            }

            // Prepare MRZ key material
            String dob = padLeft(dateOfBirth, 6, '0');
            if (dob.length() > 6) dob = dob.substring(0, 6);
            String exp = padLeft(dateOfExpiry, 6, '0');
            if (exp.length() > 6) exp = exp.substring(0, 6);

            String paddedDocNum = documentNumber;
            while (paddedDocNum.length() < 9) paddedDocNum += "<";

            Log.d(TAG, "Auth inputs - docNum: '" + documentNumber + "', padded: '" + paddedDocNum + "', dob: '" + dob + "', exp: '" + exp + "', noAuthRequired: " + noAuthRequired);

            if (noAuthRequired) {
                // Card doesn't require authentication — skip to reading data
                bacSucceeded = true;
            }

          if (!noAuthRequired) {
            BACKeySpec bacKey;
            try {
                bacKey = new BACKey(paddedDocNum, dob, exp);
            } catch (IllegalArgumentException e) {
                this.errorCode = "INVALID_MRZ";
                this.error = "The document details provided are invalid. Please scan the document again.";
                this.technicalError = "Invalid BAC key parameters: " + e.getMessage() +
                    " | Doc#: " + documentNumber + ", DOB: " + dateOfBirth + ", Exp: " + dateOfExpiry;
                return;
            }

            // Try PACE first with PACEKeySpec (required for PACE-only cards)
            String paceDebugInfo = "";
            this.paceDebugInfo = "";
            try {
                InputStream cardAccessInputStream = null;
                try {
                    cardAccessInputStream = passportService.getInputStream(PassportService.EF_CARD_ACCESS);
                } catch (Exception e) {
                    Log.d(TAG, "No CardAccess file: " + e.getMessage());
                    paceDebugInfo = "No EF.CardAccess";
                }

                if (cardAccessInputStream != null) {
                    org.jmrtd.lds.CardAccessFile cardAccessFile =
                        new org.jmrtd.lds.CardAccessFile(cardAccessInputStream);
                    List<PACEInfo> paceInfos = new ArrayList<>();
                    for (Object si : cardAccessFile.getSecurityInfos()) {
                        if (si instanceof PACEInfo) {
                            paceInfos.add((PACEInfo) si);
                        }
                    }

                    paceDebugInfo = "PACEInfos: " + paceInfos.size();

                    if (!paceInfos.isEmpty()) {
                        // Build list of BACKeySpecs to try: padded doc number + original doc number
                        List<BACKeySpec> paceKeys = new ArrayList<>();
                        paceKeys.add(bacKey); // padded doc number
                        if (!paddedDocNum.equals(documentNumber)) {
                            try {
                                paceKeys.add(new BACKey(documentNumber, dob, exp)); // original doc number
                            } catch (Exception ignored) {}
                        }

                        // Try PACE with each PACEInfo and each key variant
                        for (PACEInfo paceInfo : paceInfos) {
                            String oid = paceInfo.getObjectIdentifier();
                            java.math.BigInteger paramId = paceInfo.getParameterId();
                            Log.d(TAG, "PACE entry - OID: " + oid + ", paramId: " + paramId);
                            paceDebugInfo += "\nOID:" + oid + " param:" + paramId;

                            for (BACKeySpec keyVariant : paceKeys) {
                                // Attempt 1: PACEKeySpec
                                try {
                                    PACEKeySpec paceKey = PACEKeySpec.createMRZKey(keyVariant);
                                    Log.d(TAG, "Trying PACE with PACEKeySpec, docNum: '" + keyVariant.getDocumentNumber() + "'");
                                    passportService.doPACE(
                                        paceKey, oid,
                                        PACEInfo.toParameterSpec(paramId), paramId
                                    );
                                    paceSucceeded = true;
                                    Log.d(TAG, "PACE succeeded with PACEKeySpec");
                                    break;
                                } catch (Exception e1) {
                                    Log.w(TAG, "PACE PACEKeySpec failed (doc:'" + keyVariant.getDocumentNumber() + "'): " + e1.getMessage());
                                    paceDebugInfo += "\nPACEKey err:" + e1.getMessage();

                                    // Attempt 2: BACKeySpec directly
                                    try {
                                        passportService.sendSelectApplet(false);
                                        Log.d(TAG, "Trying PACE with BACKeySpec, docNum: '" + keyVariant.getDocumentNumber() + "'");
                                        passportService.doPACE(
                                            keyVariant, oid,
                                            PACEInfo.toParameterSpec(paramId), paramId
                                        );
                                        paceSucceeded = true;
                                        Log.d(TAG, "PACE succeeded with BACKeySpec");
                                        break;
                                    } catch (Exception e2) {
                                        Log.w(TAG, "PACE BACKeySpec failed (doc:'" + keyVariant.getDocumentNumber() + "'): " + e2.getMessage());
                                        paceDebugInfo += "\nBACKey err:" + e2.getMessage();

                                        // Reset connection for next attempt
                                        try {
                                            passportService.sendSelectApplet(false);
                                        } catch (Exception ignored) {}
                                    }
                                }
                            }
                            if (paceSucceeded) break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "PACE setup failed: " + e.getMessage());
                paceDebugInfo += "\nSetup err:" + e.getMessage();
            }

            // Fall back to BAC with multiple document number variants
            if (!paceSucceeded) {
                // CRITICAL: Re-select MRTD applet before BAC.
                // The prior EF.COM (no-auth check) and EF.CardAccess (PACE check) read attempts
                // may have left the card's state machine in a state that rejects MUTUAL AUTHENTICATE.
                // Re-selecting the applet resets the card to accept BAC authentication.
                try {
                    passportService.sendSelectApplet(false);
                    Log.d(TAG, "Re-selected MRTD applet before BAC");
                } catch (Exception e) {
                    Log.w(TAG, "Could not re-select applet before BAC: " + e.getMessage());
                }

                Set<String> docNumVariants = new LinkedHashSet<>();
                docNumVariants.add(paddedDocNum);
                docNumVariants.add(documentNumber);

                if (documentNumber.length() < 9) {
                    docNumVariants.add(padRight(documentNumber, 9, '<'));
                }
                if (documentNumber.length() > 9) {
                    docNumVariants.add(documentNumber.substring(0, 9));
                }
                String trimmed = trimRight(documentNumber, '<');
                if (!trimmed.equals(documentNumber)) {
                    docNumVariants.add(trimmed);
                }

                Exception lastError = null;
                for (String variant : docNumVariants) {
                    try {
                        BACKeySpec key = new BACKey(variant, dob, exp);
                        Log.d(TAG, "Trying BAC with docNum variant: '" + variant + "'");
                        passportService.doBAC(key);
                        bacSucceeded = true;
                        Log.d(TAG, "BAC succeeded with docNum: '" + variant + "'");
                        break;
                    } catch (Exception e) {
                        Log.w(TAG, "BAC failed with docNum '" + variant + "': " + e.getMessage());
                        lastError = e;

                        try {
                            if (isoDep.isConnected()) {
                                passportService.sendSelectApplet(false);
                            } else {
                                Log.e(TAG, "Tag lost during BAC attempts");
                                break;
                            }
                        } catch (Exception reopenEx) {
                            Log.w(TAG, "Could not re-select applet: " + reopenEx.getMessage());
                            break;
                        }
                    }
                }

                if (!bacSucceeded) {
                    Log.e(TAG, "All BAC attempts failed", lastError);
                    String lastErrMsg = lastError != null ? lastError.getMessage() : "unknown";
                    this.errorCode = "AUTH_FAILED";
                    this.error = "Unable to read this document. Please ensure the document details are correct and try again.";
                    this.technicalError = "BAC error: " + lastErrMsg +
                        " | Doc#: " + documentNumber + " (padded: " + paddedDocNum + ")" +
                        " | DOB: " + dateOfBirth + " | Exp: " + dateOfExpiry +
                        " | PACE: " + paceDebugInfo;
                    this.paceDebugInfo = paceDebugInfo;
                    return;
                }
            }
          } // end if (!noAuthRequired)

            // Verify authentication by reading EF.COM
            Log.d(TAG, "Auth complete (PACE=" + paceSucceeded + ", BAC=" + bacSucceeded +
                ", noAuth=" + noAuthRequired + "). Verifying file access...");
            if (listener != null) listener.onReadingDataGroup(0, "Verifying access");

            int[] comTagList = null;
            boolean authVerified = noAuthRequired; // Already verified if no auth was needed

            try {
                InputStream comIn = passportService.getInputStream(PassportService.EF_COM);
                COMFile comFile = new COMFile(comIn);
                comTagList = comFile.getTagList();
                authVerified = true;
                Log.d(TAG, "EF.COM read OK!");
            } catch (Exception e) {
                Log.e(TAG, "EF.COM read FAILED: " + e.getMessage());
            }

            // If PACE was used but files can't be read, try BAC as fallback
            if (!authVerified && paceSucceeded && !bacSucceeded) {
                Log.w(TAG, "PACE may not have established proper secure messaging - trying BAC fallback...");
                try {
                    passportService.sendSelectApplet(false);
                    BACKeySpec bacFallbackKey = new BACKey(paddedDocNum, dob, exp);
                    passportService.doBAC(bacFallbackKey);
                    bacSucceeded = true;
                    Log.d(TAG, "BAC fallback succeeded!");

                    try {
                        InputStream comIn2 = passportService.getInputStream(PassportService.EF_COM);
                        COMFile comFile2 = new COMFile(comIn2);
                        comTagList = comFile2.getTagList();
                        authVerified = true;
                        Log.d(TAG, "EF.COM now readable after BAC fallback!");
                    } catch (Exception e2) {
                        Log.e(TAG, "EF.COM still unreadable after BAC: " + e2.getMessage());
                    }
                } catch (Exception bacEx) {
                    Log.e(TAG, "BAC fallback failed: " + bacEx.getMessage());
                }
            }

            // Try re-selecting the MRTD applet
            if (!authVerified) {
                Log.w(TAG, "Trying MRTD applet re-selection after auth...");
                try {
                    passportService.sendSelectApplet(paceSucceeded || bacSucceeded);
                    InputStream comIn3 = passportService.getInputStream(PassportService.EF_COM);
                    COMFile comFile3 = new COMFile(comIn3);
                    comTagList = comFile3.getTagList();
                    authVerified = true;
                    Log.d(TAG, "EF.COM readable after applet re-selection!");
                } catch (Exception e) {
                    Log.e(TAG, "Applet re-selection didn't help: " + e.getMessage());
                }
            }

            // If nothing works, try BAC with all doc number variants (even if PACE succeeded)
            if (!authVerified && paceSucceeded) {
                Log.w(TAG, "Trying BAC with doc number variants after PACE failure...");
                Set<String> variants = new LinkedHashSet<>();
                variants.add(paddedDocNum);
                variants.add(documentNumber);
                if (documentNumber.length() < 9) variants.add(padRight(documentNumber, 9, '<'));
                if (documentNumber.length() > 9) variants.add(documentNumber.substring(0, 9));
                String trimmed2 = trimRight(documentNumber, '<');
                if (!trimmed2.equals(documentNumber)) variants.add(trimmed2);

                for (String variant : variants) {
                    try {
                        passportService.sendSelectApplet(false);
                        BACKeySpec key = new BACKey(variant, dob, exp);
                        Log.d(TAG, "Trying BAC variant after PACE: '" + variant + "'");
                        passportService.doBAC(key);
                        bacSucceeded = true;
                        Log.d(TAG, "BAC variant '" + variant + "' succeeded!");

                        try {
                            InputStream comInV = passportService.getInputStream(PassportService.EF_COM);
                            COMFile comFileV = new COMFile(comInV);
                            comTagList = comFileV.getTagList();
                            authVerified = true;
                            Log.d(TAG, "EF.COM readable!");
                        } catch (Exception comEx) {
                            Log.e(TAG, "BAC variant succeeded but EF.COM still unreadable");
                        }
                        break;
                    } catch (Exception e) {
                        Log.w(TAG, "BAC variant '" + variant + "' failed: " + e.getMessage());
                        try {
                            if (!isoDep.isConnected()) {
                                Log.e(TAG, "Tag lost during BAC variant attempts");
                                break;
                            }
                        } catch (Exception connEx) {
                            break;
                        }
                    }
                }
            }

            if (!authVerified) {
                this.errorCode = "READ_FAILED";
                this.error = "Unable to read document data. Please keep the document still and try again.";
                this.technicalError = "Auth appeared to succeed (PACE=" + paceSucceeded + ", BAC=" + bacSucceeded +
                    ") but EF.COM unreadable after all fallback attempts" +
                    " | Doc#: " + documentNumber + " | DOB: " + dateOfBirth + " | Exp: " + dateOfExpiry;
                return;
            }

            // ---- Read DG1 - MRZ Information ----
            if (listener != null) listener.onReadingDataGroup(1, "MRZ Information");
            try {
                byte[] dg1Bytes = readAllBytes(
                        passportService.getInputStream(PassportService.EF_DG1));
                rawDgBytes.put(1, dg1Bytes);
                DG1File dg1File = new DG1File(new ByteArrayInputStream(dg1Bytes));
                org.jmrtd.lds.icao.MRZInfo mrzInfo = dg1File.getMRZInfo();

                String genderStr;
                try {
                    Object g = mrzInfo.getGender();
                    if (g == null) {
                        genderStr = "Unspecified";
                    } else {
                        String gs = g.toString().toUpperCase();
                        if (gs.startsWith("M")) genderStr = "Male";
                        else if (gs.startsWith("F")) genderStr = "Female";
                        else genderStr = "Unspecified";
                    }
                } catch (Exception e) {
                    genderStr = "Unspecified";
                }

                documentData.documentType = safeString(mrzInfo.getDocumentCode());
                documentData.issuingState = safeString(mrzInfo.getIssuingState());
                documentData.primaryIdentifier = safeReplace(mrzInfo.getPrimaryIdentifier(), "<", " ");
                documentData.secondaryIdentifier = safeReplace(mrzInfo.getSecondaryIdentifier(), "<", " ");
                documentData.documentNumber = safeString(mrzInfo.getDocumentNumber());
                documentData.nationality = safeString(mrzInfo.getNationality());
                documentData.dateOfBirth = safeString(mrzInfo.getDateOfBirth());
                documentData.gender = genderStr;
                documentData.dateOfExpiry = safeString(mrzInfo.getDateOfExpiry());
                String pn = safeString(mrzInfo.getPersonalNumber());
                documentData.personalNumber = pn.replace("<", "").trim();

                dataGroupsRead.add(1);
                Log.d(TAG, "DG1 read successfully: " + documentData.primaryIdentifier + " " + documentData.secondaryIdentifier);
            } catch (Exception e) {
                Log.e(TAG, "Error reading DG1: " + e.getMessage(), e);
                readErrors.put(1, e.getMessage() != null ? e.getMessage() : "Unknown error");
            }

            // ---- Read DG2 - Facial Image ----
            if (listener != null) listener.onReadingDataGroup(2, "Facial Image");
            try {
                byte[] dg2Bytes = readAllBytes(
                        passportService.getInputStream(PassportService.EF_DG2));
                rawDgBytes.put(2, dg2Bytes);
                DG2File dg2File = new DG2File(new ByteArrayInputStream(dg2Bytes));
                List<?> faceInfos = dg2File.getFaceInfos();
                Log.d(TAG, "DG2 faceInfos count: " + (faceInfos != null ? faceInfos.size() : 0));

                if (faceInfos != null && !faceInfos.isEmpty()) {
                    org.jmrtd.lds.iso19794.FaceInfo faceInfo =
                        (org.jmrtd.lds.iso19794.FaceInfo) faceInfos.get(0);
                    List<FaceImageInfo> faceImageInfos = faceInfo.getFaceImageInfos();

                    if (faceImageInfos != null && !faceImageInfos.isEmpty()) {
                        FaceImageInfo faceImageInfo = faceImageInfos.get(0);
                        Log.d(TAG, "Face image: mimeType=" + faceImageInfo.getMimeType() +
                            ", width=" + faceImageInfo.getWidth() + ", height=" + faceImageInfo.getHeight());

                        Bitmap bitmap = decodeImage(faceImageInfo);
                        if (bitmap != null) {
                            documentData.faceImage = bitmap;
                            Log.d(TAG, "Face image decoded: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                        } else {
                            readErrors.put(2, "Image format not supported");
                        }
                    }
                }
                dataGroupsRead.add(2);
            } catch (Exception e) {
                Log.e(TAG, "Error reading DG2: " + e.getMessage(), e);
                readErrors.put(2, e.getMessage() != null ? e.getMessage() : "Unknown error");
            }

            // ---- Read DG7 - Signature (optional) ----
            if (listener != null) listener.onReadingDataGroup(7, "Signature");
            try {
                byte[] dg7Bytes = readAllBytes(
                        passportService.getInputStream(PassportService.EF_DG7));
                rawDgBytes.put(7, dg7Bytes);
                DG7File dg7File = new DG7File(new ByteArrayInputStream(dg7Bytes));
                List<?> displayedImages = dg7File.getImages();

                if (displayedImages != null && !displayedImages.isEmpty()) {
                    org.jmrtd.lds.DisplayedImageInfo imageInfo =
                        (org.jmrtd.lds.DisplayedImageInfo) displayedImages.get(0);
                    byte[] imageBytes = readAllBytes(imageInfo.getImageInputStream());
                    Bitmap bitmap = decodeImageBytes(imageBytes);
                    if (bitmap != null) {
                        documentData.signatureImage = bitmap;
                        Log.d(TAG, "Signature decoded: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    }
                }
                dataGroupsRead.add(7);
            } catch (Exception e) {
                Log.w(TAG, "DG7 not available: " + e.getMessage());
                readErrors.put(7, e.getMessage() != null ? e.getMessage() : "Not available");
            }

            // ---- Read DG11 - Additional Personal Details (optional) ----
            if (listener != null) listener.onReadingDataGroup(11, "Additional Personal Details");
            try {
                byte[] dg11Bytes = readAllBytes(
                        passportService.getInputStream(PassportService.EF_DG11));
                rawDgBytes.put(11, dg11Bytes);
                DG11File dg11File = new DG11File(new ByteArrayInputStream(dg11Bytes));

                documentData.fullNameOfHolder = safeString(dg11File.getNameOfHolder());
                try {
                    List<String> names = dg11File.getOtherNames();
                    if (names != null) documentData.otherNames = names;
                } catch (Exception ignored) {}
                documentData.personalSummary = safeString(dg11File.getPersonalSummary());
                try {
                    List<String> pob = dg11File.getPlaceOfBirth();
                    if (pob != null) {
                        documentData.placeOfBirth = joinStrings(pob, ", ");
                        documentData.placeOfBirthLines = pob;
                    }
                } catch (Exception ignored) {}
                try {
                    List<String> addr = dg11File.getPermanentAddress();
                    if (addr != null) {
                        // Kept unflattened: this field carries several distinct attributes on some
                        // documents, not one address.
                        documentData.permanentAddress = joinStrings(addr, ", ");
                        documentData.permanentAddressLines = addr;
                    }
                } catch (Exception ignored) {}
                documentData.telephone = safeString(dg11File.getTelephone());

                dataGroupsRead.add(11);
                Log.d(TAG, "DG11 read successfully");
            } catch (Exception e) {
                Log.w(TAG, "DG11 not available: " + e.getMessage());
                readErrors.put(11, e.getMessage() != null ? e.getMessage() : "Not available");
            }

            // ---- Read DG12 - Additional Document Details (optional) ----
            if (listener != null) listener.onReadingDataGroup(12, "Additional Document Details");
            try {
                byte[] dg12Bytes = readAllBytes(
                        passportService.getInputStream(PassportService.EF_DG12));
                rawDgBytes.put(12, dg12Bytes);
                DG12File dg12File = new DG12File(new ByteArrayInputStream(dg12Bytes));

                documentData.issuingAuthority = safeString(dg12File.getIssuingAuthority());
                documentData.dateOfIssue = safeString(dg12File.getDateOfIssue());
                documentData.endorsementsAndObservations = safeString(dg12File.getEndorsementsAndObservations());

                dataGroupsRead.add(12);
                Log.d(TAG, "DG12 read successfully");
            } catch (Exception e) {
                Log.w(TAG, "DG12 not available: " + e.getMessage());
                readErrors.put(12, e.getMessage() != null ? e.getMessage() : "Not available");
            }

            // ---- Read SOD - Document Security Object ----
            if (listener != null) listener.onReadingDataGroup(0, "Security Object");
            SODFile sodFile = null;
            byte[] sodBytes = null;
            try {
                sodBytes = readAllBytes(passportService.getInputStream(PassportService.EF_SOD));
                sodFile = new SODFile(new ByteArrayInputStream(sodBytes));
                Log.d(TAG, "SOD read successfully");
            } catch (Exception e) {
                Log.w(TAG, "SOD not available: " + e.getMessage());
                readErrors.put(0, e.getMessage() != null ? e.getMessage() : "Not available");
            }

            // ---- Recover non-Latin text that UTF-8 decoding destroyed ----
            // Runs before the raw bytes are dropped, and only rewrites fields that actually came
            // back with replacement characters. See MrtdTextDecoder.
            documentData.textEncoding = MrtdTextDecoder.recover(documentData, rawDgBytes);

            // ---- Passive authentication ----
            // Runs last because it needs both the SOD and every data group's raw bytes. Skipped
            // only when no context was supplied, in which case the payload reports "notVerified"
            // rather than implying the data was checked.
            if (passiveAuthContext != null) {
                PassiveAuthenticator authenticator =
                        new PassiveAuthenticator(passiveAuthContext, passiveAuthConfig);
                documentData.passiveAuthentication = authenticator.verify(sodFile, rawDgBytes);
            } else {
                Log.w(TAG, "Passive authentication skipped: no context configured");
            }
            // Handed to the caller only on request: they are a second full copy of every field
            // and the portrait, in the rawest form the holder's data takes.
            if (includeRawDataGroups) {
                documentData.rawDataGroups = new LinkedHashMap<>(rawDgBytes);
                documentData.rawSod = sodBytes;
            }

            // The raw groups are holder data; drop them now that the hashes have been compared.
            rawDgBytes.clear();

            documentData.dataGroupsRead = dataGroupsRead;
            documentData.chipAccessEstablished = bacSucceeded || paceSucceeded;
            // The protocol that actually unlocked the chip. BAC wins when both are set, because
            // the BAC path only runs as a fallback after PACE could not deliver a readable EF.COM.
            documentData.accessProtocol = bacSucceeded ? "BAC" : (paceSucceeded ? "PACE" : null);
            documentData.readErrors = readErrors;

            this.result = documentData;

            try {
                passportService.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing passport service: " + e.getMessage());
            }

        } catch (android.nfc.TagLostException e) {
            Log.e(TAG, "Tag was lost during reading", e);
            this.errorCode = "TAG_LOST";
            this.error = "Connection lost. Please hold the document steady on your phone and try again.";
            this.technicalError = "TagLostException: " + e.getMessage();
        } catch (java.io.IOException e) {
            Log.e(TAG, "IO error during NFC reading", e);
            this.errorCode = "COMM_ERROR";
            this.error = "A communication error occurred. Please try again and keep the document still.";
            this.technicalError = "IOException: " + e.getMessage();
        } catch (Exception e) {
            Log.e(TAG, "Error reading document: " + e.getMessage(), e);
            this.errorCode = "UNKNOWN";
            this.error = "An unexpected error occurred. Please try again.";
            this.technicalError = e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "no message");
        }
    }

    // ==================== Image Decoding ====================

    private Bitmap decodeImage(FaceImageInfo faceImageInfo) {
        try {
            InputStream inputStream = faceImageInfo.getImageInputStream();
            int imageLength = faceImageInfo.getImageLength();

            byte[] imageBytes;
            if (imageLength > 0) {
                DataInputStream dataIn = new DataInputStream(inputStream);
                imageBytes = new byte[imageLength];
                dataIn.readFully(imageBytes);
            } else {
                imageBytes = readAllBytes(inputStream);
            }

            Log.d(TAG, "Face image raw bytes: " + imageBytes.length);
            return decodeImageBytes(imageBytes);
        } catch (Exception e) {
            Log.e(TAG, "Error decoding face image: " + e.getMessage(), e);
            return null;
        }
    }

    private Bitmap decodeImageBytes(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            Log.w(TAG, "Empty image data");
            return null;
        }

        // 1. Try direct BitmapFactory decode
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (bitmap != null) {
            Log.d(TAG, "Image decoded directly by BitmapFactory");
            return bitmap;
        }

        // 2. Search for JPEG SOI marker (0xFF 0xD8)
        for (int i = 0; i < imageBytes.length - 1; i++) {
            if (imageBytes[i] == (byte) 0xFF && imageBytes[i + 1] == (byte) 0xD8) {
                bitmap = BitmapFactory.decodeByteArray(imageBytes, i, imageBytes.length - i);
                if (bitmap != null) {
                    Log.d(TAG, "Found and decoded JPEG at offset " + i);
                    return bitmap;
                }
            }
        }

        // 3. Check for JPEG2000 format
        boolean isJP2Container = imageBytes.length > 12 &&
            imageBytes[0] == (byte) 0x00 && imageBytes[1] == (byte) 0x00 &&
            imageBytes[2] == (byte) 0x00 && imageBytes[3] == (byte) 0x0C &&
            imageBytes[4] == (byte) 0x6A && imageBytes[5] == (byte) 0x50;
        boolean isJ2KCodestream = imageBytes.length > 2 &&
            imageBytes[0] == (byte) 0xFF && imageBytes[1] == (byte) 0x4F;

        if (isJP2Container || isJ2KCodestream) {
            Log.d(TAG, "Image is JPEG2000 (" + (isJP2Container ? "JP2 container" : "J2K codestream") + ")");
            bitmap = Jpeg2000Decoder.decode(imageBytes);
            if (bitmap != null) {
                Log.d(TAG, "JPEG2000 decoded successfully");
                return bitmap;
            }
        }

        // 4. Search for PNG signature
        for (int i = 0; i < imageBytes.length - 8; i++) {
            if (imageBytes[i] == (byte) 0x89 && imageBytes[i + 1] == (byte) 0x50 &&
                imageBytes[i + 2] == (byte) 0x4E && imageBytes[i + 3] == (byte) 0x47) {
                bitmap = BitmapFactory.decodeByteArray(imageBytes, i, imageBytes.length - i);
                if (bitmap != null) {
                    Log.d(TAG, "Found and decoded PNG at offset " + i);
                    return bitmap;
                }
            }
        }

        Log.w(TAG, "Could not decode image (" + imageBytes.length + " bytes)");
        return null;
    }

    // ==================== Utility Methods ====================

    private byte[] readAllBytes(InputStream inputStream) throws java.io.IOException {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        return output.toByteArray();
    }

    private static String safeString(String value) {
        return value != null ? value : "";
    }

    private static String safeReplace(String value, String target, String replacement) {
        if (value == null) return "";
        return value.replace(target, replacement).trim();
    }

    private static String joinStrings(List<String> list, String separator) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static String padLeft(String s, int length, char padChar) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < length) {
            sb.insert(0, padChar);
        }
        return sb.toString();
    }

    private static String padRight(String s, int length, char padChar) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < length) {
            sb.append(padChar);
        }
        return sb.toString();
    }

    private static String trimRight(String s, char trimChar) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == trimChar) {
            end--;
        }
        return s.substring(0, end);
    }
}
