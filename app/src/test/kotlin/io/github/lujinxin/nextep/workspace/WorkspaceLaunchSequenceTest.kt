package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceLaunchSequenceTest {
    private class Fixture {
        val scheduled = ArrayDeque<() -> Unit>()
        val started = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val visible = mutableSetOf<String>()
        val busy = mutableListOf<Boolean>()
        var result: Result<Unit>? = null
        var startFailure: String? = null
        var moveFailure: String? = null
        val flow = WorkspaceLaunchSequence<String>(
            { _, action -> scheduled.addLast(action) },
            { if (it == startFailure) throw SecurityException(); started += it },
            { it in visible },
            { if (it == moveFailure) error("move rejected"); committed += it },
            { busy += it },
        )
        fun step() { scheduled.removeFirst().invoke() }
        fun drain() { repeat(200) { if (scheduled.isNotEmpty()) step() } }
        fun begin(vararg apps: String) = flow.begin(apps.toList()) { result = it }
    }

    @Test fun threeSearchAppsAreObservedAndPlacedBeforeTheNextStarts() {
        val f = Fixture()
        assertTrue(f.begin("jd-main", "tb-slot0", "mt-slot1").isSuccess)
        f.step()
        assertEquals(listOf("jd-main"), f.started)
        repeat(3) { f.step() }
        assertTrue(f.committed.isEmpty())
        f.visible += "jd-main"; f.step()
        assertEquals(listOf("jd-main"), f.committed)
        assertEquals(listOf("jd-main"), f.started)
        f.step(); assertEquals(listOf("jd-main", "tb-slot0"), f.started)
        f.visible += "tb-slot0"; f.step(); f.step()
        f.visible += "mt-slot1"; f.drain()
        assertEquals(listOf("jd-main", "tb-slot0", "mt-slot1"), f.committed)
        assertTrue(f.result!!.isSuccess)
        assertEquals(listOf(true, false), f.busy)
        assertFalse(f.flow.running)
    }

    @Test fun anExistingForegroundAppCanBeCommittedWithoutWaitingForANewTaskId() {
        val f = Fixture(); f.visible += "existing"
        f.begin("existing"); f.drain()
        assertEquals(listOf("existing"), f.committed)
        assertTrue(f.result!!.isSuccess)
    }

    @Test fun aStartDenialStopsLaterAppsAndReleasesTheInputLock() {
        val f = Fixture(); f.startFailure = "denied"
        f.begin("denied", "untouched"); f.drain()
        val error = f.result!!.exceptionOrNull() as WorkspaceLaunchSequence.Failure
        assertEquals(0, error.index)
        assertEquals(0, error.completed)
        assertTrue(error.cause is SecurityException)
        assertTrue(f.committed.isEmpty())
        assertTrue(f.started.isEmpty())
        assertEquals(listOf(true, false), f.busy)
    }

    @Test fun partialFailurePreservesCompletedTasksAndDoesNotClaimWholeBatchSuccess() {
        val f = Fixture(); f.visible += "main"; f.startFailure = "second"
        f.begin("main", "second", "third"); f.drain()
        val error = f.result!!.exceptionOrNull() as WorkspaceLaunchSequence.Failure
        assertEquals(1, error.index)
        assertEquals(1, error.completed)
        assertEquals(listOf("main"), f.committed)
        assertFalse("third" in f.started)
    }

    @Test fun aTaskThatNeverAppearsTimesOutWithoutStartingTheNextApp() {
        val f = Fixture(); f.begin("missing", "untouched"); f.drain()
        val error = f.result!!.exceptionOrNull() as WorkspaceLaunchSequence.Failure
        assertTrue(error.cause is java.util.concurrent.TimeoutException)
        assertEquals(listOf("missing"), f.started)
        assertEquals(listOf(true, false), f.busy)
    }

    @Test fun aFailedWindowMoveIsReportedAsFailureAfterActivityStart() {
        val f = Fixture(); f.visible += "app"; f.moveFailure = "app"
        f.begin("app"); f.drain()
        assertTrue(f.result!!.isFailure)
        assertTrue(f.committed.isEmpty())
    }

    @Test fun cancelAndRetryCannotLetOldPollsMoveTheNewSelection() {
        val f = Fixture(); f.begin("old", "later"); f.step()
        f.flow.cancel()
        assertTrue(f.result!!.exceptionOrNull() is java.util.concurrent.CancellationException)
        f.visible += "new"; f.begin("new"); f.drain()
        assertEquals(listOf("old", "new"), f.started)
        assertEquals(listOf("new"), f.committed)
        assertTrue(f.result!!.isSuccess)
    }

    @Test fun duplicateSubmissionCannotReplaceTheActiveBatch() {
        val f = Fixture(); f.begin("first")
        assertTrue(f.begin("second").isFailure)
        f.visible += "first"; f.drain()
        assertEquals(listOf("first"), f.started)
        assertTrue(f.result!!.isSuccess)
    }

    @Test fun desktopPlusCanRetargetOrCancelWithoutReusingAStaleDisplay() {
        val selection = WorkspaceSlotSelection()
        assertEquals(WorkspaceSlotSelection.Target(0, 10), selection.toggle(0, 10))
        assertEquals(WorkspaceSlotSelection.Target(3, 13), selection.toggle(3, 13))
        assertNull(selection.toggle(3, 13))
        selection.toggle(3, 14)
        assertNotEquals(WorkspaceSlotSelection.Target(3, 13), selection.target)
        selection.clear()
        assertNull(selection.target)
    }

    @Test fun aRejectedSchedulerCannotLeaveTheWorkspaceBusy() {
        val busy = mutableListOf<Boolean>()
        val flow = WorkspaceLaunchSequence<String>({ _, _ -> error("scheduler stopped") }, {}, { true }, {}, { busy += it })
        assertTrue(flow.begin(listOf("app")) {}.isFailure)
        assertFalse(flow.running)
        assertEquals(listOf(true, false), busy)
    }
}
