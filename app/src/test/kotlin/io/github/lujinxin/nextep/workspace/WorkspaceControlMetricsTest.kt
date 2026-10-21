package io.github.lujinxin.nextep.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceControlMetricsTest {
    @Test
    fun fullAndButtonLayoutsKeepEnoughHeightForTheWholeIconRow() {
        for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132))) {
            val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, insets)
            val controlHeight = profile.geometry.topHeight
            assertEquals(426, controlHeight)
            assertTrue(profile.geometry.contentTop < 470)
            val rowHeight = controlHeight - (WorkspaceControlMetrics.STATUS_HEIGHT_DP +
                WorkspaceControlMetrics.ACTION_HEIGHT_DP + WorkspaceControlMetrics.CONTENT_BOTTOM_PADDING_DP) * 3
            assertTrue(rowHeight >= (WorkspaceControlMetrics.APP_ICON_SIZE_DP +
                WorkspaceControlMetrics.APP_BOTTOM_PADDING_DP) * 3)
        }
    }

    @Test
    fun oppositeLandscapeControlsHaveTheSameCompactThickness() {
        for (rotation in listOf(1, 3)) {
            val g = WorkspaceLayoutProfile.create(2354, 1080, 480, WorkspaceNavigationInsets(), rotation).geometry
            assertEquals(426, g.topHeight)
        }
    }
}
