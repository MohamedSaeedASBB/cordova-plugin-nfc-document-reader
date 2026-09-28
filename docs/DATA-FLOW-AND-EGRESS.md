# Where the data goes

Written in answer to: *"التأكيد التام على أن خاصية التحقق لا تقوم نهائياً بإرسال أي بيانات أو معلومات خارج نطاق جهاز المستخدم وخوادم البنك الآمنة."*
— full confirmation that the verification feature never sends any data outside the user's device and the bank's secure servers.

**Short answer: identity data, yes — confirmed. Everything, no — not while ML Kit is in the build.**
The first part is now true and was not before. The second part is a third-party SDK's behaviour, not ours,
and it is documented below rather than glossed over, because a statement signed on a false premise is worse
than a qualified one.

---

## 1. What this plugin itself sends: nothing

The plugin opens no network connections. Verified by searching every source file for every network
primitive on both platforms:

```
grep -rn "HttpURLConnection|URLSession|dataTask|openConnection|Socket|http://|https://" src/
→ no matches
```

Document fields, MRZ, chip data groups, the DG2 portrait, the captured photographs, the liveness frames
and the face-match similarity are computed on the handset and returned to the calling app through the
Cordova callback. They reach the bank only if the bank's own app sends them to the bank's own backend.

Face matching runs on-device: MobileFaceNet as a bundled TensorFlow Lite model, inference on the CPU.
No image is uploaded to score a match.

## 2. What it used to send, and no longer does

Until this change, **every NFC read failure was POSTed to a third-party Supabase project belonging to the
plugin's developer**, at `sjcjfnnoasoddtpbmjxf.supabase.co`, on both Android and iOS. The payload carried:

| Field | Value |
|---|---|
| `mrz_masked` | document number as `1139***06`, date of birth as `97***2`, expiry likewise |
| `app_package` | the host application's bundle/package identifier |
| `device_model`, `os_version` | handset make, model and OS build |
| `pace_info`, `nfc_tech_list` | chip protocol details |
| `technical_error` | up to 2000 characters of reader internals |

The endpoint and its API key were XOR-obfuscated in the source, with the stated intent — in the code
comments — of keeping them out of a decompiled build.

**The masking did not anonymise anything.** Four leading and two trailing digits of a nine-digit document
number leave a thousand candidates; a birth year and a bank's bundle identifier narrow that to one person.
This was customer data leaving the bank to a service the bank neither controls nor has a contract with.

Both loggers are now local-only. They write to logcat and the iOS unified log, and the MRZ arguments they
still accept are deliberately not written to either, because a device log is readable by anything with the
handset attached. The network code is deleted, not disabled by a flag.

**Do not reintroduce an endpoint in the plugin.** If diagnostics must leave the device, they leave through
the host application, to a bank-controlled destination, under the bank's retention rules — where they can
be audited.

## 3. What a third party still sends: Google ML Kit

This is the part that prevents an unqualified statement, and it is Google's code, not ours.

ML Kit performs its inference on-device — a face is never uploaded to be detected. But the SDK also reports
usage and diagnostic data to Google. Evidence, taken from the pods resolved for this plugin rather than
from documentation alone:

- `MLKitCommon.framework` contains the endpoints `https://play.googleapis.com/log` and
  `https://play.googleapis.com/log/batch`, and a class named `MLKAnalyticsLogger`.
- It depends on `GoogleDataTransport`, whose Apple privacy manifest declares it collects
  `OtherDiagnosticData` for the purpose `Analytics`.
- **`MLKitFaceDetection`'s own Apple privacy manifest** (`PrivacyInfo.xcprivacy`, shipped by Google)
  declares these collected data types:

  ```
  NSPrivacyCollectedDataTypeDeviceID
  NSPrivacyCollectedDataTypeOtherUserContent      ← needs clarification from Google
  NSPrivacyCollectedDataTypeOtherDiagnosticData
  NSPrivacyCollectedDataTypePerformanceData
  NSPrivacyCollectedDataTypeProductInteraction
  NSPrivacyCollectedDataTypeOtherDataTypes
  ```

  `NSPrivacyTracking` is `false` and no tracking domains are declared, so this is not advertising
  tracking. But `OtherUserContent` in a face-detection SDK is not a line a bank should sign underneath
  without asking Google what it covers.

Google's own data-disclosure pages state that ML Kit for Android and for iOS collect device information,
application information and performance metrics for diagnostics and usage analytics. **No comprehensive
opt-out is documented for standalone ML Kit.** The `FIREBASE_ANALYTICS_COLLECTION_ENABLED` Info.plist key
governs Firebase Analytics, which is a different component.

### What ML Kit is used for here

| Platform | Component | Used for |
|---|---|---|
| Android | `com.google.mlkit:text-recognition` | MRZ OCR, document evidence check, proof-of-address OCR |
| Android | `com.google.mlkit:face-detection` | liveness challenges, face cropping before the match |
| iOS | `GoogleMLKit/FaceDetection` | liveness challenges, face cropping before the match |
| iOS | — | OCR uses **Apple Vision**, not ML Kit |

### The route to an unqualified answer

**iOS can be made ML-Kit-free.** Vision already does all the OCR on this platform. Vision also detects
faces, face landmarks and head pose entirely on-device with no analytics component. What it does not
provide is ML Kit's `smilingProbability` and `leftEyeOpenProbability` / `rightEyeOpenProbability`; those
would have to be derived from Vision's eye and mouth landmarks (eye aspect ratio for a blink, mouth corner
geometry for a smile). That is real work and it needs recalibration of the liveness thresholds, but it is
well-understood work, and it would remove the last third-party network component from the iOS build.

**Android is harder.** ML Kit provides both the OCR and the face detection there, and replacing it means
bundling a text recogniser and a face detector as TFLite models — a much larger change, with a model
governance question attached to each.

**Interim mitigation** available today, if the bank wants it before that work is done: ML Kit's traffic
goes to `play.googleapis.com`. That is visible to, and blockable by, a managed-device network policy. It
is a mitigation, not a fix, and it is the bank's call whether it is sufficient.

## 4. Where a CSCA trust bundle comes from

Passive authentication needs Country Signing CA certificates. These are **downloaded once by a developer
and compiled into the app** — the plugin never fetches them at runtime. See `src/csca/README.md`.

## 5. Summary for the requester

| Claim | Status |
|---|---|
| The plugin sends no document, chip, biometric or image data anywhere | **Confirmed** — no network code exists in it |
| Face matching happens on the device | **Confirmed** — bundled TFLite model, CPU inference |
| The plugin previously sent partially-masked customer identifiers to a third party | **Was true; removed in this change** |
| Nothing at all leaves the handset | **Not confirmed.** Google ML Kit reports usage and diagnostic analytics to `play.googleapis.com`, and its own privacy manifest declares `OtherUserContent`. Scope to be clarified with Google; iOS can be made ML-Kit-free. |
