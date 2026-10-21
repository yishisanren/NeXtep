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

    fun installScenes(module: XposedModule, loader: ClassLoader) {
        val method = loader.loadClass("com.android.server.wm.ActivityRecord")
            .getDeclaredMethod("setRequestedOrientation", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                runCatching {
                    val orientation = chain.getArg(0) as? Int ?: return@runCatching
                    val activity = chain.thisObject ?: return@runCatching
                    val owner = service.read(activity) ?: return@runCatching
                    val ctx = context.read(owner) as? Context ?: return@runCatching
                    if (!SystemServerWorkspaceBridge.isWorkspaceActive(ctx)) return@runCatching
                    val display = displayId.read(activity) as? Int ?: return@runCatching
                    if (display != 0 && !SystemServerWorkspaceBridge.isWideDisplay(ctx, display)) return@runCatching
                    if (display == 0 && !WorkspaceSceneRequest.landscape(orientation)) return@runCatching
                    val id = task.read(activity)?.let { taskId.read(it) as? Int } ?: return@runCatching
                    val previous = SystemServerWorkspaceBridge.scene(ctx)
                    if (previous?.taskId == id && previous.orientation == orientation) return@runCatching
                    val wm = windowManager.read(owner)
                    val update = wm?.javaClass?.methods?.firstOrNull {
                        it.name == "updateRotation" && it.parameterTypes.contentEquals(
                            arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
                    }
                    val wmHandler = handler.read(owner) as? Handler
                    // Hold a portrait display briefly only if a native re-evaluation
                    // is guaranteed. A failed/unavailable pin never leaves it locked.
                    val scheduled = if (display == 0 && WorkspaceSceneRequest.landscape(orientation) &&
                        wm != null && update != null && wmHandler != null) {
                        wmHandler.postDelayed({ runCatching { update.invoke(wm, false, false) } }, 1_800L)
                    } else false
                    SystemServerWorkspaceBridge.publishScene(ctx,
                        WorkspaceSceneRequest(id, orientation, SystemClock.uptimeMillis(), scheduled))
                }.onFailure { NeXtepLog.warn("workbench_scene", "Scene hint unavailable; keeping native rotation", it) }
                return chain.proceed()
            }
        })
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
