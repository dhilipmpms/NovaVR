# NovaVR

**Your Smartphone. Your Virtual Workspace.**

NovaVR is an open-source Android app that turns an ordinary smartphone into a
VR display for inexpensive Cardboard-style headsets. The long-term goal is a
complete VR workstation: Android screen mirroring, virtual desktops, floating
app windows and Bluetooth keyboard and mouse support.

Version **0.2.0** adds **Android screen mirroring**: a real app's live
content on the virtual screen, rendered in stereo straight from the GPU. It
builds on 0.1's low-latency stereoscopic renderer, head tracking and
calibration tools.

The app works offline, requires no account and does not need Google Play
Services.

---

## New in 0.2.0: screen mirroring

* **Live Android content in VR** through MediaProjection → VirtualDisplay →
  SurfaceTexture → OpenGL ES external texture. No CPU copies; the newest
  frame is latched every display refresh.
* **Single-app sharing.** In the system dialog choose **"A single app"** and
  pick the app. NovaVR draws VR in an overlay above that app, so the app keeps
  running while you watch it in the headset.
* **No feedback loop.** NovaVR is never part of its own capture. If "Entire
  screen" is chosen, the secure overlay is blacked out instead of recursing,
  and VR explains what to do.
* **Portrait / Landscape** apply to captured content, which is fitted without
  stretching. With **Follow content orientation**, the mode switches
  automatically when the shared app rotates, and the VirtualDisplay is resized
  to match.
* **VR controls:** stop mirroring, hide/show VR, switch mode, recenter, and
  adjust screen size and distance (tuning panel), without leaving the headset.
* **Foreground service + notification** with Show/Hide VR and Stop. Cleanup is
  complete when sharing stops: the status-bar chip, screen lock and the Stop
  button all lead to it.
* **Settings:** capture resolution (50/75/100%) and follow content
  orientation.
* The calibration test pattern stays available as **Test pattern VR**.

Requires Android 14 or newer. See **[docs/screen-mirroring.md](docs/screen-mirroring.md)**
for the design, Android's restrictions, and why NovaVR can't (yet) show
arbitrary apps as independent VR windows.

## Features from 0.1.0

* **Stereoscopic VR rendering** (OpenGL ES 3.0)
  * Separate left-eye and right-eye views, each with its own eye offset and
    asymmetric projection from the headset's lens model.
  * Lens distortion correction by the Google Cardboard SDK's GLES 3.0
    distortion renderer. NovaVR has no distortion code of its own.
  * A virtual rectangular screen with anti-aliased rounded corners and an
    optional floor grid for spatial reference.
* **Head tracking** from the gyroscope and accelerometer, through the
  Cardboard SDK's sensor fusion: an extended Kalman filter with gyro bias
  estimation, a neck model and pose prediction tuned to the display refresh
  rate. You can recenter at any time.
* **Portrait and Landscape display modes**
  * Portrait: a phone-like virtual screen with the device's own portrait
    proportions.
  * Landscape: a wide virtual screen with the device's landscape proportions.
  * Proportions come from the device's real panel at runtime, so content is
    never stretched.
  * Switch modes on the home screen, in Settings, or in VR by tapping the
    screen (the headset button).
  * Both landscape directions are supported in VR. Rotating the phone 180°
    updates the tracking orientation without restarting rendering.
* **Adjustable virtual screen**: size (20–200″ diagonal) and distance
  (0.5–6 m).
* **Calibration**
  * Headset alignment instructions.
  * Left/right eye check in VR: a head-locked red **LEFT EYE** and blue
    **RIGHT EYE** crosshair.
  * Screen size and distance, with a live field-of-view readout and diagram.
  * Lens-separation adjustment on top of the headset profile.
  * Gyroscope responsiveness test: sample rate, per-axis response, noise at
    rest, gravity magnitude and a rotation-following dot.
* **Home screen** with branding, Start screen mirroring, Test pattern VR, the display-mode picker, quick
  settings, links to Calibration and Settings, and device information: model,
  Android version, display, refresh rates, OpenGL ES version and sensors.
* **Settings**: render resolution, a high-refresh-rate preference, the
  environment toggle, lens separation and reset to defaults.

### VR controls

| Action | Effect |
|---|---|
| Tap screen / press headset button | Switch Portrait ⇄ Landscape |
| Long press | Recenter the view |
| ✕ button or Back | Exit VR (test pattern) / hide VR, keep mirroring (mirroring) |
| ■ button | Stop screen mirroring |
| ⧉ button | Start screen mirroring (from test-pattern VR) |
| ⇄ / ◎ buttons | Switch mode / recenter |
| ☰ (tune) button | Screen size − / + and distance − / + |

