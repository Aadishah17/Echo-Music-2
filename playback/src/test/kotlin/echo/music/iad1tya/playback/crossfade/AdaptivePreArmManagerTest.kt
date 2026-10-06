package echo.music.iad1tya.playback.crossfade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePreArmManagerTest {
  @Test
  fun testConstructorRejectsInconsistentConfiguration() {
    assertThrows(IllegalArgumentException::class.java) {
      AdaptivePreArmManager(baseLeadTimeMs = 40_000L, maxLeadTimeMs = 35_000L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdaptivePreArmManager(slowPrepThresholdMs = 0L)
    }
  }

  @Test
  fun testFreshManagerStartsAtBaseLeadTime() {
    assertEquals(20_000L, AdaptivePreArmManager().currentLeadTimeMs())
  }

  @Test
  fun testSlowPreparationExpandsLeadTime() {
    val manager = AdaptivePreArmManager()
    manager.onPreparationCompleted(9_000L)
    val leadTime = manager.currentLeadTimeMs()
    assertTrue("leadTime=$leadTime must exceed base", leadTime > 20_000L)
    assertTrue("leadTime=$leadTime must respect max", leadTime <= 35_000L)
  }

  @Test
  fun testVerySlowPreparationHitsMaxLeadTime() {
    val manager = AdaptivePreArmManager()
    manager.onPreparationCompleted(22_000L)
    assertEquals(35_000L, manager.currentLeadTimeMs())
  }

  @Test
  fun testFastPreparationRelaxesLeadTimeBackToBase() {
    val manager = AdaptivePreArmManager()
    manager.onPreparationCompleted(22_000L)
    assertEquals(35_000L, manager.currentLeadTimeMs())

    manager.onPreparationCompleted(1_000L)
    assertEquals(20_000L, manager.currentLeadTimeMs())
  }

  @Test
  fun testLeadTimeExpandsOnlyWhenPreparationExceedsSlowThreshold() {
    val manager = AdaptivePreArmManager()
    manager.onPreparationCompleted(8_000L)
    assertEquals(20_000L, manager.currentLeadTimeMs())

    manager.onPreparationCompleted(8_001L)
    assertTrue(manager.currentLeadTimeMs() > 20_000L)
  }

  @Test
  fun testZeroPreparationNeverDropsBelowBaseLeadTime() {
    val manager = AdaptivePreArmManager()
    manager.onPreparationCompleted(0L)
    assertEquals(20_000L, manager.currentLeadTimeMs())
  }

  @Test
  fun testCustomBoundsAndThresholdAreRespected() {
    val manager =
      AdaptivePreArmManager(
        baseLeadTimeMs = 10_000L,
        maxLeadTimeMs = 30_000L,
        slowPrepThresholdMs = 5_000L,
      )
    assertEquals(10_000L, manager.currentLeadTimeMs())

    manager.onPreparationCompleted(6_000L)
    val leadTime = manager.currentLeadTimeMs()
    assertTrue("leadTime=$leadTime must exceed base", leadTime > 10_000L)
    assertTrue("leadTime=$leadTime must respect max", leadTime <= 30_000L)

    manager.onPreparationCompleted(100_000L)
    assertEquals(30_000L, manager.currentLeadTimeMs())
  }

  @Test
  fun testPreArmTriggersOnlyWhenRemainingReachesLeadTime() {
    val manager = AdaptivePreArmManager()
    assertFalse(manager.shouldPreArm(21_000L, 6_000L))
    assertTrue(manager.shouldPreArm(20_000L, 6_000L))
    assertTrue(manager.shouldPreArm(15_000L, 6_000L))
  }

  @Test
  fun testPreArmUsesExpandedLeadTimeAfterSlowPreparation() {
    val manager = AdaptivePreArmManager()
    manager.onPreparationCompleted(22_000L)
    assertFalse(manager.shouldPreArm(36_000L, 6_000L))
    assertTrue(manager.shouldPreArm(35_000L, 6_000L))
  }

  @Test
  fun testPreArmDoesNotTriggerWhenRemainingIsShorterThanCrossfadeDuration() {
    val manager = AdaptivePreArmManager()
    assertFalse(manager.shouldPreArm(4_000L, 6_000L))
    assertFalse(manager.canCrossfade(4_000L, 6_000L))
    assertTrue(manager.canCrossfade(6_000L, 6_000L))
    assertTrue(manager.canCrossfade(60_000L, 6_000L))
  }

  @Test
  fun testPreArmDoesNotTriggerAfterTrackRanOut() {
    val manager = AdaptivePreArmManager()
    assertFalse(manager.shouldPreArm(0L, 6_000L))
    assertFalse(manager.shouldPreArm(-1_000L, 6_000L))
    assertFalse(manager.canCrossfade(-1_000L, 6_000L))
  }
}
