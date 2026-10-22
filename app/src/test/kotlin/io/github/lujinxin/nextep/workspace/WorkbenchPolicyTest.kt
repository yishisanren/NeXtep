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

    @Test fun aFullSidebarReplacesOldAppsButReservesLaterMatchesFirst() {
        assertEquals(listOf(3, 0), WorkspaceSearchPlacement.plan(
            listOf("a", "b", "c"), listOf("video", "other", "reserved", null)))
        assertEquals(listOf(1, 0, 3), WorkspaceSearchPlacement.plan(
            listOf("pdd", "dy", "jd", "tb"), listOf("jd", "old", "video", "tb")))
        assertEquals(listOf(0, 1, 2), WorkspaceSearchPlacement.plan(
            listOf("main", "a", "b", "c"), listOf("old0", "old1", "old2", "old3")))
        assertThrows(IllegalStateException::class.java) {
            WorkspaceSearchPlacement.plan(listOf("a", "b", "c"), listOf("video", "other", "reserved", null),
                unavailable = setOf(0, 1, 2))
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
        val request = WorkspaceSceneRequest(42, 0, 10_000, true, fullscreen = true)
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

    @Test fun onlyFreshLandscapeFullscreenMediaCanEnterTheTopPlayer() {
        val request = WorkspaceSceneRequest(42, 0, 10_000, true, fullscreen = true)
        assertTrue(request.shouldPin(10_500, playingMedia = true))
        assertFalse(request.copy(orientation = 1).shouldPin(10_500, true))
        assertFalse(request.copy(fullscreen = false).shouldPin(10_500, true))
        assertFalse(request.shouldPin(10_500, playingMedia = false))
        assertFalse(request.shouldPin(13_001, playingMedia = true))
        assertFalse(request.copy(fullscreen = false).holdRotation(10_500))
        assertFalse(WorkspaceSceneRequest.decode("42,0,10000,1")!!.shouldPin(10_500, true))
        assertNull(WorkspaceSceneRequest.decode("42,0,10000,1,2"))
    }
}
