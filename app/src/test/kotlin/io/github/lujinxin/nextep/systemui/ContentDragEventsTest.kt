package io.github.lujinxin.nextep.systemui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Intent
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import io.github.lujinxin.nextep.display.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentDragEventsTest {
    private fun event(action: Int, description: ClipDescription?, result: Boolean = false): DragEvent {
        val method = DragEvent::class.java.declaredMethods.first { it.name == "obtain" &&
            it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType }
        val args = method.parameterTypes.mapIndexed { index, type -> when {
            index == 0 -> action
            type == Int::class.javaPrimitiveType -> 0
            type == Float::class.javaPrimitiveType -> 10f
            type == Boolean::class.javaPrimitiveType -> result
            type == ClipDescription::class.java -> description
            else -> null
        } }.toTypedArray()
        return method.apply { isAccessible = true }.invoke(null, *args) as DragEvent
    }

    @Test fun nativeDragNeedsNoClipDataBeforeDropAndReportsEnterAndFinalResult() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = TaskSwitcherView(activity, 2)
        activity.setContentView(view)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 200, 400)
        view.showState(SlotState.Occupied(20, 42, ComponentName("target.app", "Main")))
        var starts = 0; var enters = 0; var ends = 0
        var accepted: Boolean? = null
        view.setListener(object : TaskSwitcherView.Listener {
            override fun onSlotClicked(slotIndex: Int) {}
            override fun onIntentDropped(slotIndex: Int, intent: Intent) = false
            override fun onSlotDragStarting(drag: SlotTaskDrag) = false
            override fun onSlotDragStarted(source: TaskSwitcherView, drag: SlotTaskDrag) = false
            override fun onSlotDragTouch(source: View, event: MotionEvent) = false
            override fun onSlotBackgroundSwiped(drag: SlotTaskDrag) = false
            override fun onSlotDragEnded() {}
            override fun onSlotDropped(slotIndex: Int, drag: SlotTaskDrag) = false
            override fun onContentDragStarted() { starts++ }
            override fun onContentDragEntered(slotIndex: Int) { assertEquals(2, slotIndex); enters++ }
            override fun onContentDragEnded(result: Boolean) { accepted = result; ends++ }
        })
        val description = ClipDescription("", arrayOf("image/jpeg"))
        val events = listOf(event(DragEvent.ACTION_DRAG_STARTED, description),
            event(DragEvent.ACTION_DRAG_LOCATION, description),
            event(DragEvent.ACTION_DRAG_ENDED, null, true))
        try { events.forEach { view.dispatchDragEvent(it) } }
        finally { events.forEach { DragEvent::class.java.getDeclaredMethod("recycle").invoke(it) } }
        assertEquals(1, starts); assertEquals(1, enters); assertEquals(1, ends)
        assertEquals(true, accepted)
    }
}
