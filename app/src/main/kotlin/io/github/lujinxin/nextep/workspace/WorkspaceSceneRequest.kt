package io.github.lujinxin.nextep.workspace

internal data class WorkspaceSceneRequest(val taskId: Int, val orientation: Int, val time: Long, val canHold: Boolean,
                                          val fullscreen: Boolean = false) {
    fun fresh(now: Long): Boolean = taskId >= 0 && now - time in 0..3_000L
    fun holdRotation(now: Long): Boolean = canHold && landscape(orientation) && fresh(now) && now - time <= 1_500L
    /** Only a landscape request fits the 16:9 wide slot; a portrait page there is letterboxed
     * into a thin column, bars hidden or not. */
    fun shouldPin(now: Long) = fresh(now) && landscape(orientation)
    /** The pinned task asked for anything but landscape again: give it the main window back. */
    fun releasesWide(): Boolean = !landscape(orientation)
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
