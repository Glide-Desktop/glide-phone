package org.mindenit.glidephone

import android.content.Context
import android.content.Intent
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** SharePlugin's dynamic shortcuts: one share target per connected computer. */
internal object DirectShare {
    private const val CATEGORY = "org.mindenit.glidephone.category.SHARE_TARGET"

    private fun build(context: Context, id: String, label: String, previous: ShortcutInfoCompat? = null) =
        ShortcutInfoCompat.Builder(context, id)
            .setIntent(previous?.intent ?: Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_menu_share))
            .setShortLabel(label)
            .setCategories(previous?.categories ?: setOf(CATEGORY))
            .setLocusId(previous?.locusId ?: LocusIdCompat(id))
            .setLongLived(true)
            .build()

    fun connected(context: Context, id: String, name: String) {
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, build(context, id, name)) }
    }

    /** A pinned shortcut stays (marked unreachable); others go away, like SharePlugin.onDestroy. */
    fun disconnected(context: Context, id: String) = runCatching {
        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).firstOrNull { it.id == id } ?: return@runCatching
        if (shortcut.isPinned) {
            ShortcutManagerCompat.updateShortcuts(context, listOf(build(context, id, "${shortcut.shortLabel} (недоступний)", shortcut)))
        } else {
            ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(id))
        }
    }

    fun forget(context: Context, id: String) {
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(id)) }
    }
}
