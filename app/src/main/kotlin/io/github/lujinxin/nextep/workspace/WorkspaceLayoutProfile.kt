package io.github.lujinxin.nextep.workspace

import io.github.lujinxin.nextep.display.SlotGeometry
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT

/** One recorded layout shared by panels, main-task fitting and all preview displays. */
data class WorkspaceLayoutProfile(
    val geometry: WorkspaceGeometry,
    val densityDpi: Int,
    val gapPixels: Int,
    val wideSlotIndex: Int? = null,
) {
    data class Slot(val width: Int, val height: Int, val top: Int)
    data class Source(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    data class PreviewTransform(val scaleX: Float, val scaleY: Float, val x: Float, val y: Float)

    init {
        require(densityDpi > 0 && gapPixels > 0)
        require(geometry.sidebarWidth > gapPixels)
        require(wideSlotIndex == null || wideSlotIndex in 0 until WORKSPACE_SLOT_COUNT)
    }

    private val leftGeometry = geometry.copy(sidebarSide = SidebarSide.LEFT)
    private val rightGeometry = geometry.copy(sidebarSide = SidebarSide.RIGHT)
    val canvas = SlotGeometry.forRecordedDisplay(geometry.availableWidth, geometry.availableHeight, densityDpi)
    // Apps lay out directly at the recorded usable resolution. Fit that complete
    // buffer into a slot; do not crop navigation pixels off an already laid-out app.
    val source = Source(0, 0, canvas.width, canvas.height)
    val slots: List<Slot> = if (wideSlotIndex == null) slotBounds(WORKSPACE_SLOT_COUNT) else {
        val rail = slotBounds(WORKSPACE_SLOT_COUNT - 1).iterator()
        List(WORKSPACE_SLOT_COUNT) { index ->
            if (index == wideSlotIndex) Slot(minOf(geometry.availableWidth, geometry.availableHeight),
                wideHeight(), 0) else rail.next()
        }
    }

    // The status bar/camera strip stays free; the video lives below it like the controls.
    private val statusInset: Int get() = (WorkspaceControlMetrics.STATUS_HEIGHT_DP * densityDpi / 160f).toInt()

    private fun wideHeight(): Int = (geometry.topHeight - statusInset).coerceAtLeast(1)

    fun canvas(index: Int): SlotGeometry = if (index == wideSlotIndex) {
        SlotGeometry(canvas.width, (canvas.width.toDouble() * wideHeight() /
            minOf(geometry.availableWidth, geometry.availableHeight)).toInt().coerceAtLeast(1), densityDpi)
    } else canvas

    fun source(index: Int): Source = canvas(index).let { Source(0, 0, it.width, it.height) }

    fun withWideSlot(index: Int?): WorkspaceLayoutProfile {
        if (index == null && wideSlotIndex == null) return this
        val normal = create(geometry.screenWidth, geometry.screenHeight, densityDpi,
            geometry.navigationInsets, geometry.displayRotation)
        if (index == null) return normal
        val g = normal.geometry
        val short = minOf(g.availableWidth, g.availableHeight)
        val long = maxOf(g.availableWidth, g.availableHeight)
        // The control edge is the one height at which the main window stays proportional
        // and three full-aspect rail cards fill the rail width: 4t = long + g(3·long/short − 2).
        // A 16:9 player then letterboxes inside its own window; the layout itself has no slack.
        val top = ((long + gapPixels * (3.0 * long / short - 2)) / 4).toInt().coerceIn(1, (long * .70).toInt())
        val rail = WorkspaceGeometry.proportionalRail(short, long, top).coerceAtLeast(gapPixels + 1)
        return normal.copy(geometry = g.copy(topHeight = top, sidebarWidth = rail), wideSlotIndex = index)
    }

    private fun slotBounds(count: Int, fit: Boolean = true): List<Slot> {
        val height = (geometry.sidebarLogicalHeight - gapPixels * (count - 1))
            .coerceAtLeast(count)
        val base = height / count
        val remainder = height % count
        return List(count) { index ->
            val cellHeight = base + if (index < remainder) 1 else 0
            val cellWidth = geometry.sidebarWidth - gapPixels
            val top = index * (base + gapPixels) + minOf(index, remainder)
            if (!fit) Slot(cellWidth, cellHeight, top) else {
                val scale = minOf(cellWidth.toDouble() / canvas.width, cellHeight.toDouble() / canvas.height)
                val height = (canvas.height * scale).toInt().coerceAtLeast(1)
                Slot((canvas.width * scale).toInt().coerceAtLeast(1), height, top + (cellHeight - height) / 2)
            }
        }
    }

    /** The recorded profile is position-neutral; side and control edge are session choices. */
    fun geometry(side: SidebarSide, controlAtBottom: Boolean = false): WorkspaceGeometry =
        (if (side == SidebarSide.LEFT) leftGeometry else rightGeometry).let {
            if (controlAtBottom) it.copy(controlAtBottom = true, railTopInset = statusInset) else it
        }

    /** Undo TextureView's default stretching and fit the complete recorded canvas.
     * Slot bounds have the same aspect ratio apart from integer-pixel rounding. */
    fun previewTransform(width: Int, height: Int): PreviewTransform {
        require(width > 0 && height > 0)
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        return PreviewTransform(canvas.width * scale / width, canvas.height * scale / height,
            (width - source.width * scale) / 2f - source.left * scale,
            (height - source.height * scale) / 2f - source.top * scale)
    }

    fun encode(): String = (listOf(if (wideSlotIndex == null) "12" else "13", geometry.screenWidth, geometry.screenHeight,
        geometry.topHeight, geometry.sidebarWidth, densityDpi, gapPixels,
        geometry.navigationInsets.left, geometry.navigationInsets.top,
        geometry.navigationInsets.right, geometry.navigationInsets.bottom,
        geometry.contentWidth, geometry.contentHeight, geometry.contentScale,
        canvas.width, canvas.height,
        source.left, source.top, source.right, source.bottom,
        *slots.flatMap { listOf(it.width, it.height, it.top) }.toTypedArray(),
        geometry.displayRotation) + if (wideSlotIndex == null) emptyList() else listOf(wideSlotIndex)).joinToString(",")

    companion object {
        fun create(width: Int, height: Int, densityDpi: Int, insets: WorkspaceNavigationInsets,
            displayRotation: Int = 0) =
            WorkspaceLayoutProfile(WorkspaceGeometry.forDisplay(width, height, density = densityDpi / 160f,
                navigationInsets = insets, displayRotation = displayRotation),
                densityDpi, (densityDpi / 160f).toInt().coerceAtLeast(1))

        fun decode(encoded: String?): WorkspaceLayoutProfile? = runCatching {
            val p = encoded?.split(',') ?: return@runCatching null
            val version = p.firstOrNull() ?: return@runCatching null
            if (version !in setOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13") ||
                p.size != when (version) { "7", "9", "11", "13" -> 34; "6", "8", "10", "12" -> 33; "5" -> 30; else -> 29 }) return@runCatching null
            val profile = WorkspaceLayoutProfile(WorkspaceGeometry(p[1].toInt(), p[2].toInt(),
                p[3].toInt(), p[4].toInt(), SidebarSide.RIGHT,
                WorkspaceNavigationInsets(p[7].toInt(), p[8].toInt(), p[9].toInt(), p[10].toInt()),
                displayRotation = when (version) { "6", "7", "8", "9", "10", "11", "12", "13" -> p[32].toInt(); "5" -> p[29].toInt(); else -> 0 }),
                p[5].toInt(), p[6].toInt(), if (version in setOf("7", "9", "11", "13")) p[33].toInt() else null)
            val expected = if (version.toInt() >= 6) profile.encode().split(',').toMutableList() else {
                // Validate the old three-slot record before recalculating four slots.
                (profile.encode().split(',').take(20) +
                    profile.slotBounds(3, fit = false).flatMap { listOf(it.width, it.height, it.top).map(Int::toString) } +
                    if (version == "5") listOf(profile.geometry.displayRotation.toString()) else emptyList()).toMutableList()
            }
            expected[0] = p[0]
            if (version.toInt() in 6..11) {
                val rail = profile.slotBounds(if (profile.wideSlotIndex == null) 4 else 3, fit = false).iterator()
                repeat(4) { index ->
                    val slot = if (index == profile.wideSlotIndex) profile.slots[index] else rail.next()
                    expected[20 + index * 3] = slot.width.toString()
                    expected[21 + index * 3] = slot.height.toString()
                    expected[22 + index * 3] = slot.top.toString()
                }
            }
            if (version == "7" || version == "9") {
                val index = checkNotNull(profile.wideSlotIndex)
                expected[21 + index * 3] = (profile.geometry.topHeight -
                    ((if (version == "7") 134 else WorkspaceControlMetrics.COMPACT_HEIGHT_DP) *
                        profile.densityDpi / 160f).toInt()).coerceAtLeast(1).toString()
            }
            if (p[0] == "2") {
                // Migrate the old full-screen canvas using its recorded insets.
                // This requires no WindowMetrics query or fresh calibration.
                val oldCanvas = SlotGeometry.forRecordedDisplay(profile.geometry.screenWidth,
                    profile.geometry.screenHeight, profile.densityDpi)
                expected[14] = oldCanvas.width.toString()
                expected[15] = oldCanvas.height.toString()
                expected[18] = oldCanvas.width.toString()
                expected[19] = oldCanvas.height.toString()
            }
            if (expected.joinToString(",") != encoded) return@runCatching null
            if (version == "12" || version == "13") profile else {
                // Recalculate the old slot proportions once using the already
                // recorded display and navigation mode; never resample metrics.
                create(profile.geometry.screenWidth, profile.geometry.screenHeight,
                    profile.densityDpi, profile.geometry.navigationInsets, profile.geometry.displayRotation)
                    .withWideSlot(profile.wideSlotIndex)
            }
        }.getOrNull()

    }
}
