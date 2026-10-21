package io.github.lujinxin.nextep.task

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.view.Display
import android.os.UserHandle
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.logging.NeXtepLog

class TaskRepository(context: Context) {
    data class RetainedTask(
        val taskId: Int,
        val userId: Int,
        val baseComponent: ComponentName,
        val lastActiveTime: Long,
    )

    /** Includes recent tasks whose app process was reclaimed; a failed query is not an empty list. */
    // Runs in LSPosed-injected SystemUI, where privileged task APIs are required.
    // Keep the exemption local; ordinary APK code must not use this hidden API.
    @SuppressLint("BlockedPrivateApi")
    fun recentTasks(userId: Int): Result<List<RetainedTask>> = runCatching {
        val service = Class.forName("android.app.ActivityTaskManager")
            .getDeclaredMethod("getService").invoke(null)
        val result = service.javaClass.getMethod(
            "getRecentTasks", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(service, 256, 0, userId)
        val list = result.javaClass.getMethod("getList").invoke(result) as List<*>
        list.filterIsInstance<ActivityManager.RecentTaskInfo>().mapNotNull { info ->
            val base = info.baseIntent.component ?: info.baseActivity ?: return@mapNotNull null
            RetainedTask(
                info.taskId,
                info.javaClass.getField("userId").getInt(info),
                base,
                info.javaClass.getField("lastActiveTime").getLong(info),
            )
        }
    }

    // Same injected SystemUI context as recentTasks; failures remain explicit Results.
    @SuppressLint("BlockedPrivateApi")
    fun restoreRecentTask(taskId: Int, displayId: Int): Result<Unit> = runCatching {
        val service = Class.forName("android.app.ActivityTaskManager")
            .getDeclaredMethod("getService").invoke(null)
        val options = android.app.ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
        service.javaClass.getMethod(
            "startActivityFromRecents", Int::class.javaPrimitiveType, android.os.Bundle::class.java,
        ).invoke(service, taskId, options.toBundle())
    }

    data class TaskSnapshot(
        val taskId: Int,
        val displayId: Int,
        val component: ComponentName,
        val bounds: Rect,
        val windowingMode: Int,
        val resizeMode: Int?,
        val densityDpi: Int,
        val token: Any?,
        val userId: Int?,
        val vendorWindowed: Boolean = false,
        val baseComponent: ComponentName = component,
    )

    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val contextPackageManager = context.packageManager

    fun foregroundTask(): TaskSnapshot? = try {
        runningTasks().firstOrNull { snapshot ->
            snapshot.displayId == Display.DEFAULT_DISPLAY
        }
    } catch (error: Throwable) {
        NeXtepLog.error("task_repository", "Foreground task query failed", error)
        null
    }

    fun findTask(taskId: Int): TaskSnapshot? = try {
        runningTasks().firstOrNull { it.taskId == taskId }
    } catch (error: Throwable) {
        NeXtepLog.error("task_repository", "Task lookup failed taskId=$taskId", error)
        null
    }

    fun findTaskForComponent(
        component: ComponentName,
        userHandle: UserHandle? = null,
    ): TaskSnapshot? {
        val tasks = runningTasks().let { snapshots ->
            val userId = userHandle?.let(TaskInfoCompat::userIdentifier) ?: return@let snapshots
            snapshots.filter { it.userId == userId }
        }
        tasks.firstOrNull { it.baseComponent == component }?.let { return it }
        tasks.firstOrNull { it.component == component }?.let { return it }
        val launcher = runCatching { contextPackageManager.getLaunchIntentForPackage(component.packageName)?.component }.getOrNull()
        return selectForLaunch(tasks, component, launcher == component)
    }

    internal fun selectForLaunch(tasks: List<TaskSnapshot>, component: ComponentName, launcher: Boolean): TaskSnapshot? {
        // A mini-program and the ordinary app can share a package but own
        // different tasks. A launcher click must not steal the mini-program.
        tasks.firstOrNull { it.baseComponent == component }?.let { return it }
        tasks.firstOrNull { it.component == component }?.let { return it }
        if (launcher) return null
        return tasks.filter { it.component.packageName == component.packageName }.singleOrNull()
    }

    fun bringTaskToFront(taskId: Int): Result<Unit> = runCatching {
        val manager = checkNotNull(activityManager) { "ActivityManager unavailable" }
        manager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
        NeXtepLog.info("task_repository", "Brought taskId=$taskId to front")
    }

    @Suppress("DEPRECATION")
    fun runningTasks(): List<TaskSnapshot> = try {
        activityManager?.getRunningTasks(MAX_TASK_QUERY)
            ?.mapNotNull(::snapshot)
            .orEmpty()
    } catch (error: Throwable) {
        NeXtepLog.error("task_repository", "Running task query failed", error)
        emptyList()
    }

    private fun snapshot(info: ActivityManager.RunningTaskInfo): TaskSnapshot? {
        val component = info.topActivity ?: info.baseActivity ?: return null
        val windowState = TaskInfoCompat.readWindowState(info) ?: return null
        TaskSurfaceCompat.observeTaskState(info.taskId, windowState)
        return TaskSnapshot(
            taskId = info.taskId,
            displayId = windowState.displayId,
            component = component,
            bounds = windowState.bounds,
            windowingMode = windowState.windowingMode,
            resizeMode = windowState.resizeMode,
            densityDpi = windowState.densityDpi,
            token = windowState.token,
            userId = TaskInfoCompat.readUserId(info),
            vendorWindowed = windowState.vendorWindowed,
            baseComponent = info.baseIntent.component ?: info.baseActivity ?: component,
        )
    }

    private companion object {
        const val MAX_TASK_QUERY = 64
    }
}
