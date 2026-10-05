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
    var capabilities: DacCapabilities = DacCapabilities(uacVersion = 2),
    private val dacConnectionProvider: (() -> DacDeviceState.Connected?)? = null
) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var playing = false
    private var isStreamEnded = false
    private var isStreaming = false
    private var inputFormat: Format? = null
    private var volume = 1.0f
    private var framesWrittenJvm: Long = 0L

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
        val sampleRate = inputFormat?.sampleRate ?: 44100
        if (sampleRate <= 0) return 0L
        val frames = if (driver.isLoaded() && isStreaming) {
            driver.getFramesPlayed()
        } else {
            framesWrittenJvm
        }
        return (frames * 1_000_000L) / sampleRate
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        this.inputFormat = inputFormat
        framesWrittenJvm = 0L
        isStreamEnded = false
    }

    override fun play() {
        playing = true
        startStreamIfNeeded()
    }

    private fun startStreamIfNeeded() {
        if (isStreaming) return
        val connected = dacConnectionProvider?.invoke()
        if (connected != null) {
            capabilities = connected.capabilities
            val format = inputFormat
            val sampleRate = if (format != null && format.sampleRate != Format.NO_VALUE) format.sampleRate else 44100
            val channels = if (format != null && format.channelCount != Format.NO_VALUE) format.channelCount else 2
            val matching = capabilities.supportedFormats.firstOrNull { fmt ->
                (format?.sampleRate == null || format.sampleRate == Format.NO_VALUE || fmt.sampleRates.isEmpty() || fmt.sampleRates.contains(sampleRate)) &&
                (format?.channelCount == null || format.channelCount == Format.NO_VALUE || fmt.channels == channels)
            } ?: capabilities.supportedFormats.firstOrNull()

            val dataEp = matching?.endpointAddress ?: 0x01
            val syncEp = matching?.syncEndpointAddress ?: -1
            val bitDepth = matching?.bitDepth ?: 16

            val ret = driver.startStream(
                fd = connected.fileDescriptor,
                dataEp = dataEp,
                syncEp = syncEp,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels
            )
            if (ret == 0) {
                isStreaming = true
            }
        }
    }

    override fun handleDiscontinuity() {}

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!buffer.hasRemaining()) return true
        if (playing) {
            startStreamIfNeeded()
        }

        val remaining = buffer.remaining()
        val startPos = buffer.position()
        val temp = ByteArray(remaining)
        buffer.get(temp)

        val written = driver.writeAudio(temp, remaining)
        if (written < remaining) {
            val actualWritten = maxOf(0, written)
            buffer.position(startPos + actualWritten)
            val channels = inputFormat?.channelCount ?: 2
            val bytesPerSample = 2
            framesWrittenJvm += actualWritten / (channels * bytesPerSample)
            return false // Backpressure: ring buffer full, retry on next cycle
        }

        val channels = inputFormat?.channelCount ?: 2
        val bytesPerSample = 2
        framesWrittenJvm += remaining / (channels * bytesPerSample)
        return true
    }

    override fun playToEndOfStream() {
        isStreamEnded = true
    }

    override fun isEnded(): Boolean = isStreamEnded && !hasPendingData()

    override fun hasPendingData(): Boolean = driver.hasPendingData()

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
        driver.flushStream()
        framesWrittenJvm = 0L
        isStreamEnded = false
    }

    override fun reset() {
        if (isStreaming) {
            driver.stopStream()
            isStreaming = false
        }
        flush()
        playing = false
        isStreamEnded = false
        inputFormat = null
    }
}
