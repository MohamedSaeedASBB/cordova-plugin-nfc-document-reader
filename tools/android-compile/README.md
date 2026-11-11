# Android compile check

```
bash tools/android-compile/check.sh
```

Compiles all of `src/android` in a few seconds, with no Gradle project.

## Why this exists

The plugin's Android half is built inside a throwaway Cordova project that takes minutes to
recreate and kept being cleared, so changes were going out on a read-through of the diff. This is
the Android counterpart of [`tools/ios-compile`](../ios-compile/README.md), and it exists for the
same reason: the cloud build should not be the first thing that reads the code.

## How it works

`build-classpath.sh` assembles a `javac` classpath from two things already on the machine:

- the newest `android.jar` under the Android SDK, and
- every `.jar` in the Gradle module cache, plus the `classes.jar` inside every cached `.aar`.

That gives the real jmrtd, scuba, BouncyCastle, ML Kit, CameraX, Guava and TensorFlow Lite classes
rather than stubs of them. `stubs/org/apache/cordova` holds the only hand-written declarations —
Cordova itself, which is not in the cache.

`check.sh` caches the classpath under `.cache/` (gitignored) and rebuilds it when it is missing or
more than a day old.

No generated `R` class is needed: the activities resolve resources by name through
`getResources().getIdentifier()`. That is convenient here and is also exactly why a label aimed at
a view with no `android:id` used to fail silently at runtime — `tools/labels/check-native.js`
covers that gap, not this script.

## If it cannot build a classpath

It needs a populated Gradle cache and an Android SDK. Both are present on any machine that has
built an Android project once. Override the locations if they are somewhere unusual:

```
GRADLE_CACHE=/path/to/modules-2/files-2.1 ANDROID_SDK_ROOT=/path/to/sdk bash tools/android-compile/check.sh
```

## What it catches, and what it does not

It is a compile, so it catches what a compile catches: a renamed method, a wrong type, a missing
override, an unhandled exception. It runs nothing, so it says nothing about behaviour on a device,
and it does not build resources — a broken layout XML or a missing drawable still only shows up in
a real build.

A previous version tolerated one known failure, ML Kit's `OptionalModuleApi` annotation, which
`javac` could not resolve from a jar alone. Unpacking the `.aar` files fixed that, so the script now
requires zero errors.
