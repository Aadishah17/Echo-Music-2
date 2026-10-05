package echo.music.usbaudio

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import echo.music.usbaudio.model.DacCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDacAudioSinkTest {
  @Test
  fun testSupportsPcmFormatsMatchingDacCapabilities() {
    val caps = DacCapabilities(
      uacVersion = 2,
      supportedSampleRates = listOf(44100, 48000, 88200, 96000, 176400, 192000)
    )
    val sink = UsbDacAudioSink(UsbAudioDriver(), capabilities = caps)

    val pcm96k = Format.Builder()
      .setSampleMimeType(MimeTypes.AUDIO_RAW)
      .setChannelCount(2)
      .setSampleRate(96000)
      .setPcmEncoding(androidx.media3.common.C.ENCODING_PCM_24BIT)
      .build()
    assertTrue(sink.supportsFormat(pcm96k))

    val pcmOverMax = Format.Builder()
      .setSampleMimeType(MimeTypes.AUDIO_RAW)
      .setChannelCount(2)
      .setSampleRate(384000)
      .build()
    assertFalse(sink.supportsFormat(pcmOverMax))
  }
}
