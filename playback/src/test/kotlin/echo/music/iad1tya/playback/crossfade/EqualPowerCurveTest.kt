package echo.music.iad1tya.playback.crossfade

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualPowerCurveTest {
  @Test
  fun testCurveEndpoints() {
    assertEquals(1.0f, EqualPowerCurve.outgoing(0.0f), 1e-6f)
    assertEquals(0.0f, EqualPowerCurve.incoming(0.0f), 1e-6f)
    assertEquals(0.0f, EqualPowerCurve.outgoing(1.0f), 1e-6f)
    assertEquals(1.0f, EqualPowerCurve.incoming(1.0f), 1e-6f)
  }

  @Test
  fun testAcousticPowerStaysConstantAcrossAllSteps() {
    for (step in 0..100) {
      val t = step / 100f
      val out = EqualPowerCurve.outgoing(t)
      val inc = EqualPowerCurve.incoming(t)
      val power = out * out + inc * inc
      assertTrue("power $power at t=$t must stay 1.0", abs(power - 1.0f) <= 0.001f)
    }
  }

  @Test
  fun testOutgoingFadesWhileIncomingRises() {
    assertTrue(EqualPowerCurve.outgoing(0.25f) > EqualPowerCurve.outgoing(0.75f))
    assertTrue(EqualPowerCurve.incoming(0.25f) < EqualPowerCurve.incoming(0.75f))
    assertEquals(0.7071f, EqualPowerCurve.outgoing(0.5f), 0.001f)
    assertEquals(0.7071f, EqualPowerCurve.incoming(0.5f), 0.001f)
  }

  @Test
  fun testInputsOutsideUnitRangeAreClampedNotExtrapolated() {
    assertEquals(EqualPowerCurve.outgoing(0.0f), EqualPowerCurve.outgoing(-0.5f), 1e-6f)
    assertEquals(EqualPowerCurve.outgoing(1.0f), EqualPowerCurve.outgoing(2.5f), 1e-6f)
    assertEquals(EqualPowerCurve.incoming(0.0f), EqualPowerCurve.incoming(-3.0f), 1e-6f)
    assertEquals(EqualPowerCurve.incoming(1.0f), EqualPowerCurve.incoming(99.0f), 1e-6f)
  }

  @Test
  fun testNonFiniteProgressYieldsSafeGainInsteadOfNaN() {
    assertEquals(0.0f, EqualPowerCurve.outgoing(Float.NaN), 0.0f)
    assertEquals(0.0f, EqualPowerCurve.incoming(Float.NaN), 0.0f)
    assertEquals(0.0f, EqualPowerCurve.outgoing(Float.POSITIVE_INFINITY), 0.0f)
    assertEquals(0.0f, EqualPowerCurve.incoming(Float.NEGATIVE_INFINITY), 0.0f)
  }
}
