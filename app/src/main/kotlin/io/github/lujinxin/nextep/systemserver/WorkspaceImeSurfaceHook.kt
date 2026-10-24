package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.graphics.Rect
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * WM attaches the input method to a fullscreen app's surface only when the app covers the display
 * (DisplayContent.shouldImeAttachedToApp), so it scales with the main app. Under a wide picture the
 * main app lays out on a shortened canvas and the keyboard stays on the display: full width, partly
 * under the rail. Give its container the main app's transform whenever WM leaves it there; the
 * canvas sits at the screen bottom, so keyboard and app meet.
 */
internal object WorkspaceImeSurfaceHook {
    private val methods = ConcurrentHashMap<String, Optional<Method>>()
    private val fields = ConcurrentHashMap<String, Optional<Field>>()
    private val setMatrix by lazy {
        SurfaceControl.Transaction::class.java.getDeclaredMethod("setMatrix", SurfaceControl::class.java,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }
    }
    @Volatile private var fitted: SurfaceControl? = null
    @Volatile private var context: Context? = null
    private var reportedFailure = false

    fun install(module: XposedModule, loader: ClassLoader) {
        val method = loader.loadClass("com.android.server.wm.DisplayContent")
            .getDeclaredMethod("prepareSurfaces").apply { isAccessible = true }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching { chain.thisObject?.let(::fit) }.onFailure {
                    if (!reportedFailure) {
                        reportedFailure = true
                        NeXtepLog.warn("workspace_ime_surface", "Input method fitting unavailable", it)
                    }
                }
                return result
            }
        })
        NeXtepLog.info("workspace_ime_surface", "Installed input method fitting for the shortened main canvas")
    }

    private fun fit(display: Any) {
        if (call(display, "getDisplayId") != 0) return
        val context = context ?: (field(display, "mWmService")?.let { field(it, "mContext") } as? Context)
            ?.also { context = it } ?: return
        // RAM only: this runs on every surface placement, inside the WM lock.
        val geometry = if (SystemServerWorkspaceBridge.observedActive(context))
            SystemServerWorkspaceBridge.observedActiveGeometry() else null
        if (geometry == null && fitted == null) return
        val transaction = call(display, "getPendingTransaction") as? SurfaceControl.Transaction ?: return
        val container = call(display, "getImeContainer") ?: field(display, "mImeWindowsContainer")
        val surface = container?.let { call(it, "getSurfaceControl") } as? SurfaceControl
        val task = (field(display, "mImeLayeringTarget") ?: field(display, "mImeTarget"))?.let { call(it, "getTask") }
        // The main app's own bounds (the shortened canvas under a wide picture, else the screen); WM
        // attaches the keyboard to it when they cover the display, and then the app's transform applies.
        val fit = geometry != null && surface != null && task != null &&
            call(task, "getWindowingMode") == 1 &&
            call(task, "getBounds") == Rect(0, geometry.mainTaskTop, geometry.screenWidth, geometry.screenHeight) &&
            call(display, "shouldImeAttachedToApp") != true
        if (fit) {
            val g = checkNotNull(geometry)
            setMatrix.invoke(transaction, surface, g.mainScale, 0f, 0f, g.mainScale)
            transaction.setPosition(surface, g.mainTranslationX, g.imeTranslationY)
            if (fitted !== surface) NeXtepLog.info("workspace_ime_surface", "Fitted the input method to the main pane")
            fitted = surface
        } else fitted?.let { previous ->
            if (previous.isValid) {
                setMatrix.invoke(transaction, previous, 1f, 0f, 0f, 1f)
                transaction.setPosition(previous, 0f, 0f)
            }
            fitted = null
            NeXtepLog.info("workspace_ime_surface", "Released the input method fitting")
        }
    }

    private fun field(target: Any, name: String): Any? = fields.getOrPut(target.javaClass.name + "#" + name) {
        Optional.ofNullable(generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true })
    }.orElse(null)?.get(target)

    private fun call(target: Any, name: String): Any? = methods.getOrPut(target.javaClass.name + "." + name) {
        Optional.ofNullable(generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true })
    }.orElse(null)?.invoke(target)
}
