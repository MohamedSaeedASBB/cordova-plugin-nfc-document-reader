#!/usr/bin/env bash
# Compiles every Android source offline, without the Gradle project.
#
# The plugin's Android half has repeatedly had to ship on a diff rather than a compile, because the
# throwaway Cordova project that could build it kept being cleared and takes minutes to recreate.
# This takes seconds: the real jmrtd, scuba, BouncyCastle, ML Kit and CameraX classes out of the
# Gradle cache, plus small stubs for Cordova itself and the generated R.
#
# One known failure is tolerated: ML Kit's text recogniser is annotated with OptionalModuleApi,
# which javac cannot resolve outside a real AAR build. It is unrelated to this plugin's code and is
# checked by name, so a new error of any kind still fails the script.
set -uo pipefail
cd "$(dirname "$0")/../.."
CP_FILE="${ANDROID_CHECK_CP:-/tmp/fullcp.txt}"
if [ ! -f "$CP_FILE" ]; then
  echo "classpath file $CP_FILE missing — see tools/android-compile/README.md" >&2
  exit 2
fi
OUT=$(javac -nowarn -proc:none -d /tmp/android-check -cp "$(cat "$CP_FILE")" src/android/*.java 2>&1)
ERRORS=$(printf '%s\n' "$OUT" | grep -c "error:" || true)
KNOWN=$(printf '%s\n' "$OUT" | grep -c "cannot access OptionalModuleApi" || true)
if [ "$ERRORS" -eq "$KNOWN" ]; then
  echo "✅ Android sources compile ($KNOWN known ML Kit annotation gap tolerated)"
  exit 0
fi
printf '%s\n' "$OUT" | grep "error:" | head -15
echo "❌ Android compile failed — $((ERRORS - KNOWN)) error(s) beyond the known gap"
exit 1
