package echo.music.iad1tya.playback

sealed interface CutoffDecision {
  data object AllowEnd : CutoffDecision
  data class RecoverPrematureCutoff(val resumePositionMs: Long, val retryAttempt: Int) : CutoffDecision
}

class PlaybackCutoffGuard(
  private val thresholdMs: Long = 3000L,
  private val maxRetriesPerTrack: Int = 2
) {
  private var retryCount = 0
  private var currentMediaId: String? = null

  fun onTrackChanged(mediaId: String?) {
    currentMediaId = mediaId
    retryCount = 0
  }

  /**
   * Evaluates whether a STATE_ENDED transition is genuine or premature.
   * Returns CutoffDecision.AllowEnd if genuine, or CutoffDecision.RecoverPrematureCutoff if premature.
   */
  fun verifyTrackCompletion(
    currentPositionMs: Long,
    canonicalDurationMs: Long
  ): CutoffDecision {
    if (canonicalDurationMs <= 0L) return CutoffDecision.AllowEnd

    val safePosition = currentPositionMs.coerceAtLeast(0L)
    val discrepancy = canonicalDurationMs - safePosition
    return if (discrepancy > thresholdMs && retryCount < maxRetriesPerTrack) {
      retryCount++
      CutoffDecision.RecoverPrematureCutoff(
        resumePositionMs = safePosition,
        retryAttempt = retryCount
      )
    } else {
      CutoffDecision.AllowEnd
    }
  }

  fun getRetryCount(): Int = retryCount
}
