package io.github.lujinxin.nextep.task

import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

interface MainTaskPresenter {
    fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit>
    fun restore(taskId: Int, animated: Boolean = false): Result<Unit>
    fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit>
}
