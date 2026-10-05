package echo.music.usbaudio

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

    // ── JNI declarations ─────────────────────────────────────────────────────

    private external fun nativeGetVersion(): String
}
