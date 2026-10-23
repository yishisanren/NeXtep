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
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.config.SystemDialogs
import io.github.lujinxin.nextep.logging.NeXtepLog

internal class WorkbenchToolsController(
    private val context: Context,
    private val appearance: () -> WorkbenchAppearance,
    private val captureAllowed: () -> Boolean,
    private val open: (Intent) -> Result<Unit>,
    private val search: (List<Intent>, (Result<Unit>) -> Unit) -> Result<Unit>,
    private val cancelSearch: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var searching = false

    fun dismiss() {
        val previous = dialog
        dialog = null
        if (searching) { searching = false; cancelSearch() }
        previous?.dismiss()
    }

    fun showPayment() {
        dismiss()
        val labels = listOf(R.string.workbench_wechat_scan, R.string.workbench_wechat_pay,
            R.string.workbench_alipay_scan, R.string.workbench_alipay_pay)
        // The OEM list container drew no adapter rows on the device, so the four
        // entries are plain rows inside the dialog's custom view instead.
        val panel = column().apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val ripple = android.util.TypedValue().also {
            panel.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        WorkbenchIntents.Payment.entries.forEachIndexed { position, action ->
            panel.addView(TextView(panel.context).apply {
                text = string(labels[position])
                textSize = 16f
                setTextColor(appearance().text)
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = dp(56)
                setPadding(dp(16), dp(4), dp(16), dp(4))
                if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
                val pkg = if (position < 2) "com.tencent.mm" else "com.eg.android.AlipayGphone"
                val icon = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
                    ?: WorkbenchIcon(if (position % 2 == 0) WorkbenchIcon.Kind.SCAN else WorkbenchIcon.Kind.CODE,
                        currentTextColor)
                icon.setBounds(0, 0, dp(32), dp(32))
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(16)
                val available = WorkbenchIntents.payment(action).any(::canOpen)
                alpha = if (available) 1f else .45f
                contentDescription = text
                setOnClickListener {
                    if (!available) { message(R.string.workbench_unavailable); return@setOnClickListener }
                    val result = runCatching {
                        if (!startPaymentShortcut(action)) {
                            val intent = WorkbenchIntents.payment(action).firstOrNull(::canOpen)
                                ?: error("No supported payment entry")
                            open(intent).getOrThrow()
                        }
                    }
                    if (result.isSuccess) dismiss() else message(R.string.workbench_unavailable)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        show(builder().setTitle(string(R.string.workbench_payment)).setView(panel)
            .setNegativeButton(string(R.string.workbench_cancel), null).create())
    }

    fun showSearch() {
        dismiss()
        val a = appearance()
        var mode = WorkbenchIntents.Search.DELIVERY
        val panel = LinearLayout(themedContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(20))
        }
        fun label(text: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(panel.context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        fun rounded(fill: Int, radius: Int, stroke: Int = 0) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            if (stroke > 0) setStroke(stroke, a.outline)
        }
        val onAccent = if (a == WorkbenchAppearance.DARK) 0xFF0C131F.toInt() else android.graphics.Color.WHITE
        val fill = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        panel.addView(label(string(R.string.workbench_compare), 20f, a.text, bold = true), fill)
        panel.addView(label(string(R.string.workbench_compare_hint), 13f, a.secondary), fill.apply { topMargin = dp(4) })

        // Segmented choice: a card-coloured track with an accent pill on the selected half.
        val choices = mutableListOf<Pair<WorkbenchIntents.Search, TextView>>()
        val apps = LinearLayout(panel.context).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderApps() {
            apps.removeAllViews()
            runCatching { WorkbenchIntents.searches(mode, "x") }.getOrDefault(emptyList()).forEach { target ->
                val installed = canOpen(target.intent)
                val icon = target.intent.`package`?.let { pkg -> runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() }
                    ?: WorkbenchIcon(WorkbenchIcon.Kind.COMPARE, a.secondary)
                apps.addView(LinearLayout(panel.context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    alpha = if (installed) 1f else .35f
                    addView(android.widget.ImageView(panel.context).apply {
                        setImageDrawable(icon)
                        contentDescription = target.name
                    }, LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(label(target.name, 11f, a.secondary).apply {
                        gravity = android.view.Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(6)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        fun renderChoices() {
            choices.forEach { (choice, view) ->
                val selected = choice == mode
                view.background = if (selected) rounded(a.accent, 10) else null
                view.setTextColor(if (selected) onAccent else a.secondary)
                view.typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            }
            renderApps()
        }
        val segments = LinearLayout(panel.context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(a.card, 13, dp(1))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            WorkbenchIntents.Search.entries.forEach { choice ->
                val view = label(string(if (choice == WorkbenchIntents.Search.DELIVERY) R.string.workbench_delivery
                    else R.string.workbench_shopping), 15f, a.secondary).apply {
                    gravity = android.view.Gravity.CENTER
                    setOnClickListener { if (mode != choice) { mode = choice; renderChoices() } }
                }
                choices += choice to view
                addView(view, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
        }
        panel.addView(segments, fill.apply { topMargin = dp(18) })
        panel.addView(apps, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        val query = EditText(panel.context).apply {
            hint = string(R.string.workbench_query)
            contentDescription = hint
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(200))
            textSize = 16f
            setTextColor(a.text)
            setHintTextColor(a.secondary)
            background = rounded(a.card, 14, dp(1))
            setPadding(dp(16), 0, dp(16), 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            val glyph = WorkbenchIcon(WorkbenchIcon.Kind.COMPARE, a.secondary).apply { setBounds(0, 0, dp(20), dp(20)) }
            setCompoundDrawablesRelative(glyph, null, null, null)
            compoundDrawablePadding = dp(12)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        panel.addView(query, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(18) })
        val status = label("", 12f, a.secondary).apply { visibility = View.GONE }
        panel.addView(status, fill.apply { topMargin = dp(8) })

        val cancel = label(string(R.string.workbench_cancel), 15f, a.secondary).apply {
            gravity = android.view.Gravity.CENTER
            background = rounded(a.card, 12, dp(1))
            setOnClickListener { dismiss() }
        }
        val go = label(string(R.string.workbench_search), 15f, onAccent, bold = true).apply {
            gravity = android.view.Gravity.CENTER
            background = rounded(a.accent, 12)
        }
        panel.addView(LinearLayout(panel.context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancel, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(go, LinearLayout.LayoutParams(0, dp(46), 1.5f).apply { marginStart = dp(12) })
        }, fill.apply { topMargin = dp(18) })
        renderChoices()

        val window = builder().setView(panel).create()
        if (!show(window)) return
        fun setBusy(busy: Boolean) {
            searching = busy
            query.isEnabled = !busy
            choices.forEach { it.second.isEnabled = !busy }
            go.isEnabled = !busy
            go.alpha = if (busy) .6f else 1f
            go.text = string(if (busy) R.string.workbench_search_running else R.string.workbench_search)
        }
        fun submit() {
            if (searching) return
            val term = query.text.toString().trim()
            val requested = runCatching { WorkbenchIntents.searches(mode, term) }.getOrNull()
            if (requested == null) { query.error = string(R.string.workbench_query_empty); return }
            val available = requested.filter { canOpen(it.intent) }
            if (available.isEmpty()) { message(R.string.workbench_compare_missing); return }
            status.visibility = View.GONE
            setBusy(true)
            fun finished(result: Result<Unit>) {
                if (dialog !== window) return
                setBusy(false)
                result.onSuccess { dismiss() }.onFailure { error ->
                    if (error is java.util.concurrent.CancellationException) return@onFailure
                    val failure = error as? io.github.lujinxin.nextep.workspace.WorkspaceLaunchSequence.Failure
                    val original = failure?.cause ?: error
                    val cause = if (original is java.lang.reflect.InvocationTargetException) original.targetException else original
                    val reason = when (cause) {
                        is io.github.lujinxin.nextep.workspace.WorkspaceLaunchSequence.PreparationTimeout -> R.string.workbench_window_timeout
                        is io.github.lujinxin.nextep.workspace.WorkspaceSearchPlacement.NoSpace -> R.string.workbench_compare_space
                        is java.util.concurrent.TimeoutException -> R.string.workbench_launch_timeout
                        is SecurityException -> R.string.workbench_launch_denied
                        else -> if (failure?.stage == io.github.lujinxin.nextep.workspace.WorkspaceLaunchSequence.Stage.PLACE)
                            R.string.workbench_place_failed else R.string.workbench_launch_failed
                    }
                    val name = available.getOrNull(failure?.index ?: 0)?.name.orEmpty()
                    status.text = string(R.string.workbench_search_failed_detail,
                        name, failure?.completed ?: 0, available.size, string(reason))
                    status.visibility = View.VISIBLE
                    NeXtepLog.warn("compare_launch", "Search sequence failed index=" + failure?.index, error)
                }
            }
            search(available.map { it.intent }, ::finished).onFailure { finished(Result.failure(it)) }
        }
        go.setOnClickListener { submit() }
        query.setOnEditorActionListener { _, _, _ -> submit(); true }
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

    fun exportDiagnostics(): Boolean {
        // No logcat scraping: export only this module's bounded event metadata.
        val name = "NeXtep-diagnostics-${System.currentTimeMillis()}.txt"
        var created: android.net.Uri? = null
        return runCatching {
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
        }.isSuccess
    }

    private fun canOpen(intent: Intent): Boolean =
        io.github.lujinxin.nextep.framework.WorkspaceIntentResolver.resolve(context.packageManager, intent) != null

    private fun show(value: AlertDialog): Boolean {
        dismiss()
        dialog = value
        return runCatching {
            value.window?.apply {
                setType(checkNotNull(SystemUiWindowTypeResolver.resolve()))
                if (!captureAllowed()) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                // The frame follows the workbench panels (same tone, 28dp corners) in both appearances.
                setBackgroundDrawable(android.graphics.drawable.InsetDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(28).toFloat()
                        setColor(appearance().opaquePanel)
                        setStroke(dp(1), appearance().outline)
                    }, dp(16), 0, dp(16), 0))
            }
            value.setOnDismissListener {
                if (dialog === value) {
                    dialog = null
                    if (searching) { searching = false; cancelSearch() }
                }
            }
            value.show()
            value.window?.decorView?.let { decor ->
                // Our colours are final: ColorOS force-dark would re-tint the light accent, and the
                // ROM's own rounded panel inside the window would show as a second frame.
                decor.isForceDarkAllowed = false
                (decor.findViewById<View>(android.R.id.content) as? android.view.ViewGroup)
                    ?.getChildAt(0)?.background = null
            }
            true
        }.onFailure {
            dismiss()
            NeXtepLog.warn("workbench_dialog", "Tool window unavailable", it)
            message(R.string.workbench_unavailable)
        }.getOrDefault(false)
    }

    private fun builder(): AlertDialog.Builder = AlertDialog.Builder(themedContext())
    private fun themedContext() = SystemDialogs.context(context, appearance() == WorkbenchAppearance.DARK)
    private fun column() = LinearLayout(themedContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    private fun message(id: Int) = Toast.makeText(context, string(id), Toast.LENGTH_LONG).show()
    private fun string(id: Int, vararg arguments: Any) = AppLanguage.string(context, id, *arguments)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
