#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "usbaudio_driver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include "spsc_ring_buffer.h"

// Native driver version constant — must match UsbAudioDriver.kt JVM fallback.
static constexpr const char* NATIVE_DRIVER_VERSION = "1.0.0-usbaudio";

static echo::music::usbaudio::SpscRingBuffer g_test_ring_buffer(echo::music::usbaudio::SpscRingBuffer::DEFAULT_CAPACITY);

extern "C" {

JNIEXPORT jstring JNICALL
Java_echo_music_usbaudio_UsbAudioDriver_nativeGetVersion(JNIEnv* env, jobject /* thiz */) {
    LOGI("nativeGetVersion called");
    return env->NewStringUTF(NATIVE_DRIVER_VERSION);
}

JNIEXPORT jint JNICALL
Java_echo_music_usbaudio_UsbAudioDriver_nativeTestRingBufferWrite(JNIEnv* env, jobject /* thiz */, jbyteArray data) {
    if (!data) return 0;
    jsize len = env->GetArrayLength(data);
    if (len == 0) return 0;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    size_t written = g_test_ring_buffer.write(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    return static_cast<jint>(written);
}

JNIEXPORT jbyteArray JNICALL
Java_echo_music_usbaudio_UsbAudioDriver_nativeTestRingBufferRead(JNIEnv* env, jobject /* thiz */, jint size) {
    if (size <= 0) return env->NewByteArray(0);

    std::vector<uint8_t> temp(static_cast<size_t>(size));
    size_t read_bytes = g_test_ring_buffer.read(temp.data(), static_cast<size_t>(size));

    jbyteArray result = env->NewByteArray(static_cast<jsize>(read_bytes));
    if (read_bytes > 0) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(read_bytes), reinterpret_cast<const jbyte*>(temp.data()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_echo_music_usbaudio_UsbAudioDriver_nativeTestRingBufferFlush(JNIEnv* /* env */, jobject /* thiz */) {
    g_test_ring_buffer.flush();
}

} // extern "C"
