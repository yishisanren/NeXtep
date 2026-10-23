package io.github.lujinxin.nextep.task

import android.content.Context
import android.graphics.Rect
import android.view.Display
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

class MainTaskPresentationCoordinator(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val taskRepository = TaskRepository(applicationContext)
    private val surfacePresenter = SurfaceTaskPresenter()
    private var presentedTaskId: Int? = null
    private var activePresenter: MainTaskPresenter? = null
    var onHomePresented: (() -> Unit)? = null
    private var homePresented = false

    fun presentForeground(geometry: WorkspaceGeometry): Result<Unit> {
        val task = taskRepository.foregroundTask()
            ?: return Result.failure(IllegalStateException("No foreground Display 0 task"))
        return presentTask(task, geometry)
    }

    fun presentTask(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> {
        val task = taskRepository.findTask(taskId)
            ?: return Result.failure(IllegalStateException("Task $taskId is unavailable"))
        return presentTask(task, geometry)
    }

    private fun presentTask(
        task: TaskRepository.TaskSnapshot,
        geometry: WorkspaceGeometry,
    ): Result<Unit> {
        if (!isMainFullscreen(task)) {
            // Native freeform/PiP/split-screen surfaces belong to WM Shell, not our
            // fullscreen presentation. Do not replace their position or rotation matrix.
            return restoreForMove(task.taskId)
        }
        return presentTask(task, geometry, TriggerBroadcastContract.resolveHomePackage(applicationContext))
    }

    private fun presentTask(
        task: TaskRepository.TaskSnapshot,
        geometry: WorkspaceGeometry,
        homePackage: String?,
    ): Result<Unit> {
        if (task.component.packageName == homePackage) {
            NeXtepLog.info("main_task_presenter", "Foreground task is HOME; Launcher owns transform")
            notifyHomePresented()
            return Result.success(Unit)
        }
        if (task.component.packageName == TriggerBroadcastContract.SYSTEM_UI_PACKAGE) {
            return Result.failure(
                IllegalStateException("Unsafe foreground task ${task.component.packageName}"),
            )
        }

        if (presentedTaskId == task.taskId && activePresenter === surfacePresenter) {
            return applyMainBounds(task, geometry).mapCatching { surfacePresenter.reapply(task.taskId, geometry).getOrThrow() }
        }

        // Fullscreen tasks inherit display bounds. Explicit screen-sized bounds survive
        // rotation and can leave a portrait Activity confined to the old landscape area.
        homePresented = false
        val surfaceResult = applyMainBounds(task, geometry).mapCatching {
            surfacePresenter.present(task.taskId, geometry).getOrThrow()
        }
        if (surfaceResult.isSuccess) {
            val previousTaskId = presentedTaskId
            val previousPresenter = activePresenter
            presentedTaskId = task.taskId
            activePresenter = surfacePresenter
            if (previousTaskId != null && previousTaskId != task.taskId) {
                previousPresenter?.restore(previousTaskId)?.onFailure {
                    NeXtepLog.warn("main_task_presenter", "Previous task release failed taskId=$previousTaskId", it)
                }
            }
        }
        return surfaceResult
    }

    fun reconcileForeground(geometry: WorkspaceGeometry): Result<Unit> =
        reconcileForeground(geometry, taskRepository.runningTasks())

    fun reconcileForeground(
        geometry: WorkspaceGeometry,
        tasks: List<TaskRepository.TaskSnapshot>,
    ): Result<Unit> = runCatching {
        val foreground = tasks.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
            ?: error("No foreground Display 0 task")
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
        if (foreground.component.packageName == homePackage) {
            restoreForeground().getOrThrow()
            notifyHomePresented()
            return@runCatching
        }
        if (foreground.component.packageName == TriggerBroadcastContract.SYSTEM_UI_PACKAGE) {
            return@runCatching
        }
        if (!isMainFullscreen(foreground)) {
            // Keep a different fullscreen task fitted behind the native floating window.
            // If the presented task itself became freeform, release our ownership.
            presentedTaskId?.let { taskId ->
                if (tasks.firstOrNull { it.taskId == taskId }?.let(::isMainFullscreen) != true) {
                    restoreForeground().getOrThrow()
                }
            }
            return@runCatching
        }
        val current = presentedTaskId
        if (current != null && current != foreground.taskId) {
            restoreForeground().getOrThrow()
        }
        if (presentedTaskId == foreground.taskId) {
            activePresenter?.reapply(foreground.taskId, geometry)
                ?.getOrThrow()
                ?: error("Main presenter state is incomplete")
        } else {
            presentTask(foreground, geometry, homePackage).getOrThrow()
        }
    }

    fun restoreForMove(taskId: Int): Result<Unit> {
        if (presentedTaskId != taskId) return Result.success(Unit)
        return restoreForeground()
    }

    fun currentTaskId(): Int? = presentedTaskId

    fun restoreForeground(animated: Boolean = false): Result<Unit> {
        val taskId = presentedTaskId ?: return Result.success(Unit)
        val presenter = activePresenter ?: return Result.success(Unit)
        return presenter.restore(taskId, animated).mapCatching {
            // The entry cleared bounds already; release must not start another layout change,
            // unless the app was laid out on the shortened wide-picture canvas.
            if (shortenedTaskId == taskId) {
                shortenedTaskId = null
                taskRepository.findTask(taskId)?.let { task ->
                    task.token?.let { token -> WindowContainerTransactionCompat.applyTaskBounds(token, Rect()) }
                        ?: ActivityTaskManagerCompat.resizeTask(taskId, Rect())
                }
            }
            if (presentedTaskId == taskId && activePresenter === presenter) {
                presentedTaskId = null
                activePresenter = null
            }
        }
    }

    private var shortenedTaskId: Int? = null

    /**
     * Under a wide picture the main app is laid out at the shortened canvas so it shares the rail
     * cards' aspect; otherwise any override is cleared as before.
     */
    private fun applyMainBounds(task: TaskRepository.TaskSnapshot, geometry: WorkspaceGeometry): Result<Unit> {
        val height = geometry.mainTaskHeight
        if (height <= 0) {
            if (shortenedTaskId == task.taskId) shortenedTaskId = null
            return clearFullscreenBounds(task)
        }
        if (shortenedTaskId == task.taskId && task.bounds.height() == height) return Result.success(Unit)
        if (task.vendorWindowed || task.displayId != Display.DEFAULT_DISPLAY) return Result.success(Unit)
        NeXtepLog.info("main_task_bounds", "Laying out taskId=${task.taskId} at ${geometry.screenWidth}x$height for the wide picture")
        val bounds = Rect(0, 0, geometry.screenWidth, height)
        return (task.token?.let { WindowContainerTransactionCompat.applyTaskBounds(it, bounds) }
            ?: ActivityTaskManagerCompat.resizeTask(task.taskId, bounds)).onSuccess { shortenedTaskId = task.taskId }
    }

    private fun notifyHomePresented() {
        if (homePresented) return
        homePresented = true
        onHomePresented?.invoke()
    }

    private fun clearFullscreenBounds(
        task: TaskRepository.TaskSnapshot,
    ): Result<Unit> {
        // The display-move owner already cleared the fullscreen override during
        // layout restoration. Do not request a second layout after the move.
        if (TaskSurfaceCompat.hasPreparedPromotion(task.taskId)) return Result.success(Unit)
        val fullscreenMode = ActivityTaskManagerCompat
            .resolveWindowingMode("WINDOWING_MODE_FULLSCREEN")
            ?: WINDOWING_MODE_FULLSCREEN
        if (task.vendorWindowed || task.displayId != Display.DEFAULT_DISPLAY || task.windowingMode != fullscreenMode) {
            return Result.success(Unit)
        }

        NeXtepLog.info(
            "main_task_bounds",
            "Clearing fullscreen bounds override taskId=${task.taskId}",
        )
        // Empty bounds remove the override, including when the resolved bounds already
        // equal the display. Comparing resolved bounds cannot detect a stale override.
        return task.token?.let { token ->
            WindowContainerTransactionCompat.applyTaskBounds(token, Rect())
        } ?: ActivityTaskManagerCompat.resizeTask(task.taskId, Rect())
    }

    private companion object {
        const val WINDOWING_MODE_FULLSCREEN = 1
    }

    private fun isMainFullscreen(task: TaskRepository.TaskSnapshot): Boolean =
        !task.vendorWindowed && task.displayId == Display.DEFAULT_DISPLAY &&
            task.windowingMode == WINDOWING_MODE_FULLSCREEN

}
