package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkbenchPolicyTest {
    @Test fun capturePermissionNeverSurvivesAWorkspaceSession() {
        val policy = WorkspaceCapturePolicy()
        assertFalse(policy.captureAllowed)
        repeat(5) {
            policy.allowForSession()
            assertTrue(policy.captureAllowed)
            policy.reset()
            assertFalse(policy.captureAllowed)
        }
        assertFalse(WorkspaceCapturePolicy().captureAllowed)
    }

    @Test fun searchReusesMatchingAppsAndReservesDistinctEmptySlots() {
        assertEquals(listOf(2, 1, 3), WorkspaceSearchPlacement.plan(
            listOf("main", "jd", "taobao", "pdd"), listOf("video", "taobao", null, null)))
        assertEquals(listOf(1, 2), WorkspaceSearchPlacement.plan(
            listOf("jd", "taobao", "mt"), listOf("jd", null, null, "video")))
    }

    @Test fun aFullSidebarIsRejectedBeforeAnySearchCanStart() {
        assertThrows(IllegalStateException::class.java) {
            WorkspaceSearchPlacement.plan(listOf("a", "b", "c"), listOf("video", "other", "reserved", null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSearchPlacement.plan(listOf("a", "a"), listOf(null, null, null, null))
        }
    }

    @Test fun promotingTheFirstSearchReleasesOnlyItsOwnSlot() {
        assertEquals(listOf(0, 2, 3), WorkspaceSearchPlacement.plan(listOf("pdd", "dy", "jd", "tb"),
            listOf("pdd", "video", "jd", "tb"), promotedSlot = 0))
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSearchPlacement.plan(listOf("pdd", "dy"), listOf("video", null), promotedSlot = 0)
        }
    }

    @Test fun sceneRotationHoldExpiresAndRequiresANativeReevaluation() {
        val request = WorkspaceSceneRequest(42, 0, 10_000, true)
        assertEquals(request, WorkspaceSceneRequest.decode(request.encode()))
        assertTrue(request.holdRotation(11_500))
        assertFalse(request.holdRotation(11_501))
        assertFalse(request.holdRotation(9_999))
        assertFalse(request.copy(canHold = false).holdRotation(10_000))
        assertFalse(request.copy(orientation = 1).holdRotation(10_000))
        assertTrue(request.fresh(13_000))
        assertFalse(request.fresh(13_001))
        assertNull(WorkspaceSceneRequest.decode("42,0,10000,2"))
        assertNull(WorkspaceSceneRequest.decode("-1,0,10000,1"))
        assertNull(WorkspaceSceneRequest.decode("42,999,10000,1"))
    }
}
