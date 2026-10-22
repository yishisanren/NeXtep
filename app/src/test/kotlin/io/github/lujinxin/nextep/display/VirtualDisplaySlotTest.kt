package io.github.lujinxin.nextep.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VirtualDisplaySlotTest {
    private val slot = VirtualDisplaySlot(RuntimeEnvironment.getApplication(), 1)

    @Test fun parkingWaitsForTheMovedOutputLayerAndStillRunsOnceAfterTheTimeout() {
        var runs = 0
        slot.whenOutputBound { runs += 1 }
        assertEquals(0, runs)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(599))
        assertEquals(0, runs)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, runs)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(1, runs)
    }

    @Test fun aLaterRequestReplacesThePendingOne() {
        var first = 0
        var second = 0
        slot.whenOutputBound { first += 1 }
        slot.whenOutputBound { second += 1 }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test fun repeatingTheProtectedStateKeepsTheOutputLayer() {
        val layer = slot.view.textureView
        slot.setCaptureAllowed(false)
        slot.setCaptureAllowed(false)
        assertSame(layer, slot.view.textureView)
        assertEquals(false, slot.view.isCaptureAllowed())
    }
}
