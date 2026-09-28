#!/usr/bin/env bash
# Renders the security assessment PDF from assessment.html.
#
# The cover and footer carry the commit the document describes. That is the whole point of the
# document — an assessor needs to know which build the conclusions were drawn from — so the sha is
# stamped in here at render time rather than typed into the HTML and left to rot.
set -euo pipefail
cd "$(dirname "$0")/../.."

SHA="$(git rev-parse --short HEAD)"
OUT="docs/security-assessment/NFC-Plugin-Security-Assessment.pdf"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

sed "s/COMMIT_SHA/${SHA}/g" docs/security-assessment/assessment.html > "$TMP/assessment.html"

# Headless Chrome, because it renders the inline SVG diagrams. textutil and cupsfilter do not.
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
[ -x "$CHROME" ] || { echo "Google Chrome not found at $CHROME" >&2; exit 1; }

"$CHROME" --headless --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$PWD/$OUT" "file://$TMP/assessment.html" 2>/dev/null

echo "Rendered $OUT at commit $SHA"
