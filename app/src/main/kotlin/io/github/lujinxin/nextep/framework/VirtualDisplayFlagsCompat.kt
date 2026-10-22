package io.github.lujinxin.nextep.framework

import android.hardware.display.DisplayManager
import io.github.lujinxin.nextep.logging.NeXtepLog

object VirtualDisplayFlagsCompat {
    data class Resolved(val value: Int, val names: List<String>)

    fun resolveCandidates(): List<Resolved> {
        // SECURE is permission to render protected source windows, not screenshot
        // protection. SystemUI may lack CAPTURE_SECURE_VIDEO_OUTPUT; protect the
        // output SurfaceView/window instead, as the workbench already does.
        val fallbackValue = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        var value = fallbackValue
        val names = mutableListOf("OWN_CONTENT_ONLY", "PRESENTATION")

        listOf(
            "VIRTUAL_DISPLAY_FLAG_TRUSTED" to "TRUSTED",
            "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS" to "OWN_FOCUS",
            "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP" to "OWN_DISPLAY_GROUP",
        ).forEach { (fieldName, label) ->
            readFlag(fieldName)?.let { flag ->
                value = value or flag
                names += label
            }
        }

        val ownFocus = Resolved(value, names.toList())
        // Slots render previews and receive selections through display 0. Keep
        // their local focus without letting a moved editor take the global IME
        // focus from MAIN during an exchange.
        if ("OWN_FOCUS" in names) {
            readFlag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED")?.let { flag ->
                value = value or flag
                names += "STEAL_TOP_FOCUS_DISABLED"
            }
        }
        val preferred = Resolved(value, names.toList())
        val fallback = Resolved(
            fallbackValue,
            listOf("OWN_CONTENT_ONLY", "PRESENTATION"),
        )
        return listOf(preferred, ownFocus, fallback).distinctBy { it.value }.also { candidates ->
            NeXtepLog.info(
                "virtual_display_flags",
                "Resolved candidates=${candidates.joinToString { "${it.value}:${it.names}" }}",
            )
        }
    }

    private fun readFlag(name: String): Int? = runCatching {
        DisplayManager::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
    }.onFailure { error ->
        NeXtepLog.debug("virtual_display_flags", "Hidden flag unavailable name=$name error=${error.javaClass.simpleName}")
    }.getOrNull()
}
