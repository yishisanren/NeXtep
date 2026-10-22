package io.github.lujinxin.nextep.task

import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

class SurfaceTaskPresenter : MainTaskPresenter {
    override fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.present(taskId, geometry)

    override fun restore(taskId: Int, animated: Boolean): Result<Unit> = TaskSurfaceCompat.restore(taskId, animated)

    override fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.reapply(taskId, geometry)
}
