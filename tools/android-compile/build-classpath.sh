#!/usr/bin/env bash
# Assembles a javac classpath from the Gradle cache and the Android SDK, and prints it.
#
# check.sh calls this and caches the result. It is separate because it is the slow half (it
# unpacks .aar files) and because it is the half that depends on what happens to be in the cache.
set -uo pipefail

CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
WORK="${1:?usage: build-classpath.sh <work-dir>}"

if [ ! -d "$CACHE" ]; then
  echo "no Gradle cache at $CACHE — build an Android project once, or set GRADLE_CACHE" >&2
  exit 2
fi

ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null \
  | sed 's|.*/android-\([0-9]*\)/.*|\1 &|' | sort -rn | head -1 | cut -d' ' -f2)
if [ -z "${ANDROID_JAR:-}" ]; then
  echo "no android.jar under $SDK/platforms — set ANDROID_SDK_ROOT" >&2
  exit 2
fi

mkdir -p "$WORK/aar"
CP="$ANDROID_JAR"

# Plain jars: jmrtd, scuba, BouncyCastle, the ML Kit and CameraX Java halves, Guava, TFLite.
while IFS= read -r jar; do
  case "$jar" in *-sources.jar|*-javadoc.jar) continue ;; esac
  CP="$CP:$jar"
done < <(find "$CACHE" -name '*.jar' 2>/dev/null)

# .aar files carry their classes in an inner classes.jar; unpack each once.
while IFS= read -r aar; do
  name=$(basename "$aar" .aar)
  out="$WORK/aar/$name"
  if [ ! -f "$out/classes.jar" ]; then
    mkdir -p "$out"
    unzip -o -q "$aar" classes.jar -d "$out" 2>/dev/null || true
  fi
  [ -f "$out/classes.jar" ] && CP="$CP:$out/classes.jar"
done < <(find "$CACHE" -name '*.aar' 2>/dev/null)

printf '%s\n' "$CP"
