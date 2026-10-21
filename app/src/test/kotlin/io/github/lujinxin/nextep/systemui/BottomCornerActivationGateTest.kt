package io.github.lujinxin.nextep.systemui

import org.junit.Assert.*
import org.junit.Test

class BottomCornerActivationGateTest {
    @Test fun ordinaryTouchStreamsNeverQueryScreenOrKeyguardState() {
        var checks = 0
        val detector = BottomCornerGestureDetector(1f) { checks++; true }
        repeat(1000) { stream ->
            detector.observe(0, 500f, 1000f, 1, stream * 100L, 1080, 2400)
            repeat(10) { detector.observe(2, 510f, 990f, 1, stream * 100L + it, 1080, 2400) }
            detector.observe(1, 510f, 990f, 1, stream * 100L + 20, 1080, 2400)
        }
        assertEquals(0, checks)
    }

    @Test fun aValidCornerChecksOnlyAtStartAndConfirmation() {
        var checks = 0
        val detector = BottomCornerGestureDetector(1f) { checks++; true }
        detector.observe(0, 20f, 2380f, 1, 0, 1080, 2400)
        repeat(25) { detector.observe(2, 20f + it, 2380f - it, 1, it + 1L, 1080, 2400) }
        assertEquals(1, checks)
        assertEquals(BottomCornerGestureDetector.Result.CONFIRMED,
            detector.observe(2, 80f, 2320f, 1, 100, 1080, 2400))
        detector.observe(1, 100f, 2300f, 1, 120, 1080, 2400)
        assertEquals(2, checks)
    }

    @Test fun lockingDuringTheSwipePreventsActivationAndReleasesTheSession() {
        var unlocked = true
        val detector = BottomCornerGestureDetector(1f) { unlocked }
        detector.observe(0, 20f, 2380f, 1, 0, 1080, 2400)
        unlocked = false
        assertEquals(BottomCornerGestureDetector.Result.ABANDONED,
            detector.observe(2, 80f, 2320f, 1, 100, 1080, 2400))
        assertFalse(detector.isObserving)
    }

    @Test fun unlockingDoesNotResumeATouchThatStartedWhileLocked() {
        var unlocked = false
        val detector = BottomCornerGestureDetector(1f) { unlocked }
        assertEquals(BottomCornerGestureDetector.Result.ABANDONED,
            detector.observe(0, 20f, 2380f, 1, 0, 1080, 2400))
        unlocked = true
        assertEquals(BottomCornerGestureDetector.Result.IGNORED,
            detector.observe(2, 80f, 2320f, 1, 100, 1080, 2400))
    }
}
