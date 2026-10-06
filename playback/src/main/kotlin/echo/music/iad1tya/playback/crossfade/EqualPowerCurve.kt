package echo.music.iad1tya.playback.crossfade

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-power sine/cosine volume ramps for crossfading. [t] is normalized progress in [0, 1] and
 * the results are linear volume multipliers in [0, 1] where `outgoing^2 + incoming^2` is always
 * 1.0, so acoustic power never dips mid-fade. Non-finite input yields 0f rather than NaN.
 */
object EqualPowerCurve {

  fun outgoing(t: Float): Float = if (t.isFinite()) cos(clamp(t) * (PI / 2.0)).toFloat() else 0f

  fun incoming(t: Float): Float = if (t.isFinite()) sin(clamp(t) * (PI / 2.0)).toFloat() else 0f

  private fun clamp(t: Float): Double = t.coerceIn(0.0f, 1.0f).toDouble()
}
