# Pulsar R50 experimental APK — v3

Download [pulsar-r50-liveview-experimental.apk](pulsar-r50-liveview-experimental.apk) using GitHub’s **Download raw file** button.

This debug-signed build installs as **Pulsar R50 Test** (`com.ehrocha.pulsar.r50test`), version `0.649.0-r50-liveview-v3`, code `662`. It updates the previous experimental APK in place, alongside the original app.

V3 automatically selects several valid stars and shows a smoothed, relative focus measurement. Smaller star size is better; the best measured value is not a guarantee of perfect focus. Tap a star for manual selection or reset the comparison after changing framing/exposure. It does not move the lens automatically.

The v2 FPS counter, reduced inter-frame wait, camera+mobile output request and camera display cleanup are retained. The first APK was confirmed to show Live View on the user’s R50; subsequent changes need hardware testing.

See the [focus assistant guide](../../docs/star-focus-assistant.md) and [camera connection test guide](../../docs/r50-live-view-test.md). The adjacent `.sha256` file contains the APK checksum.

Validation: 286 Android unit tests passed; `lintDebug` passed with no errors. APK signature verified and matches v1/v2. Real night-sky detection still needs testing.
