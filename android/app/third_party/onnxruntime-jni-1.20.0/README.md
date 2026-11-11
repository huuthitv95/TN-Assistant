# ONNX Runtime Android JNI 1.20.0

This directory contains the ONNX Runtime Java JNI bridge sources and generated JNI headers from the official `microsoft/onnxruntime` `v1.20.0` source tree. The upstream ONNX Runtime C API headers and license notices are retained alongside them.

To recreate the app's local AAR, build the official Maven artifact `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`, then run `scripts/build_onnxruntime_android_16k.py` with that AAR and an installed Android NDK. The script rebuilds the bridge for `arm64-v8a` and `armeabi-v7a`, checks all JNI exports against the original bridge, checks 16 KB `PT_LOAD` alignment, and patches only those two JNI libraries into the AAR. All Java classes and `libonnxruntime.so` remain from the official AAR.

The generated local dependency is `android/app/libs/onnxruntime-android-1.20.0-tn16k.aar`. Its JNI library is rebuilt with the Android linker flags `-z max-page-size=16384` and `-z common-page-size=16384`.
