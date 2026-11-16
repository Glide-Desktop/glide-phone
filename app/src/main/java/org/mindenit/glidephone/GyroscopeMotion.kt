package org.mindenit.glidephone

/**
 * Converts angular velocity (radians/second) into presenter pointer deltas.
 * The first sample establishes a timestamp; long pauses are not integrated.
 */
internal class GyroscopeMotion {
    private var lastNanos: Long? = null
    private var accumulatedSeconds = 0f
    private var horizontal = 0f
    private var vertical = 0f

    fun reset() {
        lastNanos = null
        accumulatedSeconds = 0f
        horizontal = 0f
        vertical = 0f
    }

    fun sample(timestampNanos: Long, x: Float, z: Float): Pair<Float, Float>? {
        if (!x.isFinite() || !z.isFinite()) {
            reset()
            return null
        }
        val previous = lastNanos
        lastNanos = timestampNanos
        if (previous == null) return null
        val deltaNanos = timestampNanos - previous
        if (deltaNanos <= 0L || deltaNanos > 100_000_000L) {
            accumulatedSeconds = 0f
            horizontal = 0f
            vertical = 0f
            return null
        }
        val seconds = deltaNanos / 1_000_000_000f
        accumulatedSeconds += seconds
        horizontal += -z * seconds * SENSITIVITY
        vertical += -x * seconds * SENSITIVITY
        if (accumulatedSeconds < 1f / 30f) return null
        val result = horizontal.coerceIn(-MAX_DELTA, MAX_DELTA) to vertical.coerceIn(-MAX_DELTA, MAX_DELTA)
        accumulatedSeconds = 0f
        horizontal = 0f
        vertical = 0f
        return result.takeIf { kotlin.math.abs(it.first) > DEAD_ZONE || kotlin.math.abs(it.second) > DEAD_ZONE }
    }

    private companion object {
        const val SENSITIVITY = 1.5f
        const val MAX_DELTA = 0.25f
        const val DEAD_ZONE = 0.001f
    }
}
