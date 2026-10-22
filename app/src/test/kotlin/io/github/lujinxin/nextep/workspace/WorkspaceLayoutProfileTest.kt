package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceLayoutProfileTest {
    @Test fun mainHasNoVerticalLetterboxAndVideoUsesTheEntireTopRegion() {
        for ((width, height) in listOf(1216 to 2640, 2640 to 1216, 1600 to 2560)) {
            for (rotation in if (width > height) listOf(1, 3) else listOf(0)) {
                for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 80))) {
                    val normal = WorkspaceLayoutProfile.create(width, height, 476, insets, rotation)
                    for (wideIndex in listOf(null, 0, 3)) {
                        val profile = normal.withWideSlot(wideIndex)
                        val g = profile.geometry
                        assertEquals(g.contentHeight.toFloat(), g.availableHeight * g.contentScale, 1f)
                        assertEquals(g.contentWidth.toFloat(), g.availableWidth * g.contentScale, 1f)
                        if (wideIndex != null) {
                            val inset = (WorkspaceControlMetrics.STATUS_HEIGHT_DP * 476 / 160f).toInt()
                            assertEquals(g.topHeight - inset, profile.slots[wideIndex].height)
                            assertEquals(profile.slots[wideIndex].height, profile.canvas(wideIndex).height)
                            // The three rail cards fill the rail width: no side slack beside the main window.
                            profile.slots.filterIndexed { i, _ -> i != wideIndex }.forEach { slot ->
                                assertTrue("rail card ${slot.width} in ${g.sidebarWidth}", slot.width >= g.sidebarWidth - profile.gapPixels - 1)
                            }
                            val railHeight = profile.slots.filterIndexed { i, _ -> i != wideIndex }.sumOf { it.height } + profile.gapPixels * 2
                            assertTrue(railHeight >= g.sidebarLogicalHeight - 12)
                        }
                        assertEquals(normal, profile.withWideSlot(null))
                    }
                }
            }
        }
    }

    @Test fun previousVideoRecordMigratesWithoutKeepingTheToolbarReserve() {
        val old = WorkspaceLayoutProfile(WorkspaceGeometry(1216, 2640, 1106, 237, SidebarSide.RIGHT), 476, 2, 1)
            .let { legacyFourSlotRecord(it, "9") }
        old[24] = (1106 - (WorkspaceControlMetrics.COMPACT_HEIGHT_DP * 476 / 160f).toInt()).toString()
        val migrated = checkNotNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
        val fresh = WorkspaceLayoutProfile.create(1216, 2640, 476, WorkspaceNavigationInsets()).withWideSlot(1)
        assertEquals(fresh.geometry.topHeight, migrated.geometry.topHeight)
        assertEquals(migrated.geometry.topHeight - (WorkspaceControlMetrics.STATUS_HEIGHT_DP * 476 / 160f).toInt(), migrated.slots[1].height)
        assertEquals(migrated, WorkspaceLayoutProfile.decode(migrated.encode()))
        old[24] = "1"
        assertNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
    }
    @Test
    fun everySlotFitsItsWholeCanvasInBothNavigationModesAndRotations() {
        for ((width, height, densityDpi) in listOf(
            Triple(1080, 2354, 480), Triple(2354, 1080, 480),
            Triple(1600, 2560, 320), Triple(2560, 1600, 320),
        )) {
            for (insets in listOf(WorkspaceNavigationInsets(),
                if (width < height) WorkspaceNavigationInsets(bottom = 132)
                else WorkspaceNavigationInsets(right = 132))) {
                val profile = WorkspaceLayoutProfile.create(width, height, densityDpi, insets)
                for (side in SidebarSide.entries) {
                    val positioned = profile.copy(geometry = profile.geometry(side))
                    positioned.slots.forEachIndexed { index, slot ->
                        val transform = positioned.previewTransform(slot.width, slot.height)
                        val drawnWidth = slot.width * transform.scaleX
                        val drawnHeight = slot.height * transform.scaleY
                        val message = "$width x $height navigation=$insets side=$side slot=$index"
                        assertTrue(message, drawnWidth <= slot.width + .01f)
                        assertTrue(message, drawnHeight <= slot.height + .01f)
                        assertTrue(message, kotlin.math.abs(slot.width - drawnWidth) < .01f ||
                            kotlin.math.abs(slot.height - drawnHeight) < .01f)
                        assertEquals(message, slot.width / positioned.canvas.width.toFloat() * transform.scaleX,
                            slot.height / positioned.canvas.height.toFloat() * transform.scaleY, .000001f)
                    }
                    assertTrue(positioned.slots.last().top + positioned.slots.last().height <=
                        positioned.geometry.sidebarLogicalHeight)
                    assertTrue(positioned.slots.first().width + positioned.gapPixels <=
                        positioned.geometry.sidebarWidth)
                    positioned.slots.forEach { slot ->
                        val drawnHeight = slot.width.toFloat() * positioned.canvas.height / positioned.canvas.width
                        assertEquals("Card and app must share an aspect ratio", slot.height.toFloat(), drawnHeight, 3f)
                    }
                }
            }
        }
    }

    @Test
    fun controlEdgeIsASessionChoiceThatNeverEntersTheRecordedProfile() {
        val profile = WorkspaceLayoutProfile.create(1216, 2640, 476, WorkspaceNavigationInsets())
        val bottom = profile.geometry(SidebarSide.LEFT, controlAtBottom = true)
        assertTrue(bottom.controlAtBottom)
        assertEquals(SidebarSide.LEFT, bottom.sidebarSide)
        // The rail leaves the status-bar band free; the main region and its scale do not change.
        val top = profile.geometry(SidebarSide.LEFT)
        val reserve = (WorkspaceControlMetrics.STATUS_HEIGHT_DP * 476 / 160f).toInt()
        assertEquals(reserve, bottom.sidebarTop - bottom.contentTop)
        assertEquals(top.sidebarPhysicalHeight - reserve, bottom.sidebarPhysicalHeight)
        assertEquals(top.contentHeight, bottom.contentHeight)
        assertEquals(top.contentScale, bottom.contentScale, 0f)
        val bottomSlots = profile.copy(geometry = bottom).slots
        assertTrue(bottomSlots.last().top + bottomSlots.last().height <= bottom.sidebarLogicalHeight)
        assertTrue(bottomSlots.first().height < profile.slots.first().height)
        assertEquals(profile.slots.first().width.toFloat() / profile.slots.first().height,
            bottomSlots.first().width.toFloat() / bottomSlots.first().height, .02f)
        assertFalse(profile.geometry(SidebarSide.LEFT).controlAtBottom)
        assertFalse(checkNotNull(WorkspaceLayoutProfile.decode(profile.encode())).geometry.controlAtBottom)
    }

    @Test
    fun savedProfileRoundTripsBothNavigationModesAndRotations() {
        for ((width, height) in listOf(1080 to 2354, 2354 to 1080)) {
            for (insets in listOf(WorkspaceNavigationInsets(),
                if (width < height) WorkspaceNavigationInsets(bottom = 132)
                else WorkspaceNavigationInsets(right = 132))) {
                for (rotation in if (width > height) listOf(1, 3) else listOf(0)) {
                    val profile = WorkspaceLayoutProfile.create(width, height, 480, insets, rotation)
                    val restored = checkNotNull(WorkspaceLayoutProfile.decode(profile.encode()))
                    assertEquals(profile, restored)
                    assertEquals(profile.slots, restored.slots)
                    assertEquals(profile.canvas, restored.canvas)
                    assertEquals(profile.source, restored.source)
                    assertEquals(profile.geometry(SidebarSide.LEFT), restored.geometry(SidebarSide.LEFT))
                    assertEquals(profile.geometry(SidebarSide.RIGHT), restored.geometry(SidebarSide.RIGHT))
                }
            }
        }
    }

    @Test
    fun actualPixelsUseTheSameScaleOnBothAxesAndFitInsideEverySlot() {
        for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132))) {
            val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, insets)
            profile.slots.forEach { slot ->
                val transform = profile.previewTransform(slot.width, slot.height)
                val sx = slot.width * transform.scaleX / profile.canvas.width
                val sy = slot.height * transform.scaleY / profile.canvas.height
                assertEquals(sx, sy, .000001f)
                val source = profile.source
                val left = source.left * sx + transform.x
                val right = source.right * sx + transform.x
                val top = source.top * sy + transform.y
                val bottom = source.bottom * sy + transform.y
                assertTrue(left >= -.01f && top >= -.01f)
                assertTrue(right <= slot.width + .01f && bottom <= slot.height + .01f)
            }
        }
    }

    @Test
    fun canvasUsesTheRecordedUsableSizeAndDoesNotDependOnTheSlotOrSide() {
        val full = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets())
        val buttons = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets(bottom = 132))
        assertEquals(1080, buttons.canvas.width)
        assertEquals(2222, buttons.canvas.height)
        assertEquals(2354, full.source.height)
        assertEquals(2222, buttons.source.height)
        assertEquals(2222, buttons.geometry.availableHeight)
        assertNotEquals(full.slots, buttons.slots)
        assertEquals(buttons.canvas, buttons.copy(geometry = buttons.geometry(SidebarSide.LEFT)).canvas)
    }

    @Test
    fun previewKeepsTheWholeAppIncludingItsBottomControlsInButtonMode() {
        val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets(bottom = 132))
        // A virtual display has no physical navigation bar. Cropping 132 pixels
        // off its app would remove the app's own bottom controls.
        assertEquals(2222, profile.geometry.availableHeight)
        assertEquals(0, profile.source.top)
        assertEquals(profile.canvas.height, profile.source.bottom)
    }

    @Test
    fun oldFullCanvasProfileMigratesUsingItsRecordedNavigationInsets() {
        val buttons = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets(bottom = 132))
        val old = legacyRecord(buttons, "2")
        val migrated = checkNotNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
        assertEquals(buttons, migrated)
        assertEquals(2222, migrated.canvas.height)
        assertEquals("12", migrated.encode().substringBefore(','))
    }

    @Test
    fun oldRecordedRailDimensionsAreMigratedWithoutChangingTheNavigationMode() {
        for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132))) {
            val oldGeometry = WorkspaceGeometry(1080, 2354, 624,
                if (insets.bottom == 0) 286 else 303, SidebarSide.RIGHT, insets)
            val recorded = WorkspaceLayoutProfile(oldGeometry, 480, 3)
            for (version in listOf("2", "3")) {
                val old = legacyRecord(recorded, version)
                val migrated = checkNotNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
                assertEquals(insets, migrated.geometry.navigationInsets)
                assertEquals(1080, migrated.canvas.width)
                assertEquals(2354 - insets.bottom, migrated.canvas.height)
                assertEquals(WorkspaceLayoutProfile.create(1080, 2354, 480, insets), migrated)
                assertNotEquals(oldGeometry.topHeight, migrated.geometry.topHeight)
                assertNotEquals(recorded.slots, migrated.slots)
                assertEquals(migrated, WorkspaceLayoutProfile.decode(migrated.encode()))
            }
        }
    }

    @Test
    fun oldLayoutProfileKeepsRecordedDimensionsWhenRotationIsAdded() {
        val profile = WorkspaceLayoutProfile.create(2354, 1080, 480, WorkspaceNavigationInsets(left = 132))
        val old = legacyRecord(profile, "4")
        val restored = checkNotNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
        val reversed = restored.copy(geometry = restored.geometry.copy(displayRotation = 1))
        assertEquals(profile, restored)
        assertEquals(restored.canvas, reversed.canvas)
        assertEquals(restored.slots, reversed.slots)
        assertEquals(reversed, WorkspaceLayoutProfile.decode(reversed.encode()))
    }

    @Test
    fun inconsistentSavedDimensionsAreRejected() {
        val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets())
        val parts = profile.encode().split(',').toMutableList()
        parts[14] = "300" // stale native canvas width must not be combined with this profile.
        assertNull(WorkspaceLayoutProfile.decode(parts.joinToString(",")))
        assertNull(WorkspaceLayoutProfile.decode("1080,2354,3,0,0,0,0"))
    }

    @Test
    fun threeSlotCalibrationMigratesToFourSlotsUsingTheSameRecordedDisplay() {
        val old = WorkspaceLayoutProfile(WorkspaceGeometry(1080, 2354, 590, 271, SidebarSide.RIGHT), 480, 3)
        val migrated = checkNotNull(WorkspaceLayoutProfile.decode(legacyRecord(old, "5").joinToString(",")))
        assertEquals(4, migrated.slots.size)
        assertEquals(WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets()), migrated)
    }

    @Test
    fun wideVideoAndThreeRailAppsKeepTheirCanvasAndSurviveBothLandscapeRotations() {
        for ((width, height) in listOf(1080 to 2354, 2354 to 1080, 1600 to 2560)) {
            for (rotation in if (width > height) listOf(1, 3) else listOf(0)) {
                val normal = WorkspaceLayoutProfile.create(width, height, 480, WorkspaceNavigationInsets(), rotation)
                for (index in 0..3) {
                    val wide = normal.withWideSlot(index)
                    assertEquals(4, wide.slots.size)
                    assertTrue(wide.canvas(index).width > wide.canvas(index).height)
                    assertEquals(wide, WorkspaceLayoutProfile.decode(wide.encode()))
                    val rail = wide.slots.filterIndexed { i, _ -> i != index }
                    assertEquals(3, rail.size)
                    assertTrue(rail.last().top + rail.last().height <= wide.geometry.sidebarLogicalHeight)
                    for (slot in rail) assertTrue(slot.width > 0 && slot.height > 0)
                    val g = wide.geometry
                    assertTrue(g.availableLeft * g.contentScale + g.contentTranslationX >= g.contentLeft - 1f)
                    assertTrue(g.availableTop * g.contentScale + g.contentTranslationY >= g.contentTop - 1f)
                    assertEquals(normal, wide.withWideSlot(null))
                }
            }
        }
    }

    @Test fun previousWorkbenchProfilesLoseThePlayerReserveButKeepTheirRecordedMetrics() {
        val geometry = WorkspaceGeometry(1080, 2354, 474, 218, SidebarSide.RIGHT)
        val old = legacyFourSlotRecord(WorkspaceLayoutProfile(geometry, 480, 3), "6").joinToString(",")
        val migrated = checkNotNull(WorkspaceLayoutProfile.decode(old))
        assertEquals(474, migrated.geometry.topHeight)
        assertEquals(1080, migrated.geometry.screenWidth)
        assertEquals(2354, migrated.geometry.screenHeight)
        assertEquals(480, migrated.densityDpi)
        assertEquals(WorkspaceNavigationInsets(), migrated.geometry.navigationInsets)
        assertEquals(migrated, WorkspaceLayoutProfile.decode(migrated.encode()))
    }

    @Test fun oldWideProfilesValidateTheirOldPlayerHeightBeforeMigrating() {
        val geometry = WorkspaceGeometry(1080, 2354, 1009, 207, SidebarSide.RIGHT)
        for (index in 0..3) {
            val parts = legacyFourSlotRecord(WorkspaceLayoutProfile(geometry, 480, 3, index), "7")
            parts[21 + index * 3] = "607" // Previous 134dp header plus 16:9 wide window.
            val migrated = checkNotNull(WorkspaceLayoutProfile.decode(parts.joinToString(",")))
            assertEquals(index, migrated.wideSlotIndex)
            val fresh = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets()).withWideSlot(index)
            assertEquals(fresh.geometry.topHeight, migrated.geometry.topHeight)
            assertEquals(migrated.geometry.topHeight - (WorkspaceControlMetrics.STATUS_HEIGHT_DP * 480 / 160f).toInt(), migrated.slots[index].height)
            parts[21 + index * 3] = "606"
            assertNull(WorkspaceLayoutProfile.decode(parts.joinToString(",")))
        }
    }

    @Test fun workbenchSixRecordsMigrateAndRejectCorruptedSlotSizes() {
        for (index in listOf(null, 0, 3)) {
            val profile = WorkspaceLayoutProfile(WorkspaceGeometry(1216, 2640, 422, 194, SidebarSide.RIGHT), 476, 2, index)
            val old = legacyFourSlotRecord(profile, if (index == null) "10" else "11")
            val migrated = checkNotNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
            assertEquals(WorkspaceLayoutProfile.create(1216, 2640, 476, WorkspaceNavigationInsets()).withWideSlot(index), migrated)
            old[20] = "1"
            assertNull(WorkspaceLayoutProfile.decode(old.joinToString(",")))
        }
    }

    @Test fun fourCardsFillTheRailWithoutTallInternalLetterboxes() {
        val profile = WorkspaceLayoutProfile.create(1216, 2640, 476, WorkspaceNavigationInsets())
        assertEquals(530, profile.geometry.topHeight)
        assertEquals(244, profile.geometry.sidebarWidth)
        profile.slots.forEachIndexed { i, slot ->
            assertTrue(slot.top >= 0)
            if (i > 0) assertTrue(slot.top >= profile.slots[i - 1].let { it.top + it.height } + profile.gapPixels)
            assertTrue(kotlin.math.abs(slot.width * profile.canvas.height.toDouble() / profile.canvas.width - slot.height) < 3)
            assertTrue(slot.height > 520)
        }
    }

    private fun legacyFourSlotRecord(profile: WorkspaceLayoutProfile, version: String): MutableList<String> {
        val parts = profile.encode().split(',').toMutableList()
        parts[0] = version
        val count = if (profile.wideSlotIndex == null) 4 else 3
        val usable = profile.geometry.sidebarLogicalHeight - profile.gapPixels * (count - 1)
        val base = usable / count
        val extra = usable % count
        var rail = 0
        repeat(4) { index ->
            if (index != profile.wideSlotIndex) {
                parts[20 + index * 3] = (profile.geometry.sidebarWidth - profile.gapPixels).toString()
                parts[21 + index * 3] = (base + if (rail < extra) 1 else 0).toString()
                parts[22 + index * 3] = (rail * (base + profile.gapPixels) + minOf(rail, extra)).toString()
                rail++
            }
        }
        return parts
    }

    private fun legacyRecord(profile: WorkspaceLayoutProfile, version: String): MutableList<String> {
        val old = profile.encode().split(',').take(20).toMutableList()
        old[0] = version
        val usable = profile.geometry.sidebarLogicalHeight - profile.gapPixels * 2
        val base = usable / 3
        val extra = usable % 3
        repeat(3) { index -> old.addAll(listOf(profile.geometry.sidebarWidth - profile.gapPixels,
            base + if (index < extra) 1 else 0, index * (base + profile.gapPixels) + minOf(index, extra)).map(Int::toString)) }
        if (version == "5") old += profile.geometry.displayRotation.toString()
        if (version == "2") {
            old[14] = minOf(profile.geometry.screenWidth, profile.geometry.screenHeight).toString()
            old[15] = maxOf(profile.geometry.screenWidth, profile.geometry.screenHeight).toString()
            old[18] = old[14]
            old[19] = old[15]
        }
        return old
    }
}
