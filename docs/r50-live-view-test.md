# Canon EOS R50 Live View experiment

Addresses [issue #1](https://github.com/pulsar-trigger/pulsar-trigger/issues/1).
This is an experimental protocol correction; passing automated tests does not
establish that Live View works on a physical R50.

## Change

USB PTP and Wi-Fi PTP/IP now share EVF setup. The existing standard property
write (`0x1016`, property `0xD1B0`, value `2`) remains the first attempt. If it
returns DevicePropNotSupported (`0x200A`) or OperationNotSupported (`0x2005`),
the client fetches Canon property events (`0x9116`), tries EVFMode=1
(`0xD1B1`, best effort), and writes EVFOutput=2 using Canon EOS
SetDevicePropValueEx (`0x9110`). Its data record consists of three little-endian
32-bit words: length=12, property code, value; the command has no parameters.
Stopping Live View also falls back to the EOS writer, with output=0, without
enabling EVFMode. Busy responses and communication failures do not trigger
the unsupported-property fallback. Capability downgrading uses the final
output response, after the EOS attempt.

USB also accepts a response without a DATA phase when a data-in operation is
rejected. Otherwise an unsupported GetEvent would throw away the response
before the EVF output write could run.

The protocol sequence and encoding follow libgphoto2's
[`ptp_canon_eos_setdevicepropvalue`](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.c)
and [EOS preview setup](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/library.c).
There is no speculative change to SetRemoteMode: the reported Wi-Fi
`0x2002` remains a separate diagnostic if this experiment still fails.

## Build

Use JDK 17 and Android SDK 36, as for the normal debug build. In the prepared
cloud environment, first run `source /workspace/.pulsar-env/activate.sh`.

```sh
cd android
./gradlew --no-daemon --max-workers=4 testDebugUnitTest assembleDebug
./gradlew --no-daemon --max-workers=4 \
  -I ../scripts/r50-experimental.init.gradle assembleDebug
```

The second command produces `app/build/outputs/apk/debug/app-debug.apk` with
application ID `com.ehrocha.pulsar.r50test`, version suffix `-r50-liveview`, and
launcher label **Pulsar R50 Test**. It is debug signed and installs alongside
the existing app, with separate settings and data. The release application's
ID and version are unchanged when the init script is omitted.

## Test on the R50

1. Install the experimental APK and open **Pulsar R50 Test**. Grant the
   permissions it requests. Close the original app so it does not also
   connect to the camera.
2. Put the camera in still-photo Manual mode with a short shutter speed,
   rather than Bulb, and connect by USB PTP.
3. Open Star Focus and check that a live image arrives and keeps updating.
   If supported by the lens, try one small focus step. Leave and reopen
   Star Focus to check that stopping and restarting work.
4. Disconnect USB, connect through Wi-Fi PTP/IP, and repeat the same check.
   Reconnect for each test to clear any earlier capability downgrade.
5. Run the compatibility report for each transport and export diagnostics
   through Tools → Collect diagnostics. Record whether image updates,
   focus drive, and reopening worked, plus the camera firmware version.

The exported log includes `EVF standard 0x1016`, `EVF GetEvent 0x9116`,
`EVF EOS 0x9110 mode=1`, and `EVF EOS 0x9110 output=2` with response codes.
On exit, the output write should use `output=0`. If it still fails, include
those lines and the `SetRemoteMode` / `EventMode` setup results. A successful
output write without JPEG frames is a different failure from rejecting
the property write.
