#!/usr/bin/env bash
# Typechecks every iOS source offline, without CocoaPods or an Xcode project.
#
# The plugin's iOS half cannot be built locally: it needs NFCPassportReader, ML Kit and
# TensorFlowLite from CocoaPods, and it cannot link against an Apple Silicon simulator. So it kept
# shipping on a diff, and the MABS cloud build — seven minutes away — was the first thing that ever
# read the code. It twice reported errors that no local check could have seen.
#
# Worse, three quarters of NfcDocumentReader.swift sits behind `#if canImport(NFCPassportReader)`.
# Without the pod that condition is false, so a plain `swiftc -typecheck` of that file skips the
# code and reports success. A green local check meant nothing.
#
# This compiles small stub modules for the five external dependencies and typechecks all of
# src/ios against them. The stubs make `canImport` true, so the conditional code is checked too.
# They are declarations only — no implementations — and exist solely for this script.
#
# What it catches: implicit `self` in escaping closures, wrong argument labels, type mismatches,
# non-exhaustive switches, renamed members — the whole class of error that reached MABS.
# What it does not: anything depending on a real dependency's true signature. A stub that drifts
# from the real pod reports a false error here rather than hiding a real one, which is the safe
# direction, but it does mean a stub may need updating when a pod is upgraded.
set -uo pipefail
cd "$(dirname "$0")/../.."

SDK=$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null)
if [ -z "$SDK" ]; then
  echo "no iOS SDK — Xcode and its command line tools are required" >&2
  exit 2
fi
TARGET="${IOS_CHECK_TARGET:-arm64-apple-ios15.0}"
MODULES=$(mktemp -d)
trap 'rm -rf "$MODULES"' EXIT

for m in Cordova MLKitVision MLKitFaceDetection TensorFlowLite NFCPassportReader; do
  if ! OUT=$(xcrun swiftc -emit-module -module-name "$m" -sdk "$SDK" -target "$TARGET" \
      -emit-module-path "$MODULES/$m.swiftmodule" -I "$MODULES" \
      "tools/ios-compile/stubs/$m.swift" 2>&1); then
    printf '%s\n' "$OUT" >&2
    echo "❌ stub module $m failed to build" >&2
    exit 1
  fi
done

OUT=$(xcrun swiftc -typecheck -sdk "$SDK" -target "$TARGET" -I "$MODULES" src/ios/*.swift 2>&1)
ERRORS=$(printf '%s\n' "$OUT" | grep -cE '^[^ ].*\.swift:[0-9]+:[0-9]+: error:' || true)
if [ "$ERRORS" -eq 0 ]; then
  echo "✅ iOS sources typecheck ($(ls src/ios/*.swift | wc -l | tr -d ' ') files, stubbed dependencies)"
  exit 0
fi
printf '%s\n' "$OUT" | grep -E '^[^ ].*\.swift:[0-9]+:[0-9]+: error:' \
  | sed 's|.*/src/ios/|src/ios/|' | head -20
echo "❌ iOS typecheck failed — $ERRORS error(s)"
exit 1
