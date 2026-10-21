package io.github.lujinxin.nextep.systemserver

import android.content.Context
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface

class FixedOrientationAspectRatioHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val context = findContext(chain.thisObject) ?: return chain.proceed()
        val activity = readField(chain.thisObject, "mActivityRecord")
        val displayId = activity?.let {
            runCatching { it.javaClass.getMethod("getDisplayId").invoke(it) as? Int }.getOrNull()
        }
        if (displayId != null && SystemServerWorkspaceBridge.isWideDisplay(context, displayId)) return chain.proceed()
        val aspectRatio = SystemServerWorkspaceBridge.activePhysicalAspectRatio(context)
            ?: return chain.proceed()
        NeXtepLog.info("fixed_orientation_aspect", "Using recorded usable aspectRatio=$aspectRatio")
        return aspectRatio
    }

    private fun findContext(target: Any?): Context? = runCatching {
        val activityRecord = readField(target, "mActivityRecord") ?: return@runCatching null
        val service = readField(activityRecord, "mAtmService") ?: return@runCatching null
        readField(service, "mContext") as? Context
    }.onFailure { error ->
        NeXtepLog.warn("fixed_orientation_aspect", "Context lookup failed open", error)
    }.getOrNull()

    private fun readField(target: Any?, name: String): Any? {
        var type: Class<*>? = target?.javaClass
        while (type != null && type != Any::class.java) {
            runCatching {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }
            type = type.superclass
        }
        return null
    }
}
