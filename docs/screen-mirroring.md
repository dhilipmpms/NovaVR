# Screen mirroring in NovaVR 0.2 — design and Android restrictions

This document explains how NovaVR shows real Android content in VR, which
platform rules shape that design, and what is and isn't possible without
system privileges.

## The pipeline

```
Shared app (keeps running, drawn by SurfaceFlinger)
      │  MediaProjection, single-app capture
      ▼
VirtualDisplay  (sized to the captured region × resolution scale)
      │  Surface
      ▼
SurfaceTexture  (detached; owned by ScreenMirrorService)
      │  attachToGLContext / updateTexImage   ← zero copy, stays on the GPU
      ▼
GL_TEXTURE_EXTERNAL_OES  ──►  VirtualScreen shader (left eye, right eye)
      ▼
Cardboard distortion pass  ──►  VR overlay window (TYPE_APPLICATION_OVERLAY)
```

* **No CPU copies.** Captured buffers go from the compositor straight into a
  GL external texture. `updateTexImage` only latches a buffer handle.
* **Lowest latency.** Each frame the renderer latches the newest queued buffer
  and drops older ones.
* **Both eyes use the same texture,** through each eye's own view and
  projection, so the source content is identical and the stereo alignment is
  geometric.
* **No stretching.** The VirtualDisplay has the same aspect ratio as the
  captured region. The virtual screen (Portrait or Landscape) fits that
  content inside itself with letter- or pillar-boxing.
* **Static content costs nothing.** A VirtualDisplay produces frames only when
  the content changes, so the overlay shows "capture 0 fps" on a static
  screen.
* **Anti-aliasing.** External textures have no mipmaps, so the screen shader
  uses a 4-tap filter sized by screen-space derivatives to avoid shimmer when
  a high-resolution capture is shown small. The capture resolution setting
  (50/75/100%) saves power, since the headset can't resolve full panel
  resolution anyway.

## Why the VR view is an overlay window and not an Activity

On a phone there's one physical display, and whatever MediaProjection
captures is what is on that display or on the chosen app's window. That
leaves three options.

| Option | What happens |
|---|---|
| NovaVR VR activity in front, capture **entire screen** | The capture contains NovaVR's own VR output, which renders itself again: an infinite mirror tunnel. |
| NovaVR VR activity in front, capture **a single app** | That app is fully covered, so Android stops it. It stops drawing and the capture shows nothing new (`onCapturedContentVisibilityChanged(false)`). |
| **NovaVR VR in an overlay window above the shared app** (chosen) | Overlays don't pause or stop the activity underneath, so the shared app stays resumed and keeps drawing. Single-app capture records only that app's task, never the overlay, so there is **no feedback loop**. |

The overlay needs the **"Display over other apps"** special access
(`SYSTEM_ALERT_WINDOW`). The user grants it once in system settings, and
NovaVR explains why before opening that page.

The overlay window requests `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`. Phones
honour this for overlay windows, so the display stays landscape for the
headset. A portrait-only shared app is then letterboxed by the system, its
captured region becomes portrait, and NovaVR switches to Portrait mode if
"Follow content orientation" is on.

## Feedback-loop protection

* With **"A single app"** (the default in the Android 14+ consent dialog), the
  overlay is never part of the capture.
* If the user picks **"Entire screen"**, the overlay is `FLAG_SECURE`, so
  Android blacks it out in the capture instead of recursing. A low-cost GPU
  probe notices the black stream and VR tells the user to restart with "A
  single app". The probe is a 16×16 downsample read back asynchronously through
  a pixel-buffer object every 2 s.
* Debug builds can turn off `FLAG_SECURE` for testing with
  `adb shell setprop debug.novavr.insecure_overlay 1`, so screenshots and
  recordings can see the VR view. Release builds ignore this.

## Android version requirements and lifecycle rules

Screen mirroring requires **Android 14 (API 34) or newer**. That release
introduced single-app capture and the callbacks NovaVR depends on:
`onCapturedContentResize` and `onCapturedContentVisibilityChanged`. On
Android 10–13 only entire-screen capture exists, which can't work with a VR
view on the same display. Test-pattern VR still runs there.

`ScreenMirrorService` follows the Android 14+ MediaProjection rules:

1. The consent intent comes from `MediaProjectionManager.createScreenCaptureIntent()`.
2. After consent, the service calls `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`
   *before* `getMediaProjection()`.
3. A `MediaProjection.Callback` is registered before `createVirtualDisplay()`.
4. A consent result is single use, with exactly **one** VirtualDisplay per
   projection. Rotation and resolution changes use `VirtualDisplay.resize()`.
5. `onStop` tears everything down. The system calls it when the user taps
   "Stop sharing" in the status bar or the device locks. A new session always
   needs fresh consent, so the service is `START_NOT_STICKY`.

