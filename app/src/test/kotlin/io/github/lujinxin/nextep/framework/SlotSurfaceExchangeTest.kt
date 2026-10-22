package io.github.lujinxin.nextep.framework

import android.graphics.Rect
import android.view.SurfaceControl
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [SlotSurfaceExchangeTest.LeashShadow::class,
    SlotSurfaceExchangeTest.TransactionShadow::class])
class SlotSurfaceExchangeTest {
    private val task = 42
    private val display = 20
    private val crop = Rect(0, 0, 1216, 2640)

    @Suppress("UNCHECKED_CAST")
    private fun map(name: String) = TaskSurfaceCompat.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(TaskSurfaceCompat) as MutableMap<Int, Any>

    private fun attachSlot(): SurfaceControl {
        val leash = org.robolectric.util.ReflectionHelpers.newInstance(SurfaceControl::class.java)
        TaskSurfaceCompat.observeTaskState(task,
            TaskInfoCompat.WindowState(display, crop, 1, 2, 476, null))
        map("observedLeashes")[task] = leash
        val type = Class.forName("${TaskSurfaceCompat::class.java.name}\$SlotSurface")
        map("slotSurfaces")[task] = type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(display, leash, crop)
        return leash
    }

    @After fun clear() {
        TaskSurfaceCompat.cancelDisplayExchange(task)
        TaskSurfaceCompat.forgetTaskSurface(task)
        TransactionShadow.scale = 0f
    }

    @Test fun destinationFitIsRequiredBeforeRevealAndLateShellFinishCannotUndoIt() {
        TaskSurfaceCompat.markDisplayExchange(task, display)
        val leash = attachSlot()
        // Ordinary polling must not interfere with the outgoing native transition.
        assertFalse(TaskSurfaceCompat.fitSlotSurface(task, display, crop).getOrThrow())
        assertTrue(TaskSurfaceCompat.fitSlotSurface(task, display, crop, finishExchange = true).getOrThrow())
        assertEquals(1f, TransactionShadow.scale, 0f)
        assertTrue("Keep native transition classification after fitting", TaskSurfaceCompat.isDisplayExchanging(task))
        SurfaceControl.Transaction().use { finish ->
            finish.setScale(leash, .7f, .7f)
            TaskSurfaceCompat.interceptTransactionApply(finish) { finish.apply() }
        }
        assertEquals("No old main scale may survive a late finish", 1f, TransactionShadow.scale, 0f)
    }

    @Test fun anotherMoveRevokesSlotOwnershipBeforeNativeReparenting() {
        val leash = attachSlot()
        TaskSurfaceCompat.markDisplayExchange(task, 0)
        assertFalse(map("slotSurfaces").containsKey(task))
        assertFalse(TaskSurfaceCompat.fitSlotSurface(task, display, crop, finishExchange = true).getOrThrow())
        SurfaceControl.Transaction().use { native ->
            native.setScale(leash, .8f, .8f)
            TaskSurfaceCompat.interceptTransactionApply(native) { native.apply() }
        }
        assertEquals(.8f, TransactionShadow.scale, 0f)
    }

    @Implements(SurfaceControl::class)
    class LeashShadow {
        @Implementation fun isValid() = true
    }

    @Implements(SurfaceControl.Transaction::class)
    class TransactionShadow {
        @RealObject lateinit var real: SurfaceControl.Transaction
        @Implementation fun setScale(surface: SurfaceControl, x: Float, y: Float): SurfaceControl.Transaction {
            assertEquals(x, y, 0f)
            scale = x
            return real
        }
        @Implementation fun setCrop(surface: SurfaceControl, crop: Rect?) = real
        @Implementation fun setPosition(surface: SurfaceControl, x: Float, y: Float) = real
        @Implementation fun apply() {}
        @Implementation fun close() {}
        companion object { var scale = 0f }
    }
}
