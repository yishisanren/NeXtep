package io.github.lujinxin.nextep.systemui

import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.provider.MediaStore
import android.text.InputFilter
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog

internal class WorkbenchToolsController(
    private val context: Context,
    private val appearance: () -> WorkbenchAppearance,
    private val captureAllowed: () -> Boolean,
    private val setCaptureAllowed: (Boolean) -> Unit,
    private val recentsHidden: () -> Boolean,
    private val setRecentsHidden: (Boolean) -> Unit,
    private val open: (Intent) -> Result<Unit>,
    private val search: (List<Intent>) -> Result<Unit>,
    private val wideRequested: () -> Boolean,
    private val settings: () -> Unit,
    private val exit: () -> Unit,
    private val media: () -> View,
) {
    private var dialog: AlertDialog? = null

    fun dismiss() { dialog?.dismiss(); dialog = null }

    fun showPayment() {
        dismiss()
        val panel = column()
        val labels = listOf(R.string.workbench_wechat_scan, R.string.workbench_wechat_pay,
            R.string.workbench_alipay_scan, R.string.workbench_alipay_pay)
        WorkbenchIntents.Payment.entries.chunked(2).forEach { actions ->
            val row = LinearLayout(panel.context)
            actions.forEach { action ->
                row.addView(Button(panel.context).apply {
                    text = string(labels[action.ordinal])
                    setTextColor(appearance().text)
                    isEnabled = WorkbenchIntents.payment(action).any(::canOpen)
                    setOnClickListener {
                        val result = runCatching {
                            if (!startPaymentShortcut(action)) {
                                val intent = WorkbenchIntents.payment(action).firstOrNull(::canOpen)
                                    ?: error("No supported payment entry")
                                open(intent).getOrThrow()
                            }
                        }
                        if (result.isSuccess) dismiss() else message(R.string.workbench_unavailable)
                    }
                }, LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            panel.addView(row)
        }
        show(builder().setTitle(string(R.string.workbench_payment)).setView(panel)
            .setNegativeButton(string(R.string.workbench_cancel), null).create())
    }

    fun showSearch() {
        dismiss()
        var mode = WorkbenchIntents.Search.DELIVERY
        val panel = column()
        val targets = TextView(panel.context).apply {
            text = string(R.string.workbench_delivery_apps)
            setTextColor(appearance().secondary)
            setPadding(0, dp(12), 0, dp(12))
        }
        val buttons = LinearLayout(panel.context)
        WorkbenchIntents.Search.entries.forEach { choice ->
            buttons.addView(Button(panel.context).apply {
                text = string(if (choice == WorkbenchIntents.Search.DELIVERY) R.string.workbench_delivery else R.string.workbench_shopping)
                setTextColor(appearance().text)
                isSelected = choice == mode
                setOnClickListener {
                    mode = choice
                    for (index in 0 until buttons.childCount) buttons.getChildAt(index).isSelected = index == choice.ordinal
                    targets.text = string(if (mode == WorkbenchIntents.Search.DELIVERY)
                        R.string.workbench_delivery_apps else R.string.workbench_shopping_apps)
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        val query = EditText(panel.context).apply {
            hint = string(R.string.workbench_query)
            contentDescription = hint
            setTextColor(appearance().text)
            setHintTextColor(appearance().secondary)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(200))
        }
        panel.addView(buttons)
        panel.addView(targets)
        panel.addView(query)
        val window = builder().setTitle(string(R.string.workbench_compare)).setView(panel)
            .setNegativeButton(string(R.string.workbench_cancel), null)
            .setPositiveButton(string(R.string.workbench_search), null).create()
        if (!show(window)) return
        window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val term = query.text.toString().trim()
            val requested = runCatching { WorkbenchIntents.searches(mode, term) }.getOrNull()
            if (requested == null) { query.error = string(R.string.workbench_query_empty); return@setOnClickListener }
            val available = requested.filter { canOpen(it.intent) }
            if (available.size < 2) { message(R.string.workbench_compare_missing); return@setOnClickListener }
            search(available.map { it.intent }).onSuccess { dismiss() }
                .onFailure { message(R.string.workbench_compare_failed) }
        }
    }

    fun showMore() {
        dismiss()
        fun checked(checked: Boolean, id: Int) = (if (checked) "✓  " else "    ") + string(id)
        val items = arrayOf(string(R.string.workbench_compare), string(R.string.workbench_wide),
            checked(recentsHidden(), R.string.workbench_hide_recents),
            checked(captureAllowed(), R.string.workbench_capture), string(R.string.workbench_export),
            string(R.string.media_control_title), string(R.string.open_settings), string(R.string.window_exit))
        show(builder().setTitle(string(R.string.workbench_more)).setItems(items) { _, index ->
            when (index) {
                0 -> showSearch()
                1 -> if (!wideRequested()) message(R.string.workbench_wide_failed)
                2 -> setRecentsHidden(!recentsHidden())
                3 -> {
                    val allowed = !captureAllowed()
                    setCaptureAllowed(allowed)
                    message(if (captureAllowed()) R.string.workbench_capture_on else R.string.workbench_capture_off)
                }
                4 -> exportDiagnostics()
                5 -> show(builder().setTitle(string(R.string.media_control_title)).setView(column().apply {
                    addView(media(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)))
                }).setNegativeButton(string(R.string.workbench_cancel), null).create())
                6 -> settings()
                7 -> exit()
            }
        }.create())
    }

    private fun startPaymentShortcut(action: WorkbenchIntents.Payment): Boolean = runCatching {
        val wechat = action == WorkbenchIntents.Payment.WECHAT_SCAN || action == WorkbenchIntents.Payment.WECHAT_PAY
        val scan = action == WorkbenchIntents.Payment.WECHAT_SCAN || action == WorkbenchIntents.Payment.ALIPAY_SCAN
        val target = if (wechat) "com.tencent.mm" else "com.eg.android.AlipayGphone"
        val keywords = if (scan) listOf("扫一扫", "scan") else listOf("收付款", "付款码", "payment code", "pay code")
        val manager = context.getSystemService(LauncherApps::class.java)
        val query = LauncherApps.ShortcutQuery().setPackage(target).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val shortcut = manager.getShortcuts(query, Process.myUserHandle()).orEmpty().firstOrNull { entry ->
            entry.isEnabled && keywords.any { entry.shortLabel.toString().contains(it, ignoreCase = true) }
        } ?: return@runCatching false
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
        manager.startShortcut(target, shortcut.id, null, options.toBundle(), shortcut.userHandle)
        true
    }.getOrDefault(false)

    private fun exportDiagnostics() {
        // No logcat scraping: export only this module's bounded event metadata.
        val name = "NeXtep-diagnostics-${System.currentTimeMillis()}.txt"
        var created: android.net.Uri? = null
        runCatching {
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            created = uri
            checkNotNull(resolver.openOutputStream(uri)).bufferedWriter().use {
                it.write("NeXtep diagnostics\nSDK=${android.os.Build.VERSION.SDK_INT}\n")
                it.write(NeXtepLog.diagnostics())
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }.onSuccess { message(R.string.workbench_exported) }.onFailure {
            created?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            message(R.string.workbench_export_failed)
        }
    }

    private fun canOpen(intent: Intent): Boolean = runCatching {
        val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return@runCatching false
        info.exported && info.enabled && info.applicationInfo.enabled
    }.getOrDefault(false)

    private fun show(value: AlertDialog): Boolean {
        dismiss()
        dialog = value
        return runCatching {
            value.window?.apply {
                setType(checkNotNull(SystemUiWindowTypeResolver.resolve()))
                if (!captureAllowed()) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                setBackgroundDrawable(appearance().background(dp(22).toFloat(), dp(1)))
            }
            value.setOnDismissListener { if (dialog === value) dialog = null }
            value.show()
            value.window?.setLayout(minOf(context.resources.displayMetrics.widthPixels - dp(24), dp(460)),
                WindowManager.LayoutParams.WRAP_CONTENT)
            true
        }.onFailure {
            dismiss()
            NeXtepLog.warn("workbench_dialog", "Tool window unavailable", it)
            message(R.string.workbench_unavailable)
        }.getOrDefault(false)
    }

    private fun builder(): AlertDialog.Builder = AlertDialog.Builder(themedContext())
    private fun themedContext() = ContextThemeWrapper(context, if (appearance() == WorkbenchAppearance.DARK)
        android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
    private fun column() = LinearLayout(themedContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    private fun message(id: Int) = Toast.makeText(context, string(id), Toast.LENGTH_LONG).show()
    private fun string(id: Int) = AppLanguage.string(context, id)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