Cleanup happens in a fixed order:
1. The overlay: the renderer releases its GL objects and detaches the
   SurfaceTexture, and NovaVR waits for that to finish.
2. The VirtualDisplay.
3. The Surface and the SurfaceTexture.
4. `MediaProjection.stop()`.
5. The foreground notification is removed and the service stops.

While VR is **hidden** (✕ in VR, Back, or the "Hide VR" notification
action), the VirtualDisplay's surface is detached, so no frames are produced.
VR is also hidden when the screen turns off.

### Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required to run a MediaProjection capture (Android 14+). |
| `SYSTEM_ALERT_WINDOW` (user-granted special access) | Draw VR over the shared app so it keeps running (see above). |
| `POST_NOTIFICATIONS` (asked once, optional) | Ongoing notification with **Show/Hide VR** and **Stop mirroring**. Mirroring works if it is denied. |
| `HIGH_SAMPLING_RATE_SENSORS` (from 0.1) | Fast gyroscope sampling for head tracking. |

No camera, microphone, storage, accessibility or internet permission is used.
MediaProjection itself needs no manifest permission: the user grants each
session in the system dialog.

## What happens when apps change

| Situation | Behaviour |
|---|---|
| Shared app in front, NovaVR overlay on top | Live mirroring. |
| User opens another app or goes Home | Android stops delivering the shared app's frames. NovaVR shows "The shared app is hidden" in both eyes until the app is visible again. |
| Shared app rotates or resizes | `onCapturedContentResize` → VirtualDisplay resized, optional Portrait/Landscape switch. |
| Phone rotated 180° in the headset | Display rotation listener updates head-tracker orientation; capture continues. |
| NovaVR's own home screen opened | NovaVR covers the shared app, which then shows as hidden. **Return to VR** sends NovaVR to the back and shows the overlay again. |
| Screen locked / "Stop sharing" tapped | Projection stops; NovaVR cleans up completely. |
| Protected content (DRM video, FLAG_SECURE apps such as banking) | Android blacks it out in every capture. NovaVR cannot and does not bypass this. |

## Refresh rate while mirroring

NovaVR votes for the panel's maximum rate in two ways:
* the window's preferred display mode
* `Surface.setFrameRate` on the GL surface, because overlay windows can't
  choose a display mode

The display policy still honours the **shared app's** own request. On the
Motorola Edge 60 Fusion (Android 17), apps such as Clock and Settings get a
60 Hz render cap from the OEM refresh-rate policy. While one of them is
shared, VR therefore runs at 60 fps. NovaVR's own test-pattern VR runs at
about 118 fps. Head-pose prediction adapts automatically to the actual rate.

## Verification

| Check | Android 15 emulator | Motorola Edge 60 Fusion, Android 17 |
|---|---|---|
| Overlay-permission explanation → system settings page | ✓ | already granted |
| Consent dialog, single-app picker | ✓ ("A single app") | ✓ ("Share one app") |
| Shared app stays resumed under the overlay | ✓ | ✓ |
| Overlay keeps the display landscape (auto-rotate off on device) | ✓ | ✓ |
| Content resize → VirtualDisplay resized | 2340×1080 → 1754×810 | 2712×1220 → 2034×914 |
| Captured frames shown in both eyes, aspect preserved | ✓ (screenshots, debug flag) | frames latched, probe not blank (phone lay flat, so no visual) |
| Portrait / Landscape switch, letterboxing | ✓ | — |
| In-VR size / distance controls | ✓ | — |
| 180° rotation, follow content orientation | ✓ | — |
| Other app in front → "shared app is hidden"; back → resumes | ✓ | — |
| Entire screen → no loop, black-screen guidance | ✓ | — |
| Hide VR / Show VR (notification action) | ✓ | hide ✓ |
| Stop (VR ■, home Stop) → projection, VirtualDisplay, service, notification gone | ✓ | ✓ |



* **Arbitrary apps as independent VR windows aren't possible.** A normal app
  can't launch other apps' activities onto its own VirtualDisplay, because
  Android only allows that for trusted (system) displays. MediaProjection
  mirrors existing content. It doesn't give NovaVR separate app windows.
* **One shared app at a time.** Each consent covers one app (or the whole
  screen), and only one projection can be active.
* **Interacting with the shared app inside VR** needs input routing, planned
  for 0.3. For now, tap ✕ (Hide VR), use the app normally, then tap **Show VR**
  in the notification.

The technically valid route to multi-window VR (0.4+) is to host apps on
NovaVR-owned virtual displays through a privileged helper:
* **Shizuku or ADB:** shell-level `ActivityOptions.setLaunchDisplayId` on a
  trusted virtual display, plus input injection into that display.
* **Desktop-mode displays:** where the platform exposes them.

Each window then becomes its own SurfaceTexture → VR quad, reusing the
`MirrorSource` / `VirtualScreen` path built here.
