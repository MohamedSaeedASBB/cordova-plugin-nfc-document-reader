# Face-match threshold calibration

Turns a labelled set of chip-portrait/selfie pairs into the numbers needed to choose
`faceMatch.threshold`: a similarity score per pair, a sweep across every threshold, and the
false-accept and false-reject rate at each one.

The plugin ships no default threshold (see `src/models/README.md`). This is the tool that
replaces the missing default with a measured one.

## Before you run it

The input is customer biometric data. Run this on an approved machine inside the bank's
environment. `calibrate.py` makes no network calls and never copies input images into its output;
outputs hold scores and file identifiers only. Add `--anonymise-ids` to replace identifiers with a
salted hash if the results will be shared beyond the person running it.

Assembling the pair set is itself a data project — consent, retention, and access control for a
collection of selfies and chip portraits — and should be signed off before collection, not after.

## Running it

```
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

python3 calibrate.py \
    --model /path/to/mobilefacenet.tflite \
    --pairs pairs.csv \
    --boxes boxes.json \
    --out results/
```

`pairs.csv`:

```csv
document_image,liveness_image,label
/data/pairs/0001_chip.png,/data/pairs/0001_selfie.jpg,genuine
/data/pairs/0002_chip.png,/data/pairs/0009_selfie.jpg,impostor
```

`boxes.json` (optional) gives the face rectangle per image, so the crop matches the device:

```json
{ "/data/pairs/0001_chip.png": { "x": 12, "y": 20, "width": 180, "height": 180 } }
```

Export those boxes from the app: `faceComparison.documentPortrait` and
`faceComparison.livenessPortrait` in the `readNFC` payload carry what ML Kit detected. Without
boxes the whole image is used, which is only valid for images that are already tight face crops —
a different crop shifts every score, and the threshold then will not transfer to the phone. The
tool prints which mode it used.

## Outputs

| File | Contents |
|---|---|
| `scores.csv` | One row per pair: identifiers, label, cosine similarity |
| `sweep.csv` | Every threshold with its false-accept and false-reject counts and rates |
| `summary.json` | Model hash and config, score distributions, EER, operating points, skipped pairs |

## Reading the results honestly

- **The threshold is a decision, not an output.** Pick it from the operating points against the
  bank's false-accept target and record the FAR/FRR beside it. Those two numbers, not the
  threshold alone, are what a reviewer needs.
- **Equal error rate is for comparing models, not for choosing an operating point.** An identity
  check at a bank is not indifferent between a false accept and a false reject.
- **A measured FAR of zero is not a small FAR.** With N impostor pairs nothing below 1/N is
  measurable, so the tool prints its resolution and marks any target below it `NOT MEASURABLE`
  along with the pair count that target would need. A threshold reported next to an unmeasurable
  FAR is real; the FAR beside it is not evidence.
- **Calibrate on the real capture conditions.** Chip portraits are often low-resolution and years
  old. A threshold derived from a public benchmark, or from clean studio selfies, will not hold on
  a phone in a branch.
- **Skipped pairs are printed and recorded.** A sweep over an unknown subset of the data proves
  nothing, so unreadable pairs and bad labels are always listed rather than dropped quietly.

## Keeping it aligned with the app

`calibrate.py` reproduces `FaceMatcher.java` / `FaceMatcher.swift`: 112x112 input, 0.25 crop
padding, `(x - 127.5) / 128` RGB normalisation, L2-normalised embeddings, cosine similarity, and
`similarity >= threshold` as the accept test. Change any of those in the plugin and change them
here too (`PIXEL_MEAN`, `PIXEL_SCALE`, and the `--input-size` / `--embedding-size` /
`--crop-padding` defaults), or the threshold this tool derives is not the threshold the app
applies.
