#!/usr/bin/env bash
#
# Type-checks the plugin's iOS sources against the real iOS SDK, without CocoaPods.
#
# WHY THIS EXISTS
# `swiftc -parse` only checks syntax. It accepted this, which is valid Swift grammar and not
# valid Swift:
#
#     "accessProtocol": pace == .success ? "PACE" : (bac == .success ? "BAC" : NSNull())
#
# A ternary's branches must share one type, and String and NSNull do not — the surrounding
# [String: Any] literal does not rescue it. The mistake reached a MABS build and failed it after
# twenty minutes of queueing, pod installs and compilation, with the plugin's own file named in
# the error. Nothing local had caught it, because nothing local was type-checking.
#
# The plugin's iOS sources depend on pods that cannot be installed here, so this stubs them:
# NFCPassportReader, TensorFlowLite, MLKitFaceDetection, MLKitVision and CordovaLib's Swift-facing
# surface, each mirroring the real API for the parts this plugin touches. Everything else — UIKit,
# AVFoundation, Vision, CoreNFC — comes from the genuine iOS SDK.
#
# WHAT IT DOES NOT DO
# The stubs assert what the real APIs look like; they are not the real APIs. A signature that
# drifts in a pod, or one this plugin starts using and the stub lacks, shows up here as a missing
# member rather than as a silent pass — add it to the stub when that happens. A clean run means
# the plugin's own Swift is internally consistent, not that the pods still match.
#
# Usage: tools/ios-typecheck/check.sh
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STUB_SRC="$REPO/tools/ios-typecheck/stubs"
BUILD="${TMPDIR:-/tmp}/nfc-plugin-ios-typecheck.$$"
TARGET="arm64-apple-ios15.5"          # matches plugin.xml's deployment-target

command -v xcrun >/dev/null || { echo "xcrun not found — Xcode command line tools required"; exit 2; }
SDK="$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null || true)"
[ -n "$SDK" ] || { echo "iOS SDK not found — install Xcode (not just the CLI tools)"; exit 2; }

mkdir -p "$BUILD"
trap 'rm -rf "$BUILD"' EXIT

# MLKitVision before MLKitFaceDetection, which imports it; the rest are independent.
for module in NFCPassportReader TensorFlowLite MLKitVision MLKitFaceDetection Cordova; do
    xcrun swiftc -emit-module -module-name "$module" -sdk "$SDK" -target "$TARGET" \
        -I "$BUILD" -emit-module-path "$BUILD/$module.swiftmodule" \
        "$STUB_SRC/$module.swift"
done

echo "Type-checking $(ls "$REPO"/src/ios/*.swift | wc -l | tr -d ' ') iOS sources against $(basename "$SDK")…"
if xcrun swiftc -typecheck -sdk "$SDK" -target "$TARGET" -I "$BUILD" "$REPO"/src/ios/*.swift; then
    echo "✅ iOS sources type-check"
else
    echo "❌ iOS type-check failed — this would have failed the MABS build too"
    exit 1
fi
