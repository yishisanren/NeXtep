package io.github.lujinxin.nextep.logging

import android.os.Build
import android.util.Log

object NeXtepLog {
    private const val TAG = "NeXtep"
    // Features whose messages carry only ids, flags and geometry.
    private val MESSAGE_FEATURES = setOf("workbench_scene", "workspace_configuration", "systemui_runtime", "wide_layout",
        "virtual_display", "slot_state", "slot_coordinator", "slot_retention", "task_surface", "activity_task_manager")
    private val events = java.util.ArrayDeque<String>()

    fun diagnostics(): String = synchronized(events) { events.joinToString("\n") }

    fun debug(feature: String, message: String) = write(Log.DEBUG, feature, message, null)
    fun info(feature: String, message: String) = write(Log.INFO, feature, message, null)
    fun warn(feature: String, message: String, error: Throwable? = null) =
        write(Log.WARN, feature, message, error)
    fun error(feature: String, message: String, error: Throwable? = null) =
        write(Log.ERROR, feature, message, error)

    private fun write(priority: Int, feature: String, message: String, error: Throwable?) {
        synchronized(events) {
            if (events.size == 128) events.removeFirst()
            // Deliberately exclude message/Intent/ClipData: they may contain a
            // search phrase, URI, account label or text dragged from another app.
            val detail = if (feature in MESSAGE_FEATURES) " message=$message" else ""
            events.addLast("${android.os.SystemClock.uptimeMillis()} priority=$priority feature=$feature error=${error?.javaClass?.simpleName ?: "none"}$detail")
        }
        val enriched = "pid=${android.os.Process.myPid()} thread=${Thread.currentThread().name} " +
            "feature=$feature sdk=${Build.VERSION.SDK_INT} message=$message"
        val output = if (error == null) enriched else "$enriched\n${Log.getStackTraceString(error)}"
        Log.println(priority, TAG, output)
    }
}
