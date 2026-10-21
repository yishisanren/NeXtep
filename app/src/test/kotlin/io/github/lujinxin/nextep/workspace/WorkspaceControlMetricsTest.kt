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
            val mediaHeight = WorkspaceControlMetrics.mediaHeightPixels(controlHeight, 3f)
            val rowHeight = controlHeight - (WorkspaceControlMetrics.STATUS_HEIGHT_DP +
                WorkspaceControlMetrics.ACTION_HEIGHT_DP + WorkspaceControlMetrics.CONTENT_BOTTOM_PADDING_DP) * 3 - mediaHeight
            assertTrue(rowHeight >= (WorkspaceControlMetrics.APP_ICON_SIZE_DP +
                WorkspaceControlMetrics.APP_BOTTOM_PADDING_DP) * 3)
            assertTrue("A clipped media card must move to tools", mediaHeight == 0 || mediaHeight >= 24 * 3)
        }
    }

    @Test
    fun largerControlAreaKeepsThePreferredMediaHeight() {
        assertEquals(68 * 3, WorkspaceControlMetrics.mediaHeightPixels(624, 3f))
        assertEquals(68 * 2, WorkspaceControlMetrics.mediaHeightPixels(642, 2f))
    }
}
