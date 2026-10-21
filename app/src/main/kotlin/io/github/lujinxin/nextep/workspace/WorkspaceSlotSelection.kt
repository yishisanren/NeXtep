package io.github.lujinxin.nextep.workspace

internal class WorkspaceSlotSelection {
    data class Target(val index: Int, val displayId: Int?)
    var target: Target? = null
        private set

    fun toggle(index: Int, displayId: Int?): Target? {
        val next = Target(index, displayId)
        target = next.takeUnless { it == target }
        return target
    }

    fun clear() { target = null }
}
