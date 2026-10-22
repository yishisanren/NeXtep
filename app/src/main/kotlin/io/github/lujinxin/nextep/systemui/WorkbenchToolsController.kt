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
import io.github.lujinxin.nextep.workspace.SidebarSide

internal class WorkbenchToolsController(
    private val context: Context,
    private val appearance: () -> WorkbenchAppearance,
    private val captureAllowed: () -> Boolean,
    private val setCaptureAllowed: (Boolean) -> Unit,
    private val recentsHidden: () -> Boolean,
    private val setRecentsHidden: (Boolean) -> Unit,
    private val open: (Intent) -> Result<Unit>,
    private val search: (List<Intent>, (Result<Unit>) -> Unit) -> Result<Unit>,
    private val wideRequested: () -> Boolean,
    private val settings: () -> Unit,
    private val exit: () -> Unit,
    private val theme: () -> Unit,
    private val side: () -> SidebarSide,
    private val setSide: (SidebarSide) -> Unit,
    private val cancelSearch: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var searching = false
    private var editorGeneration = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun dismiss() {
        editorGeneration++
        val previous = dialog
        dialog = null
        if (searching) { searching = false; cancelSearch() }
        previous?.dismiss()
    }

    fun showPayment() {
        dismiss()
        val labels = listOf(R.string.workbench_wechat_scan, R.string.workbench_wechat_pay,
            R.string.workbench_alipay_scan, R.string.workbench_alipay_pay)
        val adapter = object : android.widget.ArrayAdapter<String>(themedContext(),
            android.R.layout.select_dialog_item, labels.map { string(it) }) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val action = WorkbenchIntents.Payment.entries[position]
                return (super.getView(position, convertView, parent) as TextView).apply {
                    val pkg = if (position < 2) "com.tencent.mm" else "com.eg.android.AlipayGphone"
                    val icon = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
                        ?: WorkbenchIcon(if (position % 2 == 0) WorkbenchIcon.Kind.SCAN else WorkbenchIcon.Kind.CODE,
                            currentTextColor)
                    icon.setBounds(0, 0, dp(32), dp(32))
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = dp(16)
                    isEnabled = WorkbenchIntents.payment(action).any(::canOpen)
                    alpha = if (isEnabled) 1f else .45f
                }
            }
        }
        show(builder().setTitle(string(R.string.workbench_payment)).setAdapter(adapter) { _, position ->
            val action = WorkbenchIntents.Payment.entries[position]
            val result = runCatching {
                if (!startPaymentShortcut(action)) {
                    val intent = WorkbenchIntents.payment(action).firstOrNull(::canOpen)
                        ?: error("No supported payment entry")
                    open(intent).getOrThrow()
                }
            }
            if (result.isSuccess) dismiss() else message(R.string.workbench_unavailable)
        }
            .setNegativeButton(string(R.string.workbench_cancel), null).create())
    }

    fun showTopApps(current: List<String>) {
        dismiss()
        val loading = builder().setTitle(string(R.string.workbench_edit_apps))
            .setMessage(string(R.string.workbench_loading_apps))
            .setNegativeButton(string(R.string.workbench_cancel), null).create()
        if (!show(loading)) return
        val generation = editorGeneration
        Thread({
            val result = runCatching {
                io.github.lujinxin.nextep.apps.LauncherAppRepository(context).load()
                    .sortedBy { it.label.lowercase() }
            }
            handler.post {
                if (generation != editorGeneration || dialog !== loading) return@post
                result.onSuccess { entries ->
                    val selected = current.filter { key -> entries.any { it.key == key } }.take(10).toMutableList()
                    val adapter = object : android.widget.ArrayAdapter<String>(themedContext(),
                        android.R.layout.select_dialog_multichoice, entries.map { it.label }) {
                        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                            (super.getView(position, convertView, parent) as android.widget.CheckedTextView).apply {
                                val entry = entries[position]
                                entry.icon.setBounds(0, 0, dp(32), dp(32))
                                setCompoundDrawablesRelative(entry.icon, null, null, null)
                                compoundDrawablePadding = dp(16)
                                isChecked = entry.key in selected
                            }
                    }
                    val list = android.widget.ListView(themedContext()).apply {
                        choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
                        this.adapter = adapter
                        entries.forEachIndexed { index, entry -> setItemChecked(index, entry.key in selected) }
                        setOnItemClickListener { _, _, index, _ ->
                            val key = entries[index].key
                            if (key in selected) selected.remove(key)
                            else if (selected.size < 10) selected.add(key)
                            else message(R.string.workbench_apps_limit)
                            setItemChecked(index, key in selected)
                        }
                    }
                    val editor = builder().setTitle(string(R.string.workbench_edit_apps))
                        .setView(list).setNegativeButton(string(R.string.workbench_cancel), null)
                        .setPositiveButton(string(R.string.workbench_save), null).create()
                    if (!show(editor)) return@onSuccess
                    editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        val keys = ArrayList(selected)
                        Thread({
                            val saved = runCatching {
                                context.contentResolver.call(android.net.Uri.parse("content://io.github.lujinxin.nextep.workspace.config"),
                                    "set_top_apps", null, android.os.Bundle().apply {
                                        putStringArrayList(io.github.lujinxin.nextep.config.WorkspaceConfigContract.EXTRA_APPS, keys)
                                    })?.getBoolean("saved") == true
                            }.getOrDefault(false)
                            handler.post {
                                if (dialog !== editor) return@post
                                if (saved) dismiss() else {
                                    editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                    message(R.string.workbench_unavailable)
                                }
                            }
                        }, "NeXtep-SaveFavorites").start()
                    }
                }.onFailure { dismiss(); message(R.string.workbench_unavailable) }
            }
        }, "NeXtep-Favorites").start()
    }

    fun showSearch() {
        dismiss()
        var mode = WorkbenchIntents.Search.DELIVERY
        val panel = column()
        val targets = TextView(panel.context).apply {
            text = string(R.string.workbench_delivery_apps)
            setPadding(0, dp(12), 0, dp(12))
        }
        val buttons = android.widget.RadioGroup(panel.context).apply { orientation = LinearLayout.HORIZONTAL }
        WorkbenchIntents.Search.entries.forEach { choice ->
            buttons.addView(android.widget.RadioButton(panel.context).apply {
                id = View.generateViewId()
                text = string(if (choice == WorkbenchIntents.Search.DELIVERY) R.string.workbench_delivery else R.string.workbench_shopping)
                isChecked = choice == mode
                setOnClickListener {
                    mode = choice
                    targets.text = string(if (mode == WorkbenchIntents.Search.DELIVERY)
                        R.string.workbench_delivery_apps else R.string.workbench_shopping_apps)
                }
            }, android.widget.RadioGroup.LayoutParams(0, dp(48), 1f))
        }
        val query = EditText(panel.context).apply {
            hint = string(R.string.workbench_query)
            contentDescription = hint
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
            targets.text = requested.joinToString("\n") { target ->
                (if (target in available) "✓  " else "—  ") + target.name
            }
            if (available.isEmpty()) { message(R.string.workbench_compare_missing); return@setOnClickListener }
            searching = true
            query.isEnabled = false
            for (index in 0 until buttons.childCount) buttons.getChildAt(index).isEnabled = false
            window.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                text = string(R.string.workbench_search_running)
            }
            fun finished(result: Result<Unit>) {
                if (dialog !== window) return
                searching = false
                query.isEnabled = true
                for (index in 0 until buttons.childCount) buttons.getChildAt(index).isEnabled = true
                window.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    isEnabled = true
                    text = string(R.string.workbench_search)
                }
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
                    query.error = string(R.string.workbench_search_failed_detail,
                        name, failure?.completed ?: 0, available.size, string(reason))
                    NeXtepLog.warn("compare_launch", "Search sequence failed index=" + failure?.index, error)
                }
            }
            search(available.map { it.intent }, ::finished).onFailure { finished(Result.failure(it)) }
        }
    }

    fun showMore() {
        dismiss()
        fun checked(checked: Boolean, id: Int) = (if (checked) "✓  " else "    ") + string(id)
        val items = arrayOf(string(R.string.workbench_theme),
            string(if (side() == SidebarSide.LEFT) R.string.window_side_right else R.string.window_side_left),
            string(R.string.workbench_wide),
            checked(recentsHidden(), R.string.workbench_hide_recents),
            checked(captureAllowed(), R.string.workbench_capture), string(R.string.workbench_export),
            string(R.string.open_settings), string(R.string.window_exit))
        show(builder().setTitle(string(R.string.workbench_more)).setItems(items) { _, index ->
            when (index) {
                0 -> theme()
                1 -> setSide(if (side() == SidebarSide.LEFT) SidebarSide.RIGHT else SidebarSide.LEFT)
                2 -> if (!wideRequested()) message(R.string.workbench_wide_failed)
                3 -> setRecentsHidden(!recentsHidden())
                4 -> {
                    val allowed = !captureAllowed()
                    setCaptureAllowed(allowed)
                    message(if (captureAllowed()) R.string.workbench_capture_on else R.string.workbench_capture_off)
                }
                5 -> exportDiagnostics()
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

    private fun canOpen(intent: Intent): Boolean =
        io.github.lujinxin.nextep.framework.WorkspaceIntentResolver.resolve(context.packageManager, intent) != null

    private fun show(value: AlertDialog): Boolean {
        dismiss()
        dialog = value
        return runCatching {
            value.window?.apply {
                setType(checkNotNull(SystemUiWindowTypeResolver.resolve()))
                if (!captureAllowed()) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            value.setOnDismissListener {
                if (dialog === value) {
                    dialog = null
                    if (searching) { searching = false; cancelSearch() }
                }
            }
            value.show()
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
