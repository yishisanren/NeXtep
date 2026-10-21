package io.github.lujinxin.nextep.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGeometryTest {
    @Test
    fun oppositeLandscapeDirectionsProjectTheSameSelectedButtonLayout() {
        for (side in SidebarSide.entries) for (navigationWidth in listOf(0, 132)) {
            val topOnRight = WorkspaceGeometry.forDisplay(2354, 1080, side, 3f,
                WorkspaceNavigationInsets(left = navigationWidth), displayRotation = 3)
            val topOnLeft = WorkspaceGeometry.forDisplay(2354, 1080, side, 3f,
                WorkspaceNavigationInsets(right = navigationWidth), displayRotation = 1)
            assertEquals(2354 - topOnRight.controlLeft - topOnRight.controlWidth, topOnLeft.controlLeft)
            assertEquals(2354 - topOnRight.contentRight, topOnLeft.contentLeft)
            assertEquals(1080 - topOnRight.contentBottom, topOnLeft.contentTop)
            assertEquals(2354 - topOnRight.sidebarLeft - topOnRight.sidebarPhysicalWidth, topOnLeft.sidebarLeft)
            assertEquals(1080 - topOnRight.sidebarTop - topOnRight.sidebarPhysicalHeight, topOnLeft.sidebarTop)
            assertEquals(side == SidebarSide.LEFT, topOnRight.isSidebarAtTop)
            assertEquals(!topOnRight.isSidebarAtTop, topOnLeft.isSidebarAtTop)
            assertEquals(topOnRight.panelSidebarSide, topOnLeft.panelSidebarSide)
            assertEquals(side, topOnRight.panelSidebarSide)
            assertEquals(topOnRight.contentScale, topOnLeft.contentScale, 0f)
            assertEquals(90f, topOnRight.panelRotationDegrees, 0f)
            assertEquals(-90f, topOnLeft.panelRotationDegrees, 0f)
            assertTrue(topOnRight != topOnLeft)
        }
    }

    @Test
    fun repeatedReverseLandscapeRotationsNeverChangeTheButtonDiagramOrSelection() {
        for (side in SidebarSide.entries) {
            val reference = WorkspaceGeometry.forDisplay(2354, 1080, side, 3f, displayRotation = 3)
            for (rotation in listOf(1, 3, 1, 3, 1, 3)) {
                val current = reference.copy(displayRotation = rotation)
                assertEquals(side, current.sidebarSide)
                assertEquals(reference.panelSidebarSide, current.panelSidebarSide)
                val diagramAtTop = if (rotation == 1) {
                    current.panelSidebarSide == SidebarSide.RIGHT
                } else current.panelSidebarSide == SidebarSide.LEFT
                assertEquals(diagramAtTop, current.isSidebarAtTop)
                assertEquals(reference.sidebarPhysicalHeight, current.sidebarPhysicalHeight)
            }
        }
    }

    @Test
    fun mainViewportPreservesTheAppAspectRatioAboveThreeButtonNavigation() {
        for (side in SidebarSide.entries) {
            val geometry = WorkspaceGeometry.forDisplay(
                1080, 2354, side, 3f, WorkspaceNavigationInsets(bottom = 132),
            )
            assertEquals(
                "The safe app canvas must not be squeezed into a different aspect ratio",
                geometry.availableWidth.toFloat() / geometry.availableHeight,
                (geometry.availableWidth * geometry.contentScale) / (geometry.availableHeight * geometry.contentScale),
                1f / geometry.contentHeight,
            )
        }
    }

    @Test
    fun uniformTransformCropsNavigationExactlyOnceInEitherOrientation() {
        val devices = listOf(1080 to 2354, 2354 to 1080, 1600 to 2560, 2560 to 1600)
        val insets = listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132),
            WorkspaceNavigationInsets(left = 132), WorkspaceNavigationInsets(right = 132),
            WorkspaceNavigationInsets(bottom = 48))
        for ((width, height) in devices) for (navigation in insets) for (side in SidebarSide.entries) {
            for (rotation in if (width > height) listOf(1, 3) else listOf(0)) {
                val g = WorkspaceGeometry.forDisplay(width, height, side, 3f, navigation, rotation)
                val left = g.availableLeft * g.contentScale + g.contentTranslationX
                val right = g.availableRight * g.contentScale + g.contentTranslationX
                val top = g.availableTop * g.contentScale + g.contentTranslationY
                val bottom = g.availableBottom * g.contentScale + g.contentTranslationY
                assertTrue(left >= g.contentLeft - .6f && right <= g.contentRight + .6f)
                assertTrue(top >= g.contentTop - .6f && bottom <= g.contentBottom + .6f)
                assertEquals(left - g.contentLeft, g.contentRight - right, .6f)
                assertEquals(top - g.contentTop, g.contentBottom - bottom, .6f)
                assertEquals(g.availableWidth.toFloat() / g.availableHeight, (right-left)/(bottom-top), .0001f)
                assertTrue(kotlin.math.abs((right-left)-g.contentWidth) < .6f ||
                    kotlin.math.abs((bottom-top)-g.contentHeight) < .6f)
                assertTrue(left >= g.availableLeft - .01f && right <= g.availableRight + .01f)
                assertTrue(top >= g.availableTop - .01f && bottom <= g.availableBottom + .01f)
            }
        }
    }
}
