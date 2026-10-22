package io.github.lujinxin.nextep.systemui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.LinearLayout
import io.github.lujinxin.nextep.config.SettingsRepository
import io.github.lujinxin.nextep.config.WorkspaceConfigContract
import io.github.lujinxin.nextep.config.WorkspaceConfigProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FavoriteAppsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun nativeSettingsDialogsKeepTheActivityWindowContext() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val themed = io.github.lujinxin.nextep.config.SystemDialogs.context(activity, true) as android.view.ContextThemeWrapper
        assertSame(activity, themed.baseContext)
        val dialog = android.app.AlertDialog.Builder(themed).setTitle("Favorites").setPositiveButton("Save", null).create()
        dialog.show()
        assertTrue(dialog.isShowing)
        assertNotNull(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE))
        dialog.dismiss()
        activity.finish()
    }
    @Test fun theStripShowsExactlyTheSelectionWithNoEditorTileAndSurvivesAnEmptySelection() {
        val strip = TopAppStripView(context, {}, {}, { _, _ -> false }, { _, _ -> false })
        val render = strip.javaClass.getDeclaredMethod("renderApps", List::class.java).apply { isAccessible = true }
        val row = strip.javaClass.getDeclaredField("appRow").apply { isAccessible = true }.get(strip) as LinearLayout
        val entries = (0..11).map { TopAppRepository.AppEntry("app$it", "App $it",
            ColorDrawable(Color.BLUE), Intent(), 0) }
        render.invoke(strip, entries)
        assertEquals(10, row.childCount)
        render.invoke(strip, entries.take(3).reversed())
        assertEquals(3, row.childCount)
        assertEquals(listOf("App 2", "App 1", "App 0"), (0 until 3).map { row.getChildAt(it).contentDescription })
        render.invoke(strip, emptyList<TopAppRepository.AppEntry>())
        assertEquals(0, row.childCount)
    }

    @Test fun savedSelectionKeepsOrderCapsAtTenAndAllowsAnExplicitEmptyList() {
        SettingsRepository(context).setTopApps((0..15).map { "app$it" })
        assertEquals((0..9).map { "app$it" }, SettingsRepository(context).topBarSettings().appComponents)
        SettingsRepository(context).setTopApps(listOf("b", "a", "b"))
        assertEquals(listOf("b", "a"), SettingsRepository(context).topBarSettings().appComponents)
        SettingsRepository(context).setTopApps(emptyList())
        assertTrue(SettingsRepository(context).topBarSettings().appComponents.isEmpty())
        val provider = Robolectric.buildContentProvider(WorkspaceConfigProvider::class.java).create().get()
        assertNull(provider.call("set_top_apps", null, android.os.Bundle()))
        assertEquals(emptyList<String>(), provider.call("query", null, null)!!
            .getStringArrayList(WorkspaceConfigContract.EXTRA_APPS))
    }
}
