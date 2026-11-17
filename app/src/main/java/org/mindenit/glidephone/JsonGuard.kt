package org.mindenit.glidephone

/**
 * Android's org.json parses recursively without a depth limit, so a line of 64 KB of `[[[[`
 * overflows the stack (a StackOverflowError, which `catch (Exception)` doesn't stop) and kills
 * the app. Every line from the network passes this cheap scan before it is parsed.
 */
internal object JsonGuard {
    const val MAX_DEPTH = 64

    /** True when brackets outside strings nest at most [maxDepth] deep. */
    fun shallow(text: String, maxDepth: Int = MAX_DEPTH): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > maxDepth) return false
                '}', ']' -> depth--
            }
        }
        return true
    }
}
