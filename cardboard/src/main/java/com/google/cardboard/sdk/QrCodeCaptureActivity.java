/*
 * NovaVR modification of the Google Cardboard SDK (Apache-2.0).
 *
 * The upstream QrCodeCaptureActivity scans viewer QR codes with the camera and
 * depends on Google Play Services (Mobile Vision). NovaVR must work without
 * Play Services or camera access, so the activity is replaced by this stub.
 *
 * The native SDK still looks this class up during initialisation and registers
 * {@link #nativeIncrementDeviceParamsChangedCount()} on it, so the class and
 * the native method declaration must exist. It is never launched.
 */
package com.google.cardboard.sdk;

@UsedByNative
public final class QrCodeCaptureActivity {
  private QrCodeCaptureActivity() {}

  @UsedByNative
  private static native void nativeIncrementDeviceParamsChangedCount();
}
