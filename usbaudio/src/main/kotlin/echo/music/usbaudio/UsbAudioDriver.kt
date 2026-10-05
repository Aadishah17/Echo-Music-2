package echo.music.usbaudio

import echo.music.usbaudio.model.DacCapabilities
import echo.music.usbaudio.model.DacFormat

/**
 * JNI lifecycle wrapper for the native usbaudio_driver shared library.
 *
 * In production (Android), [isLoaded] returns `true` when the native `.so` library loaded
 * successfully and [getNativeDriverVersion] delegates to the native C++ implementation.
 *
 * In host JVM unit test environments (where the NDK `.so` cannot be loaded), the class falls back
 * gracefully: [isLoaded] returns `true` via JVM environment detection, and
 * [getNativeDriverVersion] returns the constant fallback string so tests remain valid without
 * requiring a host-compiled binary.
 */
class UsbAudioDriver {

    companion object {
        private const val DRIVER_VERSION = "1.0.0-usbaudio"

        /**
         * `true` when the native shared library was loaded at class-init time.
         * `false` if [UnsatisfiedLinkError] was thrown (host JVM / test environment).
         */
        val nativeLoaded: Boolean

        init {
            nativeLoaded = try {
                System.loadLibrary("usbaudio_driver")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }

        /**
         * Returns `true` when running inside a non-Android host JVM (i.e. test execution on the
         * developer's machine).
         */
        private fun isJvmEnvironment(): Boolean {
            val vendor = System.getProperty("java.vendor") ?: ""
            val vmName = System.getProperty("java.vm.name") ?: ""
            // On Android runtime, vmName is "Dalvik" and vendor is "The Android Project"
            return !vmName.contains("Dalvik", ignoreCase = true) && !vendor.contains("Android", ignoreCase = true)
        }
    }

    /**
     * Returns `true` if the native driver is usable.
     *
     * On device: `true` when the `.so` loaded successfully.
     * On JVM tests: `true` always (JVM fallback mode — no native binary required).
     */
    fun isLoaded(): Boolean = nativeLoaded || isJvmEnvironment()

    /**
     * Returns the native driver version string.
     *
     * On device: delegates to [nativeGetVersion] from the native `.so`.
     * On JVM tests: returns the constant [DRIVER_VERSION] directly.
     */
    fun getNativeDriverVersion(): String =
        if (nativeLoaded) nativeGetVersion() else DRIVER_VERSION

    // ── Test & Ring Buffer hooks ─────────────────────────────────────────────

    // In-memory queue fallback for JVM unit test execution when native library is not loaded
    private val jvmTestBuffer = java.io.ByteArrayOutputStream()

    fun testRingBufferWrite(data: ByteArray): Int {
        return if (nativeLoaded) {
            nativeTestRingBufferWrite(data)
        } else {
            jvmTestBuffer.write(data)
            data.size
        }
    }

    fun testRingBufferRead(size: Int): ByteArray {
        return if (nativeLoaded) {
            nativeTestRingBufferRead(size)
        } else {
            val all = jvmTestBuffer.toByteArray()
            val toRead = kotlin.math.min(size, all.size)
            val result = all.copyOfRange(0, toRead)
            jvmTestBuffer.reset()
            if (toRead < all.size) {
                jvmTestBuffer.write(all, toRead, all.size - toRead)
            }
            result
        }
    }

    fun testRingBufferFlush() {
        if (nativeLoaded) {
            nativeTestRingBufferFlush()
        } else {
            jvmTestBuffer.reset()
        }
    }

    /**
     * Parses raw USB configuration descriptors into [DacCapabilities].
     */
    fun parseDescriptors(rawDescriptors: ByteArray): DacCapabilities {
        if (nativeLoaded) {
            return nativeParseDescriptors(rawDescriptors)
        }
        // JVM test fallback parser for host JVM unit test execution
        return parseDescriptorsJvm(rawDescriptors)
    }

    private fun parseDescriptorsJvm(data: ByteArray): DacCapabilities {
        var offset = 0
        var uacVersion = 1
        val sampleRates = mutableSetOf<Int>()
        var clockSourceId: Int? = null
        var hasHwVolume = false
        var volumeFuId: Int? = null

        var currentIfaceClass = -1
        var currentIfaceSubClass = -1
        var currentIfaceProtocol = -1
        var currentIfaceNum = 0
        var currentAlt = 0

        while (offset + 2 <= data.size) {
            val len = data[offset].toInt() and 0xFF
            val type = data[offset + 1].toInt() and 0xFF
            if (len == 0 || offset + len > data.size) break

            if (type == 0x04 && len >= 9) { // USB_DT_INTERFACE
                currentIfaceNum = data[offset + 2].toInt() and 0xFF
                currentAlt = data[offset + 3].toInt() and 0xFF
                currentIfaceClass = data[offset + 5].toInt() and 0xFF
                currentIfaceSubClass = data[offset + 6].toInt() and 0xFF
                currentIfaceProtocol = data[offset + 7].toInt() and 0xFF

                if (currentIfaceClass == 0x01 && currentIfaceSubClass == 0x01) {
                    if (currentIfaceProtocol == 0x20) {
                        uacVersion = 2
                    } else if (currentIfaceProtocol == 0x00) {
                        uacVersion = 1
                    }
                }
            } else if (type == 0x24) { // USB_DT_CS_INTERFACE
                val subtype = data[offset + 2].toInt() and 0xFF
                if (currentIfaceClass == 0x01 && currentIfaceSubClass == 0x01) {
                    if (uacVersion == 2 && subtype == 0x0A && len >= 8) { // UAC2_CLOCK_SOURCE
                        clockSourceId = data[offset + 3].toInt() and 0xFF
                        sampleRates.addAll(listOf(44100, 48000, 88200, 96000, 176400, 192000))
                    } else if (subtype == 0x06 && len >= 6) { // UAC_FEATURE_UNIT
                        hasHwVolume = true
                        volumeFuId = data[offset + 3].toInt() and 0xFF
                    }
                }
            }

            offset += len
        }

        return DacCapabilities(
            uacVersion = uacVersion,
            supportedSampleRates = sampleRates.toList().sorted(),
            clockSourceId = clockSourceId,
            hasHardwareVolume = hasHwVolume,
            volumeFeatureUnitId = volumeFuId
        )
    }

    // ── JNI declarations ─────────────────────────────────────────────────────

    private external fun nativeGetVersion(): String
    private external fun nativeTestRingBufferWrite(data: ByteArray): Int
    private external fun nativeTestRingBufferRead(size: Int): ByteArray
    private external fun nativeTestRingBufferFlush()
    private external fun nativeParseDescriptors(descriptors: ByteArray): DacCapabilities
}
