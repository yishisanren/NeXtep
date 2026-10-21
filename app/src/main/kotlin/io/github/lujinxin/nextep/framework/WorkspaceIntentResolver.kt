package io.github.lujinxin.nextep.framework

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager

/** A system chooser is not the target app's router Activity. */
internal object WorkspaceIntentResolver {
    fun resolve(manager: PackageManager, source: Intent): Intent? = runCatching {
        val target = source.component?.packageName ?: source.`package` ?: return@runCatching null
        fun valid(info: ActivityInfo) = info.packageName == target && info.exported &&
            info.enabled && info.applicationInfo?.enabled != false
        val info = source.component?.let { manager.getActivityInfo(it, 0).takeIf(::valid) }
            ?: manager.resolveActivity(source, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.takeIf(::valid)
            ?: manager.queryIntentActivities(source, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo }.firstOrNull(::valid)
            ?: return@runCatching null
        Intent(source).setComponent(ComponentName(info.packageName, info.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()
}
