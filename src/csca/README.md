# CSCA trust store

Passive authentication needs a bundle of Country Signing CA (CSCA) certificates to decide whether
the certificate that signed a chip is one an issuing state actually published. This directory is
where that bundle goes.

## Why this matters more than it looks

Without a trust store, passive authentication can still check that the SOD's signature is valid
and that every data group hashes to the value the SOD records. Those two checks only prove the
chip is **internally consistent** — and a forger who signs their own data with their own
certificate produces a chip that passes both. The CSCA bundle is what makes the check mean
"signed by Bahrain / by Germany / by the state that claims to have issued this document".

So a build with no bundle reports `authentication.passiveAuthentication.status` as
`"notVerified"` with reason `NO_TRUST_ANCHORS`, never `"passed"`. It is not a failure — nothing
about the document was contradicted — but it is not evidence of authenticity either.

## Getting the certificates

CSCA certificates come from the ICAO Public Key Directory (PKD). The ICAO master list is
distributed as a CMS-signed `.ml` file, which neither platform reads directly — convert it to a
PEM bundle first, on a machine inside the bank:

```
# Extract the signed master list content, then the certificates within it
openssl cms -verify -inform DER -in <masterlist>.ml -noverify -out masterlist.der
openssl asn1parse -inform DER -in masterlist.der            # inspect before trusting
# Convert each extracted certificate to PEM and concatenate into one bundle
cat csca-*.pem > csca_master_list.pem
```

Verify the master list's own CMS signature against ICAO's published signer **before** converting
— `-noverify` above only skips chain building during extraction, and a master list you did not
authenticate is exactly as trustworthy as no master list at all. Record where the file came from
and when: the bundle is a trust anchor, so its provenance belongs in the same governance file as
the rest of the identity-check controls.

Countries roll CSCA keys, and documents stay valid for up to ten years, so the bundle needs
periodic refresh. An out-of-date bundle shows up as `ISSUER_NOT_TRUSTED` on genuine documents.

## Installing the bundle

1. Put the PEM bundle here as `csca_master_list.pem`.

2. Uncomment the two `<!-- CSCA trust store -->` blocks in `plugin.xml` (one under the Android
   platform, one under iOS). They are commented out because Cordova fails plugin installation if
   a `<resource-file>` points at a file that does not exist — with the bundle present,
   uncommenting installs it into Android assets and the iOS bundle.

3. Reinstall the plugin so the asset is staged:

   ```
   cordova plugin remove cordova-plugin-nfc-document-reader
   cordova plugin add <path-to-this-plugin>
   ```

Both platforms default to this filename, so `readNFC` picks it up with no JS options. To point at
a different bundle, pass `passiveAuth: { trustStoreAsset: "other.pem" }`; to disable the issuer
check explicitly, pass `passiveAuth: { trustStoreAsset: null }`.

## What passive authentication does and does not prove

Proves, when `status` is `"passed"`: the data groups read off this chip are byte-for-byte what the
issuing state signed, and the signer chains to a CSCA in your bundle.

Does not prove:

- **That the chip is not a clone.** Passive authentication verifies data, not the medium. A
  bit-perfect copy of a genuine chip passes. Chip Authentication (part of EAC) is what detects
  cloning; `authentication.chipAuthentication` reports whether it ran — `"notPerformed"` on
  Android today.
- **That the holder is the rightful holder.** That is the face match, separately reported.
- **Revocation.** Neither platform consults CRLs or PKD deltas, so a document signer certificate
  revoked after issuance still verifies. Implementation limits are listed on
  `PassiveAuthenticator` (Android) and in `NfcDocumentReader.swift` (iOS).
