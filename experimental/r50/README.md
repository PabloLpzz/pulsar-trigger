# Pulsar R50 experimental APK — v2

Download [pulsar-r50-liveview-experimental.apk](pulsar-r50-liveview-experimental.apk) using GitHub's **Download raw file** button.

This debug-signed build installs as **Pulsar R50 Test** (`com.ehrocha.pulsar.r50test`), version `0.649.0-r50-liveview-v2`, code `661`. It updates the first experimental APK in place, alongside the original app.

V2 adds a decoded-preview FPS counter, removes the fixed 150 ms pause after each frame, and requests simultaneous camera and mobile display. If combined output is rejected, it falls back to host-only output. It restores camera output when leaving preview.

The first APK was confirmed to show Live View on the user's R50. V2 hardware validation is pending. See the [test guide](../../docs/r50-live-view-test.md). The adjacent `.sha256` file contains the APK checksum.

Validation: 256 Android unit tests passed, APK signature verified, and signer matches v1.
