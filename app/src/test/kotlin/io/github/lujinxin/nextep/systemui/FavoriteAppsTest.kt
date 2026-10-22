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
    @Test fun theEleventhPositionEditsExactlyTheVisibleOrderAndSurvivesAnEmptySelection() {
        var edited = emptyList<String>()
        val strip = TopAppStripView(context, {}, {}, { _, _ -> false }, { _, _ -> false },
            onEditAppsRequested = { edited = it })
        val render = strip.javaClass.getDeclaredMethod("renderApps", List::class.java).apply { isAccessible = true }
        val row = strip.javaClass.getDeclaredField("appRow").apply { isAccessible = true }.get(strip) as LinearLayout
        val entries = (0..11).map { TopAppRepository.AppEntry("app$it", "App $it",
            ColorDrawable(Color.BLUE), Intent(), 0) }
        render.invoke(strip, entries)
        assertEquals(11, row.childCount)
        row.getChildAt(10).performClick()
        assertEquals(entries.take(10).map { it.key }, edited)
        render.invoke(strip, entries.take(10).reversed())
        row.getChildAt(10).performClick()
        assertEquals(entries.take(10).reversed().map { it.key }, edited)
        render.invoke(strip, emptyList<TopAppRepository.AppEntry>())
        assertEquals(1, row.childCount)
        row.getChildAt(0).performClick()
        assertTrue(edited.isEmpty())
    }

    @Test fun savingFavoritesAtomicallyEnablesManualOrderAndKeepsAnExplicitEmptyList() {
        assertTrue(SettingsRepository(context).setFavoriteApps((0..15).map { "app$it" }))
        val reopened = SettingsRepository(context).topBarSettings()
        assertTrue(reopened.manualAppOrder)
        assertEquals((0..9).map { "app$it" }, reopened.appComponents)
        assertTrue(SettingsRepository(context).setFavoriteApps(emptyList()))
        assertTrue(SettingsRepository(context).topBarSettings().manualAppOrder)
        assertTrue(SettingsRepository(context).topBarSettings().appComponents.isEmpty())
    }

    @Test fun theProviderRejectsOverLimitAndMalformedWritesBeforeChangingPreferences() {
        val provider = Robolectric.buildContentProvider(WorkspaceConfigProvider::class.java).create().get()
        SettingsRepository(context).setFavoriteApps(listOf("kept"))
        for (keys in listOf((0..10).map { "app$it" }, listOf("one\ntwo"))) {
            val extras = android.os.Bundle().apply { putStringArrayList(WorkspaceConfigContract.EXTRA_APPS, ArrayList(keys)) }
            assertThrows(IllegalArgumentException::class.java) { provider.call("set_top_apps", null, extras) }
            assertEquals(listOf("kept"), SettingsRepository(context).topBarSettings().appComponents)
        }
    }
}
