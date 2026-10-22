package io.github.lujinxin.nextep.launcher

import android.content.Intent

internal object WorkspaceLauncherAnimationPolicy {
    fun useStaticSource(active: Boolean, intent: Intent?): Boolean = active &&
        intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
        intent.component != null
}
