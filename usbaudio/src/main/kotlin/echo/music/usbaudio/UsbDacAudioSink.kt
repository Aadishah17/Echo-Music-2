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
    private var scratchBuffer = ByteArray(0)

    private var activeDacBitDepth: Int = 16
    private var conversionBuffer = ByteArray(0)

    private fun getBytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    private fun convertPcm(
        src: ByteArray,
        srcOffset: Int,
        srcLen: Int,
        srcBitDepth: Int,
        dstBitDepth: Int
    ): ByteArray {
        if (srcBitDepth == dstBitDepth) {
            return if (srcOffset == 0 && srcLen == src.size) src else src.copyOfRange(srcOffset, srcOffset + srcLen)
        }
        val srcBytesPerSample = srcBitDepth / 8
        val dstBytesPerSample = dstBitDepth / 8
        val numSamples = srcLen / srcBytesPerSample
        val needed = numSamples * dstBytesPerSample

        if (conversionBuffer.size < needed) {
            conversionBuffer = ByteArray(needed)
        }

        if (srcBitDepth == 16 && dstBitDepth == 24) {
            var s = srcOffset
            var d = 0
            for (i in 0 until numSamples) {
                conversionBuffer[d] = 0
                conversionBuffer[d + 1] = src[s]
                conversionBuffer[d + 2] = src[s + 1]
                s += 2
                d += 3
            }
        } else if (srcBitDepth == 16 && dstBitDepth == 32) {
            var s = srcOffset
            var d = 0
            for (i in 0 until numSamples) {
                conversionBuffer[d] = 0
                conversionBuffer[d + 1] = 0
                conversionBuffer[d + 2] = src[s]
                conversionBuffer[d + 3] = src[s + 1]
                s += 2
                d += 4
            }
        } else if (srcBitDepth == 24 && dstBitDepth == 32) {
            var s = srcOffset
            var d = 0
            for (i in 0 until numSamples) {
                conversionBuffer[d] = 0
                conversionBuffer[d + 1] = src[s]
                conversionBuffer[d + 2] = src[s + 1]
                conversionBuffer[d + 3] = src[s + 2]
                s += 3
                d += 4
            }
        } else {
            return src.copyOfRange(srcOffset, srcOffset + srcLen)
        }

        return conversionBuffer
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
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
            val inputBitDepth = when (format?.pcmEncoding) {
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT -> 32
                else -> 16
            }

            val matching = capabilities.supportedFormats.firstOrNull { fmt ->
                (format?.sampleRate == null || format.sampleRate == Format.NO_VALUE || fmt.sampleRates.isEmpty() || fmt.sampleRates.contains(sampleRate)) &&
                (format?.channelCount == null || format.channelCount == Format.NO_VALUE || fmt.channels == channels) &&
                fmt.bitDepth == inputBitDepth
            } ?: capabilities.supportedFormats.firstOrNull { fmt ->
                (format?.sampleRate == null || format.sampleRate == Format.NO_VALUE || fmt.sampleRates.isEmpty() || fmt.sampleRates.contains(sampleRate)) &&
                (format?.channelCount == null || format.channelCount == Format.NO_VALUE || fmt.channels == channels)
            } ?: capabilities.supportedFormats.firstOrNull()

            val dataEp = matching?.endpointAddress ?: 0x01
            val syncEp = matching?.syncEndpointAddress ?: -1
            val bitDepth = matching?.bitDepth ?: inputBitDepth
            activeDacBitDepth = bitDepth

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
            } else {
                listener?.onAudioSinkError(IllegalStateException("Failed to start USB audio stream: $ret"))
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
        if (dacConnectionProvider != null && !isStreaming) {
            return false // Stream hasn't started yet; backpressure until ready
        }

        val remaining = buffer.remaining()
        val startPos = buffer.position()
        if (scratchBuffer.size < remaining) {
            scratchBuffer = ByteArray(remaining)
        }
        buffer.get(scratchBuffer, 0, remaining)

        val channels = if (inputFormat?.channelCount != null && inputFormat?.channelCount != Format.NO_VALUE) inputFormat!!.channelCount else 2
        val inputBitDepth = when (inputFormat?.pcmEncoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            else -> 16
        }
        val targetBitDepth = if (activeDacBitDepth > 0) activeDacBitDepth else inputBitDepth

        val pcmToSend: ByteArray
        val bytesToSend: Int
        if (targetBitDepth == inputBitDepth) {
            pcmToSend = scratchBuffer
            bytesToSend = remaining
        } else {
            pcmToSend = convertPcm(scratchBuffer, 0, remaining, inputBitDepth, targetBitDepth)
            val inputFrameBytes = channels * (inputBitDepth / 8)
            val numFrames = remaining / maxOf(1, inputFrameBytes)
            bytesToSend = numFrames * channels * (targetBitDepth / 8)
        }

        val written = driver.writeAudio(pcmToSend, bytesToSend)
        val frameBytes = maxOf(1, channels * (inputBitDepth / 8))

        if (written < bytesToSend) {
            val targetFrameBytes = maxOf(1, channels * (targetBitDepth / 8))
            val framesWritten = maxOf(0, written) / targetFrameBytes
            val inputBytesConsumed = framesWritten * frameBytes
            buffer.position(startPos + inputBytesConsumed)
            framesWrittenJvm += framesWritten
            return false // Backpressure: ring buffer full, retry on next cycle
        }

        framesWrittenJvm += remaining / frameBytes
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
        driver.setSoftwareVolumeMultiplier(volume.toDouble().coerceIn(0.0, 1.0))
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
