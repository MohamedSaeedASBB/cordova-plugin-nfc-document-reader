#!/usr/bin/env bash
#
# Installs an approved face-embedding model into the plugin.
#
# The model is not committed to this repo (src/models/README.md): the choice of weights is a
# biometric-governance decision, and the two plugin.xml <resource-file> blocks that stage it are
# commented out because Cordova fails plugin installation when a resource-file points at a
# missing path. This script does both steps together so the XML editing is not done by hand.
#
# Usage: tools/install-face-model.sh /path/to/approved/mobilefacenet.tflite
#
set -euo pipefail

SRC="${1:-}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO/src/models/mobilefacenet.tflite"

if [[ -z "$SRC" ]]; then
    echo "usage: $0 /path/to/mobilefacenet.tflite" >&2
    exit 2
fi
if [[ ! -f "$SRC" ]]; then
    echo "error: no such file: $SRC" >&2
    exit 1
fi

# A .tflite is a FlatBuffer whose bytes 4..8 are the identifier "TFL3". Not a substitute for
# loading the model, but it catches the common mistakes: a downloaded HTML error page, a zip, or
# an ONNX file renamed rather than converted.
IDENT=$(dd if="$SRC" bs=1 skip=4 count=4 2>/dev/null || true)
if [[ "$IDENT" != "TFL3" ]]; then
    echo "error: $SRC does not look like a TensorFlow Lite model (expected 'TFL3' at offset 4," >&2
    echo "       found '$IDENT'). Convert the model to .tflite before installing it." >&2
    exit 1
fi

cp "$SRC" "$DEST"
echo "installed: $DEST"
echo "sha256:    $(shasum -a 256 "$DEST" | cut -d' ' -f1)"
echo "size:      $(wc -c < "$DEST" | tr -d ' ') bytes"

# Uncomment the two staging blocks. Each is a single XML comment wrapping one resource-file, one
# per platform; the sed below removes only those comment delimiters.
python3 - "$REPO/plugin.xml" <<'PY'
import io, re, sys

path = sys.argv[1]
xml = io.open(path, encoding="utf-8").read()
pattern = re.compile(
    r"<!--\s*face-match model:[^>]*?\n(.*?)\n\s*-->",
    re.DOTALL)

def uncomment(match):
    return match.group(1)

xml, count = pattern.subn(uncomment, xml)
if count == 0:
    print("plugin.xml: staging blocks already uncommented (or not found) — check manually")
else:
    io.open(path, "w", encoding="utf-8").write(xml)
    print("plugin.xml: uncommented %d face-match staging block(s)" % count)
PY

cat <<'NEXT'

Next:
  1. Reinstall the plugin so the asset is staged (a plain build does not re-sync resources):
       cordova plugin remove cordova-plugin-nfc-document-reader
       cordova plugin add <path-to-this-plugin>
  2. Confirm the model's shape matches the plugin's config:
       python3 tools/face-match-calibration/calibrate.py --model src/models/mobilefacenet.tflite --inspect
  3. Derive the threshold before enabling a pass/fail in production — see
     tools/face-match-calibration/README.md. Until faceMatch.threshold is set, the match runs
     and returns a real similarity with status "review".
NEXT
