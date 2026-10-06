package echo.music.iad1tya.playback.crossfade

class AdaptivePreArmManager(
  private val baseLeadTimeMs: Long = 20_000L,
  private val maxLeadTimeMs: Long = 35_000L,
  private val slowPrepThresholdMs: Long = 8_000L,
) {
  private var lastPrepDurationMs: Long = 0L

  fun currentLeadTimeMs(): Long {
    // Scaling by base/threshold keeps the expansion knee exactly on the slow-prep threshold:
    // a prep of slowPrepThresholdMs maps to baseLeadTimeMs, anything faster clamps back down.
    val scaled = lastPrepDurationMs.toDouble() * baseLeadTimeMs / slowPrepThresholdMs
    return scaled.toLong().coerceIn(baseLeadTimeMs, maxLeadTimeMs)
  }

  fun onPreparationCompleted(prepDurationMs: Long) {
    lastPrepDurationMs = prepDurationMs
  }

  fun canCrossfade(remainingMs: Long, crossfadeDurationMs: Long): Boolean =
    remainingMs >= crossfadeDurationMs

  fun shouldPreArm(remainingMs: Long, crossfadeDurationMs: Long): Boolean =
    canCrossfade(remainingMs, crossfadeDurationMs) && remainingMs <= currentLeadTimeMs()
}
