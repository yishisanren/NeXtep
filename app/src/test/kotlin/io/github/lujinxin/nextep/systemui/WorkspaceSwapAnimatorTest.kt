package io.github.lujinxin.nextep.systemui

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WorkspaceSwapAnimatorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun preview(animator: WorkspaceSwapAnimator) = animator.javaClass.getDeclaredField("overlay")
        .apply { isAccessible = true }.get(animator) as? View
    private fun WorkspaceSwapAnimator(context: android.content.Context) = WorkspaceSwapAnimator(context) { WorkbenchAppearance.DARK }

    @Test fun contentExpansionUsesOneSecureOverlayAndReleasesEveryAppPreviewOnCancel() {
        val animator = WorkspaceSwapAnimator(context)
        val bitmaps = List(5) { Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888) }
        val frames = bitmaps.mapIndexed { index, bitmap -> WorkspaceSwapAnimator.Frame(bitmap,
            RectF(260f, index * 80f, 300f, index * 80f + 80), RectF(0f, 100f, 240f, 580f)) }
        var moved = false
        animator.animateFrames(frames, frames.map { it.from }, 320, 640) { moved = true }
        val view = checkNotNull(preview(animator))
        assertTrue((view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        animator.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(moved)
        assertTrue(bitmaps.all { it.isRecycled })
        assertNull(preview(animator))
    }

    @Test fun protectedPreviewWaitsForCompletionInsteadOfAFixedDelay() {
        val animator = WorkspaceSwapAnimator(context)
        val source = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        var moves = 0
        var reveal: (() -> Unit)? = null
        val small = RectF(260f, 100f, 300f, 180f)
        val main = RectF(0f, 100f, 240f, 580f)
        animator.animate(source, null, false, small, small, main, 320, 640) {
            moves++
            reveal = it
        }
        val view = checkNotNull(preview(animator))
        assertTrue((view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals(1, moves)
        assertSame(view, preview(animator))
        assertFalse(source.isRecycled)
        checkNotNull(reveal).invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertNull(preview(animator))
        assertTrue(source.isRecycled)
    }

    @Test fun cancelBeforeTheMoveDoesNotLaunchAnAppAndReleasesFrames() {
        val animator = WorkspaceSwapAnimator(context)
        val source = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        val rect = RectF(0f, 0f, 40f, 80f)
        animator.animate(source, null, false, rect, rect, rect, 320, 640) { fail("Cancelled transition moved a task") }
        animator.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(preview(animator))
        assertTrue(source.isRecycled)
    }

    @Test fun unavailableCaptureStillProvidesMotionAndCannotRevealANewerTransition() {
        val animator = WorkspaceSwapAnimator(context)
        val rect = RectF(0f, 0f, 40f, 80f)
        var oldReveal: (() -> Unit)? = null
        animator.animate(null, null, true, rect, rect, RectF(0f, 0f, 240f, 480f), 320, 640) { oldReveal = it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertNotNull(preview(animator))
        animator.cancel()
        animator.animate(null, null, true, rect, rect, rect, 320, 640) { }
        val newer = preview(animator)
        checkNotNull(oldReveal).invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertSame(newer, preview(animator))
        animator.cancel()
    }

    @Test fun emptySlotPreviewsMatchTheLiveCardInEitherAppearanceInsteadOfAFixedDarkBox() {
        for (theme in WorkbenchAppearance.entries) {
            val card = RectF(10f, 10f, 70f, 130f)
            val cover = RectF(100f, 0f, 160f, 120f)
            val view = SwapPreviewView(context, listOf(WorkspaceSwapAnimator.Frame(null, card, card, empty = true)),
                listOf(cover), theme)
            view.progress = 1f
            view.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 200, 150)
            val image = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            assertEquals(theme.card, image.getPixel(16, 16))
            assertEquals(theme.opaquePanel, image.getPixel(130, 60))
            val live = io.github.lujinxin.nextep.display.TaskSwitcherView(context, 0).apply { setAppearance(theme) }
            assertEquals(theme.card, (live.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
            // The plus badge is present in the moving card, not only after the fade.
            val centre = image.getPixel(40, 70)
            assertNotEquals(theme.card, centre)
            assertTrue(android.graphics.Color.alpha(centre) == 255)
        }
    }
}
