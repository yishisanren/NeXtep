package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSettlementTest {
    private var time = 0L
    private val queue = java.util.ArrayDeque<() -> Unit>()
    private val settlement = WorkspaceSettlement({ delay, action -> queue.add { time += delay; action() } }, { time })

    @Test fun shellCanDeliverSurfacesBetweenPollsAndCompletionHappensOnce() {
        var surfaceReady = false
        var completed = 0
        settlement.begin({ surfaceReady }) { assertTrue(it.isSuccess); completed++ }
        assertEquals(0, completed)
        queue.remove().invoke()
        surfaceReady = true
        queue.remove().invoke()
        assertEquals(1, completed)
        assertTrue(queue.isEmpty())
    }

    @Test fun failedExchangeTimesOutWithoutBlockingTheCaller() {
        var failure: Throwable? = null
        settlement.begin({ false }) { failure = it.exceptionOrNull() }
        assertNull(failure)
        while (queue.isNotEmpty()) queue.remove().invoke()
        assertTrue(failure is java.util.concurrent.TimeoutException)
        assertTrue(time in 1500..1532)
    }

    @Test fun closeOrNewExchangeInvalidatesOldCallbacks() {
        settlement.begin({ error("old poll must not run") }) { fail("old completion") }
        settlement.cancel()
        var done = false
        settlement.begin({ true }) { done = true }
        while (queue.isNotEmpty()) queue.remove().invoke()
        assertTrue(done)
    }

    @Test fun thrownSurfaceFailureIsReportedOnce() {
        var completed = 0
        settlement.begin({ error("surface released") }) { assertTrue(it.isFailure); completed++ }
        while (queue.isNotEmpty()) queue.remove().invoke()
        assertEquals(1, completed)
    }
}
