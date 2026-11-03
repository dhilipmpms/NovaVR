# :cardboard — trimmed Google Cardboard SDK

This module vendors the native core of the
[Google Cardboard SDK](https://github.com/googlevr/cardboard) **v1.35.0**
(Apache License 2.0, see [LICENSE](LICENSE)) together with a small NovaVR JNI
bridge. Upstream publishes no prebuilt Android library, so it is built from
source with CMake.

## What is used from the SDK

| Feature | Upstream source |
|---|---|
| Head tracking (gyro + accelerometer EKF, bias estimation, neck model, pose prediction) | `src/main/cpp/sdk/sensors`, `head_tracker.cc` |
| Lens model, per-eye projection / eye offsets, distortion mesh | `lens_distortion.cc`, `distortion_mesh.cc`, `polynomial_radial_distortion.cc` |
| OpenGL ES 3.0 distortion renderer | `rendering/opengl_es3_distortion_renderer.cc` |
| Screen DPI and viewer parameter loading | `screen_params/android`, `device_params/android`, `qrcode/android/qr_code.cc` + Java helpers |

## Modifications for NovaVR

* **Removed:** Vulkan renderer, Unity plugin, iOS sources, the Java `java_api`
  layer and the sample apps.
* **Removed QR-code scanning.** Upstream scans viewer QR codes with the camera
  through Google Play Services (Mobile Vision). NovaVR must not need Play
  Services or camera permission, so `QrCodeCaptureActivity` is replaced with a
  stub class that only satisfies the native `RegisterNatives` lookup. It is
  never launched. The CAMERA, INTERNET, NFC and storage permissions from the
  upstream manifest are not declared.
* **Protobuf:** `cardboard_device.proto` is compiled with protoc 4.28.3 (lite)
  instead of 3.19.4.
* **JNI:** `src/main/cpp/novavr_cardboard_jni.cc` plus
  `org.novavr.cardboard.CardboardNative` replace the upstream Java API. Only
  `Java_*` symbols are exported (`exports.lds`).

The headset profile is the one saved by a previous Cardboard pairing if one
exists, otherwise the built-in Cardboard V1 profile. NovaVR can override the
lens separation (`DeviceParams.withInterLensDistance`).

## Updating

Copy the same directories from a newer upstream tag into `src/main/cpp/sdk`
and the Java helpers into `src/main/java`, then rebuild. The JNI bridge only
uses the public C API in `include/cardboard.h`.
