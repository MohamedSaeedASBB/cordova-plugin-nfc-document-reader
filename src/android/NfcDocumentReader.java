package com.nfcdocumentreader;

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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
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
    private String error;

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

    public String getError() {
        return error;
    }

    /**
     * Read all available data groups from the NFC chip.
     */
    public void readDocument(Tag tag, String documentNumber, String dateOfBirth,
                             String dateOfExpiry, ProgressListener listener) {
        result = null;
        error = null;

        DocumentData documentData = new DocumentData();
        List<Integer> dataGroupsRead = new ArrayList<>();
        java.util.Map<Integer, String> readErrors = new java.util.HashMap<>();

        try {
            if (listener != null) listener.onStateChanged("connecting");

            IsoDep isoDep = IsoDep.get(tag);
            if (isoDep == null) {
                error = "Tag does not support IsoDep";
                return;
            }

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

            // Ensure dates are exactly 6 characters (YYMMDD)
            String dob = padLeft(dateOfBirth, 6, '0');
            if (dob.length() > 6) dob = dob.substring(0, 6);
            String exp = padLeft(dateOfExpiry, 6, '0');
            if (exp.length() > 6) exp = exp.substring(0, 6);

            // Pad document number to 9 chars with '<' for BAC key computation
            String paddedDocNum = documentNumber;
            while (paddedDocNum.length() < 9) paddedDocNum += "<";

            Log.d(TAG, "Auth inputs - docNum: '" + documentNumber + "', padded: '" + paddedDocNum + "', dob: '" + dob + "', exp: '" + exp + "'");

            BACKeySpec bacKey;
            try {
                bacKey = new BACKey(paddedDocNum, dob, exp);
            } catch (IllegalArgumentException e) {
                this.error = "Invalid MRZ data format.\n\nDoc#: " + documentNumber +
                    "\nDOB: " + dateOfBirth + "\nExp: " + dateOfExpiry +
                    "\n\nDates must be in YYMMDD format (6 digits).";
                return;
            }

            // Try PACE first with PACEKeySpec (required for PACE-only cards)
            try {
                InputStream cardAccessInputStream = null;
                try {
                    cardAccessInputStream = passportService.getInputStream(PassportService.EF_CARD_ACCESS);
                } catch (Exception e) {
                    Log.d(TAG, "No CardAccess file: " + e.getMessage());
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

                    if (!paceInfos.isEmpty()) {
                        // Try PACE with each available PACEInfo
                        for (PACEInfo paceInfo : paceInfos) {
                            try {
                                Log.d(TAG, "Attempting PACE with OID: " + paceInfo.getObjectIdentifier() +
                                    ", paramId: " + paceInfo.getParameterId());

                                // Use PACEKeySpec for proper PACE key derivation
                                PACEKeySpec paceKey = PACEKeySpec.createMRZKey(bacKey);
                                passportService.doPACE(
                                    paceKey,
                                    paceInfo.getObjectIdentifier(),
                                    PACEInfo.toParameterSpec(paceInfo.getParameterId()),
                                    paceInfo.getParameterId()
                                );
                                paceSucceeded = true;
                                Log.d(TAG, "PACE succeeded with PACEKeySpec");
                                break;
                            } catch (Exception e1) {
                                Log.w(TAG, "PACE with PACEKeySpec failed: " + e1.getMessage());

                                // Retry with BACKeySpec (some JMRTD versions prefer this)
                                try {
                                    passportService.sendSelectApplet(false);
                                    passportService.doPACE(
                                        bacKey,
                                        paceInfo.getObjectIdentifier(),
                                        PACEInfo.toParameterSpec(paceInfo.getParameterId()),
                                        paceInfo.getParameterId()
                                    );
                                    paceSucceeded = true;
                                    Log.d(TAG, "PACE succeeded with BACKeySpec");
                                    break;
                                } catch (Exception e2) {
                                    Log.w(TAG, "PACE with BACKeySpec also failed: " + e2.getMessage());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "PACE setup failed: " + e.getMessage());
            }

            // Fall back to BAC with multiple document number variants
            if (!paceSucceeded) {
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
                    this.error = "Authentication failed.\n\nValues used:\nDoc#: " + documentNumber +
                        "\nDOB: " + dateOfBirth + "\nExp: " + dateOfExpiry +
                        "\n\nTips:\n- Use the document number from the MRZ lines, not the printed number\n" +
                        "- Keep the card perfectly still on the phone";
                    return;
                }
            }

            // Verify authentication by reading EF.COM
            Log.d(TAG, "Auth complete (PACE=" + paceSucceeded + ", BAC=" + bacSucceeded +
                "). Verifying file access...");
            if (listener != null) listener.onReadingDataGroup(0, "Verifying access");

            int[] comTagList = null;
            boolean authVerified = false;

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
                this.error = "Authentication appeared to succeed but unable to read card data.\n\n" +
                    "Possible causes:\n- Card moved during reading\n- Non-standard NFC format\n\nPlease try again.";
                return;
            }

            // ---- Read DG1 - MRZ Information ----
            if (listener != null) listener.onReadingDataGroup(1, "MRZ Information");
            try {
                InputStream dg1In = passportService.getInputStream(PassportService.EF_DG1);
                DG1File dg1File = new DG1File(dg1In);
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
                InputStream dg2In = passportService.getInputStream(PassportService.EF_DG2);
                DG2File dg2File = new DG2File(dg2In);
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
                InputStream dg7In = passportService.getInputStream(PassportService.EF_DG7);
                DG7File dg7File = new DG7File(dg7In);
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
                InputStream dg11In = passportService.getInputStream(PassportService.EF_DG11);
                DG11File dg11File = new DG11File(dg11In);

                documentData.fullNameOfHolder = safeString(dg11File.getNameOfHolder());
                try {
                    List<String> names = dg11File.getOtherNames();
                    if (names != null) documentData.otherNames = names;
                } catch (Exception ignored) {}
                documentData.personalSummary = safeString(dg11File.getPersonalSummary());
                try {
                    List<String> pob = dg11File.getPlaceOfBirth();
                    if (pob != null) documentData.placeOfBirth = joinStrings(pob, ", ");
                } catch (Exception ignored) {}
                try {
                    List<String> addr = dg11File.getPermanentAddress();
                    if (addr != null) documentData.permanentAddress = joinStrings(addr, ", ");
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
                InputStream dg12In = passportService.getInputStream(PassportService.EF_DG12);
                DG12File dg12File = new DG12File(dg12In);

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
            boolean chipAuthSucceeded = false;
            try {
                InputStream sodIn = passportService.getInputStream(PassportService.EF_SOD);
                SODFile sodFile = new SODFile(sodIn);
                try {
                    chipAuthSucceeded = sodFile.getDocSigningCertificate() != null;
                } catch (Exception ignored) {}
                Log.d(TAG, "SOD read successfully, cert present: " + chipAuthSucceeded);
            } catch (Exception e) {
                Log.w(TAG, "SOD not available: " + e.getMessage());
            }

            documentData.dataGroupsRead = dataGroupsRead;
            documentData.bacSucceeded = bacSucceeded || paceSucceeded;
            documentData.chipAuthSucceeded = chipAuthSucceeded;
            documentData.readErrors = readErrors;

            this.result = documentData;

            try {
                passportService.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing passport service: " + e.getMessage());
            }

        } catch (android.nfc.TagLostException e) {
            Log.e(TAG, "Tag was lost during reading", e);
            this.error = "Connection lost. The document was moved away too soon.\n\nPlease hold it steady and try again.";
        } catch (java.io.IOException e) {
            Log.e(TAG, "IO error during NFC reading", e);
            this.error = "Communication error with the chip.\n\nPlease try again and keep the document still.";
        } catch (Exception e) {
            Log.e(TAG, "Error reading document: " + e.getMessage(), e);
            this.error = e.getMessage() != null ? e.getMessage() : "Unknown error reading document";
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
