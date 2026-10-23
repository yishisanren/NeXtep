package io.github.lujinxin.nextep.workspace

/** Shared dimensions for control content and the workspace region it occupies. */
internal object WorkspaceControlMetrics {
    const val STATUS_HEIGHT_DP = 40
    const val ACTION_HEIGHT_DP = 50
    const val APP_ICON_SIZE_DP = 44
    const val APP_BOTTOM_PADDING_DP = 0
    const val CONTENT_BOTTOM_PADDING_DP = 2
    const val LARGE_SCREEN_MIN_SHORT_SIDE_DP = 600

    const val COMPACT_HEIGHT_DP = STATUS_HEIGHT_DP + ACTION_HEIGHT_DP +
        APP_ICON_SIZE_DP + APP_BOTTOM_PADDING_DP + CONTENT_BOTTOM_PADDING_DP

}
