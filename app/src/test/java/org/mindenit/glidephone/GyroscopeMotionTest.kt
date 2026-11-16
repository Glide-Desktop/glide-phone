package org.mindenit.glidephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GyroscopeMotionTest {
    @Test
    fun integratesMotionAndThrottlesPackets() {
        val motion = GyroscopeMotion()
        assertNull(motion.sample(1_000_000_000, x = 2f, z = 1f))
        assertNull(motion.sample(1_020_000_000, x = 2f, z = 1f))
        val result = requireNotNull(motion.sample(1_040_000_000, x = 2f, z = 1f))
        assertEquals(-0.06f, result.first, 0.0001f)
        assertEquals(-0.12f, result.second, 0.0001f)
    }

    @Test
    fun pausesAndInvalidSamplesCannotCausePointerJumps() {
        val motion = GyroscopeMotion()
        assertNull(motion.sample(1_000_000_000, 1f, 1f))
        assertNull(motion.sample(1_500_000_000, 1f, 1f))
        assertNull(motion.sample(1_520_000_000, Float.NaN, 1f))
        assertNull(motion.sample(1_540_000_000, 1f, 1f))
        assertNull(motion.sample(1_560_000_000, 1f, 1f))
        val result = requireNotNull(motion.sample(1_580_000_000, 1f, 1f))
        assertEquals(-0.06f, result.first, 0.0001f)
        assertEquals(-0.06f, result.second, 0.0001f)
    }

    @Test
    fun capsLargeMovementsAndSuppressesSensorNoise() {
        val motion = GyroscopeMotion()
        motion.sample(0, 100f, -100f)
        val result = requireNotNull(motion.sample(40_000_000, 100f, -100f))
        assertEquals(0.25f, result.first, 0.0001f)
        assertEquals(-0.25f, result.second, 0.0001f)
        motion.reset()
        motion.sample(0, 0.001f, 0.001f)
        assertNull(motion.sample(40_000_000, 0.001f, 0.001f))
    }
}
