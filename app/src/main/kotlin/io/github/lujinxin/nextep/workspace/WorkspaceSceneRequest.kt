package io.github.lujinxin.nextep.workspace

internal data class WorkspaceSceneRequest(val taskId: Int, val orientation: Int, val time: Long, val canHold: Boolean,
                                          val fullscreen: Boolean = false) {
    fun fresh(now: Long): Boolean = taskId >= 0 && now - time in 0..3_000L
    fun holdRotation(now: Long): Boolean = canHold && landscape(orientation) && fresh(now) && now - time <= 1_500L
    /** A landscape request from the main task pins it to the wide slot ("横向应用置顶"), whether or
     * not it also hides the bars; a portrait fullscreen only when it is heard playing media. */
    fun shouldPin(now: Long, playingMedia: Boolean) = fresh(now) && (landscape(orientation) || (fullscreen && playingMedia))
    /** The pinned task asked for portrait again and shows its bars: give it the main window back. */
    fun releasesWide(): Boolean = !landscape(orientation) && !fullscreen
    fun encode() = "$taskId,$orientation,$time,${if (canHold) 1 else 0},${if (fullscreen) 1 else 0}"

    companion object {
        fun landscape(orientation: Int) = orientation in setOf(0, 6, 8, 11)
        fun decode(value: String?): WorkspaceSceneRequest? = runCatching {
            val p = value?.split(',') ?: return@runCatching null
            if (p.size !in 4..5 || p[3] !in setOf("0", "1") ||
                (p.size == 5 && p[4] !in setOf("0", "1"))) return@runCatching null
            WorkspaceSceneRequest(p[0].toInt(), p[1].toInt(), p[2].toLong(), p[3] == "1", p.getOrNull(4) == "1")
                .takeIf { it.taskId >= 0 && it.time >= 0 && it.orientation in -1..14 }
        }.getOrNull()
    }
}
