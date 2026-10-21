package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.Display
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface

/** Allow both landscape directions in workspace; rotation locks remain system-owned. */
class WorkspaceLandscapeRotationHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val heldRotation = runCatching {
            val orientation = chain.getArg(0) as? Int ?: return@runCatching null
            if (!io.github.lujinxin.nextep.workspace.WorkspaceSceneRequest.landscape(orientation)) return@runCatching null
            val target = chain.thisObject ?: return@runCatching null
            val display = field(target, "mDisplayContent") ?: return@runCatching null
            if (field(display, "mDisplayId") != Display.DEFAULT_DISPLAY) return@runCatching null
            val context = field(target, "mContext") as? Context ?: return@runCatching null
            if (!SystemServerWorkspaceBridge.isWorkspaceActive(context)) return@runCatching null
            val portrait = field(target, "mPortraitRotation") as? Int ?: return@runCatching null
            val last = chain.getArg(1) as? Int ?: return@runCatching null
            if (last == portrait && SystemServerWorkspaceBridge.scene(context)
                    ?.holdRotation(android.os.SystemClock.uptimeMillis()) == true) last else null
        }.getOrNull()
        if (heldRotation != null) return heldRotation
        val allowBothDirections = runCatching {
            if (chain.getArg(0) != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) return@runCatching false
            val target = chain.thisObject ?: return@runCatching false
            val display = field(target, "mDisplayContent") ?: return@runCatching false
            if (field(display, "mDisplayId") != Display.DEFAULT_DISPLAY) return@runCatching false
            val context = field(target, "mContext") as? Context ?: return@runCatching false
            SystemServerWorkspaceBridge.isWorkspaceActive(context)
        }.getOrDefault(false)
        // Let Android choose between landscape/seascape using its sensors, docks
        // and user lock policy, instead of hardcoding a result for fixed players.
        val original = if (allowBothDirections) {
            val args = chain.args.toTypedArray()
            args[0] = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            chain.proceed(args)
        } else chain.proceed()
        val rotation = original as? Int ?: return original
        return runCatching {
            val target = chain.thisObject ?: return@runCatching original
            val display = field(target, "mDisplayContent") ?: return@runCatching original
            if (field(display, "mDisplayId") != Display.DEFAULT_DISPLAY) return@runCatching original
            val context = field(target, "mContext") as? Context ?: return@runCatching original
            if (!SystemServerWorkspaceBridge.isWorkspaceActive(context)) return@runCatching original
            val orientation = chain.getArg(0) as? Int ?: return@runCatching original
            if (orientation !in setOf(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
                )) return@runCatching original
            val landscape = field(target, "mLandscapeRotation") as? Int ?: return@runCatching original
            val seascape = field(target, "mSeascapeRotation") as? Int ?: return@runCatching original
            if (rotation != landscape) return@runCatching original
            val lastRotation = chain.getArg(1) as? Int ?: return@runCatching original
            val flexibleLandscape = allowBothDirections || orientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            if (flexibleLandscape && (lastRotation == landscape || lastRotation == seascape)) {
                return@runCatching original
            }
            // Be conservative on vendor ROMs: a missing policy field means no override.
            val sensorRotation = field(target, "mLastSensorRotation") as? Int
                ?: return@runCatching original
            if (flexibleLandscape && (sensorRotation == landscape || sensorRotation == seascape)) {
                return@runCatching original
            }
            val userRotation = field(target, "mUserRotation") as? Int ?: return@runCatching original
            if (userRotation == landscape || userRotation == seascape) return@runCatching original
            val portrait = field(target, "mPortraitRotation") as? Int ?: return@runCatching original
            // In display coordinates the portrait top edge is on the right after a
            // counter-clockwise quarter turn. Use the device mapping, not a hardcoded
            // SCREEN_ORIENTATION_LANDSCAPE/REVERSE_LANDSCAPE assumption.
            val preferred = (portrait + 3) % 4
            if (preferred != landscape && preferred != seascape) return@runCatching original
            if (rotation != preferred) {
                NeXtepLog.info("workspace_landscape_rotation", "Main display rotation=$rotation -> $preferred")
            }
            preferred
        }.onFailure {
            NeXtepLog.warn("workspace_landscape_rotation", "Rotation policy unavailable; keeping system result", it)
        }.getOrDefault(original)
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true }?.get(target)
}
