package io.github.lujinxin.nextep.launcher

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceLauncherAnimationPolicyTest {
    @Test fun onlyExplicitAppIconsInsideTheWorkspaceLoseTheNativeLaunchSource() {
        val app = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName("example.app", "example.app.Main"))
        assertTrue(WorkspaceLauncherAnimationPolicy.useStaticSource(true, app))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(false, app))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(true, Intent(app).setAction(Intent.ACTION_VIEW)))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(true, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(true, Intent(app).setComponent(null)))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(true, null))
    }
}
