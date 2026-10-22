package io.github.lujinxin.nextep.workspace

/** Keep one native drag alive through asynchronous task/layout changes. No payload is retained. */
internal class WorkspaceContentDragSession(
    private val expand: (Int, (Boolean) -> Unit) -> Boolean,
    private val restore: (Int, () -> Unit) -> Unit,
    private val cleared: () -> Unit = {},
) {
    var active = false
        private set
    private var target: Int? = null
    private var moving = false
    private var expanded = false
    private var result: Boolean? = null
    private var generation = 0

    fun begin() { if (!active) { active = true; generation++ } }

    fun enter(index: Int) {
        if (!active || target != null || moving) return
        target = index
        moving = true
        val token = generation
        val started = expand(index) { success ->
            if (!active || token != generation) return@expand
            moving = false
            expanded = success
            if (!success) abandon() else finishIfEnded()
        }
        if (!started && active && token == generation) abandon()
    }

    fun end(accepted: Boolean) {
        if (!active || result != null) return
        result = accepted
        finishIfEnded()
    }

    private fun finishIfEnded() {
        val accepted = result ?: return
        if (moving) return
        val index = target
        if (!accepted && expanded && index != null) {
            moving = true
            val token = generation
            restore(index) { if (active && token == generation) abandon() }
        } else abandon()
    }

    fun abandon() {
        generation++
        active = false
        target = null
        moving = false
        expanded = false
        result = null
        cleared()
    }
}
