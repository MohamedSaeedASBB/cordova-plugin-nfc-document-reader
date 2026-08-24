#!/usr/bin/env python3
"""
Derives the face-match decision threshold from labelled chip-vs-selfie pairs.

WHY THIS TOOL EXISTS
`faceMatch.threshold` fixes the false-accept and false-reject rates of an identity check. The
plugin ships no default on purpose (src/models/README.md), because a plausible-looking constant
would silently become the bank's identity-decision boundary. This tool produces the numbers that
justify a chosen value: a similarity score per pair, a threshold sweep, and the FAR/FRR at each
operating point.

It mirrors FaceMatcher.java / FaceMatcher.swift exactly — same crop padding, same resize, same
(x - 127.5) / 128 normalisation, same L2-normalised cosine similarity. If you change the pixel
constants in the plugin, change PIXEL_MEAN / PIXEL_SCALE here to match, or the threshold this
tool derives will not be the threshold the app applies.

DATA HANDLING — READ BEFORE RUNNING
The input is customer biometric data: chip portraits and selfies. Run this only on an approved
machine inside the bank's environment. The tool makes no network calls of any kind and never
copies input images into its output. Outputs contain scores and file identifiers only; pass
--anonymise-ids to replace identifiers with a salted hash if the results will travel further than
the person running it.

USAGE
    python3 calibrate.py --model mobilefacenet.tflite --pairs pairs.csv --out results/

pairs.csv (header required):
    document_image,liveness_image,label
    /data/pairs/0001_chip.png,/data/pairs/0001_selfie.jpg,genuine
    /data/pairs/0002_chip.png,/data/pairs/0009_selfie.jpg,impostor

`label` is `genuine` (same person) or `impostor` (different people).

Optional boxes.json supplies the face rectangles the device detected, so the crop matches what
runs on the phone:
    { "/data/pairs/0001_chip.png": {"x": 12, "y": 20, "width": 180, "height": 180}, ... }

Without boxes, whole images are used. That is only valid if the images are already tight face
crops: a different crop shifts every score, and a threshold derived from differently-cropped
images will not transfer to the device. The tool says which mode it ran in.
"""

import argparse
import csv
import hashlib
import json
import os
import secrets
import sys

import numpy as np
from PIL import Image

# Must match FaceMatcher.java / FaceMatcher.swift.
PIXEL_MEAN = 127.5
PIXEL_SCALE = 128.0
DEFAULT_INPUT_SIZE = 112
DEFAULT_CROP_PADDING = 0.25


def load_interpreter(model_path):
    """Prefers the standalone tflite-runtime; falls back to the one inside TensorFlow."""
    try:
        from tflite_runtime.interpreter import Interpreter
    except ImportError:
        try:
            from tensorflow.lite.python.interpreter import Interpreter
        except ImportError:
            sys.exit("Neither tflite-runtime nor tensorflow is installed. "
                     "See requirements.txt.")
    interpreter = Interpreter(model_path=model_path)
    interpreter.allocate_tensors()
    return interpreter


def crop_and_resize(path, box, input_size, crop_padding):
    """The same crop-with-padding then square resize the plugin performs."""
    image = Image.open(path).convert("RGB")
    if box:
        pad_x = box["width"] * crop_padding
        pad_y = box["height"] * crop_padding
        left = max(0, int(box["x"] - pad_x))
        top = max(0, int(box["y"] - pad_y))
        right = min(image.width, int(box["x"] + box["width"] + pad_x))
        bottom = min(image.height, int(box["y"] + box["height"] + pad_y))
        if right - left > 0 and bottom - top > 0:
            image = image.crop((left, top, right, bottom))
    return image.resize((input_size, input_size), Image.BILINEAR)


def embed(interpreter, image, embedding_size):
    pixels = np.asarray(image, dtype=np.float32)
    normalised = (pixels - PIXEL_MEAN) / PIXEL_SCALE
    tensor = np.expand_dims(normalised, axis=0)

    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    interpreter.set_tensor(input_detail["index"], tensor)
    interpreter.invoke()
    vector = interpreter.get_tensor(output_detail["index"])[0].astype(np.float64)

    if embedding_size and vector.shape[0] != embedding_size:
        # Same failure the plugin reports as EMBEDDING_LENGTH_MISMATCH — caught here so it is a
        # config error at calibration time rather than a runtime error on a customer's phone.
        sys.exit("Model emits %d values but --embedding-size says %d. Fix one of them; the app "
                 "must be configured with the same value." % (vector.shape[0], embedding_size))

    norm = np.linalg.norm(vector)
    return vector if norm == 0 else vector / norm


