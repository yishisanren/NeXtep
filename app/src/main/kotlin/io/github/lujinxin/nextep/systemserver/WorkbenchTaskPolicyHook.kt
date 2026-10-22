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

    private fun reportScene(activity: Any, orientation: Int, window: Any?) {
        if (window != null && mainWindow.read(activity) !== window) return
        val owner = service.read(activity) ?: return
        val ctx = context.read(owner) as? Context ?: return
        if (!SystemServerWorkspaceBridge.isWorkspaceActive(ctx)) return
        val display = displayId.read(activity) as? Int ?: return
        if (display != 0 && !SystemServerWorkspaceBridge.isWideDisplay(ctx, display)) return
        if (display == 0 && SystemServerWorkspaceBridge.hasWideDisplay(ctx)) return
        val id = task.read(activity)?.let { taskId.read(it) as? Int } ?: return
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
        // Hold a portrait display briefly only if a native re-evaluation
        // is guaranteed. A failed/unavailable pin never leaves it locked.
        val scheduled = if (display == 0 && fullscreen && WorkspaceSceneRequest.landscape(orientation) &&
            wm != null && update != null && wmHandler != null) {
            wmHandler.postDelayed({ runCatching { update.invoke(wm, false, false) } }, 1_800L)
        } else false
        SystemServerWorkspaceBridge.publishScene(ctx,
            WorkspaceSceneRequest(id, orientation, SystemClock.uptimeMillis(), scheduled, fullscreen))
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