---

## Technology stack

| Area | Choice |
|---|---|
| Language | Kotlin (app), C++17 (JNI bridge) |
| UI | Jetpack Compose, Material 3, dark theme |
| Build | Gradle 9.6 (Kotlin DSL), AGP 9.3, version catalog |
| Rendering | OpenGL ES 3.0 via `GLSurfaceView` |
| VR | Google Cardboard SDK v1.35.0, built from source (see [cardboard/README.md](cardboard/README.md)) |
| Sensors | Cardboard native sensor fusion (VR); Android `SensorManager` (calibration test) |
| SDK levels | minSdk 29, target/compile SDK 37 |
| Native | NDK 29.0.14206865, CMake 3.22.1 |

The runtime dependencies are AndroidX Core, Activity, Lifecycle, Compose and
protobuf-javalite (used by the Cardboard SDK). There are no analytics, network
or Play Services libraries. Permissions:
* `HIGH_SAMPLING_RATE_SENSORS`: fast gyroscope sampling.
* `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`: the capture
  service.
* "Display over other apps", granted by the user: the VR view over the shared
  app.
* Optional `POST_NOTIFICATIONS`: mirroring controls in the notification.

The [mirroring document](docs/screen-mirroring.md#permissions) explains each
one.

---

## Project structure

```
NovaVR/
├── app/                                   Application module
│   └── src/main/java/org/novavr/app/
│       ├── MainActivity.kt                Compose host + navigation
│       ├── NovaVRApplication.kt           Process singletons (settings)
│       ├── ui/
│       │   ├── HomeScreen.kt
│       │   ├── SettingsScreen.kt
│       │   ├── CalibrationScreen.kt
│       │   ├── VROverlay.kt               In-VR overlay, gestures
│       │   ├── Components.kt              Shared composables
│       │   └── theme/Theme.kt
│       ├── mirror/
│       │   ├── ScreenMirrorService.kt     Foreground service: MediaProjection + VirtualDisplay
│       │   ├── MirrorSource.kt            Detached SurfaceTexture: zero-copy frames for GL
│       │   ├── VROverlayWindow.kt         Secure VR overlay above the shared app
│       │   ├── MirrorLauncher.kt          Permission + consent flow (Compose)
│       │   └── MirrorStatus.kt            Process-wide mirroring state
│       ├── vr/
│       │   ├── VRSession.kt               VR engine shared by activity and overlay
│       │   ├── VRActivity.kt              Test-pattern / calibration VR host
│       │   ├── VRRenderer.kt              GLSurfaceView.Renderer, per-frame logic
│       │   ├── BlankFrameProbe.kt         Detects an all-black capture (async PBO)
│       │   ├── StereoRenderer.kt          Eye buffer + Cardboard distortion pass
│       │   ├── HeadTracker.kt             Cardboard head tracker wrapper
│       │   ├── VirtualScreen.kt           Virtual screen: 2D or OES texture, aspect fit
│       │   ├── ScreenContent.kt           Test-pattern / eye-marker textures
│       │   ├── Environment.kt             Floor grid
│       │   ├── EyeMarkers.kt              Calibration per-eye markers
│       │   └── GlUtil.kt
│       ├── sensors/
│       │   ├── SensorManagerHelper.kt     Lifecycle-scoped sensor flow
│       │   └── GyroTestAnalyzer.kt        Gyroscope test logic (unit tested)
│       ├── settings/VRSettings.kt         Settings model + SharedPreferences store
│       └── utils/DeviceInfo.kt            Runtime device capabilities
├── cardboard/                             Trimmed Cardboard SDK + JNI bridge
│   └── src/main/
│       ├── cpp/                           SDK native sources, novavr_cardboard_jni.cc
│       ├── java/                          SDK Java helpers used by native code
│       ├── kotlin/org/novavr/cardboard/   CardboardNative, DeviceParams
│       └── proto/                         cardboard_device.proto
├── docs/screen-mirroring.md               Mirroring design + Android restrictions
├── gradle/libs.versions.toml
├── build.gradle.kts · settings.gradle.kts · gradle.properties
├── LICENSE (GPL-3.0)
└── README.md
```

### How a frame is rendered

1. `VRRenderer.onDrawFrame` applies any pending settings, which arrive from the
   UI thread through an atomic hand-off.
2. `HeadTracker` returns the head pose predicted for when the frame will be
   displayed.
3. `StereoRenderer` draws the scene twice into one side-by-side offscreen
   buffer, once per eye, using `eyeFromHead × headView` and the per-eye
   projection.
4. The Cardboard distortion renderer warps that buffer onto the screen.

### Lifecycle and resource management

* GL objects are released on the GL thread in `onPause`, before the EGL
  context is destroyed. They are recreated in `onSurfaceCreated`.
* Head-tracker sensors are paused in `onPause`. The calibration gyroscope test
  registers sensors only while it runs and while the app is visible. Nothing
  samples sensors in the background.
* The Cardboard SDK is initialised once per process with a display context,
  not an Activity, so no Activity can leak.

---

## Build

Requirements: JDK 17+, the Android SDK with platform 37, NDK `29.0.14206865`
and CMake `3.22.1`. Android Studio installs the NDK and CMake on first sync;
from the command line:

```bash
sdkmanager "ndk;29.0.14206865" "cmake;3.22.1"
```

The first build also downloads `protoc` from Maven Central. Then:

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # minified release build (unsigned)
```

You can also open the folder in Android Studio and run the `app`
configuration.

## Installation

1. Enable **Developer options → USB debugging** (or Wireless debugging) on the
   phone.
2. Install the APK:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Launch **NovaVR** and run **Calibration** once.
4. Tap **Start screen mirroring**. Allow **Display over other apps** the first
   time, choose **A single app** in the system dialog, and pick the app.
5. Place the phone in the headset with the white divider centred. To stop,
   tap ■ in VR, tap **Stop mirroring** in the notification, or tap **Stop
   sharing** in the status bar.

---

## Device compatibility

* **Primary test device:** Motorola Edge 60 Fusion (1220 × 2712, 120 Hz
  pOLED). Rendering was verified on it at about 118 fps with stereo views,
  head tracking and lens distortion.
* **Requirements:** Android 10 (API 29) or newer, OpenGL ES 3.0, a gyroscope
  and an accelerometer. The home screen reports anything missing.
* **No hard-coded dimensions:** resolution, aspect ratio, pixel density,
  refresh rates and sensor rates are all read at runtime.
* **High refresh rate:** NovaVR selects the fastest display mode at the
  current resolution (60/90/120/144 Hz). It turns on sustained-performance
  mode where supported, to reduce thermal throttling.
* **ABIs:** arm64-v8a, armeabi-v7a, x86_64 (emulators and Chromebooks).
* **Display cutouts:** VR draws under the cutout so the image covers the whole
  panel, which correct lens distortion needs.

## Current limitations

* Mirroring shows one shared app at a time, as a mirror. Apps can't yet be
  separate VR windows; that needs privileged display APIs (see the
  [mirroring document](docs/screen-mirroring.md#what-isnt-possible-yet-and-the-path-forward)).
* There's no input to the shared app from inside VR yet: hide VR, interact,
  then show VR again. Protected content (DRM video, secure apps) is black in
  any capture.
* Screen mirroring needs Android 14+; Android 10–13 get test-pattern VR only.
* While mirroring, the refresh rate follows the shared app's own request. On
  the Edge 60 Fusion most apps are capped at 60 Hz by the OEM policy, so VR
  runs at 60 fps there; test-pattern VR runs at about 118 fps.
* There's no QR-code headset pairing, because it would need camera and Play
  Services. The Cardboard V1 profile is used, with adjustable lens separation.
* Orientation tracking only (3DoF, with the SDK's neck model). There's no
  positional tracking.
* On large screens with Android 16+, the system may ignore the landscape lock.
  NovaVR then asks you to rotate the device.
* Not yet tested on a wide range of phones and headsets.

## Roadmap

1. ~~**0.2:** Android screen mirroring onto the virtual screen~~ (done).
2. **0.3:** Bluetooth keyboard and mouse input with a virtual pointer routed
   to the shared app, a curved screen option, and headset profile import from
   a viewer URL.
3. **0.4:** Floating application windows and multiple virtual screens.
4. **0.5:** Virtual desktop environment and launcher.
5. Later: optional Shizuku/ADB integrations, environments and themes, and
   per-headset profiles.

## License

NovaVR is licensed under the **GNU General Public License v3.0**, see
[LICENSE](LICENSE).

The vendored Google Cardboard SDK in `cardboard/` is licensed under the
Apache License 2.0 ([cardboard/LICENSE](cardboard/LICENSE)), which is
compatible with GPL-3.0.
