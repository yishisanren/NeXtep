package io.github.lujinxin.nextep.systemui

import org.junit.Assert.*
import org.junit.Test

class FrameCoalescerTest {
    @Test fun aBurstOfTouchSamplesUsesOnlyTheLatestPointOncePerFrame() {
        val queue = mutableListOf<Runnable>()
        var point = 0
        var rendered = -1
        var frames = 0
        val coalescer = FrameCoalescer({ queue.add(it) }, { queue.remove(it) }) {
            frames++
            rendered = point
        }
        repeat(120) { frame ->
            repeat(8) { point++; coalescer.request() }
            assertEquals(1, queue.size)
            queue.removeAt(0).run()
            assertEquals(point, rendered)
            assertEquals(frame + 1, frames)
        }
        assertEquals(120, frames)
        assertEquals(960, point)
    }

    @Test fun droppingOrCancellingRemovesThePendingHoverUpdate() {
        val queue = mutableListOf<Runnable>()
        var updates = 0
        val coalescer = FrameCoalescer({ queue.add(it) }, { queue.remove(it) }) { updates++ }
        coalescer.request()
        val late = queue.single()
        coalescer.cancel()
        coalescer.cancel()
        assertTrue(queue.isEmpty())
        late.run()
        assertEquals(0, updates)
        coalescer.request()
        queue.removeAt(0).run()
        assertEquals(1, updates)
    }

    @Test fun anUpdateCanRequestItsNextFrame() {
        val queue = mutableListOf<Runnable>()
        var updates = 0
        lateinit var coalescer: FrameCoalescer
        coalescer = FrameCoalescer({ queue.add(it) }, { queue.remove(it) }) {
            if (++updates == 1) coalescer.request()
        }
        coalescer.request()
        queue.removeAt(0).run()
        assertEquals(1, queue.size)
        queue.removeAt(0).run()
        assertEquals(2, updates)
    }
}
