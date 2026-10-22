package io.github.lujinxin.nextep.systemui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.LinearLayout
import io.github.lujinxin.nextep.config.TopTextStyle
import io.github.lujinxin.nextep.workspace.SidebarSide
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiReuseTest {
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    @Test fun screenStateResetCancelsRecognitionAndInvalidatesQueuedActivation() {
        val detector = BottomCornerGestureDetector(1f)
        detector.observe(0, 20f, 2380f, 1, 0, 1080, 2400)
        val monitor = SystemUiBottomCornerGestureMonitor
        val callback = monitor.javaClass.getDeclaredField("resetGesture").apply { isAccessible = true }
        val previous = callback.get(monitor)
        val before = field(monitor, "generation") as Int
        try {
            callback.set(monitor, { detector.reset() })
            monitor.reset()
            assertFalse(detector.isObserving)
            assertNotEquals(before, field(monitor, "generation"))
            assertEquals(BottomCornerGestureDetector.Result.IGNORED,
                detector.observe(2, 80f, 2320f, 1, 100, 1080, 2400))
        } finally { callback.set(monitor, previous) }
    }

    @Test fun unchangedWallpaperReusesItsDrawableAndBlurRecordingWithoutInvalidation() {
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val drawable = WorkspaceWallpaperBackdrop.crop(image, 0, 0, 50)
        val node = field(drawable, "blurredWallpaper")
        assertNotNull(node)
        var invalidations = 0
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { invalidations++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, whenMs: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        repeat(100) { assertSame(drawable, WorkspaceWallpaperBackdrop.crop(image, 0, 0, 50, drawable)) }
        assertEquals(0, invalidations)
        WorkspaceWallpaperBackdrop.crop(image, 2, 3, 80, drawable)
        assertSame(node, field(drawable, "blurredWallpaper"))
        assertEquals(1, invalidations)
        WorkspaceWallpaperBackdrop.crop(image, 2, 3, 0, drawable)
        assertNull(field(drawable, "blurredWallpaper"))
    }

    @Test fun reusedWallpaperDrawsTheNewImageAndCropPosition() {
        val image = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            setPixel(2, 0, Color.BLUE)
            setPixel(3, 0, Color.BLUE)
            setPixel(2, 1, Color.BLUE)
            setPixel(3, 1, Color.BLUE)
        }
        val output = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val drawable = WorkspaceWallpaperBackdrop.crop(image, 0, 0, 0)
        drawable.setBounds(0, 0, 2, 2)
        drawable.draw(Canvas(output))
        assertEquals(Color.RED, output.getPixel(0, 0))
        WorkspaceWallpaperBackdrop.crop(image, 2, 0, 0, drawable)
        drawable.draw(Canvas(output))
        assertEquals(Color.BLUE, output.getPixel(0, 0))
        val replacement = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        WorkspaceWallpaperBackdrop.crop(replacement, 0, 0, 0, drawable)
        drawable.draw(Canvas(output))
        assertEquals(Color.GREEN, output.getPixel(0, 0))
        WorkspaceWallpaperBackdrop.crop(null, 0, 0, 0, drawable)
        output.eraseColor(Color.TRANSPARENT)
        drawable.draw(Canvas(output))
        assertEquals(Color.TRANSPARENT, output.getPixel(0, 0))
    }

    @Test fun catalogueRefreshKeepsExistingTilesAndUpdatesClickTargetsAfterReordering() {
        val clicked = mutableListOf<Intent>()
        val strip = TopAppStripView(RuntimeEnvironment.getApplication(),
            { clicked.add(it) }, {}, { _, _ -> false }, { _, _ -> false })
        val render = strip.javaClass.getDeclaredMethod("renderApps", List::class.java).apply { isAccessible = true }
        val row = field(strip, "appRow") as LinearLayout
        fun entry(key: String, label: String = key, version: Int = 0) = TopAppRepository.AppEntry(
            key, label, ColorDrawable(Color.BLUE),
            Intent().setComponent(ComponentName("test.$key", "test.$key.Main")).putExtra("version", version), 0)
        render.invoke(strip, listOf(entry("a"), entry("b")))
        val first = row.getChildAt(0)
        val second = row.getChildAt(1)
        repeat(20) { render.invoke(strip, listOf(entry("a"), entry("b"))) }
        assertSame(first, row.getChildAt(0))
        assertSame(second, row.getChildAt(1))
        render.invoke(strip, listOf(entry("b"), entry("a", "renamed", 2)))
        assertSame(second, row.getChildAt(0))
        assertSame(first, row.getChildAt(1))
        assertEquals("renamed", first.contentDescription)
        first.performClick()
        assertEquals(2, clicked.single().getIntExtra("version", -1))
        render.invoke(strip, listOf(entry("a")))
        assertEquals(1, row.childCount)
        assertSame(first, row.getChildAt(0))
        render.invoke(strip, emptyList<TopAppRepository.AppEntry>())
        assertEquals(0, row.childCount)
        render.invoke(strip, listOf(entry("b")))
        assertEquals(1, row.childCount)
        assertEquals("b", row.getChildAt(0).contentDescription)
    }

    @Test fun genericFontDoesNotEnumerateSystemFontFiles() {
        val fonts = TopTextStyle.javaClass.getDeclaredField("systemFonts\$delegate")
            .apply { isAccessible = true }.get(TopTextStyle) as Lazy<*>
        assertFalse(fonts.isInitialized())
        TopTextStyle.typeface(TopTextStyle.DEFAULT_FAMILY, false)
        TopTextStyle.typeface("sans-serif", true)
        assertFalse(fonts.isInitialized())
    }
}
