package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.View
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.libxposed.api.XposedInterface

/** Exit a retained player fullscreen UI once after moving it into a portrait slot. */
class SlotVideoFullscreenExitHooker(private val report: (String) -> Unit) : XposedInterface.Hooker {
    private class Migration(
        val activity: Any,
        val display: Any,
        val service: Any,
        val task: Any,
        val sourceLandscape: Boolean,
        val handler: Handler,
    )

    private val pendingExits = java.util.WeakHashMap<Any, Migration>()

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val activity = chain.thisObject ?: return chain.proceed()
        val destination = chain.getArg(0)
        val source = runCatching { field(activity, "mDisplayContent") }.getOrNull()
        val sourceLandscape = runCatching {
            source != null &&
                (call(source, "getConfiguration") as? Configuration)?.orientation == Configuration.ORIENTATION_LANDSCAPE
        }.getOrDefault(false)
        val sourceTask = runCatching { call(activity, "getTask") }.getOrNull()
        val candidate = runCatching {
            source != null && sourceTask != null && source !== destination &&
                field(source, "mFocusedApp") === activity &&
                call(source, "getDisplayId") == 0 && destination != null &&
                isSlot(destination) && !isWideSlot(destination, activity) && isDynamicFullscreen(activity, sourceLandscape).also {
                    report("slot video migration: eligible=$it requested=${call(activity, "getRequestedOrientation")} " +
                        "manifest=${(field(activity, "info") as? ActivityInfo)?.screenOrientation}")
                }
        }.getOrDefault(false)
        val result = chain.proceed()
        if (!candidate || destination == null || sourceTask == null) return result
        report("slot video exit scheduled")
        runCatching {
            val service = checkNotNull(field(activity, "mAtmService"))
            val handler = field(service, "mH") as Handler
            val migration = Migration(activity, destination, service, sourceTask, sourceLandscape, handler)
            synchronized(pendingExits) { pendingExits[activity] = migration }
            // Give the application's normal configuration handler a chance to exit first.
            // ColorOS may need longer than 500 ms to focus the moved window. Retry
            // readiness only; remove the migration before injecting its single BACK.
            scheduleExitCheck(migration, 0, 500L)
        }.onFailure {
            report("slot video schedule failed: $it")
            NeXtepLog.warn("slot_video_exit", "Unable to schedule fullscreen exit", it)
        }
        return result
    }

    private fun scheduleExitCheck(migration: Migration, attempt: Int, delay: Long) {
        val posted = migration.handler.postDelayed({
            if (synchronized(pendingExits) { pendingExits[migration.activity] !== migration }) return@postDelayed
            val waiting = exitIfStillFullscreen(migration)
            if (waiting && attempt < 4) {
                scheduleExitCheck(migration, attempt + 1, 300L)
            } else {
                synchronized(pendingExits) {
                    if (pendingExits[migration.activity] === migration) pendingExits.remove(migration.activity)
                }
                if (waiting) report("slot video exit skipped: window/media did not settle before deadline")
            }
        }, delay)
        if (!posted) synchronized(pendingExits) {
            if (pendingExits[migration.activity] === migration) pendingExits.remove(migration.activity)
        }
    }

    // true means readiness is still pending; false means finished or no longer eligible.
    private fun exitIfStillFullscreen(migration: Migration): Boolean = runCatching {
        val activity = migration.activity
        val display = migration.display
        val service = migration.service
        val lock = checkNotNull(field(service, "mGlobalLock"))
        synchronized(lock) {
            if (field(activity, "mDisplayContent") !== display ||
                call(activity, "getTask") !== migration.task || field(activity, "finishing") == true ||
                !isSlot(display) || !isDynamicFullscreen(activity, migration.sourceLandscape, allowMissingWindow = true)
            ) return@runCatching false
        }
        val context = field(service, "mContext") as Context
        val packageName = field(activity, "packageName") as String
        val sessions = context.getSystemService(MediaSessionManager::class.java)
            ?: return@runCatching false
        val userId = field(activity, "mUserId") as? Int
            ?: field(activity, "userId") as? Int ?: return@runCatching false
        val controllers = sessions.javaClass.methods.firstOrNull {
            it.name == "getActiveSessionsForUser" && it.parameterTypes.size == 2 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.invoke(sessions, null, userId) as? List<*>
            ?: if (userId == 0) sessions.getActiveSessions(null) else return@runCatching false
        val hasPortraitTarget = synchronized(lock) { hasPortraitReturnTarget(activity) }
        val hasMedia = controllers.filterIsInstance<android.media.session.MediaController>().any {
            it.packageName == packageName && (it.playbackState?.state in setOf(
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            ) || hasPortraitTarget)
        }
        if (!hasMedia) {
            return@runCatching true
        }
        val input = context.getSystemService(InputManager::class.java) ?: return@runCatching false
        val inject = input.javaClass.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
        )
        val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        synchronized(lock) {
            // Validate ownership and focus again after the delay. Never send BACK to
            // Display 0, a replacement task, a dialog, or an Activity that already exited.
            if (field(activity, "mDisplayContent") !== display ||
                call(activity, "getTask") !== migration.task || field(activity, "finishing") == true ||
                !isSlot(display) || !isDynamicFullscreen(activity, migration.sourceLandscape, allowMissingWindow = true)
            ) {
                report("slot video exit skipped: Activity moved or fullscreen already cleared")
                return@runCatching false
            }
            val mainWindow = call(activity, "findMainWindow") ?: return@runCatching true
            if (field(display, "mCurrentFocus") !== mainWindow) {
                val focused = field(display, "mFocusedApp")
                return@runCatching focused == null || focused === activity
            }
            val displayId = call(display, "getDisplayId") as? Int ?: return@runCatching false
            if (displayId <= 0) return@runCatching false
            synchronized(pendingExits) {
                if (pendingExits[activity] !== migration) return@runCatching false
                pendingExits.remove(activity)
            }
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(
                    now, now, action, KeyEvent.KEYCODE_BACK, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD,
                )
                setDisplay.invoke(event, displayId)
                // ASYNC: never wait for an app while holding the window-manager lock.
                check(inject.invoke(input, event, 0) == true) { "BACK injection rejected" }
            }
            NeXtepLog.info("slot_video_exit", "Requested one fullscreen exit package=$packageName display=$displayId")
            report("slot video exit sent once display=$displayId")
        }
        false
    }.onFailure {
        report("slot video exit failed: $it")
        NeXtepLog.warn("slot_video_exit", "Fullscreen exit failed open", it)
    }.getOrDefault(false)

    private fun isDynamicFullscreen(
        activity: Any,
        sourceLandscape: Boolean,
        allowMissingWindow: Boolean = false,
    ): Boolean {
        val info = field(activity, "info") as? ActivityInfo ?: return false
        if (info.applicationInfo.category == ApplicationInfo.CATEGORY_GAME) return false
        if (isLandscape(info.screenOrientation) && !hasPortraitReturnTarget(activity)) return false
        val requested = call(activity, "getRequestedOrientation") as? Int ?: return false
        // Sensor/user orientation can produce fullscreen landscape without a fixed
        // LANDSCAPE request. Preserve the source-display evidence across migration.
        if (!isLandscape(requested) && !(sourceLandscape && requested in setOf(
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR,
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                ActivityInfo.SCREEN_ORIENTATION_USER,
                ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            ))) return false
        val window = call(activity, "findMainWindow") ?: return allowMissingWindow
        val visibleTypes = call(window, "getRequestedVisibleTypes") as? Int
        val attrs = field(window, "mAttrs") as? WindowManager.LayoutParams
        // Legacy player fullscreen flags remain meaningful when OEM inset policy
        // reports the status bar as requested-visible on the destination display.
        return (visibleTypes != null && visibleTypes and WindowInsets.Type.statusBars() == 0) ||
            (attrs != null && (attrs.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0 ||
                attrs.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0))
    }

    // Dedicated players may use either startActivityForResult or an ordinary
    // same-task push. Only the immediate portrait page is a safe return target;
    // do not search farther down the history or across tasks/packages.
    private fun hasPortraitReturnTarget(activity: Any): Boolean {
        val task = call(activity, "getTask") ?: return false
        fun isPortraitTarget(target: Any?): Boolean = target != null &&
            field(target, "finishing") != true &&
            field(target, "packageName") == field(activity, "packageName") &&
            call(target, "getTask") === task &&
            (call(target, "getRequestedOrientation") as? Int) in setOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            )
        if (isPortraitTarget(field(activity, "resultTo"))) return true
        return isPortraitTarget(call(task, "getActivityBelow", activity))
    }

    private fun isLandscape(orientation: Int?): Boolean = orientation in setOf(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
    )

    private fun isSlot(display: Any): Boolean {
        val info = call(display, "getDisplayInfo") ?: return false
        return (field(info, "name") as? String)?.startsWith("NeXtep-slot-") == true
    }

    private fun isWideSlot(display: Any, activity: Any): Boolean = runCatching {
        val service = field(activity, "mAtmService") ?: return@runCatching false
        val context = field(service, "mContext") as? Context ?: return@runCatching false
        val id = call(display, "getDisplayId") as? Int ?: return@runCatching false
        io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge.isWideDisplay(context, id)
    }.getOrDefault(false)

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == name }
            ?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String, vararg args: Any): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                method.name == name && method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { method.parameterTypes[it].isInstance(args[it]) }
            }?.apply { isAccessible = true }?.invoke(target, *args)
}
