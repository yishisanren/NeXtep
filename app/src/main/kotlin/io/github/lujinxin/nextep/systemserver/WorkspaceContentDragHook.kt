package io.github.lujinxin.nextep.systemserver

import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.framework.CachedMember
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge

/** Enroll promoted windows in the native drag, retaining its permission checks. */
internal object WorkspaceContentDragHook {
    private val attrs = CachedMember(listOf("mAttrs"))
    private val task = CachedMember(emptyList(), listOf("getTask"))
    private val display = CachedMember(emptyList(), listOf("getDisplayId"))
    private val mode = CachedMember(emptyList(), listOf("getWindowingMode"))
    private val service = CachedMember(listOf("mWmService"))
    private val controller = CachedMember(listOf("mDragDropController"))
    private val dragging = CachedMember(emptyList(), listOf("dragDropActiveLocked"))

    fun install(module: XposedModule, loader: ClassLoader) {
        val windowClass = loader.loadClass("com.android.server.wm.WindowState")
        val displayClass = loader.loadClass("com.android.server.wm.DisplayContent")
        val changed = windowClass.getDeclaredMethod("onDisplayChanged", displayClass).apply { isAccessible = true }
        val enroll = loader.loadClass("com.android.server.wm.DragDropController")
            .getDeclaredMethod("sendDragStartedIfNeededLocked", windowClass).apply { isAccessible = true }
        module.hook(changed).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (SystemServerWorkspaceBridge.observedActiveGeometry() == null) return result
                    runCatching {
                        val window = chain.thisObject ?: return@runCatching
                        val params = attrs.read(window) as? WindowManager.LayoutParams ?: return@runCatching
                        if (params.type !in 1..99 || display.read(window) != 0) return@runCatching
                        val owner = task.read(window) ?: return@runCatching
                        if (mode.read(owner) != 1) return@runCatching
                        val dragController = service.read(window)?.let { controller.read(it) } ?: return@runCatching
                        if (dragging.read(dragController) != true) return@runCatching
                        // WM holds its global lock here. The native helper deduplicates
                        // STARTED and validates source flags, users and target capability.
                        enroll.invoke(dragController, window)
                    }.onFailure { NeXtepLog.warn("content_drag", "Native drag enrollment unavailable", it) }
                    return result
                }
            })
        module.log(android.util.Log.INFO, "NeXtep", "Installed native content drag enrollment")
    }
}
