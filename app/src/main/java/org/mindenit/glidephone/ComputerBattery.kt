package org.mindenit.glidephone

/** Glide-only alerts about the computer's battery (KDE Connect only shows the level). */
internal object ComputerBattery {
    const val LOW_PERCENT = 15

    enum class Change { LOW, FULL, CHARGING }

    /** What changed between two reports; never anything on the first report after connecting. */
    fun change(before: Pair<Int, Boolean>?, charge: Int, charging: Boolean): Change? {
        val (oldCharge, wasCharging) = before ?: return null
        return when {
            !charging && charge <= LOW_PERCENT && (oldCharge > LOW_PERCENT || wasCharging) -> Change.LOW
            charging && charge >= 100 && oldCharge < 100 -> Change.FULL
            charging && !wasCharging -> Change.CHARGING
            else -> null
        }
    }
}
