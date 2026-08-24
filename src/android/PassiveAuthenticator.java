package com.nfcdocumentreader;

import android.content.Context;
import android.util.Log;

import org.jmrtd.Util;
import org.jmrtd.lds.SODFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passive authentication (ICAO 9303 part 11): proves the data read off the chip is the data the
 * issuing state signed, and has not been altered.
 *
 * WHY THIS EXISTS
 * BAC/PACE only prove that whoever presents the chip knows the MRZ — they say nothing about
 * whether the contents are genuine. Chip Authentication proves the chip is not a clone, but not
 * that its data is authentic. Without passive authentication, every field this plugin returns —
 * including the DG2 portrait a face match is compared against — is unverified input. A forged
 * chip programmed with an attacker's portrait and a victim's name passes BAC/PACE happily.
 *
 * THREE INDEPENDENT CHECKS, REPORTED SEPARATELY
 *   1. sodSignatureVerified  - the Document Security Object (EF.SOD) carries a valid signature
 *                              made by the document signer certificate (DSC) embedded in it.
 *   2. dataIntegrityVerified - every data group actually read hashes to the value recorded for it
 *                              in the SOD, and no data group we read is absent from the SOD.
 *   3. issuerTrusted         - that DSC chains to a Country Signing CA (CSCA) in a trust store
 *                              the bank supplies.
 *
 * They are reported separately because only 1+2+3 together mean anything. 1+2 alone prove the
 * chip is *internally consistent*, which any forger can achieve by signing their own data with
 * their own certificate — so a build with no trust store reports "notVerified", never "passed".
 *
 * DELIBERATE LIMITS, stated so nobody over-reads the result:
 *   - The chain check is one level: DSC verified directly against a CSCA in the supplied bundle.
 *     It is not full PKIX path building, and it does not follow CSCA link certificates.
 *   - No revocation checking. CRLs and the ICAO PKD deltas are not consulted, so a DSC revoked
 *     after issuance still verifies here.
 *   - An expired DSC is reported but not failed: documents outlive the certificates that signed
 *     them, and a DSC that had expired by the time you read a ten-year-old passport is normal.
 *
 * PassiveAuthenticator has no counterpart file on iOS: NFCPassportReader already performs the
 * equivalent checks (verifyPassport) and NfcDocumentReader.swift surfaces them into the same
 * wire format this class produces.
 */
public class PassiveAuthenticator {

    private static final String TAG = "PassiveAuth";

    /** Default asset name for the CSCA bundle, auto-installed by plugin.xml. See src/csca/README.md. */
    public static final String DEFAULT_TRUST_STORE_ASSET = "csca_master_list.pem";

    public static class Config {
        /**
         * Asset path of a PEM or DER bundle of CSCA certificates. Null means no trust store, in
         * which case the issuer cannot be established and the result is "notVerified".
         */
        public String trustStoreAsset;
    }

    public static class Result {
        /** "passed", "failed" or "notVerified". Never "passed" without all three checks. */
        public String status = "notVerified";
        public boolean sodSignatureVerified = false;
        public boolean dataIntegrityVerified = false;
        public boolean issuerTrusted = false;
        /** Digest algorithm named in the SOD, e.g. "SHA-256". */
        public String digestAlgorithm;
        /** Signature algorithm named in the SOD, e.g. "SHA256withRSA". */
        public String signatureAlgorithm;
        /** Issuer-side identity of the signer. Never holder data. */
        public String documentSignerSubject;
        /** "none", or the number of anchors loaded, e.g. "loaded:412". */
        public String trustStore = "none";
        /** Per data group: was it covered by the SOD, and did the hash match. */
        public Map<Integer, Boolean> dataGroupHashMatches = new LinkedHashMap<>();
        /** Machine-readable codes explaining a non-"passed" status. */
        public List<String> reasons = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("status", status);
            json.put("sodSignatureVerified", sodSignatureVerified);
            json.put("dataIntegrityVerified", dataIntegrityVerified);
            json.put("issuerTrusted", issuerTrusted);
            json.put("digestAlgorithm", digestAlgorithm != null ? digestAlgorithm : JSONObject.NULL);
            json.put("signatureAlgorithm",
                    signatureAlgorithm != null ? signatureAlgorithm : JSONObject.NULL);
            json.put("documentSignerSubject",
                    documentSignerSubject != null ? documentSignerSubject : JSONObject.NULL);
            json.put("trustStore", trustStore);

