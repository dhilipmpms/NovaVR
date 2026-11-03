# Classes, methods and fields reached from native code via JNI must survive R8.
-keep class com.google.cardboard.sdk.UsedByNative
-keepclasseswithmembers,includedescriptorclasses class ** {
  @com.google.cardboard.sdk.UsedByNative *;
}
-keep class com.google.cardboard.sdk.QrCodeCaptureActivity { *; }
-keep class com.google.cardboard.proto.** { *; }

# NovaVR JNI facade.
-keepclasseswithmembernames,includedescriptorclasses class org.novavr.cardboard.CardboardNative {
  native <methods>;
}
