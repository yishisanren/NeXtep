package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceContentDragSessionTest {
    private var expanded: ((Boolean) -> Unit)? = null
    private var restored: (() -> Unit)? = null
    private val targets = mutableListOf<Int>()
    private var restores = 0
    private val session = WorkspaceContentDragSession(
        expand = { index, complete -> targets += index; expanded = complete; true },
        restore = { _, complete -> restores++; restored = complete },
    )

    @Test fun enteringStartsOneExpansionAndSuccessfulNativeDropKeepsTheNewLayout() {
        session.begin()
        session.begin() // Each interested card receives STARTED.
        session.enter(2)
        session.enter(1)
        assertEquals(listOf(2), targets)
        checkNotNull(expanded)(true)
        assertTrue(session.active)
        session.end(true)
        assertFalse(session.active)
        assertEquals(0, restores)
    }

    @Test fun earlyReleaseWaitsForMoveToFinishThenRestoresExactlyOnce() {
        session.begin(); session.enter(0)
        session.end(false)
        session.end(false)
        assertEquals(0, restores)
        checkNotNull(expanded)(true)
        assertEquals(1, restores)
        assertTrue(session.active)
        checkNotNull(restored)()
        session.end(false)
        assertFalse(session.active)
        assertEquals(1, restores)
    }

    @Test fun rejectedDropRestoresRatherThanSharingToTheOldSourceNowInTheSlot() {
        session.begin(); session.enter(3)
        checkNotNull(expanded)(true)
        session.end(false)
        assertEquals(1, restores)
        checkNotNull(restored)()
        assertFalse(session.active)
    }

    @Test fun noTargetOrFailedExpansionDoesNotExchangeAnotherTask() {
        session.begin(); session.end(false)
        assertTrue(targets.isEmpty())
        session.begin(); session.enter(1)
        checkNotNull(expanded)(false)
        session.end(false)
        assertFalse(session.active)
        assertEquals(0, restores)
    }

    @Test fun lockOrCloseInvalidatesCallbacksWithoutClearingANewerDrag() {
        session.begin(); session.enter(1)
        val old = checkNotNull(expanded)
        session.abandon()
        session.begin(); session.enter(2)
        old(true)
        assertTrue(session.active)
        checkNotNull(expanded)(true)
        session.end(true)
        assertFalse(session.active)
        assertEquals(0, restores)
    }

    @Test fun unavailableTargetIsIgnoredAndTheNextDragCanStart() {
        val unavailable = WorkspaceContentDragSession({ _, _ -> false }, { _, _ -> fail("No restore") })
        unavailable.begin(); unavailable.enter(0)
        assertFalse(unavailable.active)
        unavailable.begin()
        assertTrue(unavailable.active)
        unavailable.end(false)
        assertFalse(unavailable.active)
    }
}