            JSONObject hashes = new JSONObject();
            for (Map.Entry<Integer, Boolean> entry : dataGroupHashMatches.entrySet()) {
                hashes.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            json.put("dataGroupHashes", hashes);

            JSONArray reasonArray = new JSONArray();
            for (String reason : reasons) {
                reasonArray.put(reason);
            }
            json.put("reasons", reasonArray);
            return json;
        }
    }

    private final Context context;
    private final Config config;

    public PassiveAuthenticator(Context context, Config config) {
        this.context = context;
        this.config = config != null ? config : new Config();
    }

    /**
     * @param sod        parsed EF.SOD, or null if it could not be read
     * @param rawDgBytes data group number to the exact bytes read from the chip. These must be the
     *                   bytes as read — re-encoding a parsed data group (jmrtd's getEncoded())
     *                   produces a different byte string in edge cases and would fail the hash
     *                   comparison on a genuine document.
     */
    public Result verify(SODFile sod, Map<Integer, byte[]> rawDgBytes) {
        Result result = new Result();

        if (sod == null) {
            result.status = "notVerified";
            result.reasons.add("SOD_NOT_READ");
            return result;
        }

        X509Certificate dsc;
        try {
            dsc = sod.getDocSigningCertificate();
        } catch (Exception e) {
            dsc = null;
        }
        if (dsc == null) {
            // Nothing can be verified without the signer's public key.
            result.status = "failed";
            result.reasons.add("NO_DOC_SIGNING_CERTIFICATE");
            return result;
        }
        result.documentSignerSubject = String.valueOf(dsc.getSubjectX500Principal());

        boolean hardFailure = false;

        // ---- 1. Is the SOD signed by the DSC it carries? ----
        try {
            result.signatureAlgorithm = sod.getDigestEncryptionAlgorithm();
            // getEContent() returns the signed attributes and, on the way, checks that the
            // messageDigest attribute matches the digest of the encapsulated LDSSecurityObject.
            // That check matters: without it, an attacker could keep an authentic signature over
            // the attributes while swapping the content that carries the data group hashes.
            byte[] eContent = sod.getEContent();
            byte[] signature = sod.getEncryptedDigest();

            Signature verifier = Util.getSignature(result.signatureAlgorithm);
            AlgorithmParameterSpec params = sod.getDigestEncryptionAlgorithmParams();
            if (params != null) {
                verifier.setParameter(params);
            }
            verifier.initVerify(dsc.getPublicKey());
            verifier.update(eContent);
            result.sodSignatureVerified = verifier.verify(signature);
            if (!result.sodSignatureVerified) {
                hardFailure = true;
                result.reasons.add("SOD_SIGNATURE_INVALID");
            }
        } catch (java.security.SignatureException e) {
            // Raised by getEContent() when the signed messageDigest does not match the content.
            Log.e(TAG, "SOD content digest mismatch: " + e.getMessage());
            hardFailure = true;
            result.reasons.add("SOD_CONTENT_DIGEST_MISMATCH");
        } catch (Exception e) {
            Log.e(TAG, "SOD signature check failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            hardFailure = true;
            result.reasons.add("SOD_SIGNATURE_UNCHECKABLE");
        }

        // ---- 2. Do the data groups we read hash to what the SOD says? ----
        try {
            result.digestAlgorithm = sod.getDigestAlgorithm();
            Map<Integer, byte[]> sodHashes = sod.getDataGroupHashes();
            MessageDigest digest = Util.getMessageDigest(result.digestAlgorithm);

            boolean allMatched = true;
            for (Map.Entry<Integer, byte[]> entry : rawDgBytes.entrySet()) {
                int dg = entry.getKey();
                byte[] sodHash = sodHashes != null ? sodHashes.get(dg) : null;
                if (sodHash == null) {
                    // We read a data group the SOD does not vouch for. Treated as a failure, not
                    // a gap: unsigned data presented alongside signed data is exactly what a
                    // tampered chip looks like.
                    result.dataGroupHashMatches.put(dg, false);
                    result.reasons.add("DG_NOT_COVERED_BY_SOD:" + dg);
                    allMatched = false;
                    continue;
                }
                digest.reset();
                boolean match = Arrays.equals(digest.digest(entry.getValue()), sodHash);
                result.dataGroupHashMatches.put(dg, match);
                if (!match) {
                    result.reasons.add("DG_HASH_MISMATCH:" + dg);
                    allMatched = false;
                }
            }

            if (result.dataGroupHashMatches.isEmpty()) {
                allMatched = false;
                result.reasons.add("NO_DATA_GROUPS_TO_VERIFY");
            }

            // Data groups listed in the SOD that we did not read are not a failure: this reader
            // deliberately skips EAC-protected groups. Their absence is reported by
            // dataGroupsRead, and nothing claims they were verified.
            result.dataIntegrityVerified = allMatched;
            if (!allMatched) {
                hardFailure = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Data group hash comparison failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            hardFailure = true;
            result.reasons.add("DG_HASHES_UNCHECKABLE");
        }

        // ---- 3. Does the DSC chain to a CSCA the bank trusts? ----
        Collection<X509Certificate> anchors = loadTrustAnchors(result);
        if (anchors == null || anchors.isEmpty()) {
            result.reasons.add("NO_TRUST_ANCHORS");
        } else {
            result.trustStore = "loaded:" + anchors.size();
            result.issuerTrusted = chainsToAnchor(dsc, anchors);
            if (!result.issuerTrusted) {
                hardFailure = true;
                result.reasons.add("ISSUER_NOT_TRUSTED");
            }
        }

        try {
            dsc.checkValidity();
        } catch (Exception e) {
            // Reported, never failed: see the class comment.
            result.reasons.add("DOC_SIGNER_CERTIFICATE_EXPIRED");
        }

        if (result.sodSignatureVerified && result.dataIntegrityVerified && result.issuerTrusted) {
            result.status = "passed";
        } else if (hardFailure) {
            result.status = "failed";
        } else {
            result.status = "notVerified";
        }

        // Verdict and reasons only — no holder data, no hashes of holder data.
        Log.i(TAG, "Passive authentication: status=" + result.status
                + " sodSignature=" + result.sodSignatureVerified
                + " dataIntegrity=" + result.dataIntegrityVerified
                + " issuerTrusted=" + result.issuerTrusted
                + " reasons=" + result.reasons);
        return result;
    }

    /**
     * One-level issuer check: the DSC must be signed by a CSCA in the bundle. Deliberately not
     * CertPathValidator — a CSCA bundle is a flat set of self-signed roots plus link
     * certificates, and PKIX path building over that rejects genuine documents more often than
     * it catches forged ones. The trade-off (no link-certificate following, no revocation) is
     * documented on the class.
     */
    private boolean chainsToAnchor(X509Certificate dsc, Collection<X509Certificate> anchors) {
        for (X509Certificate anchor : anchors) {
            if (!anchor.getSubjectX500Principal().equals(dsc.getIssuerX500Principal())) {
                continue;
            }
            try {
                dsc.verify(anchor.getPublicKey());
                return true;
            } catch (Exception e) {
                // Same subject name, different key — keep looking. Countries roll CSCA keys and
                // publish several certificates under one name.
                Log.d(TAG, "Anchor matched issuer name but not the signature; trying the next.");
            }
        }
        return false;
    }

    /** Mirrors FaceMatcher.candidatePaths: the same staging uncertainty applies to the bundle. */
    private String[] candidateTrustStorePaths(String assetPath) {
        String name = assetPath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return new String[] { assetPath, "www/" + name, "public/" + name, name };
    }

    private Collection<X509Certificate> loadTrustAnchors(Result result) {
        if (config.trustStoreAsset == null || config.trustStoreAsset.isEmpty()) {
            return null;
        }
        // Absent and unreadable are different situations and were being reported as both at
        // once: "not installed yet" is expected on a build without a bundle, while "present but
        // will not parse" is a fault worth chasing. Only the second is TRUST_STORE_UNREADABLE.
        String resolved = null;
        for (String candidate : candidateTrustStorePaths(config.trustStoreAsset)) {
            try (InputStream probe = context.getAssets().open(candidate)) {
                resolved = candidate;
                break;
            } catch (Exception notHere) {
                // Try the next location.
            }
        }
        if (resolved == null) {
            Log.i(TAG, "No CSCA trust store installed. Searched: "
                    + java.util.Arrays.toString(candidateTrustStorePaths(config.trustStoreAsset)));
            return null;                    // NO_TRUST_ANCHORS is added by the caller
        }

        try (InputStream in = context.getAssets().open(resolved)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            @SuppressWarnings("unchecked")
            Collection<X509Certificate> certs =
                    (Collection<X509Certificate>) factory.generateCertificates(in);
            Log.i(TAG, "Loaded " + certs.size() + " CSCA anchors from " + resolved);
            return certs;
        } catch (Exception e) {
            Log.e(TAG, "CSCA trust store is present but could not be parsed (" + resolved
                    + "): " + e.getMessage());
            result.trustStore = "unreadable";
            result.reasons.add("TRUST_STORE_UNREADABLE");
            return null;
        }
    }
}
