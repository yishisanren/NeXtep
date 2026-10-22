package io.github.lujinxin.nextep.display

import android.graphics.RectF
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SlotTouchForwarderTest {
    @Test fun playerTouchesUseTheFittedImageCoordinatesAndDoNotModifyTheSourceEvent() {
        val received = mutableListOf<MotionEvent>()
        val forwarder = SlotTouchForwarder { received += MotionEvent.obtain(it); true }
        val bounds = RectF(10f, 20f, 210f, 120f)
        val down = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 110f, 70f, 0)
        assertTrue(forwarder.touch(down, bounds, 2000, 1000))
        assertEquals(1000f, received.single().x, .01f)
        assertEquals(500f, received.single().y, .01f)
        assertEquals(110f, down.x, .01f)
        forwarder.cancel()
        assertEquals(MotionEvent.ACTION_CANCEL, received.last().actionMasked)
        forwarder.cancel()
        assertEquals(2, received.size)
        down.recycle(); received.forEach { it.recycle() }
    }

    @Test fun touchesOutsideTheImageAndOrphanMovesNeverReachTheApp() {
        var calls = 0
        val forwarder = SlotTouchForwarder { calls++; true }
        val bounds = RectF(10f, 20f, 210f, 120f)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(1, 1, action, 5f, 5f, 0)
            assertFalse(forwarder.touch(event, bounds, 2000, 1000))
            event.recycle()
        }
        assertEquals(0, calls)
    }

    @Test fun aFailedMoveCancelsTheAcceptedGestureInsteadOfLeavingThePlayerPressed() {
        val actions = mutableListOf<Int>()
        val forwarder = SlotTouchForwarder { actions += it.actionMasked; it.actionMasked != MotionEvent.ACTION_MOVE }
        val bounds = RectF(0f, 0f, 100f, 100f)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(1, 1, action, 50f, 50f, 0)
            forwarder.touch(event, bounds, 1000, 1000)
            event.recycle()
        }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL), actions)
    }
}
