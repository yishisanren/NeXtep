package io.github.lujinxin.nextep.workspace

import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import kotlin.math.roundToInt

data class WorkspaceNavigationInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

data class WorkspaceGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val topHeight: Int,
    val sidebarWidth: Int,
    val sidebarSide: SidebarSide,
    val navigationInsets: WorkspaceNavigationInsets = WorkspaceNavigationInsets(),
    val displayRotation: Int = 0,
) {
    val isLandscape: Boolean get() = screenWidth > screenHeight
    // Surface rotations are quarter turns from the phone's natural portrait axes.
    // Keep controls at the physical phone top while the main app follows the display.
    val isControlOnLeft: Boolean get() = isLandscape && displayRotation == 1
    val panelRotationDegrees: Float get() = if (!isLandscape) 0f else if (isControlOnLeft) -90f else 90f
    // Project the selected button's unchanged diagram into display coordinates.
    // Rotation never changes the selected layout or the diagram in panel axes.
    val panelSidebarSide: SidebarSide get() = SidebarButtonLayout.sideInPanel(sidebarSide)
    val isSidebarAtTop: Boolean get() =
        SidebarButtonLayout.isAtScreenTop(sidebarSide, isLandscape, isControlOnLeft)

    // The task still occupies the full display. Its navigation-safe source rectangle
    // is fitted uniformly, so the native navigation padding is not scaled a second time.
    val availableLeft: Int get() = navigationInsets.left
    val availableTop: Int get() = navigationInsets.top
    val availableRight: Int get() = screenWidth - navigationInsets.right
    val availableBottom: Int get() = screenHeight - navigationInsets.bottom
    val availableWidth: Int get() = availableRight - availableLeft
    val availableHeight: Int get() = availableBottom - availableTop

    init {
        require(screenWidth > 1 && screenHeight > 1)
        require(displayRotation in 0..3)
        require(navigationInsets.left >= 0 && navigationInsets.top >= 0 &&
            navigationInsets.right >= 0 && navigationInsets.bottom >= 0)
        require(navigationInsets.left <= screenWidth - 2 &&
            navigationInsets.right <= screenWidth - navigationInsets.left - 2)
        require(navigationInsets.top <= screenHeight - 2 &&
            navigationInsets.bottom <= screenHeight - navigationInsets.top - 2)
        require(availableWidth > 1 && availableHeight > 1)
        require(topHeight in 1 until (if (isLandscape) availableWidth else availableHeight))
        require(sidebarWidth in 1 until (if (isLandscape) availableHeight else availableWidth))
    }

    // Control-strip thickness and slot-rail thickness use portrait logical axes.
    val controlWidth: Int get() = if (isLandscape) topHeight else availableWidth
    val controlHeight: Int get() = if (isLandscape) availableHeight else topHeight
    val controlLeft: Int get() = if (isLandscape && !isControlOnLeft) availableRight - topHeight else availableLeft
    val controlTop: Int get() = availableTop
    val sidebarLeft: Int get() = if (isLandscape) contentLeft else if (sidebarSide == SidebarSide.LEFT) availableLeft else contentRight
    val sidebarTop: Int get() = if (!isLandscape) availableTop + topHeight else
        if (isSidebarAtTop) availableTop else availableBottom - sidebarWidth
    val sidebarPhysicalWidth: Int get() = if (isLandscape) contentWidth else sidebarWidth
    val sidebarPhysicalHeight: Int get() = if (isLandscape) sidebarWidth else contentHeight
    val sidebarLogicalHeight: Int get() = if (isLandscape) contentWidth else contentHeight

    val contentTop: Int get() = availableTop + if (!isLandscape) topHeight else
        if (isSidebarAtTop) sidebarWidth else 0
    val contentBottom: Int get() = contentTop + contentHeight

    val contentWidth: Int
        get() = availableWidth - if (isLandscape) topHeight else sidebarWidth

    val contentHeight: Int
        get() = availableHeight - if (isLandscape) sidebarWidth else topHeight

    val contentLeft: Int
        get() = availableLeft + if (isLandscape) {
            if (isControlOnLeft) topHeight else 0
        } else if (sidebarSide == SidebarSide.LEFT) sidebarWidth else 0

    val contentRight: Int
        get() = contentLeft + contentWidth

    val contentScale: Float
        get() = minOf(contentWidth.toFloat() / availableWidth, contentHeight.toFloat() / availableHeight)

    val contentTranslationX: Float
        get() = contentLeft + (contentWidth - availableWidth * contentScale) / 2f - availableLeft * contentScale

    val contentTranslationY: Float
        get() = contentTop + (contentHeight - availableHeight * contentScale) / 2f - availableTop * contentScale

    companion object {
        const val TOP_HEIGHT_FRACTION = 0.265f
        const val SIDEBAR_WIDTH_FRACTION = 0.267f

        fun forDisplay(
            screenWidth: Int,
            screenHeight: Int,
            sidebarSide: SidebarSide = SidebarSide.RIGHT,
            density: Float,
            navigationInsets: WorkspaceNavigationInsets = WorkspaceNavigationInsets(),
            displayRotation: Int = 0,
        ): WorkspaceGeometry {
            require(screenWidth > 1 && screenHeight > 1) { "Display dimensions must be usable" }
            require(density.isFinite() && density > 0f) { "Display density must be positive and finite" }
            val left = navigationInsets.left.coerceIn(0, screenWidth - 2)
            val top = navigationInsets.top.coerceIn(0, screenHeight - 2)
            val safeInsets = WorkspaceNavigationInsets(
                left, top,
                navigationInsets.right.coerceIn(0, screenWidth - left - 2),
                navigationInsets.bottom.coerceIn(0, screenHeight - top - 2),
            )
            val availableWidth = screenWidth - safeInsets.left - safeInsets.right
            val availableHeight = screenHeight - safeInsets.top - safeInsets.bottom
            val landscape = screenWidth > screenHeight
            val longSide = if (landscape) availableWidth else availableHeight
            val shortSide = if (landscape) availableHeight else availableWidth
            val gap = density.toInt().coerceAtLeast(1)
            val count = WORKSPACE_SLOT_COUNT
            // Remove the player reserve; keep portrait previews proportional.
            val controlThickness = (WorkspaceControlMetrics.COMPACT_HEIGHT_DP * density)
                .roundToInt().coerceIn(1, (longSide / 2).coerceAtLeast(1))
            val railWidth = (gap + (longSide - controlThickness - (count - 1) * gap).toDouble() *
                shortSide / longSide / count).roundToInt().coerceIn(1, shortSide - 1)
            return WorkspaceGeometry(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                topHeight = controlThickness,
                sidebarWidth = railWidth,
                sidebarSide = sidebarSide,
                navigationInsets = safeInsets,
                displayRotation = displayRotation,
            )
        }
    }
}
