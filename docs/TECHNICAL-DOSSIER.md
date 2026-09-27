# Technical dossier

Written in answer to: *"إعداد وإرسال كافة التفاصيل والمعلومات التقنية حول البرمجيات والمكتبات المستخدمة في تفعيل خاصية التحقق."*
— all technical details and information about the software and libraries used to enable the verification feature.

Every version below is taken from `plugin.xml` and from the resolved `Podfile.lock` of a real build, not
from memory. Where a library pulls in others, the transitive set is listed too, because that is what ends
up in the shipped binary.

## 1. What the plugin is

A Cordova plugin that reads ICAO 9303 electronic identity documents (passports and national ID cards) over
NFC, scans the MRZ with the camera, photographs the document, runs a challenge-response liveness check and
compares the holder's face to the portrait stored on the chip. All processing is on the handset.

| | |
|---|---|
| Plugin id | `cordova-plugin-nfc-document-reader` |
| Android languages | Java |
| iOS language | Swift 5 |
| Android minimum | as set by the host app; NFC hardware required |
| iOS minimum | 15.5 (set by ML Kit 8.0) |
| Cordova | cordova ≥ 10, cordova-android ≥ 10, cordova-ios ≥ 6 |

## 2. Android dependencies (declared in `plugin.xml`)

| Library | Version | Licence | Why it is here |
|---|---|---|---|
| `org.jmrtd:jmrtd` | 0.7.42 | LGPL 3.0 | ICAO 9303 MRTD protocol: BAC/PACE, EF.SOD, data groups |
| `net.sf.scuba:scuba-sc-android` | 0.0.26 | LGPL 3.0 | Smart-card transport layer under jmrtd |
| `org.bouncycastle:bcprov-jdk18on` | 1.78 | MIT-style (BC) | Cryptography for passive authentication |
| `edu.ucar:jj2000` | 5.2 | BSD-style | JPEG 2000 decoding for the DG2 portrait |
| `com.google.mlkit:text-recognition` | 16.0.0 | Google ToS | MRZ OCR, document evidence check, proof-of-address OCR |
| `com.google.mlkit:face-detection` | 16.1.7 | Google ToS | Liveness challenges, face cropping |
| `androidx.camera:camera-core / camera2 / lifecycle / view` | 1.3.1 | Apache 2.0 | Camera preview and frame analysis |
| `androidx.appcompat:appcompat` | 1.6.1 | Apache 2.0 | Activity theming for the plugin's screens |
| `org.tensorflow:tensorflow-lite` | 2.14.0 | Apache 2.0 | Runs the face-match model on-device |

## 3. iOS dependencies (CocoaPods)

Declared:

| Pod | Constraint | Resolved | Purpose |
|---|---|---|---|
| `NFCPassportReader` | `~> 1.1.9` | 1.1.9 | ICAO 9303 over CoreNFC |
| `GoogleMLKit/FaceDetection` | `~> 8.0` | 8.0.0 | Liveness challenges, face cropping |
| `TensorFlowLiteSwift` | `~> 2.14` | 2.14.x | Face-match model inference |

Pulled in transitively by the above — these ship in the binary and belong in any review:

```
GoogleDataTransport 10.1.0        GoogleToolboxForMac 4.2.1
GoogleUtilities 8.1.0             GTMSessionFetcher/Core 3.5.0
MLKitCommon 13.0.0                MLKitFaceDetection 7.0.0
MLKitVision 9.0.0                 MLImage 1.0.0-beta7
nanopb 3.30910.x                  PromisesObjC 2.4.x
```

Apple frameworks used directly, with no third party involved: **CoreNFC** (chip), **Vision** (all OCR on
iOS, and the document evidence check), **AVFoundation** (camera), **UIKit**, **CoreGraphics**,
**ImageIO**.

> Note for reviewers: OCR on iOS is Apple Vision, not ML Kit. Vision also recognises Arabic, which ML Kit
> has no model for — so Arabic proof-of-address OCR works on iPhone and returns nothing on Android.

## 4. The face-match model

