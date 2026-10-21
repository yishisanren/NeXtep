package io.github.lujinxin.nextep.systemui

import io.github.lujinxin.nextep.systemui.BottomCornerGestureDetector.Result.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomCornerGestureDetectorTest {
    private class Trace(
        val width: Int = 400,
        val height: Int = 900,
        val density: Float = 1f,
    ) {
        val detector = BottomCornerGestureDetector(density)
        fun event(
            action: Int, x: Float, y: Float, time: Long = 100L, pointers: Int = 1,
            w: Int = width, h: Int = height, rotation: Int = 0,
        ) = detector.observe(action, x, y, pointers, time, w, h, rotation)
    }

    @Test fun bothCornersWorkInPortraitAndBothLandscapeRotationsAtDifferentDensities() {
        for (density in listOf(1f, 2f, 3f)) {
            for ((w, h, rotation) in listOf(Triple(400, 900, 0), Triple(900, 400, 1), Triple(900, 400, 3))) {
                for (left in listOf(true, false)) {
                    val t = Trace((w * density).toInt(), (h * density).toInt(), density)
                    val x = if (left) 20f * density else t.width - 20f * density
                    val y = t.height - 20f * density
                    assertEquals(OBSERVING, t.event(0, x, y, rotation = rotation))
                    assertEquals(CONFIRMED, t.event(2,
                        x + (if (left) 40f else -40f) * density, y - 40f * density,
                        200L, rotation = rotation))
                }
            }
        }
    }

    @Test fun exactDistanceThresholdConfirms() {
        val t = Trace()
        t.event(0, 20f, 880f)
        assertEquals(OBSERVING, t.event(2, 51f, 849f))
        assertEquals(CONFIRMED, t.event(2, 52f, 848f))
    }

    @Test fun shortSwipeAndTapDoNotConfirm() {
        val t = Trace()
        t.event(0, 20f, 880f)
        assertEquals(OBSERVING, t.event(2, 40f, 860f))
        assertEquals(ABANDONED, t.event(1, 40f, 860f))
        t.event(0, 20f, 880f)
        assertEquals(ABANDONED, t.event(1, 20f, 880f))
    }

    @Test fun topCornersSideAndBottomCenterAreIgnored() {
        for ((x, y) in listOf(20f to 20f, 380f to 20f, 20f to 700f, 200f to 880f)) {
            val t = Trace()
            assertEquals(IGNORED, t.event(0, x, y))
            assertEquals(IGNORED, t.event(2, x + 40f, y - 40f))
        }
    }

    @Test fun cornerStartBoundaryIsInclusiveAndCannotBeEnteredMidSwipe() {
        val t = Trace()
        assertEquals(OBSERVING, t.event(0, 72f, 828f))
        assertEquals(CONFIRMED, t.event(2, 104f, 796f))
        assertEquals(IGNORED, t.event(0, 73f, 828f))
        assertEquals(IGNORED, t.event(2, 50f, 850f))
        assertEquals(IGNORED, t.event(0, 20f, 827f))
    }

    @Test fun straightHorizontalAndHomeSwipesAreAbandoned() {
        for ((x, y) in listOf(80f to 880f, 20f to 820f)) {
            val t = Trace()
            t.event(0, 20f, 880f)
            assertEquals(ABANDONED, t.event(2, x, y))
            assertEquals(IGNORED, t.event(2, 100f, 800f))
        }
    }

    @Test fun outwardAndDownwardSwipesAreAbandonedFromEitherSide() {
        for ((start, end) in listOf(60f to 20f, 340f to 380f)) {
            val t = Trace()
            t.event(0, start, 850f)
            assertEquals(ABANDONED, t.event(2, end, 810f))
            t.event(0, start, 850f)
            assertEquals(ABANDONED, t.event(2, start, 890f))
        }
    }

    @Test fun diagonalSlopeLimitsAreAcceptedButFlatPathsAreNot() {
        for ((dx, dy) in listOf(32f to 64f, 64f to 32f)) {
            val t = Trace()
            t.event(0, 20f, 880f)
            assertEquals(CONFIRMED, t.event(2, 20f + dx, 880f - dy))
        }
        val t = Trace()
        t.event(0, 20f, 880f)
        assertEquals(ABANDONED, t.event(1, 100f, 850f))
    }

    @Test fun secondPointerCancelsAndLiftingItDoesNotRearm() {
        val t = Trace()
        t.event(0, 20f, 880f)
        assertEquals(ABANDONED, t.event(5, 25f, 875f, pointers = 2))
        assertEquals(IGNORED, t.event(6, 30f, 870f, pointers = 2))
        assertEquals(IGNORED, t.event(2, 60f, 840f))
    }

    @Test fun cancelNeverConfirmsEvenAtTheTarget() {
        val t = Trace()
        t.event(0, 20f, 880f)
        assertEquals(ABANDONED, t.event(3, 80f, 820f))
        assertEquals(IGNORED, t.event(2, 80f, 820f))
    }

    @Test fun timeoutAndBackwardTimestampsCancel() {
        for (time in listOf(99L, 1301L)) {
            val t = Trace()
            t.event(0, 20f, 880f)
            assertEquals(ABANDONED, t.event(2, 60f, 840f, time))
        }
        val t = Trace()
        t.event(0, 20f, 880f)
        assertEquals(CONFIRMED, t.event(2, 60f, 840f, 1300L))
    }

    @Test fun viewportAndRotationChangesCancelEvenWhenDimensionsStayTheSame() {
        for ((w, h, rotation) in listOf(Triple(900, 400, 1), Triple(400, 900, 2), Triple(401, 900, 0))) {
            val t = Trace()
            t.event(0, 20f, 880f)
            assertEquals(ABANDONED, t.event(2, 60f, 840f, w = w, h = h, rotation = rotation))
        }
    }

    @Test fun invalidCoordinatesAndPointerCountsFailClosed() {
        for ((x, y) in listOf(Float.NaN to 840f, Float.POSITIVE_INFINITY to 840f,
            -1f to 840f, 401f to 840f, 60f to 901f)) {
            val t = Trace()
            t.event(0, 20f, 880f)
            assertEquals(ABANDONED, t.event(2, x, y))
        }
        val t = Trace()
        t.event(0, 20f, 880f)
        assertEquals(ABANDONED, t.event(2, 60f, 840f, pointers = 0))
    }

    @Test fun resetAndRepeatedMovesDoNotToggleTwice() {
        val t = Trace()
        t.event(0, 20f, 880f)
        t.detector.reset()
        assertEquals(IGNORED, t.event(2, 60f, 840f))
        t.event(0, 20f, 880f)
        assertEquals(CONFIRMED, t.event(2, 60f, 840f))
        assertEquals(IGNORED, t.event(2, 80f, 820f))
        assertEquals(IGNORED, t.event(1, 80f, 820f))
        assertEquals(OBSERVING, t.event(0, 380f, 880f))
        assertEquals(CONFIRMED, t.event(2, 340f, 840f))
    }

    @Test fun finalUpSampleCanCompleteAQuickSwipe() {
        val t = Trace()
        t.event(0, 20f, 880f)
        assertEquals(CONFIRMED, t.event(1, 60f, 840f, 200L))
        assertEquals(IGNORED, t.event(2, 80f, 820f))
    }

    @Test fun moveWithoutDownAndInvalidDisplaysCannotStartRecognition() {
        val t = Trace()
        assertEquals(IGNORED, t.event(2, 60f, 840f))
        assertEquals(IGNORED, t.event(0, 20f, 880f, w = 0))
        assertEquals(IGNORED, t.event(0, 20f, 880f, h = -1))
    }
}
