package echo.music.iad1tya.musicflow

import echo.music.iad1tya.lyrics.LyricsEntry

data class MusicFlowLyricFrame(
  val index: Int = -1,
  val currentLine: String = "",
  val nextLine: String = "",
) {
  companion object {
    fun at(
      lines: List<LyricsEntry>,
      positionMs: Long,
      offsetMs: Long = 0L,
    ): MusicFlowLyricFrame {
      val index = lines.indexOfLast { it.time <= positionMs + offsetMs }
      return MusicFlowLyricFrame(
        index = index,
        currentLine = lines.getOrNull(index)?.text.orEmpty(),
        nextLine = lines.asSequence().drop(index + 1).firstOrNull { it.text.isNotBlank() }?.text.orEmpty(),
      )
    }
  }
}
