/*
 * NovaVR - Cardboard SDK JNI bridge.
 * Copyright (C) 2026 NovaVR contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * A deliberately small C bridge over the Cardboard C API. All OpenGL calls
 * (viewer create / render / destroy) must happen on the GL thread that owns
 * the current EGL context; the Kotlin side guarantees this.
 */

#include <android/log.h>
#include <jni.h>
#include <time.h>

#include <cstdint>
#include <new>

#include "include/cardboard.h"

#define LOG_TAG "NovaVR-Cardboard"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define JNI_METHOD(return_type, method_name) \
  extern "C" JNIEXPORT return_type JNICALL   \
      Java_org_novavr_cardboard_CardboardNative_##method_name

namespace {

constexpr int64_t kNanosPerSecond = 1000000000LL;

int64_t BootTimeNanos() {
  timespec ts{};
  clock_gettime(CLOCK_BOOTTIME, &ts);
  return static_cast<int64_t>(ts.tv_sec) * kNanosPerSecond + ts.tv_nsec;
}

// Lens distortion and the matching distortion renderer always live together.
struct Viewer {
  CardboardLensDistortion* lens = nullptr;
  CardboardDistortionRenderer* renderer = nullptr;
};

jbyteArray ToJByteArray(JNIEnv* env, const uint8_t* data, int size) {
  if (data == nullptr || size <= 0) return nullptr;
  jbyteArray array = env->NewByteArray(size);
  if (array == nullptr) return nullptr;
  env->SetByteArrayRegion(array, 0, size, reinterpret_cast<const jbyte*>(data));
  return array;
}

}  // namespace

// ---------------------------------------------------------------------------
// Initialisation and device parameters
// ---------------------------------------------------------------------------

JNI_METHOD(void, nativeInitialize)(JNIEnv* env, jclass, jobject context) {
  JavaVM* vm = nullptr;
  if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr) {
    LOGE("Unable to obtain JavaVM");
    return;
  }
  // The SDK creates its own global reference to the context.
  Cardboard_initializeAndroid(vm, context);
}

JNI_METHOD(jbyteArray, nativeGetSavedDeviceParams)(JNIEnv* env, jclass) {
  uint8_t* buffer = nullptr;
  int size = 0;
  CardboardQrCode_getSavedDeviceParams(&buffer, &size);
  jbyteArray result = ToJByteArray(env, buffer, size);
  if (buffer != nullptr) CardboardQrCode_destroy(buffer);
  return result;
}

JNI_METHOD(jbyteArray, nativeGetCardboardV1DeviceParams)(JNIEnv* env, jclass) {
  uint8_t* buffer = nullptr;
  int size = 0;
  CardboardQrCode_getCardboardV1DeviceParams(&buffer, &size);
  // The V1 buffer is static storage owned by the SDK; it must not be freed.
  return ToJByteArray(env, buffer, size);
}

// ---------------------------------------------------------------------------
// Head tracker (gyroscope + accelerometer sensor fusion, EKF based)
// ---------------------------------------------------------------------------

JNI_METHOD(jlong, nativeHeadTrackerCreate)(JNIEnv*, jclass) {
  return reinterpret_cast<jlong>(CardboardHeadTracker_create());
}

JNI_METHOD(void, nativeHeadTrackerDestroy)(JNIEnv*, jclass, jlong handle) {
  if (handle == 0) return;
  CardboardHeadTracker_destroy(reinterpret_cast<CardboardHeadTracker*>(handle));
}

JNI_METHOD(void, nativeHeadTrackerPause)(JNIEnv*, jclass, jlong handle) {
  if (handle == 0) return;
  CardboardHeadTracker_pause(reinterpret_cast<CardboardHeadTracker*>(handle));
}

JNI_METHOD(void, nativeHeadTrackerResume)(JNIEnv*, jclass, jlong handle) {
  if (handle == 0) return;
  CardboardHeadTracker_resume(reinterpret_cast<CardboardHeadTracker*>(handle));
}

JNI_METHOD(void, nativeHeadTrackerRecenter)(JNIEnv*, jclass, jlong handle) {
  if (handle == 0) return;
  CardboardHeadTracker_recenter(reinterpret_cast<CardboardHeadTracker*>(handle));
}

JNI_METHOD(void, nativeHeadTrackerSetLowPassFilter)
(JNIEnv*, jclass, jlong handle, jint cutoff_hz) {
  if (handle == 0 || cutoff_hz <= 0) return;
  CardboardHeadTracker_setLowPassFilter(
      reinterpret_cast<CardboardHeadTracker*>(handle), cutoff_hz);
}

// out_pose layout: [px, py, pz, qx, qy, qz, qw]
JNI_METHOD(void, nativeHeadTrackerGetPose)
(JNIEnv* env, jclass, jlong handle, jlong prediction_ns, jint orientation,
 jfloatArray out_pose) {
  if (handle == 0 || out_pose == nullptr || env->GetArrayLength(out_pose) < 7) {
    return;
  }
  float pose[7] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 1.f};
  CardboardHeadTracker_getPose(
      reinterpret_cast<CardboardHeadTracker*>(handle),
      BootTimeNanos() + prediction_ns,
      static_cast<CardboardViewportOrientation>(orientation), &pose[0],
      &pose[3]);
  env->SetFloatArrayRegion(out_pose, 0, 7, pose);
}