| | |
|---|---|
| Model | MobileFaceNet |
| Format | TensorFlow Lite, bundled in the plugin |
| Input | 112 × 112 RGB |
| Output | 192-dimensional embedding |
| Comparison | cosine similarity, computed on-device |
| Threshold | **none in the plugin** — the similarity is returned and the decision is the backend's |
| Provenance | `src/models/PROVENANCE.md` |

The plugin deliberately does not decide whether a face matches. It reports `similarity` and the backend
applies policy, so the threshold can be changed without shipping a new app.

## 5. Trust anchors

Passive authentication needs Country Signing CA certificates, compiled into the app as a PEM bundle at
`src/csca/csca_master_list.pem`. The plugin never fetches them at runtime. Sourcing, verification and
refresh are covered in `src/csca/README.md`.

Without a bundle the payload reports `passiveAuthentication.status: "notVerified"` with reason
`NO_TRUST_ANCHORS` — the chip's signature and data-group hashes are still checked, but the issuer is not
established.

## 6. Network behaviour

The plugin opens no network connections. ML Kit reports usage and diagnostic analytics to Google. Both
statements are evidenced in `docs/DATA-FLOW-AND-EGRESS.md`, which is the document to read alongside this
one.

## 7. Permissions and entitlements

| Platform | Declared | Why |
|---|---|---|
| Android | `android.permission.CAMERA` | MRZ, document capture, liveness |
| Android | `android.permission.NFC`, `uses-feature android.hardware.nfc` | chip reading |
| iOS | `NSCameraUsageDescription` | as above |
| iOS | `NFCReaderUsageDescription` | chip reading |
| iOS | `com.apple.developer.nfc.readersession.formats` = TAG | entitlement |
| iOS | `com.apple.developer.nfc.readersession.iso7816.select-identifiers` | the MRTD application AID |

The iOS App ID must have the **NFC Tag Reading** capability enabled in the Apple Developer portal and the
provisioning profile regenerated. The entitlement in `plugin.xml` alone is not sufficient.

## 8. Known build constraints

- **The plugin cannot link against an Apple Silicon iOS Simulator.** ML Kit and NFCPassportReader set
  `EXCLUDED_ARCHS[sdk=iphonesimulator*] = arm64`. Build and test on a device. Nothing here works in a
  simulator anyway — no NFC, no usable camera.
- **Android duplicate-class and packaging conflicts.** On a clean cordova-android 13 project, a debug
  build fails twice: duplicate Kotlin stdlib classes (`kotlin-stdlib` 1.8.22 against
  `kotlin-stdlib-jdk7` 1.7.20 / `kotlin-stdlib-jdk8` 1.6.0), and two copies of
  `META-INF/versions/9/OSGI-INF/MANIFEST.MF` from the two BouncyCastle jars. Both are dependency-graph
  problems rather than plugin code, and both need a resolution strategy and a packaging exclusion in the
  app's Gradle configuration. See §9.

## 9. Fixing the Android build conflicts

In the host application's `build-extras.gradle` (or via a Cordova hook):

```gradle
android {
    packaging {
        resources {
            excludes += ['META-INF/versions/9/OSGI-INF/MANIFEST.MF']
        }
    }
}
configurations.all {
    resolutionStrategy {
        force 'org.jetbrains.kotlin:kotlin-stdlib:1.8.22'
        force 'org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22'
        force 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22'
    }
}
```

Not applied inside the plugin, because forcing versions in a library affects every other dependency of the
host app — that is the app's decision, not a plugin's.

## 10. What the verification actually proves

Worth stating plainly in any dossier that a risk function will read:

| Check | What it proves | What it does not |
|---|---|---|
| Chip read (BAC/PACE) | the document has a genuine, readable MRTD chip | nothing about the holder |
| Passive authentication | the data groups are byte-for-byte what the issuer signed, and the signer chains to a CSCA in the bundle | **not** that the chip is a clone — that needs Chip Authentication |
| MRZ ↔ chip comparison | the print and the chip agree | — |
| Document evidence check | the right document was in the frame | **not** that it was a card rather than a photograph of one, or a screen |
| Liveness challenges | a print or a still photo did not pass | **not** a replayed video, an injected camera feed, or a 3D mask |
| Face match | a similarity score against the chip portrait | the decision — that is the backend's, against its own threshold |
