package io.github.lujinxin.nextep.framework

import org.junit.Assert.*
import org.junit.Test

class LaidOutFrameTest {
    private val white = 0xFFFFFFFF.toInt()
    private val gray = 0xFF606060.toInt()

    private fun frame(width: Int = 12, height: Int = 20, pixel: (Int, Int) -> Int) =
        IntArray(width * height) { pixel(it % width, it / width) }

    @Test fun pageWithDetailBelowTheHeaderIsLaidOut() {
        assertTrue(looksLaidOut(frame { x, y -> if ((x + y) % 3 == 0) gray else white }, 12, 20))
    }

    @Test fun staleHeaderOverABlankPageIsNotLaidOut() {
        assertFalse(looksLaidOut(frame { x, y -> if (y < 5 && x % 2 == 0) gray else white }, 12, 20))
    }

    @Test fun bareEdgesWithoutABufferAreNotLaidOut() {
        val detailed = frame { x, y -> if (x >= 8) 0 else if ((x + y) % 3 == 0) gray else white }
        assertFalse(looksLaidOut(detailed, 12, 20))
    }

    @Test fun emptyCaptureIsNotLaidOut() {
        assertFalse(looksLaidOut(IntArray(0), 0, 0))
    }

    @Test fun droppedVideoPictureIsMostlyBlack() {
        val black = 0xFF000000.toInt()
        assertTrue(mostlyBlack(frame { x, y -> if (y == 19 && x < 2) white else black }))
        assertFalse(mostlyBlack(frame { x, y -> if (y > 15) gray else black }))
        assertFalse(mostlyBlack(IntArray(0)))
    }

    @Test fun lowerHalfTellsASettledPageFromAChangingOne() {
        val page = frame { x, y -> if ((x + y) % 3 == 0) gray else white }
        val lower = lowerHalfLuminance(page, 12, 20)
        assertEquals(12 * 10, lower.size)
        assertTrue(sameFrame(lower, lowerHalfLuminance(page.copyOf(), 12, 20)))
        val moved = lowerHalfLuminance(frame { x, y -> if ((x + y) % 3 == 1) gray else white }, 12, 20)
        assertFalse(sameFrame(lower, moved))
        assertFalse(sameFrame(IntArray(0), IntArray(0)))
    }
}
