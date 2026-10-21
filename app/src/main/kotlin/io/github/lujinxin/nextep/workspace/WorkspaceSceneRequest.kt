package io.github.lujinxin.nextep.workspace

internal data class WorkspaceSceneRequest(val taskId: Int, val orientation: Int, val time: Long, val canHold: Boolean) {
    fun fresh(now: Long): Boolean = taskId >= 0 && now - time in 0..3_000L
    fun holdRotation(now: Long): Boolean = canHold && landscape(orientation) && fresh(now) && now - time <= 1_500L
    fun encode() = "$taskId,$orientation,$time,${if (canHold) 1 else 0}"

    companion object {
        fun landscape(orientation: Int) = orientation in setOf(0, 6, 8, 11)
        fun decode(value: String?): WorkspaceSceneRequest? = runCatching {
            val p = value?.split(',') ?: return@runCatching null
            if (p.size != 4 || p[3] !in setOf("0", "1")) return@runCatching null
            WorkspaceSceneRequest(p[0].toInt(), p[1].toInt(), p[2].toLong(), p[3] == "1")
                .takeIf { it.taskId >= 0 && it.time >= 0 && it.orientation in -1..14 }
        }.getOrNull()
    }
}
