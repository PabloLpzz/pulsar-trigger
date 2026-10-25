# Automatic star selection and relative focus guidance

Experimental R50 APK v3 (`0.649.0-r50-liveview-v3`, code 662) adds automatic
point-source detection to Star Focus. It keeps the v2 FPS readout and Canon
camera+host output request. It updates the existing Pulsar R50 Test app.

## Using the assistant

1. Use a fixed exposure and ISO in Manual mode (not Bulb). Aim at a field with
   several visible stars, avoiding saturation. Open Star Focus and continue
   from preparation to the preview.
2. Cyan circles mark the sources used by the automatic comparison. Automatic
   mode requires at least three valid sources and tracks up to five. No tap is
   required. If too few are visible, tap a valid star for single-star manual
   measurement (yellow circle).
3. Wait for five valid measurements. The size readout is a smoothed half-flux
   radius in preview pixels: smaller means more concentrated light. Adjust
   focus using the existing near/far buttons or the lens, then wait for the
   readout to settle. The assistant reports relative improvement, spread
   compared with the best measured value, or an unstable measurement.
4. Keep the same framing and exposure while comparing. Use **Reset comparison**
   after changing them; **Auto** switches back from manual selection. The
   comparison also resets when tracking, preview scale, selection mode or
   source brightness changes substantially.
5. When satisfied, continue to the lock instructions. The assistant does not
   move the lens automatically, change exposure, or take calibration photos.

The best value means **best measured during this comparison**, not certified
perfect focus. Seeing, noise, JPEG processing and undersampling limit precision.
A final real photograph remains the appropriate check. Single-star manual
mode is less robust than the automatic multi-star measurement.

## Implementation and validation

Detection and photometry run locally on the decode worker, at most four times
per second, without uploading images. A reduced detection grid bounds the
candidate search, while photometry uses the original decoded pixels. The
analyzer estimates local background and noise, finds a flux-weighted centroid,
and measures the radius enclosing half the source light above background.
It rejects per-channel saturation, isolated pixels, elongated sources, clipped
edges and apertures too small to contain the source. It does not identify
astronomical objects: suitable distant point lights can also be measured.

The tracker matches the same sources spatially, smooths five measurements
with a median, and withholds a best value during excessive scatter. Losing a
source invalidates the current readout; repeated loss starts a new comparison.
Large source-flux changes relative to the baseline reset history, including
gradual changes. A stalled preview clears the readout rather than retaining
an old good-looking value. Selection changes discard any in-flight analysis
for the previous selection.

Synthetic-image tests cover Gaussian sources with different widths and flux,
noise, hot pixels, saturation, trails, borders, manual selection and reduced
detection grids. Tracker tests cover smoothing, relative improvement,
unsteadiness, drift, loss, framing/scale/mode changes and exposure changes.
An end-to-end synthetic focus adjustment holds total flux approximately
constant while verifying improvement in the smoothed radius. These tests do
not replace checking the detector on real R50 night-sky previews.

Build and run the Android tests with:

```sh
cd android
./gradlew --no-daemon --max-workers=4 \
  -I ../scripts/r50-experimental.init.gradle testDebugUnitTest assembleDebug
```

On the R50, compare USB and Wi-Fi under fixed settings. Check automatic star
selection, a manual tap, the smaller-is-better response to small manual focus
changes, loss of targets, reset behavior, and FPS with analysis active. Sources
that are faint, saturated, very defocused or undersampled may not be measurable;
this should show a search/unstable state rather than an assurance of good focus.
