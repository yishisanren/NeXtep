package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import io.github.lujinxin.nextep.apps.LauncherAppRepository

class TopAppRepository(context: Context) {
    data class AppEntry(
        val key: String,
        val label: CharSequence,
        val icon: Drawable,
        val launchIntent: Intent,
        val lastTimeUsed: Long,
    )

    private val applicationContext = context.applicationContext ?: context
    private val apps = LauncherAppRepository(applicationContext)

    /** Only the apps chosen in settings, in their chosen order; usage never fills the row. */
    fun load(preferredComponents: List<String>): List<AppEntry> {
        val order = preferredComponents.distinct().take(MAX_VISIBLE_APPS)
            .withIndex().associate { (index, value) -> value to index }
        if (order.isEmpty()) return emptyList()
        return apps.load().asSequence()
            .filter { it.key in order }
            .map { app -> AppEntry(app.key, app.label, app.icon, app.launchIntent(), 0L) }
            .sortedBy { order.getValue(it.key) }
            .toList()
    }

    private companion object {
        const val MAX_VISIBLE_APPS = 10
    }
}
