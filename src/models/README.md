# Face-match model

The on-device 1:1 face match (chip portrait vs. liveness portrait) needs a face-embedding
model. This directory is where it goes.

## Why the model is not committed here

ML Kit provides face *detection* only — it has no recognition or embedding API, and iOS exposes
no public face-recognition API either. So the match runs a TensorFlow Lite embedding model.

The weights are not committed because they are not ours to choose unilaterally:

- **Provenance and licence.** Community MobileFaceNet exports circulate under assorted licences
  with unverifiable training data. A file that decides whether a customer is who they claim to be
  belongs under the bank's third-party/model governance process, not picked off a repo.
- **Threshold.** The decision boundary fixes the false-accept and false-reject rates of an
  identity check. It has to be measured, not inherited.

## Installing the model

1. Put the approved file here as `mobilefacenet.tflite`.

2. Uncomment the two `<!-- face-match model -->` blocks in `plugin.xml` (one under the Android
   platform, one under iOS). They are commented out because Cordova fails plugin installation if
   a `<resource-file>` points at a file that does not exist — with the model present, uncommenting
   makes it install automatically into Android assets and the iOS bundle.

3. Reinstall the plugin so the asset is staged:

   ```
   cordova plugin remove cordova-plugin-nfc-document-reader
   cordova plugin add <path-to-this-plugin>
   ```

Nothing else is required. `FaceMatcher` defaults `modelAsset` to `mobilefacenet.tflite`, so
`readNFC(..., { liveness: true })` picks it up with no JS options.

Until the model is installed, `match.status` is `"deferred"` with reason `"MODEL_NOT_INSTALLED"`
and no similarity is returned — the feature is un-provisioned, not broken. A `modelAsset` passed
explicitly in `faceMatch` and then not found is reported as `"error"` / `"MODEL_NOT_FOUND"`,
because that is a real misconfiguration.

## Model expectations

The defaults target a MobileFaceNet-family model:

| Property        | Default | Config key      |
|-----------------|---------|-----------------|
| Input           | 112x112 | `inputSize`     |
| Embedding       | 192     | `embeddingSize` |
| Preprocessing   | `(x - 127.5) / 128`, RGB, NHWC float32 | `PIXEL_MEAN` / `PIXEL_SCALE` in FaceMatcher |

A different family needs the matching `inputSize` and `embeddingSize` passed in `faceMatch`, and
— if it was trained with different normalisation — a change to the two pixel constants in
`FaceMatcher.java` and `FaceMatcher.swift`.

## Setting the threshold

The threshold is built into the plugin at **0.90** (`FaceMatcher.DEFAULT_THRESHOLD` /
`FaceMatcher.defaultThreshold`), so nothing needs to be passed from JavaScript.

**It is a policy floor, not a measured operating point.** Nobody has yet established what
false-accept and false-reject rates 0.90 produces on this customer population. Cosine similarity
runs from -1 to 1, so 0.90 is a demanding bar: it requires the two embeddings to be nearly the
same vector. Chip portraits are typically low-resolution and several years old, taken under
different lighting and optics from a live selfie, so genuine pairs will often score below it and
be reported as `"notMatched"`. Treat that as the expected behaviour until the number is
calibrated, and route `"notMatched"` to human review rather than to a rejection.

Passing `faceMatch: { threshold: null }` clears it: the match still runs and reports a real
similarity, but `match.status` is `"review"` and the decision stays with a human.

To validate or revise the threshold: run genuine and impostor pairs representative of the customer population
**and** the capture conditions through this matcher, sweep the similarity threshold, and pick the
operating point that meets the bank's false-accept target. Record the resulting FAR/FRR alongside
it — those numbers, not the threshold alone, are what a reviewer needs.

Chip portraits are often low-resolution and several years old, so a threshold calibrated on a
public benchmark like LFW will not transfer. Calibrate on real chip-vs-selfie pairs.

## What a match does not prove

`liveness.sdk.presentationAttackDetection` is `false` in every payload. ML Kit has no
presentation-attack detection: the challenge-response sequence defeats a printed or displayed
photo, but not a replayed video, an injected camera feed, or a 3D mask. A high similarity score
against a chip portrait plus a liveness pass is strong evidence, not proof.