// ---------------------------------------------------------------------------
// Viewer: lens distortion + OpenGL ES 3.0 distortion renderer (GL thread only)
// ---------------------------------------------------------------------------

JNI_METHOD(jlong, nativeViewerCreate)
(JNIEnv* env, jclass, jbyteArray device_params, jint width, jint height) {
  if (device_params == nullptr || width <= 0 || height <= 0) return 0;

  const jsize size = env->GetArrayLength(device_params);
  jbyte* bytes = env->GetByteArrayElements(device_params, nullptr);
  if (bytes == nullptr) return 0;
  CardboardLensDistortion* lens = CardboardLensDistortion_create(
      reinterpret_cast<const uint8_t*>(bytes), size, width, height);
  env->ReleaseByteArrayElements(device_params, bytes, JNI_ABORT);
  if (lens == nullptr) {
    LOGE("CardboardLensDistortion_create failed");
    return 0;
  }

  const CardboardOpenGlEsDistortionRendererConfig config{kGlTexture2D};
  CardboardDistortionRenderer* renderer =
      CardboardOpenGlEs3DistortionRenderer_create(&config);
  if (renderer == nullptr) {
    LOGE("CardboardOpenGlEs3DistortionRenderer_create failed");
    CardboardLensDistortion_destroy(lens);
    return 0;
  }

  CardboardMesh mesh;
  CardboardLensDistortion_getDistortionMesh(lens, kLeft, &mesh);
  CardboardDistortionRenderer_setMesh(renderer, &mesh, kLeft);
  CardboardLensDistortion_getDistortionMesh(lens, kRight, &mesh);
  CardboardDistortionRenderer_setMesh(renderer, &mesh, kRight);

  Viewer* viewer = new (std::nothrow) Viewer{lens, renderer};
  if (viewer == nullptr) {
    CardboardDistortionRenderer_destroy(renderer);
    CardboardLensDistortion_destroy(lens);
    return 0;
  }
  return reinterpret_cast<jlong>(viewer);
}

JNI_METHOD(void, nativeViewerDestroy)(JNIEnv*, jclass, jlong handle) {
  if (handle == 0) return;
  Viewer* viewer = reinterpret_cast<Viewer*>(handle);
  CardboardDistortionRenderer_destroy(viewer->renderer);
  CardboardLensDistortion_destroy(viewer->lens);
  delete viewer;
}

JNI_METHOD(void, nativeViewerGetEyeFromHeadMatrix)
(JNIEnv* env, jclass, jlong handle, jint eye, jfloatArray out_matrix) {
  if (handle == 0 || out_matrix == nullptr) return;
  float matrix[16];
  CardboardLensDistortion_getEyeFromHeadMatrix(
      reinterpret_cast<Viewer*>(handle)->lens, static_cast<CardboardEye>(eye),
      matrix);
  env->SetFloatArrayRegion(out_matrix, 0, 16, matrix);
}

JNI_METHOD(void, nativeViewerGetProjectionMatrix)
(JNIEnv* env, jclass, jlong handle, jint eye, jfloat z_near, jfloat z_far,
 jfloatArray out_matrix) {
  if (handle == 0 || out_matrix == nullptr) return;
  float matrix[16];
  CardboardLensDistortion_getProjectionMatrix(
      reinterpret_cast<Viewer*>(handle)->lens, static_cast<CardboardEye>(eye),
      z_near, z_far, matrix);
  env->SetFloatArrayRegion(out_matrix, 0, 16, matrix);
}

// out_fov layout: [left, right, bottom, top] in radians.
JNI_METHOD(void, nativeViewerGetFieldOfView)
(JNIEnv* env, jclass, jlong handle, jint eye, jfloatArray out_fov) {
  if (handle == 0 || out_fov == nullptr) return;
  float fov[4];
  CardboardLensDistortion_getFieldOfView(reinterpret_cast<Viewer*>(handle)->lens,
                                         static_cast<CardboardEye>(eye), fov);
  env->SetFloatArrayRegion(out_fov, 0, 4, fov);
}

// Distorts a side-by-side eye texture (left half = left eye) onto the target
// framebuffer.
JNI_METHOD(void, nativeViewerRender)
(JNIEnv*, jclass, jlong handle, jint texture, jint target_framebuffer, jint x,
 jint y, jint width, jint height) {
  if (handle == 0) return;
  // Field order: texture, left_u, right_u, top_v, bottom_v (GL texture space,
  // so the top of the image is v = 1).
  const CardboardEyeTextureDescription left{
      static_cast<uint64_t>(texture), 0.0f, 0.5f, 1.0f, 0.0f};
  const CardboardEyeTextureDescription right{
      static_cast<uint64_t>(texture), 0.5f, 1.0f, 1.0f, 0.0f};
  CardboardDistortionRenderer_renderEyeToDisplay(
      reinterpret_cast<Viewer*>(handle)->renderer,
      static_cast<uint64_t>(target_framebuffer), x, y, width, height, &left,
      &right);
}
