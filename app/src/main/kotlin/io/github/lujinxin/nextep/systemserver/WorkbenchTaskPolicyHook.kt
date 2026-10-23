package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.framework.CachedMember
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.workspace.WorkspaceSceneRequest

/** Optional ROM hooks. Missing members retain native behaviour. */
internal object WorkbenchTaskPolicyHook {
    private val taskId = CachedMember(listOf("mTaskId", "taskId"))
    private val task = CachedMember(emptyList(), listOf("getTask"))
    private val displayId = CachedMember(emptyList(), listOf("getDisplayId"))
    private val service = CachedMember(listOf("mAtmService"))
    private val context = CachedMember(listOf("mContext"))
    private val handler = CachedMember(listOf("mH"))
    private val windowManager = CachedMember(listOf("mWindowManager"))
    private val mainWindow = CachedMember(emptyList(), listOf("findMainWindow"))
    private val topActivity = CachedMember(emptyList(), listOf("getTopNonFinishingActivity", "topRunningActivity", "getTopMostActivity"))
    private val activityRecord = CachedMember(listOf("mActivityRecord"))
    private val requestedOrientation = CachedMember(emptyList(), listOf("getRequestedOrientation", "getOverrideOrientation"))
    private val visibleTypes = CachedMember(listOf("mRequestedVisibleTypes"), listOf("getRequestedVisibleTypes"))
    private val attributes = CachedMember(listOf("mAttrs"))

    fun installScenes(module: XposedModule, loader: ClassLoader) {
        // WindowState carries the actual app request, unlike transient system bar visibility.
        val insetsMethods = loader.loadClass("com.android.server.wm.WindowState").declaredMethods.filter {
            it.name == "setRequestedVisibleTypes" && it.parameterCount in 1..2 &&
                it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType }
        }
        loader.loadClass("com.android.server.wm.WindowManagerService").declaredMethods
            .filter { it.name == "updateRequestedVisibleTypes" }.forEach { module.deoptimize(it) }
        insetsMethods.forEach { target ->
            target.isAccessible = true
            module.hook(target).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    runCatching {
                        val window = chain.thisObject ?: return@runCatching
                        val activity = activityRecord.read(window) ?: return@runCatching
                        val orientation = requestedOrientation.read(activity) as? Int ?: return@runCatching
                        reportScene(activity, orientation, window)
                    }.onFailure { NeXtepLog.warn("workbench_scene", "Fullscreen hint unavailable", it) }
                    return result
                }
            })
        }
        val method = loader.loadClass("com.android.server.wm.ActivityRecord")
            .getDeclaredMethod("setRequestedOrientation", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                runCatching {
                    val orientation = chain.getArg(0) as? Int ?: return@runCatching
                    val activity = chain.thisObject ?: return@runCatching
                    reportScene(activity, orientation, mainWindow.read(activity))
                }.onFailure { NeXtepLog.warn("workbench_scene", "Scene hint unavailable; keeping native rotation", it) }
                return chain.proceed()
            }
        })
        NeXtepLog.info("workbench_scene", "Installed fullscreen scene observer insets=${insetsMethods.size}")
        module.log(android.util.Log.INFO, "NeXtep", "Installed fullscreen scene observer insets=${insetsMethods.size}")
    }

    /**
     * A landscape request that reached rotation policy before any hook saw it: Activities whose
     * orientation is declared in the manifest never call setRequestedOrientation, and WindowManager
     * then applies a fixed-rotation transform before SystemUI can pin the task.
     */
    fun reportOrientationSource(source: Any?, orientation: Int) {
        val container = source ?: return skipped("no orientation source", orientation)
        runCatching {
            // The source is an ActivityRecord, or the Task that carries it; anything else
            // (magnification, dreams) is ignored.
            val activity = if (task.read(container) != null) container else topActivity.read(container)
                ?: return skipped("source ${container.javaClass.simpleName} has no task", orientation)
            NeXtepLog.info("workbench_scene", "Landscape request from orientation source ${container.javaClass.simpleName}")
            reportScene(activity, orientation, mainWindow.read(activity))
        }.onFailure { NeXtepLog.warn("workbench_scene", "Orientation source unavailable", it) }
    }

    @Volatile private var lastSkip = ""
    @Volatile private var lastSkipAt = 0L
    private fun skipped(reason: String, orientation: Int) {
        // Rate-limited: this path runs for every visibility change; log a new reason or every 2 s.
        val now = SystemClock.uptimeMillis()
        if (reason == lastSkip && now - lastSkipAt < 2_000L) return
        lastSkip = reason; lastSkipAt = now
        NeXtepLog.info("workbench_scene", "Scene skipped reason=$reason orientation=$orientation")
    }

    private fun reportScene(activity: Any, orientation: Int, window: Any?) {
        val owner = service.read(activity) ?: return skipped("no service", orientation)
        val ctx = context.read(owner) as? Context ?: return skipped("no context", orientation)
        // RAM checks only: this runs for every requested-visibility change on every window.
        if (!SystemServerWorkspaceBridge.observedActive(ctx)) return
        val display = displayId.read(activity) as? Int ?: return skipped("no display", orientation)
        if (display != 0 && !SystemServerWorkspaceBridge.isWideDisplay(ctx, display)) return skipped("other display $display", orientation)
        if (display == 0 && SystemServerWorkspaceBridge.hasWideDisplay(ctx)) return skipped("wide display exists", orientation)
        val id = task.read(activity)?.let { taskId.read(it) as? Int } ?: return skipped("no task", orientation)
        val attrs = window?.let(attributes::read) as? android.view.WindowManager.LayoutParams
        val types = window?.let(visibleTypes::read) as? Int
        val fullscreen = (types != null && types and android.view.WindowInsets.Type.statusBars() == 0) ||
            (attrs != null && attrs.flags and android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
        val previous = SystemServerWorkspaceBridge.scene(ctx)
        if (previous?.taskId == id && previous.orientation == orientation && previous.fullscreen == fullscreen) return
        val wm = windowManager.read(owner)
        val update = wm?.javaClass?.methods?.firstOrNull {
            it.name == "updateRotation" && it.parameterTypes.contentEquals(
                arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
        }
        val wmHandler = handler.read(owner) as? Handler
        // Hold a portrait display briefly only if a native re-evaluation is guaranteed, so a
        // landscape request pins to the wide slot instead of rotating the whole workspace.
        // A failed/unavailable pin never leaves it locked.
        val scheduled = if (display == 0 && WorkspaceSceneRequest.landscape(orientation) &&
            wm != null && update != null && wmHandler != null) {
            wmHandler.postDelayed({ runCatching { update.invoke(wm, false, false) } }, 1_800L)
        } else false
        SystemServerWorkspaceBridge.publishScene(ctx,
            WorkspaceSceneRequest(id, orientation, SystemClock.uptimeMillis(), scheduled, fullscreen))
        NeXtepLog.info("workbench_scene", "Published scene task=$id orientation=$orientation fullscreen=$fullscreen hold=$scheduled")
    }

    fun installRecents(module: XposedModule, loader: ClassLoader) {
        val method = loader.loadClass("com.android.server.wm.RecentTasks").declaredMethods.first {
            it.name == "isVisibleRecentTask" && it.parameterCount == 1 &&
                it.parameterTypes[0].name == "com.android.server.wm.Task"
        }.apply { isAccessible = true }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val id = runCatching { chain.getArg(0)?.let { taskId.read(it) as? Int } }.getOrNull()
                if (id != null && SystemServerWorkspaceBridge.privateTask(id)) return false
                return chain.proceed()
            }
        })
    }
}
