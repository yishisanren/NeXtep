package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSearchReadinessTest {
    @Test fun aRouterMayReplaceItsTaskButCannotClaimAnUnrelatedExistingTask() {
        val state = WorkspaceSearchReadiness()
        assertFalse(state.belongsToRequest(42, setOf(41, 42), setOf(43)))
        assertTrue(state.belongsToRequest(44, setOf(41, 42), setOf(43)))
        assertTrue(state.belongsToRequest(42, setOf(41, 42), setOf(42)))
    }
    @Test fun shortLivedJdRouterNeverCompletesASearch() {
        val state = WorkspaceSearchReadiness()
        for (now in listOf(0L, 150L, 600L, 1200L)) assertFalse(state.observe(42,
            "com.jingdong.app.mall.open.InterfaceActivity", true, now))
        assertFalse(state.observe(null, null, false, 1400))
    }

    @Test fun taobaoBrowserMustFinishRoutingBeforeTheProductSearchIsAccepted() {
        val state = WorkspaceSearchReadiness()
        assertFalse(state.observe(42, "com.taobao.browser.BrowserActivity", true, 0, true))
        assertFalse(state.observe(42, "com.taobao.browser.BrowserActivity", true, 900, true))
        val result = "com.taobao.search.uniform.SearchActivity"
        assertFalse(state.observe(42, result, true, 1100, true))
        assertFalse(state.observe(42, result, true, 1699, true))
        assertTrue(state.observe(42, result, true, 1700, true))
    }

    @Test fun resultTaskOrSurfaceReplacementRestartsTheObservationWindow() {
        val state = WorkspaceSearchReadiness()
        assertFalse(state.observe(42, "shop.Results", true, 0))
        assertFalse(state.observe(43, "shop.Results", true, 500))
        assertFalse(state.observe(43, "shop.Results", false, 900))
        assertFalse(state.observe(43, "shop.Results", true, 1000))
        assertTrue(state.observe(43, "shop.Results", true, 1600))
        assertFalse(state.observe(null, null, false, 1700))
        assertFalse(state.observe(43, "shop.Results", true, 1800))
    }

    @Test fun deliveryCanUseItsFinalBrowserPage() {
        val state = WorkspaceSearchReadiness()
        assertFalse(state.observe(42, "com.taobao.browser.BrowserActivity", true, 0))
        assertTrue(state.observe(42, "com.taobao.browser.BrowserActivity", true, 600))
    }
}
