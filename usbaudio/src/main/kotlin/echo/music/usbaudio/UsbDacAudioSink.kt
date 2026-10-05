package echo.music.usbaudio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import echo.music.usbaudio.model.DacCapabilities
import java.nio.ByteBuffer

/**
 * Custom Media3 [AudioSink] delivering decoded bit-perfect PCM buffers directly to the
 * native USB isochronous streaming engine over Linux usbdevfs.
 */
class UsbDacAudioSink(
    private val driver: UsbAudioDriver,
    var capabilities: DacCapabilities = DacCapabilities(uacVersion = 2)
) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var playing = false
    private var inputFormat: Format? = null
    private var volume = 1.0f

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) {
            return false
        }
        val sampleRate = format.sampleRate
        if (sampleRate == Format.NO_VALUE) return true

        // Match sample rate against DAC capabilities
        return if (capabilities.supportedSampleRates.isNotEmpty()) {
            capabilities.supportedSampleRates.contains(sampleRate)
        } else {
            sampleRate <= 192000
        }
    }

    override fun getFormatSupport(format: Format): Int {
        return if (supportsFormat(format)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        return C.TIME_UNSET
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        this.inputFormat = inputFormat
    }

    override fun play() {
        playing = true
    }

    override fun handleDiscontinuity() {}

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!buffer.hasRemaining()) return true

        val remaining = buffer.remaining()
        val temp = ByteArray(remaining)
        buffer.get(temp)
        driver.writeAudio(temp, remaining)
        return true
    }

    override fun playToEndOfStream() {
        playing = false
    }

    override fun isEnded(): Boolean = !playing

    override fun hasPendingData(): Boolean = false

    override fun getAudioTrackBufferSizeUs(): Long = 0L

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setAudioSessionId(audioSessionId: Int) {}

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

    override fun enableTunnelingV21() {}

    override fun disableTunneling() {}

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun pause() {
        playing = false
    }

    override fun flush() {
        driver.testRingBufferFlush()
    }

    override fun reset() {
        flush()
        playing = false
        inputFormat = null
    }
}
