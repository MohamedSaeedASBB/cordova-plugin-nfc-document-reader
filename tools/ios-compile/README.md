# iOS typecheck

```
bash tools/ios-compile/check.sh
```

Typechecks all of `src/ios` in a few seconds, with no CocoaPods install and no Xcode project.

## Why this exists

The iOS half could not be checked locally at all. It needs `NFCPassportReader`, ML Kit and
`TensorFlowLite` from CocoaPods, and the plugin sets
`EXCLUDED_ARCHS[sdk=iphonesimulator*] = arm64`, so it will not link against an Apple Silicon
simulator either. Every change therefore shipped on a read-through of the diff, and the MABS cloud
build — seven minutes away, and the first thing that ever actually compiled the code — was where
mistakes surfaced.

Two have reached it so far: a ternary whose branches were `String` and `NSNull` (see the comment at
`NfcDocumentReader.authenticationBlock`), and implicit `self` inside two escaping closures in
`NfcDocumentReaderPlugin.swift`. Both are ordinary compile errors. Neither is visible in a diff.

A plain `swiftc -typecheck` on the sources is worse than no check, because it is green for the
wrong reason:

```swift
#if canImport(NFCPassportReader)
```

guards roughly 600 of the 700 lines of `NfcDocumentReader.swift`. Without the pod that condition is
false, the compiler skips the code, and the file reports no errors because almost none of it was
read. The same applies to `import Cordova`.

## How it works

`stubs/` holds a declaration-only module for each of the five external dependencies — `Cordova`,
`MLKitVision`, `MLKitFaceDetection`, `TensorFlowLite`, `NFCPassportReader`. The script builds them
with `swiftc -emit-module` and typechecks `src/ios/*.swift` against them.

Because the stub modules exist, `canImport` is true and the conditional code is typechecked as it
will be on the build server.

The stubs contain signatures and nothing else — no implementations, no behaviour. They are never
compiled into the plugin and are not referenced by `plugin.xml`.

## What it catches, and what it does not

It catches the class of error that has actually reached MABS: implicit `self` in escaping closures,
wrong argument labels, type mismatches, non-exhaustive switches, a member renamed on one side of a
call. It does not run anything, so it says nothing about behaviour on a device.

Its one real limit is stub drift. A stub declares what the plugin uses, as that pod declares it
today; upgrade a pod and a signature may change underneath it. The failure mode is a false error
here rather than a hidden real one — the script complains about code that would compile — which is
the safe direction, but it does mean a stub needs updating when a pod is upgraded.

If a stub is wrong in the other direction — more permissive than the real pod — this check passes
and MABS still fails. Keep the stubs narrow for that reason: declare only what `src/ios` calls, with
the real types, and never reach for `Any` to make an error go away.

## Adding to a stub

When new plugin code calls a dependency API the stub does not declare, the check fails with
`has no member` or `cannot find ... in scope`. Add the declaration with the signature the real pod
publishes — check the pod's source, do not guess — and run the script again.
