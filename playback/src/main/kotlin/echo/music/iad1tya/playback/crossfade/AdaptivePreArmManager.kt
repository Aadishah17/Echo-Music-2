package echo.music.iad1tya.playback.crossfade

/**
 * Decides how far before the end of a track the standby player must be prepared so the crossfade
 * can start with the incoming player already buffered. All durations are milliseconds.
 *
 * Lead time is derived from the last preparation sample only (no averaging): it sits at
 * [baseLeadTimeMs] while preps are fast, expands once a prep exceeds [slowPrepThresholdMs], and is
 * capped at [maxLeadTimeMs].
 */
class AdaptivePreArmManager(
  private val baseLeadTimeMs: Long = 20_000L,
  private val maxLeadTimeMs: Long = 35_000L,
  private val slowPrepThresholdMs: Long = 8_000L,
) {
  private var lastPrepDurationMs: Long = 0L

  init {
    require(baseLeadTimeMs <= maxLeadTimeMs) { "baseLeadTimeMs must not exceed maxLeadTimeMs" }
    require(slowPrepThresholdMs > 0L) { "slowPrepThresholdMs must be positive" }
  }

  fun currentLeadTimeMs(): Long {
    // Scaling by base/threshold keeps the expansion knee exactly on the slow-prep threshold:
    // a prep of slowPrepThresholdMs maps to baseLeadTimeMs, anything faster clamps back down.
    val scaled = lastPrepDurationMs.toDouble() * baseLeadTimeMs / slowPrepThresholdMs
    return scaled.toLong().coerceIn(baseLeadTimeMs, maxLeadTimeMs)
  }

  fun onPreparationCompleted(prepDurationMs: Long) {
    lastPrepDurationMs = prepDurationMs
  }

  /**
   * Feasibility check only: true whenever [remainingMs] still fits a fade of [crossfadeDurationMs],
   * which holds from the first millisecond of a track. It is not a trigger — the fade itself starts
   * when the remaining time drops to [crossfadeDurationMs].
   */
  fun canCrossfade(remainingMs: Long, crossfadeDurationMs: Long): Boolean =
    remainingMs >= crossfadeDurationMs

  /**
   * Pre-arm trigger: true only once [remainingMs] reaches the current lead time, so preparation
   * begins early enough to absorb slow network prep. Also requires [canCrossfade], so it never
   * fires when the fade could not physically complete.
   */
  fun shouldPreArm(remainingMs: Long, crossfadeDurationMs: Long): Boolean =
    canCrossfade(remainingMs, crossfadeDurationMs) && remainingMs <= currentLeadTimeMs()
}
