#!/usr/bin/env bash
# Compiles every Android source offline, without the Gradle project.
#
# The plugin's Android half ships from a throwaway Cordova project that takes minutes to recreate
# and kept being cleared, so changes were going out on a diff. This takes seconds: the real jmrtd,
# scuba, BouncyCastle, ML Kit, CameraX and TensorFlow Lite classes out of the Gradle cache, plus
# the small Cordova stubs in stubs/.
#
# The classpath is assembled by build-classpath.sh and cached under .cache/. An earlier version of
# this script read a path that had to be prepared by hand and lived in /tmp; it was gone the next
# time anyone looked, and the README it pointed at was never written. Nothing here needs preparing.
#
# Resources are resolved by name at runtime (getIdentifier), so no generated R class is needed —
# which is exactly why a label aimed at a view with no android:id used to fail silently. That gap
# is checked by tools/labels/check-native.js, not here.
set -uo pipefail
cd "$(dirname "$0")/../.."

WORK="tools/android-compile/.cache"
CP_FILE="$WORK/classpath"
mkdir -p "$WORK"

# Rebuild the classpath when it is missing or a day old; the cache it reads rarely changes.
if [ ! -s "$CP_FILE" ] || [ -n "$(find "$CP_FILE" -mtime +1 2>/dev/null)" ]; then
  if ! bash tools/android-compile/build-classpath.sh "$WORK" > "$CP_FILE.tmp" 2>"$CP_FILE.err"; then
    cat "$CP_FILE.err" >&2
    rm -f "$CP_FILE.tmp" "$CP_FILE.err"
    exit 2
  fi
  mv "$CP_FILE.tmp" "$CP_FILE"
  rm -f "$CP_FILE.err"
fi

OUT=$(javac -nowarn -proc:none -d "$WORK/classes" -cp "$(cat "$CP_FILE")" \
  tools/android-compile/stubs/org/apache/cordova/*.java src/android/*.java 2>&1)
ERRORS=$(printf '%s\n' "$OUT" | grep -c "error:" || true)
if [ "$ERRORS" -eq 0 ]; then
  echo "✅ Android sources compile ($(ls src/android/*.java | wc -l | tr -d ' ') files)"
  exit 0
fi
printf '%s\n' "$OUT" | grep "error:" | head -20
echo "❌ Android compile failed — $ERRORS error(s)"
exit 1