def sweep(genuine_scores, impostor_scores, steps):
    """
    FAR = impostor pairs accepted / impostor pairs. FRR = genuine pairs rejected / genuine pairs.
    A pair is accepted when similarity >= threshold, matching the plugin's comparison exactly.
    """
    rows = []
    for i in range(steps + 1):
        threshold = -1.0 + (2.0 * i / steps)
        false_accepts = int(np.sum(np.asarray(impostor_scores) >= threshold))
        false_rejects = int(np.sum(np.asarray(genuine_scores) < threshold))
        rows.append({
            "threshold": round(threshold, 4),
            "false_accepts": false_accepts,
            "false_rejects": false_rejects,
            "far": false_accepts / len(impostor_scores) if impostor_scores else None,
            "frr": false_rejects / len(genuine_scores) if genuine_scores else None,
        })
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", required=True, help="Path to the .tflite embedding model")
    parser.add_argument("--pairs", required=True, help="CSV of labelled pairs")
    parser.add_argument("--out", required=True, help="Output directory (created if absent)")
    parser.add_argument("--boxes", help="JSON of image path to face box")
    parser.add_argument("--input-size", type=int, default=DEFAULT_INPUT_SIZE)
    parser.add_argument("--embedding-size", type=int, default=192)
    parser.add_argument("--crop-padding", type=float, default=DEFAULT_CROP_PADDING)
    parser.add_argument("--steps", type=int, default=400,
                        help="Threshold sweep resolution over [-1, 1]")
    parser.add_argument("--target-far", type=float, action="append", default=None,
                        help="Report the operating point at this FAR. Repeatable.")
    parser.add_argument("--anonymise-ids", action="store_true",
                        help="Replace file paths in outputs with a salted hash")
    args = parser.parse_args()

    target_fars = args.target_far if args.target_far else [0.01, 0.001, 0.0001]

    boxes = {}
    if args.boxes:
        with open(args.boxes) as handle:
            boxes = json.load(handle)

    with open(args.pairs, newline="") as handle:
        pairs = list(csv.DictReader(handle))
    if not pairs:
        sys.exit("No pairs found in %s" % args.pairs)

    interpreter = load_interpreter(args.model)
    os.makedirs(args.out, exist_ok=True)
    salt = secrets.token_hex(8) if args.anonymise_ids else None

    def identify(path):
        if not args.anonymise_ids:
            return path
        return hashlib.sha256((salt + path).encode()).hexdigest()[:16]

    genuine, impostor, scored, skipped = [], [], [], []
    for index, pair in enumerate(pairs):
        doc_path = pair["document_image"].strip()
        live_path = pair["liveness_image"].strip()
        label = pair["label"].strip().lower()
        if label not in ("genuine", "impostor"):
            skipped.append((index, "label must be genuine or impostor, got %r" % label))
            continue
        try:
            doc_face = crop_and_resize(doc_path, boxes.get(doc_path),
                                       args.input_size, args.crop_padding)
            live_face = crop_and_resize(live_path, boxes.get(live_path),
                                        args.input_size, args.crop_padding)
            similarity = float(np.dot(embed(interpreter, doc_face, args.embedding_size),
                                      embed(interpreter, live_face, args.embedding_size)))
        except SystemExit:
            raise
        except Exception as error:                                  # noqa: BLE001
            # A pair that cannot be read is reported, never silently dropped: a sweep run over
            # an unknown subset of the data is not evidence of anything. Exception messages carry
            # the file path, so they go through identify() too — otherwise --anonymise-ids would
            # hash the scores file and leak the same paths through the skip list.
            reason = "%s: %s" % (type(error).__name__, error)
            for path in (doc_path, live_path):
                reason = reason.replace(path, identify(path))
            skipped.append((index, reason))
            continue

        scored.append({"document_image": identify(doc_path),
                       "liveness_image": identify(live_path),
                       "label": label,
                       "similarity": round(similarity, 6)})
        (genuine if label == "genuine" else impostor).append(similarity)

    if not genuine or not impostor:
        sys.exit("Need at least one genuine and one impostor pair to sweep a threshold "
                 "(genuine=%d, impostor=%d)." % (len(genuine), len(impostor)))

    scores_path = os.path.join(args.out, "scores.csv")
    with open(scores_path, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(scored[0].keys()))
        writer.writeheader()
        writer.writerows(scored)

    rows = sweep(genuine, impostor, args.steps)
    sweep_path = os.path.join(args.out, "sweep.csv")
    with open(sweep_path, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    # Equal error rate: where FAR and FRR cross. A summary statistic for comparing models, NOT an
    # operating point — the bank's false-accept target decides the threshold, not the crossover.
    eer_row = min(rows, key=lambda row: abs(row["far"] - row["frr"]))

    # The smallest false-accept rate this data set can distinguish from zero. With N impostor
    # pairs, a measured FAR of 0 supports no claim below 1/N: zero accepts out of 200 pairs is
    # not evidence of a 1-in-10,000 false-accept rate, it is evidence that 200 pairs cannot
    # measure one. Targets below this resolution are reported as unmeasurable rather than met,
    # because "FAR = 0.00000" next to a threshold is exactly the number a reviewer will quote.
    far_resolution = 1.0 / len(impostor)
    frr_resolution = 1.0 / len(genuine)

    operating_points = []
    for target in sorted(target_fars):
        candidates = [row for row in rows if row["far"] <= target]
        if not candidates:
            operating_points.append({"target_far": target, "achievable": False,
                                     "measurable": target >= far_resolution})
            continue
        # Lowest threshold that still meets the FAR target, i.e. the best FRR available at it.
        best = min(candidates, key=lambda row: row["threshold"])
        operating_points.append({
            "target_far": target,
            "achievable": True,
            "measurable": target >= far_resolution,
            "threshold": best["threshold"],
            "far": best["far"],
            "frr": best["frr"],
            "impostor_pairs_needed_to_measure": None if target >= far_resolution
                else int(np.ceil(1.0 / target)),
        })

    summary = {
        "model": os.path.basename(args.model),
        "model_sha256": hashlib.sha256(open(args.model, "rb").read()).hexdigest(),
        "input_size": args.input_size,
        "embedding_size": args.embedding_size,
        "crop_padding": args.crop_padding,
        "face_boxes": "supplied" if boxes else "none (whole images used)",
        "pairs_scored": len(scored),
        "pairs_skipped": len(skipped),
        "genuine_pairs": len(genuine),
        "impostor_pairs": len(impostor),
        "genuine_similarity": {"min": round(min(genuine), 4),
                               "mean": round(float(np.mean(genuine)), 4),
                               "max": round(max(genuine), 4)},
        "impostor_similarity": {"min": round(min(impostor), 4),
                                "mean": round(float(np.mean(impostor)), 4),
                                "max": round(max(impostor), 4)},
        "far_resolution": round(far_resolution, 6),
        "frr_resolution": round(frr_resolution, 6),
        "equal_error_rate": {"threshold": eer_row["threshold"],
                             "far": eer_row["far"],
                             "frr": eer_row["frr"]},
        "operating_points": operating_points,
        "skipped_pairs": [{"row": row, "reason": reason} for row, reason in skipped],
    }
    with open(os.path.join(args.out, "summary.json"), "w") as handle:
        json.dump(summary, handle, indent=2)

    print("Scored %d pairs (%d genuine, %d impostor); %d skipped"
          % (len(scored), len(genuine), len(impostor), len(skipped)))
    if boxes:
        print("Face boxes: supplied")
    else:
        print("Face boxes: NONE — whole images used. Valid only if these are tight face crops;")
        print("            otherwise the threshold will not transfer to the device.")
    print("EER: threshold=%.4f far=%.4f frr=%.4f"
          % (eer_row["threshold"], eer_row["far"], eer_row["frr"]))
    print("Resolution: this data set can measure FAR no finer than %.5f (1/%d impostor pairs)"
          % (far_resolution, len(impostor)))
    for point in operating_points:
        if not point["achievable"]:
            print("FAR<=%-8g -> not achievable with this data set" % point["target_far"])
            continue
        line = ("FAR<=%-8g -> threshold=%.4f far=%.5f frr=%.5f"
                % (point["target_far"], point["threshold"], point["far"], point["frr"]))
        if not point["measurable"]:
            line += ("  NOT MEASURABLE: needs >= %d impostor pairs"
                     % point["impostor_pairs_needed_to_measure"])
        print(line)
    if skipped:
        print("\nSkipped pairs (row, reason):")
        for row, reason in skipped:
            print("  %d: %s" % (row, reason))
    print("\nWrote %s, %s and summary.json" % (scores_path, sweep_path))
    unmeasurable = [point for point in operating_points if not point["measurable"]]
    if unmeasurable:
        print("\nWARNING: %d of the requested operating points are below what %d impostor pairs "
              "can measure.\n         The threshold printed for them is real; the FAR next to it "
              "is not evidence." % (len(unmeasurable), len(impostor)))

    print("\nThe threshold is a decision, not an output: pick it from the operating points above "
          "against the bank's\nfalse-accept target, and record the FAR/FRR next to it. Confidence "
          "intervals are only as good as\nthe pair count — %d genuine and %d impostor pairs here."
          % (len(genuine), len(impostor)))


if __name__ == "__main__":
    main()
