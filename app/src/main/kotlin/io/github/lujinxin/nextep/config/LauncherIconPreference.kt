package io.github.lujinxin.nextep.config

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.icons.LauncherIconManager
import io.github.lujinxin.nextep.icons.LauncherIconStyle

internal object LauncherIconPreference {
    fun createButton(activity: AppCompatActivity): MaterialButton {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val button = SettingsRowButton.create(activity).apply {
            minHeight = dp(56)
            iconSize = dp(36)
            iconPadding = dp(6)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconTint = null
        }
        fun updateButton() {
            val current = LauncherIconManager.currentIcon(activity)
            val selection = LauncherIconManager.selection(activity)
            button.text = if (selection == LauncherIconManager.AUTOMATIC) {
                activity.getString(R.string.launcher_icon_automatic_current, activity.getString(current.label))
            } else activity.getString(LauncherIconStyle.fromKey(selection)!!.label)
            button.icon = activity.getDrawable(current.artwork)
            button.contentDescription = activity.getString(R.string.setting_description,
                activity.getString(R.string.launcher_icon_title), button.text)
        }
        val preferences = activity.getSharedPreferences(LauncherIconManager.PREFERENCES, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateButton() }
        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                preferences.registerOnSharedPreferenceChangeListener(listener)
                updateButton()
            }
            override fun onViewDetachedFromWindow(view: View) {
                preferences.unregisterOnSharedPreferenceChangeListener(listener)
            }
        })
        button.setOnClickListener {
            val seasonal = LauncherIconManager.seasonalIcon()
            val choices = listOf(LauncherIconManager.AUTOMATIC) + LauncherIconStyle.entries.map { it.key }
            val labels = choices.map { key ->
                if (key == LauncherIconManager.AUTOMATIC) activity.getString(R.string.launcher_icon_automatic)
                else activity.getString(LauncherIconStyle.fromKey(key)!!.label)
            }
            val adapter = object : ArrayAdapter<String>(SystemDialogs.context(activity),
                android.R.layout.select_dialog_singlechoice, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = super.getView(position, convertView, parent) as CheckedTextView
                    val style = LauncherIconStyle.fromKey(choices[position]) ?: seasonal
                    val preview = activity.getDrawable(style.artwork)?.apply { setBounds(0, 0, dp(44), dp(44)) }
                    row.setCompoundDrawablesRelative(preview, null, null, null)
                    row.compoundDrawablePadding = dp(16)
                    row.minHeight = dp(68)
                    return row
                }
            }
            SystemDialogs.builder(activity)
                .setTitle(R.string.launcher_icon_title)
                .setSingleChoiceItems(adapter, choices.indexOf(LauncherIconManager.selection(activity))) { dialog, index ->
                    dialog.dismiss()
                    if (!LauncherIconManager.select(activity, choices[index])) {
                        Toast.makeText(activity, R.string.launcher_icon_change_failed, Toast.LENGTH_LONG).show()
                    }
                    updateButton()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        updateButton()
        return button
    }
}
