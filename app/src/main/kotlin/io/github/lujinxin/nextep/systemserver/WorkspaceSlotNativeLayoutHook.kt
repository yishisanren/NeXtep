package io.github.lujinxin.nextep.systemserver

import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.util.concurrent.atomic.AtomicBoolean

/** A preview retains the physical display's native layout, including its status-bar space. */
internal object WorkspaceSlotNativeLayoutHook {
    private data class Reference(val timestamp: Long, val policy: Any, val info: Any, val state: Any)
    private val references = linkedMapOf<String, Reference>()
    private val warned = AtomicBoolean(false)

    fun install(module: XposedModule, loader: ClassLoader) {
        val policyClass = loader.loadClass("com.android.server.wm.DisplayPolicy")
        val decorMethod = policyClass.getDeclaredMethod(
            "getDecorInsetsInfo", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val framesClass = loader.loadClass("com.android.server.wm.DisplayFrames")
        val framesConstructor = framesClass.getDeclaredConstructor().apply { isAccessible = true }
        module.hook(decorMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val original = chain.proceed() ?: return null
                return runCatching {
                    val display = field(chain.thisObject ?: return original, "mDisplayContent") ?: return original
                    if (!isSlot(display)) return original
                    val native = reference(display, chain.getArg(0) as Int, chain.getArg(1) as Int,
                        chain.getArg(2) as Int) { framesConstructor.newInstance() }
                    // Return an independent Info. Neither display's cached Info is modified.
                    val copy = original.javaClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                    call(copy, "set", native.info)
                    copy
                }.getOrElse { failure("Decor configuration", it); original }
            }
        })
        val windowClass = loader.loadClass("com.android.server.wm.WindowState")
        val insetsMethod = windowClass.getDeclaredMethod("getInsetsState", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        module.hook(insetsMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val original = chain.proceed() ?: return null
                return runCatching {
                    val window = chain.thisObject ?: return original
                    val display = call(window, "getDisplayContent") ?: return original
                    if (!isSlot(display)) return original
                    val attrs = field(window, "mAttrs") as? WindowManager.LayoutParams ?: return original
                    if (attrs.type !in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..
                        WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) return original
                    val frame = call(original, "getDisplayFrame") as? Rect ?: return original
                    val info = call(window, "getDisplayInfo") ?: return original
                    val rotation = field(info, "rotation") as? Int ?: return original
                    val native = reference(display, rotation, frame.width(), frame.height()) { framesConstructor.newInstance() }
                    val stateClass = original.javaClass
                    val copy = stateClass.getConstructor(stateClass, Boolean::class.javaPrimitiveType)
                        .newInstance(original, true)
                    val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.systemGestures() or WindowInsets.Type.mandatorySystemGestures() or
                        WindowInsets.Type.tappableElement()
                    for (index in (call(copy, "sourceSize") as Int) - 1 downTo 0) {
                        val source = checkNotNull(call(copy, "sourceAt", index))
                        if ((call(source, "getType") as Int) and types != 0) {
                            call(copy, "removeSource", call(source, "getId") as Int)
                        }
                    }
                    for (index in 0 until (call(native.state, "sourceSize") as Int)) {
                        val source = checkNotNull(call(native.state, "sourceAt", index))
                        val type = call(source, "getType") as Int
                        if (type and types == 0) continue
                        val cloned = source.javaClass.getConstructor(source.javaClass).newInstance(source)
                        if (type and WindowInsets.Type.systemBars() != 0) {
                            val requested = call(window, "isRequestedVisible", type) as? Boolean ?: true
                            call(cloned, "setVisible", requested)
                        }
                        call(copy, "addSource", cloned)
                    }
                    call(native.state, "getDisplayCutout")?.let { call(copy, "setDisplayCutout", it) }
                    copy
                }.getOrElse { failure("Window insets", it); original }
            }
        })
        NeXtepLog.info("slot_native_layout", "Installed native display layout matching for NeXtep slots")
    }

    @Synchronized
    private fun reference(display: Any, rotation: Int, width: Int, height: Int, newFrames: () -> Any): Reference {
        val service = checkNotNull(field(display, "mWmService"))
        val physical = checkNotNull(call(checkNotNull(field(service, "mRoot")), "getDefaultDisplay"))
        val policy = checkNotNull(call(physical, "getDisplayPolicy"))
        val key = "$rotation:$width:$height"
        val now = SystemClock.uptimeMillis()
        references[key]?.takeIf { it.policy === policy && now - it.timestamp < 1_000L }?.let { return it }
        val nativeInfo = checkNotNull(call(policy, "getDecorInsetsInfo", rotation, width, height))
        val frames = newFrames()
        call(physical, "updateDisplayFrames", frames, rotation, width, height)
        call(policy, "simulateLayoutDisplay", frames)
        val state = checkNotNull(field(frames, "mInsetsState"))
        if (references.size >= 8) references.clear()
        return Reference(now, policy, nativeInfo, state).also { references[key] = it }
    }

    private fun isSlot(display: Any): Boolean =
        (call(display, "getDisplayInfo")?.let { field(it, "name") } as? String)?.startsWith("NeXtep-slot-") == true

    private fun failure(operation: String, error: Throwable) {
        if (warned.compareAndSet(false, true)) NeXtepLog.warn("slot_native_layout", "$operation failed open", error)
    }

    // getInsetsState runs on every layout of every window: resolve each member once per class.
    private val fields = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Field>()
    private val methods = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()

    private fun field(target: Any, name: String): Any? {
        val key = "${target.javaClass.name}#$name"
        val member = fields[key] ?: generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true; fields[key] = this } ?: return null
        return member.get(target)
    }

    private fun call(target: Any, name: String, vararg args: Any): Any? {
        val key = "${target.javaClass.name}#$name/${args.size}"
        val method = methods[key] ?: generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == args.size }
            ?.apply { isAccessible = true; methods[key] = this }
            ?: error("Missing ${target.javaClass.simpleName}#$name/${args.size}")
        return method.invoke(target, *args)
    }
}
