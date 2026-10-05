#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "usbaudio_driver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Native driver version constant — must match UsbAudioDriver.kt JVM fallback.
static constexpr const char* NATIVE_DRIVER_VERSION = "1.0.0-usbaudio";

extern "C" {

JNIEXPORT jstring JNICALL
Java_echo_music_usbaudio_UsbAudioDriver_nativeGetVersion(JNIEnv* env, jobject /* thiz */) {
    LOGI("nativeGetVersion called");
    return env->NewStringUTF(NATIVE_DRIVER_VERSION);
}

} // extern "C"
