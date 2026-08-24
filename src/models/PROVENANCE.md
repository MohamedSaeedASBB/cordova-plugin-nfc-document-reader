# Face-match model provenance

Record of the model file currently committed at `src/models/mobilefacenet.tflite`. Update this
file whenever the model is replaced — the threshold in `FaceMatcher` is only meaningful against a
specific set of weights.

## Installed model

| | |
|---|---|
| **File** | `src/models/mobilefacenet.tflite` |
| **SHA-256** | `be4bc7cfc53f7bc336d0f28b1ab92535f618c913a422b683210750f6b5354854` |
| **Size** | 5,233,552 bytes |
| **Source** | https://github.com/MCarlomagno/FaceRecognitionAuth — `assets/mobilefacenet.tflite` |
| **Retrieved** | 2026-08-24 |
| **Installed by** | `tools/install-face-model.sh`, at the request of the Innovation Department |

## Verified

Read directly from the FlatBuffer, not assumed:

| Property | Value | Plugin expectation | |
|---|---|---|---|
| Input | `[1, 112, 112, 3]` float32 (`input`) | `inputSize = 112`, RGB, batch 1 | matches |
| Output | `[1, 192]` float32 (`embeddings`) | `embeddingSize = 192` | matches |
| Container | TFLite FlatBuffer, `TFL3` | — | valid |

Two other candidates were checked and one was rejected:
`syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing` exports with **batch size 2**
(`[2, 112, 112, 3]`), which does not fit the plugin's single-image input buffer.
`atharvakale31/Real-Time_Face_Recognition_Android` matches the shapes and is an equivalent
alternative (sha256 `b67366e085ec9f6c2afb05c10397a46edeb823367abaec77f64f5ce946ac2847`).

## NOT verified — outstanding for model governance

This file came from a public demo repository. Before this is used to make decisions about real
customers, the following are open items, none of which can be settled by inspecting the file:

1. **Licence.** The repository's code licence does not necessarily cover the weights, and no
   licence is stated for the model file itself.
2. **Training data.** Unknown. Several widely-circulated face models derive from datasets that
   were later withdrawn or restricted (MS-Celeb-1M, VGGFace2), which is a provenance and
   data-protection question under PDPL, not just a licensing one.
3. **Pixel normalisation.** The plugin applies `(x - 127.5) / 128`, the MobileFaceNet-family
   convention. This cannot be read from a `.tflite` — it is a property of how the model was
   trained. A mismatch produces plausible-looking scores rather than an error, so it shows up as a
   disappointing operating point, not a fault.
4. **Accuracy on this population.** No FAR/FRR has been measured on Bahraini chip-portrait vs
   selfie pairs. The built-in 0.90 threshold is a policy floor, not a measured operating point.

## Status

**Development and testing only.** Suitable for proving the pipeline end to end and for producing
the score distribution that `tools/face-match-calibration` needs. Not signed off for a production
identity decision until items 1, 2 and 4 above are closed — either by clearing this file through
the bank's third-party/model governance process, or by replacing it with a model from a
contracted vendor (Jumio and Innovalor ReadID are both already in evaluation).
