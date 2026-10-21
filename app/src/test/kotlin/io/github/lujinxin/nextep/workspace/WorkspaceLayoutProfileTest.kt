package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceLayoutProfileTest {
    @Test
    fun everySlotFillsItsRecordedBoundsInBothNavigationModesAndRotations() {
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
                        // One rounded rail-width pixel can expand to an aspect-ratio
                        // worth of height; the live Surface is fitted uniformly too.
                        assertEquals(message, slot.width.toFloat(), drawnWidth, 1.5f)
                        assertEquals(message, slot.height.toFloat(), drawnHeight,
                            positioned.canvas.height.toFloat() / positioned.canvas.width + 1f)
                        assertEquals(message, slot.width / positioned.canvas.width.toFloat() * transform.scaleX,
                            slot.height / positioned.canvas.height.toFloat() * transform.scaleY, .000001f)
                    }
                    assertEquals(positioned.geometry.sidebarLogicalHeight,
                        positioned.slots.last().top + positioned.slots.last().height)
                    assertEquals(positioned.geometry.sidebarWidth,
                        positioned.slots.first().width + positioned.gapPixels)
                }
            }
        }
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
        assertEquals("6", migrated.encode().substringBefore(','))
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
                    assertEquals(wide.geometry.sidebarLogicalHeight, rail.last().top + rail.last().height)
                    for (slot in rail) assertTrue(slot.width > 0 && slot.height > 0)
                    val g = wide.geometry
                    assertTrue(g.availableLeft * g.contentScale + g.contentTranslationX >= g.contentLeft - 1f)
                    assertTrue(g.availableTop * g.contentScale + g.contentTranslationY >= g.contentTop - 1f)
                    assertEquals(normal, wide.withWideSlot(null))
                }
            }
        }
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
