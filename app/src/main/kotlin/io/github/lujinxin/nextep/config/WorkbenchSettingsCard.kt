package io.github.lujinxin.nextep.config

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.trigger.TriggerCoordinator
import io.github.lujinxin.nextep.trigger.TriggerSource
import io.github.lujinxin.nextep.workspace.WorkbenchScene

/** SystemUI keeps the workbench preferences; these cards only mirror its reply and send commands. */
internal class WorkbenchSettingsCard(
    private val activity: AppCompatActivity,
    private val query: ((TriggerCoordinator.RequestResult) -> Unit) -> Unit = { TriggerCoordinator.queryWorkbench(activity, it) },
    private val send: (String, String, (TriggerCoordinator.RequestResult) -> Unit) -> Unit = { key, value, done ->
        TriggerCoordinator.workbenchCommand(activity, key, value, done)
    },
) {
    private val status = SettingsScreen.sectionDescription(activity, activity.getString(R.string.workbench_state_loading))
    private val controls = mutableListOf<View>()
    private var syncing = false
    private var pending = false
    val appearance: MaterialButtonToggleGroup
    val side: MaterialButtonToggleGroup
    val position: MaterialButtonToggleGroup
    val recents: SwitchMaterial
    val capture: SwitchMaterial
    val layoutCard: View
    val privacyCard: View
    val supportCard: View
    val scenesCard: View
    private val scenesList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val saveScene = SettingsRowButton.create(activity).apply {
        text = activity.getString(R.string.scene_save_action)
        setOnClickListener { askSceneName() }
    }
    private var workspaceActive = false

    init {
        val scenes = SettingsScreen.sectionContent(activity)
        scenes.addView(SettingsScreen.sectionTitle(activity, activity.getString(R.string.section_scenes)), SettingsScreen.matchWidth())
        scenes.addView(row(activity.getString(R.string.section_scenes), activity.getString(R.string.scenes_summary), saveScene),
            SettingsScreen.matchWidth())
        scenes.addView(scenesList, SettingsScreen.matchWidth())
        scenesCard = SettingsScreen.sectionCard(activity).apply { addView(scenes) }

        val layout = SettingsScreen.sectionContent(activity)
        layout.addView(SettingsScreen.sectionTitle(activity, activity.getString(R.string.section_layout)), SettingsScreen.matchWidth())
        layout.addView(status, SettingsScreen.matchWidth())
        appearance = choice(layout, activity.getString(R.string.workbench_appearance), TriggerBroadcastContract.COMMAND_APPEARANCE,
            listOf("SYSTEM" to activity.getString(R.string.appearance_system),
                "LIGHT" to activity.getString(R.string.appearance_light), "DARK" to activity.getString(R.string.appearance_dark)))
        side = choice(layout, activity.getString(R.string.workbench_side), TriggerBroadcastContract.COMMAND_SIDEBAR_SIDE,
            listOf("LEFT" to activity.getString(R.string.window_side_left), "RIGHT" to activity.getString(R.string.window_side_right)))
        position = choice(layout, activity.getString(R.string.control_position), TriggerBroadcastContract.COMMAND_CONTROL_BOTTOM,
            listOf("false" to activity.getString(R.string.control_top), "true" to activity.getString(R.string.control_bottom)))
        layoutCard = SettingsScreen.sectionCard(activity).apply { addView(layout) }

        val privacy = SettingsScreen.sectionContent(activity)
        privacy.addView(SettingsScreen.sectionTitle(activity, activity.getString(R.string.section_privacy)), SettingsScreen.matchWidth())
        recents = toggle(privacy, activity.getString(R.string.workbench_hide_recents),
            activity.getString(R.string.workbench_hide_recents_summary), TriggerBroadcastContract.COMMAND_HIDE_RECENTS)
        capture = toggle(privacy, activity.getString(R.string.workbench_capture),
            activity.getString(R.string.workbench_capture_summary), TriggerBroadcastContract.COMMAND_CAPTURE)
        privacyCard = SettingsScreen.sectionCard(activity).apply { addView(privacy) }

        val support = SettingsScreen.sectionContent(activity)
        support.addView(SettingsScreen.sectionTitle(activity, activity.getString(R.string.section_support)), SettingsScreen.matchWidth())
        val export = SettingsRowButton.create(activity).apply {
            text = activity.getString(R.string.workbench_export_action)
            setOnClickListener { command(TriggerBroadcastContract.COMMAND_EXPORT_DIAGNOSTICS, "true") }
        }
        support.addView(row(activity.getString(R.string.workbench_export), activity.getString(R.string.workbench_export_summary), export),
            SettingsScreen.matchWidth())
        controls += export
        // Exit is the recovery path when SystemUI stops answering, so it never waits for a reply.
        val exit = SettingsRowButton.create(activity).apply {
            text = activity.getString(R.string.workbench_exit_action)
            setOnClickListener {
                isEnabled = false
                TriggerCoordinator.requestSet(activity, false, TriggerSource.SYSTEM_RECOVERY) { isEnabled = true; refresh() }
            }
        }
        support.addView(row(activity.getString(R.string.window_exit), activity.getString(R.string.workbench_exit_summary), exit),
            SettingsScreen.matchWidth())
        supportCard = SettingsScreen.sectionCard(activity).apply { addView(support) }

        apply(null)
        status.text = activity.getString(R.string.workbench_state_loading)
        refresh()
    }

    fun refresh() = query { result ->
        if (activity.isDestroyed || activity.isFinishing) return@query
        apply((result as? TriggerCoordinator.RequestResult.Ready)?.extras
            ?.takeIf { it.containsKey(TriggerBroadcastContract.EXTRA_APPEARANCE) })
    }

    /** Mirror SystemUI's reply; null means it did not answer. */
    fun apply(state: Bundle?) {
        syncing = true
        if (state == null) {
            status.text = activity.getString(R.string.workbench_state_unavailable)
            controls.forEach { it.isEnabled = false }
        } else {
            status.text = activity.getString(R.string.workbench_settings_summary)
            // MaterialButton ignores setChecked while disabled, so enable before selecting.
            controls.forEach { it.isEnabled = true }
            appearance.select(state.getString(TriggerBroadcastContract.EXTRA_APPEARANCE).orEmpty())
            side.select(state.getString(TriggerBroadcastContract.EXTRA_SIDEBAR_SIDE).orEmpty())
            position.select(state.getBoolean(TriggerBroadcastContract.EXTRA_CONTROL_BOTTOM).toString())
            recents.isChecked = state.getBoolean(TriggerBroadcastContract.EXTRA_HIDE_RECENTS)
            capture.isChecked = state.getBoolean(TriggerBroadcastContract.EXTRA_CAPTURE_ALLOWED)
            // Capture is a session grant; it needs a visible workspace to take effect.
            capture.isEnabled = state.getBoolean(TriggerBroadcastContract.EXTRA_ACTIVE)
        }
        workspaceActive = state?.getBoolean(TriggerBroadcastContract.EXTRA_ACTIVE) == true
        // Saving and restoring act on the live panes, so both need an open workspace.
        saveScene.isEnabled = workspaceActive
        renderScenes(WorkbenchScene.decode(state?.getString(TriggerBroadcastContract.EXTRA_SCENES)))
        syncing = false
    }

    private fun renderScenes(scenes: List<WorkbenchScene>) {
        scenesList.removeAllViews()
        if (scenes.isEmpty()) {
            scenesList.addView(SettingsScreen.sectionDescription(activity, activity.getString(R.string.scenes_empty))
                .apply { setPadding(SettingsScreen.dp(activity, 16), 0, SettingsScreen.dp(activity, 16), SettingsScreen.dp(activity, 12)) },
                SettingsScreen.matchWidth())
            return
        }
        val manager = activity.packageManager
        fun label(pkg: String) = runCatching { manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        scenes.forEach { scene ->
            val apps = (listOfNotNull(scene.main) + scene.slots.filterNotNull()).map(::label)
            val summary = if (apps.isEmpty()) activity.getString(R.string.scene_apps_none) else apps.joinToString("、")
            val buttons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(SettingsRowButton.create(activity).apply {
                    text = activity.getString(R.string.scene_apply_action)
                    isEnabled = workspaceActive
                    setOnClickListener { command(TriggerBroadcastContract.COMMAND_SCENE_APPLY, scene.name) }
                })
                addView(SettingsRowButton.create(activity).apply {
                    text = activity.getString(R.string.scene_shortcut_action)
                    setOnClickListener {
                        if (!SceneShortcutActivity.pin(activity, scene.name)) {
                            Toast.makeText(activity, R.string.scene_shortcut_unsupported, Toast.LENGTH_LONG).show()
                        }
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = SettingsScreen.dp(activity, 8) })
                addView(SettingsRowButton.create(activity).apply {
                    text = activity.getString(R.string.scene_delete_action)
                    setOnClickListener { command(TriggerBroadcastContract.COMMAND_SCENE_DELETE, scene.name) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = SettingsScreen.dp(activity, 8) })
            }
            scenesList.addView(row(scene.name, summary, buttons), SettingsScreen.matchWidth())
        }
    }

    private fun askSceneName() {
        val input = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.scene_name_hint)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
        }
        val margin = SettingsScreen.dp(activity, 20)
        SystemDialogs.builder(activity)
            .setTitle(R.string.scene_save_action)
            .setView(android.widget.FrameLayout(activity).apply { setPadding(margin, 0, margin, 0); addView(input) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { command(TriggerBroadcastContract.COMMAND_SCENE_SAVE, it) }
            }
            .show()
    }

    private fun command(key: String, value: String) {
        if (syncing || pending) return
        // Disabling the group here would run inside MaterialButtonToggleGroup's own checked-state
        // loop: a disabled MaterialButton ignores setChecked(false), so the previous choice stayed
        // highlighted next to the new one. Ignore further taps until SystemUI has answered instead.
        pending = true
        send(key, value) { result ->
            pending = false
            if (activity.isDestroyed || activity.isFinishing) return@send
            if (result !is TriggerCoordinator.RequestResult.Ready) {
                Toast.makeText(activity, R.string.workbench_command_failed, Toast.LENGTH_SHORT).show()
            }
            refresh()
        }
    }

    private fun MaterialButtonToggleGroup.select(value: String) {
        for (index in 0 until childCount) {
            val button = getChildAt(index) as MaterialButton
            if (button.tag == value) check(button.id) else if (button.isChecked) uncheck(button.id)
        }
    }

    private fun choice(content: LinearLayout, title: String, key: String, options: List<Pair<String, String>>): MaterialButtonToggleGroup {
        content.addView(SettingsScreen.subsectionTitle(activity, title), SettingsScreen.matchWidth())
        val buttons = options.map { (value, label) -> SettingsScreen.modeButton(activity, label).apply { tag = value } }
        return MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, SettingsScreen.dp(activity, 44), 1f)) }
            addOnButtonCheckedListener { group, checkedId, isChecked ->
                if (isChecked) command(key, group.findViewById<View>(checkedId).tag as String)
            }
            content.addView(this, SettingsScreen.matchWidth())
            controls += this
        }
    }

    private fun toggle(content: LinearLayout, title: String, summary: String, key: String): SwitchMaterial {
        val switch = SwitchMaterial(activity).apply {
            contentDescription = activity.getString(R.string.setting_description, title, summary)
            minimumWidth = SettingsScreen.dp(activity, 72)
            minimumHeight = SettingsScreen.dp(activity, 48)
            setOnCheckedChangeListener { _, enabled -> command(key, enabled.toString()) }
        }
        content.addView(row(title, summary, switch).apply { setOnClickListener { switch.performClick() } }, SettingsScreen.matchWidth())
        controls += switch
        return switch
    }

    private fun row(title: String, summary: String, control: View) =
        SettingsScreen.settingRow(activity, title, summary, control).apply {
            setPadding(0, SettingsScreen.dp(activity, 6), 0, SettingsScreen.dp(activity, 6))
        }
}
