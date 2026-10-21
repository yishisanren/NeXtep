package io.github.lujinxin.nextep.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SurfaceTransactionGuardTest {
    @Test
    fun inactiveWorkspacePassesThroughAndNewOwnershipEnablesFittingImmediately() {
        var owned = false
        var appended = 0
        var committed = 0
        val guard = SurfaceTransactionGuard<Any>(shouldCompose = { owned }) { appended++ }
        repeat(1000) { guard.intercept(Any()) { committed++ } }
        assertEquals(1000, committed)
        assertEquals(0, appended)
        owned = true
        guard.intercept(Any()) { committed++ }
        assertEquals(1, appended)
        owned = false
        guard.intercept(Any()) { committed++ }
        assertEquals(1002, committed)
        assertEquals(1, appended)
    }

    private data class Frame(val scale: Float, val y: Float, val alpha: Float = 1f)

    private class Transaction(private val surface: MutableMap<Int, Frame>) {
        private val writes = linkedMapOf<Int, Frame>()
        fun write(taskId: Int, frame: Frame) { writes[taskId] = frame }
        fun fit(taskId: Int, scale: Float, y: Float) {
            val native = writes[taskId] ?: surface.getValue(taskId)
            writes[taskId] = native.copy(scale = scale, y = y)
        }
        fun apply() { surface.putAll(writes) }
    }

    @Test
    fun nativeAnimationAndLateFinishNeverExposeFullscreenBetweenCorrections() {
        val surface = mutableMapOf(1 to Frame(.72f, 625f))
        val target = Frame(.72f, 625f)
        val guard = SurfaceTransactionGuard<Transaction> { it.fit(1, target.scale, target.y) }
        // WM queues its finish transaction before NeXtep has fitted the task.
        val finish = Transaction(surface).apply { write(1, Frame(1f, 0f)) }
        repeat(4) {
            val nativeFrame = Transaction(surface).apply { write(1, Frame(1f, 0f)) }
            guard.intercept(nativeFrame) { nativeFrame.apply() }
            assertEquals("Native frame must already fit at commit", target, surface[1])
        }
        guard.intercept(finish) { finish.apply() }
        assertEquals("Late finish must not revive fullscreen", target, surface[1])
    }

    @Test
    fun releasingOwnershipAllowsNativeRestoreImmediately() {
        val surface = mutableMapOf(1 to Frame(.72f, 625f))
        var owned = true
        val guard = SurfaceTransactionGuard<Transaction> {
            if (owned) it.fit(1, .72f, 625f)
        }
        owned = false
        val restore = Transaction(surface).apply { write(1, Frame(1f, 0f)) }
        guard.intercept(restore) { restore.apply() }
        assertEquals(Frame(1f, 0f), surface[1])
    }

    @Test
    fun ownedAnimationFrameIsNotReplacedByItsFinalTarget() {
        val surface = mutableMapOf(1 to Frame(.72f, 625f))
        val guard = SurfaceTransactionGuard<Transaction> { it.fit(1, .72f, 625f) }
        val intermediate = Frame(.70f, 635f)
        val frame = Transaction(surface).apply { write(1, intermediate) }
        guard.ownedTransaction { guard.intercept(frame) { frame.apply() } }
        assertEquals(intermediate, surface[1])
    }

    @Test
    fun nativeVisibilityAndOtherTasksArePreserved() {
        val surface = mutableMapOf(1 to Frame(.72f, 625f), 2 to Frame(1f, 0f))
        val guard = SurfaceTransactionGuard<Transaction> {
            it.fit(1, .72f, 625f)
        }
        val frame = Transaction(surface).apply {
            write(1, Frame(1f, 0f, .4f))
            write(2, Frame(.5f, 20f))
        }
        guard.intercept(frame) { frame.apply() }
        assertEquals(Frame(.72f, 625f, .4f), surface[1])
        assertEquals(Frame(.5f, 20f), surface[2])
    }

    @Test
    fun delegatedApplyOverloadsComposeOnce() {
        val surface = mutableMapOf(1 to Frame(1f, 0f))
        var appended = 0
        val guard = SurfaceTransactionGuard<Transaction> {
            appended++
            it.fit(1, .72f, 625f)
        }
        val frame = Transaction(surface)
        guard.intercept(frame) { guard.intercept(frame) { frame.apply() } }
        assertEquals(1, appended)
        assertEquals(Frame(.72f, 625f), surface[1])
    }

    @Test
    fun anOwnedWriteOnAnotherThreadDoesNotBypassTheNativeCommitGuard() {
        val surface = mutableMapOf(1 to Frame(1f, 0f))
        val guard = SurfaceTransactionGuard<Transaction> { it.fit(1, .72f, 625f) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = Thread {
            guard.ownedTransaction {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        writer.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val frame = Transaction(surface)
            guard.intercept(frame) { frame.apply() }
            assertEquals(Frame(.72f, 625f), surface[1])
        } finally {
            release.countDown()
            writer.join(2000)
        }
    }

    @Test
    fun aFailedAppenderStillCommitsAndDoesNotDisableLaterFitting() {
        val surface = mutableMapOf(1 to Frame(1f, 0f))
        var broken = true
        var reported = 0
        val guard = SurfaceTransactionGuard<Transaction>(onFailure = { reported++ }) {
            if (broken) error("unsupported native transaction")
            it.fit(1, .72f, 625f)
        }
        val native = Transaction(surface).apply { write(1, Frame(1f, 5f)) }
        guard.intercept(native) { native.apply() }
        assertEquals(Frame(1f, 5f), surface[1])
        assertEquals(1, reported)
        broken = false
        val later = Transaction(surface)
        guard.intercept(later) { later.apply() }
        assertEquals(Frame(.72f, 625f), surface[1])
    }
}
