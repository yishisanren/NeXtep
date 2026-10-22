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

    fun load(preferredComponents: List<String> = emptyList(),
        manualOrder: Boolean = preferredComponents.isNotEmpty()): List<AppEntry> {
        val catalogue = apps.load()
        val usage = catalogue.map { it.userId }.distinct().associateWith { userId ->
            apps.contextForUser(userId)?.let(::recentUsage).orEmpty()
        }
        return catalogue.asSequence()
            .map { app ->
                AppEntry(
                    key = app.key,
                    label = app.label,
                    icon = app.icon,
                    launchIntent = app.launchIntent(),
                    lastTimeUsed = usage[app.userId]?.get(app.component.packageName) ?: 0L,
                )
            }
            .toList()
            .let { entries ->
                if (!manualOrder) {
                    entries.sortedWith(
                        compareByDescending<AppEntry> { it.lastTimeUsed }
                            .thenBy { it.label.toString().lowercase() },
                    )
                } else {
                    val order = preferredComponents.withIndex()
                        .associate { (index, value) -> value to index }
                    entries.filter { entry ->
                        entry.key in order
                    }.sortedBy { entry ->
                        order[entry.key] ?: Int.MAX_VALUE
                    }
                }
            }
            .take(MAX_VISIBLE_APPS)
    }

    private fun recentUsage(context: Context): Map<String, Long> = runCatching {
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return@runCatching emptyMap()
        val end = System.currentTimeMillis()
        manager.queryAndAggregateUsageStats(end - USAGE_WINDOW_MS, end)
            .mapValues { (_, stats) -> stats.lastTimeUsed }
    }.getOrDefault(emptyMap())

    private companion object {
        const val MAX_VISIBLE_APPS = 10
        const val USAGE_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
