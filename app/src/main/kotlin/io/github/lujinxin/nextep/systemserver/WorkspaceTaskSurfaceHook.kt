package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.graphics.Rect
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.util.Collections
import java.util.WeakHashMap

/** Apply destination coordinates within reparent, before either task becomes visible. */
internal object WorkspaceTaskSurfaceHook {
    private val promotedTasks = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val slotTasks = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val setMatrix by lazy {
        SurfaceControl.Transaction::class.java.getDeclaredMethod("setMatrix", SurfaceControl::class.java,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        val taskClass = loader.loadClass("com.android.server.wm.Task")
        val displayClass = loader.loadClass("com.android.server.wm.DisplayContent")
        module.hook(taskClass.getDeclaredMethod("onDisplayChanged", displayClass).apply { isAccessible = true })
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val task = chain.thisObject ?: return chain.proceed()
                    val destination = chain.args.firstOrNull()
                    val fromSlot = runCatching {
                        val source = call(task, "getDisplayContent") ?: return@runCatching false
                        isSlotDisplay(source)
                    }.getOrDefault(false)
                    promotedTasks.remove(task)
                    slotTasks.remove(task)
                    val result = chain.proceed()
                    runCatching {
                        if (destination == null) return@runCatching
                        when {
                            isSlotDisplay(destination) -> {
                                // Keep the outgoing snapshot fitted while it still
                                // belongs to display 0. The native finish transaction
                                // reparents and resets it to slot coordinates together.
                                slotTasks[task] = true
                                return@runCatching
                            }
                            fromSlot && call(destination, "getDisplayId") == 0 -> promotedTasks[task] = true
                            else -> return@runCatching
                        }
                        val leash = call(task, "getSurfaceControl") as? SurfaceControl ?: return@runCatching
                        val sync = call(task, "getSyncTransaction") as? SurfaceControl.Transaction
                        // Only the reparent's sync transaction owns this change.
                        // A separate pending transaction can reset the matrix while
                        // the outgoing snapshot is still attached to display 0.
                        sync?.let { fit(task, leash, it) }
                    }.onFailure { NeXtepLog.warn("workspace_task_surface", "Early task fitting unavailable", it) }
                    return result
                }
            })
        val reset = loader.loadClass("com.android.server.wm.Transition").declaredMethods.single {
            it.name == "resetSurfaceTransform" && it.parameterCount == 3
        }.apply { isAccessible = true }
        module.hook(reset).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    val task = chain.args.getOrNull(1) ?: return@runCatching
                    if (!promotedTasks.containsKey(task) && !slotTasks.containsKey(task)) return@runCatching
                    val transaction = chain.args[0] as? SurfaceControl.Transaction ?: return@runCatching
                    val leash = chain.args[2] as? SurfaceControl ?: return@runCatching
                    if (call(task, "getSurfaceControl") !== leash) return@runCatching
                    fit(task, leash, transaction)
                }.onFailure { NeXtepLog.warn("workspace_task_surface", "Finish task fitting unavailable", it) }
                return result
            }
        })
        NeXtepLog.info("workspace_task_surface", "Installed atomic main/slot destination fitting")
    }

    private fun fit(task: Any, leash: SurfaceControl, transaction: SurfaceControl.Transaction) {
        if (!leash.isValid || call(task, "getWindowingMode") != 1) return
        val configuration = call(task, "getConfiguration") ?: return
        val extra = field(configuration, "mOplusExtraConfiguration")
        if (extra != null && call(extra, "getScenario") in setOf(1, 2)) {
            // ColorOS native floating tasks may still report fullscreen windowing mode.
            promotedTasks.remove(task)
            slotTasks.remove(task)
            return
        }
        val display = call(task, "getDisplayContent") ?: return
        if (slotTasks.containsKey(task) && isSlotDisplay(display)) {
            // The virtual display is already scaled by its TextureView. Applying
            // the main transform here scales twice and exposes wallpaper at top/left.
            setMatrix.invoke(transaction, leash, 1f, 0f, 0f, 1f)
            transaction.setCrop(leash, null as Rect?)
            transaction.setPosition(leash, 0f, 0f)
            return
        }
        if (call(task, "getDisplayId") != 0 || !promotedTasks.containsKey(task)) return
        val service = field(task, "mAtmService") ?: return
        val context = field(service, "mContext") as? Context ?: return
        val geometry = SystemServerWorkspaceBridge.activeGeometry(context) ?: return
        setMatrix.invoke(transaction, leash, geometry.mainScale, 0f, 0f, geometry.mainScale)
        transaction.setCrop(leash, Rect(geometry.availableLeft, geometry.availableTop,
            geometry.availableRight, geometry.availableBottom))
        transaction.setPosition(leash, geometry.mainTranslationX, geometry.mainTranslationY)
    }

    private fun isSlotDisplay(display: Any): Boolean {
        val info = call(display, "getDisplayInfo") ?: return false
        return (field(info, "name") as? String)?.startsWith("NeXtep-slot-") == true
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true }?.invoke(target)
}
