package echo.music.iad1tya.playback.crossfade

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object EqualPowerCurve {

  fun outgoing(t: Float): Float = cos(clamp(t) * (PI / 2.0)).toFloat()

  fun incoming(t: Float): Float = sin(clamp(t) * (PI / 2.0)).toFloat()

  private fun clamp(t: Float): Double = t.coerceIn(0.0f, 1.0f).toDouble()
}
