package io.github.lujinxin.nextep.systemui

import android.app.usage.UsageStatsManager
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

    /** Apps chosen in settings come first in their order; recently used apps fill the rest of the row. */
    fun load(preferredComponents: List<String>): List<AppEntry> {
        val catalogue = apps.load()
        val usage = catalogue.map { it.userId }.distinct().associateWith { userId ->
            apps.contextForUser(userId)?.let(::recentUsage).orEmpty()
        }
        val entries = catalogue.map { app ->
            AppEntry(app.key, app.label, app.icon, app.launchIntent(),
                usage[app.userId]?.get(app.component.packageName) ?: 0L)
        }
        return arrange(entries, preferredComponents)
    }

    private fun recentUsage(context: Context): Map<String, Long> = runCatching {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return@runCatching emptyMap()
        val end = System.currentTimeMillis()
        manager.queryAndAggregateUsageStats(end - USAGE_WINDOW_MS, end).mapValues { (_, stats) -> stats.lastTimeUsed }
    }.getOrDefault(emptyMap())

    companion object {
        private const val MAX_VISIBLE_APPS = 10
        private const val USAGE_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L

        /** Chosen apps in their order, then the rest by last use; apps never used stay out. */
        internal fun arrange(entries: List<AppEntry>, preferredComponents: List<String>): List<AppEntry> {
            val order = preferredComponents.distinct().take(MAX_VISIBLE_APPS)
                .withIndex().associate { (index, value) -> value to index }
            val chosen = entries.filter { it.key in order }.sortedBy { order.getValue(it.key) }
            val recent = entries.filter { it.key !in order && it.lastTimeUsed > 0L }
                .sortedWith(compareByDescending<AppEntry> { it.lastTimeUsed }.thenBy { it.label.toString().lowercase() })
            return (chosen + recent).take(MAX_VISIBLE_APPS)
        }
    }
}
