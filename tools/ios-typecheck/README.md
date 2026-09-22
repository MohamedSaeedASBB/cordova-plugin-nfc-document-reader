# iOS type-check

```
tools/ios-typecheck/check.sh
```

Type-checks the plugin's iOS sources against the real iOS SDK in a few seconds, without CocoaPods
and without a MABS build.

## Why

`swiftc -parse` checks only syntax. It accepted this, which is valid Swift grammar and not valid
Swift:

```swift
"accessProtocol": pace == .success ? "PACE" : (bac == .success ? "BAC" : NSNull())
```

A ternary's branches must share one type, and `String` and `NSNull` do not — the surrounding
`[String: Any]` literal does not rescue it. That reached a MABS build and failed it, after the
queue, the pod installs and the compile, with this plugin's file named in the error. Nothing local
caught it, because nothing local was type-checking.

Android has had a real compile all along, against the actual jmrtd and BouncyCastle jars. iOS had
nothing, because its dependencies are CocoaPods that cannot be installed here.

## How

`UIKit`, `AVFoundation`, `Vision` and `CoreNFC` come from the genuine iOS SDK. The pods are stubbed
in `stubs/`: `NFCPassportReader`, `TensorFlowLite`, `MLKitFaceDetection`, `MLKitVision`, and
CordovaLib's Swift-facing surface. Each mirrors the real API for the parts this plugin touches.

The Cordova stub is shaped from evidence rather than assumption. A MABS build compiled
`NfcDocumentReaderPlugin.swift` with one unrelated warning and no errors, so every construct that
file uses is valid against the genuine CordovaLib — including using `CDVCommandStatus_OK` and
`.error` interchangeably and treating `CDVPluginResult(status:messageAs:)` as optional. The stub
offers all three because the real thing evidently does.

## What a pass does and does not mean

A pass means the plugin's own Swift is internally consistent and type-correct.

It does not mean the pods still match. The stubs assert what those APIs look like; they are not
those APIs. If a pod changes a signature, or the plugin starts using a member the stub lacks, this
reports a missing member — which is a prompt to update the stub, not a failure of the plugin. Keep
the stubs honest: a stub bent to make the code compile would hide exactly the bug this exists to
catch.

Run it before pushing anything that touches `src/ios/`.
