package io.github.lujinxin.nextep.config

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper

/** Use the ROM's exported theme; never paint over its window or buttons. */
object SystemDialogs {
    fun context(context: Context, dark: Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES): Context =
        themedContext(context, dark)

    private fun themedContext(context: Context, dark: Boolean): Context {
        val oplus = context.resources.getIdentifier("Theme.Dialog.Alert", "style", "oplus")
        // Keep the Activity as base so native dialogs retain its window token.
        return ContextThemeWrapper(context, if (oplus != 0) oplus else if (dark)
            android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).apply {
            applyOverrideConfiguration(Configuration().apply {
                uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
        }
    }

    fun builder(context: Context) = AlertDialog.Builder(SystemDialogs.context(context))
}
