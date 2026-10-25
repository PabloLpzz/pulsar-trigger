# Canon EOS R50 Live View experiment

Addresses [issue #1](https://github.com/pulsar-trigger/pulsar-trigger/issues/1).
The user confirmed that the first experimental APK shows Live View on the R50.
The v2 changes still require hardware validation.

## V2: FPS and camera display

Star Focus displays decoded-preview FPS, sampled once per second against a
monotonic clock. Only successfully decoded images count; a stalled feed reports
zero on its next sampling window. This measures preview delivery, not the
camera sensor's internal frame rate.

Polling targets a 33 ms total cycle (about 30 FPS maximum), including transfer
and decode time. It no longer adds a fixed 150 ms after every image. Missing or
undecodable frames back off for at least 100 ms. Actual FPS still depends on
the camera, connection and processing time.

USB and Wi-Fi PTP/IP request EVFOutput=3 (camera display + host). If the body
rejects that value, they retry the previously working host-only output=2.
Leaving a preview step (including returning to PREP), changing transports, or
closing the screen completes output cleanup even during cancellation. Cleanup
requests camera-only output=1 and falls back to output=0 if rejected.

## Canon protocol correction

The client first tries standard SetDevicePropValue (`0x1016`, property
`0xD1B0`). When it returns DevicePropNotSupported (`0x200A`) or
OperationNotSupported (`0x2005`), it uses EOS SetDevicePropValueEx (`0x9110`).
The EOS data record contains three little-endian 32-bit words: length=12,
property code, value; the command has no parameters.

Before the first EOS enabling write, the client fetches Canon property events
(`0x9116`) and tries EVFMode=1 (`0xD1B1`, best effort). Preparation runs at most
once per start attempt. Unsupported output/parameter, invalid property value,
or general error on the preferred output permits a retry with the legacy
output value; busy responses and communication failures do not trigger this
fallback. Capability downgrading uses the final output response.

USB also accepts a RESPONSE without a DATA phase when a data-in operation is
rejected. This preserves an unsupported GetEvent response so EVF setup can
continue without an extra USB read.

Encoding and initialization follow libgphoto2's
[`ptp_canon_eos_setdevicepropvalue`](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.c)
and [EOS preview setup](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/library.c).
The display bitmask is documented by its
[CameraOutput table](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/config.c)
(camera=1, PC=2, both=3). SetRemoteMode is unchanged; the reported Wi-Fi
`0x2002` remains useful diagnostics if preview still fails.

## Build and install

Use JDK 17 and Android SDK 36. In the prepared cloud environment, first run
`source /workspace/.pulsar-env/activate.sh`.

```sh
cd android
./gradlew --no-daemon --max-workers=4 \
  -I ../scripts/r50-experimental.init.gradle testDebugUnitTest assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The experimental init
script sets application ID `com.ehrocha.pulsar.r50test`, version
`0.649.0-r50-liveview-v2`, version code `661`, and launcher label **Pulsar R50
Test**. It is debug signed with the same key as v1 and updates that experimental
app in place. The original app and its data remain separate. Omitting the init
script keeps the normal application's ID and version.

## Test on the R50

1. Install the new APK over the existing **Pulsar R50 Test**. Close the original
   app so it does not also connect to the camera.
2. Put the camera in still-photo Manual mode with a short shutter speed, rather
   than Bulb, and connect by USB PTP.
3. Open Star Focus. Note the FPS readout after a few seconds and check whether
   the camera's own display stays on. If the lens supports it, try a small
   focus step.
4. Go back to PREP and check that the camera display is restored. Re-enter
   preview, then leave Star Focus entirely and check the display again.
5. Disconnect USB, connect through Wi-Fi PTP/IP, and repeat. Record the FPS for
   each connection under the same exposure settings.
6. If anything fails, run the compatibility report and export diagnostics from
   Tools → Collect diagnostics. Include firmware version, connection type,
   observed FPS, and whether both displays worked.

Logs identify the standard and EOS output writes, optional event/mode setup,
and any fallback from `output=3` to `output=2`. Cleanup normally writes
`output=1`, or fallback `output=0`. A successful output write with no JPEG
frames is a different failure from rejecting the property write.
