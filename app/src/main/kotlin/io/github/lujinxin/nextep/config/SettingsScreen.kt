package io.github.lujinxin.nextep.config

import android.app.StatusBarManager
import android.content.ClipData
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.apps.LauncherAppCatalogContract
import io.github.lujinxin.nextep.apps.LauncherAppRepository
import io.github.lujinxin.nextep.trigger.NeXtepTileService

object SettingsScreen {
    private data class AppOption(
        val key: String,
        val userId: Int,
        val label: String,
        val icon: Drawable,
    )

    /** Six groups on one page: favourites, layout, opening, privacy, troubleshooting, about. */
    fun create(activity: AppCompatActivity, repository: SettingsRepository): View {
        val settings = repository.topBarSettings()
        val workbench = WorkbenchSettingsCard(activity)
        fun card() = matchWidth().apply { bottomMargin = dp(activity, 16) }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 24), dp(activity, 20), dp(activity, 24))
            setBackgroundColor(SettingsPalette.page(activity))

            addView(TextView(activity).apply {
                text = activity.getString(R.string.navigation_settings)
                textSize = 28f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(SettingsPalette.text(activity))
                ViewCompat.setAccessibilityHeading(this, true)
            }, matchWidth().apply { bottomMargin = dp(activity, 20) })

            addView(sectionCard(activity).apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(groupTitle(activity, activity.getString(R.string.app_strip_title)), matchWidth())
                    fun summary() = activity.getString(R.string.favorite_apps_summary,
                        repository.topBarSettings().appComponents.size, MAX_TOP_APPS)
                    val editButton = SettingsRowButton.create(activity).apply {
                        text = activity.getString(R.string.favorite_apps_edit)
                        setOnClickListener {
                            activity.startActivity(android.content.Intent(activity, FavoriteAppsActivity::class.java))
                        }
                    }
                    val row = settingRow(activity, activity.getString(R.string.app_strip_title), summary(), editButton).apply {
                        setOnClickListener { editButton.performClick() }
                        isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    val summaryView = (row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
                    // Coming back from the editor refreshes the count.
                    row.viewTreeObserver.addOnWindowFocusChangeListener { focused -> if (focused) summaryView.text = summary() }
                    addView(row, matchWidth())
                    addView(settingDivider(activity))
                    addView(settingSwitchRow(
                        activity,
                        activity.getString(R.string.auto_minimize_title),
                        activity.getString(R.string.auto_minimize_summary),
                        settings.autoMinimizeMainOnTopAppSwitch,
                        repository::setAutoMinimizeMainOnTopAppSwitch,
                    ), matchWidth())
                }, matchWidth())
            }, card())

            addView(workbench.scenesCard, card())
            addView(workbench.layoutCard, card())

            val addTileButton = SettingsRowButton.create(activity).apply {
                text = activity.getString(R.string.action_add)
                contentDescription = activity.getString(R.string.tile_add_description)
                setOnClickListener {
                    isEnabled = false
                    runCatching {
                        activity.getSystemService(StatusBarManager::class.java)
                            .requestAddTileService(
                                ComponentName(activity, NeXtepTileService::class.java),
                                activity.getString(R.string.app_name),
                                Icon.createWithResource(activity, R.drawable.ic_nextep_tile),
                                activity.mainExecutor,
                            ) { result ->
                                isEnabled = true
                                val message = when (result) {
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> activity.getString(R.string.tile_added)
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> activity.getString(R.string.tile_already_added)
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> activity.getString(R.string.tile_not_added)
                                    else -> activity.getString(R.string.tile_add_unavailable)
                                }
                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                            }
                    }.onFailure {
                        isEnabled = true
                        Toast.makeText(activity, activity.getString(R.string.tile_add_manually), Toast.LENGTH_LONG).show()
                    }
                }
            }
            addView(sectionCard(activity).apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(groupTitle(activity, activity.getString(R.string.section_open)), matchWidth())
                    addView(settingRow(activity, activity.getString(R.string.tile_entry_title),
                        activity.getString(R.string.tile_entry_summary), addTileButton), matchWidth())
                    addView(settingDivider(activity))
                    addView(settingSwitchRow(
                        activity,
                        activity.getString(R.string.gesture_title),
                        activity.getString(R.string.gesture_summary),
                        settings.statusBarGestureEnabled,
                        repository::setStatusBarGestureEnabled,
                    ), matchWidth())
                }, matchWidth())
            }, card())

            addView(workbench.privacyCard, card())
            addView(workbench.supportCard, card())
            addView(aboutCard(activity), matchWidth())
        }
    }

    private fun aboutCard(activity: AppCompatActivity) = sectionCard(activity).apply {
        addView(sectionContent(activity).apply {
            addView(sectionTitle(activity, activity.getString(R.string.navigation_about)), matchWidth())
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageDrawable(activity.applicationInfo.loadIcon(activity.packageManager))
                    contentDescription = activity.getString(R.string.app_icon_description)
                }, LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = activity.getString(R.string.app_name)
                        textSize = 20f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(SettingsPalette.text(activity))
                    }, matchWidth())
                    addView(sectionDescription(activity, activity.getString(R.string.about_tagline)), matchWidth())
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(activity, 14) })
            }, matchWidth().apply { topMargin = dp(activity, 12) })
            addView(UpdateSection.create(activity), matchWidth())
            addView(sectionDescription(activity, activity.getString(R.string.about_based_on)), matchWidth())
            addView(sectionDescription(activity, activity.getString(R.string.about_donate)),
                matchWidth().apply { topMargin = dp(activity, 12) })
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.donate_wechat)
                adjustViewBounds = true
                contentDescription = activity.getString(R.string.about_donate_description)
            }, LinearLayout.LayoutParams(dp(activity, 200), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(activity, 8)
            })
        })
    }

    private fun groupTitle(activity: AppCompatActivity, value: String) = sectionTitle(activity, value).apply {
        setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), 0)
    }

    /** The favourite-app editor shown on its own page: selected order (drag) and the full catalogue. */
    fun favoriteAppsEditor(activity: AppCompatActivity, repository: SettingsRepository): View {
        val settings = repository.topBarSettings()
        val apps = loadApps(activity).toMutableList()
        val appByName = apps.associateBy { it.key }.toMutableMap()
        // A saved clone may arrive asynchronously from SystemUI. Keep its order
        // while the unprivileged settings process is still loading the catalogue.
        val selected = settings.appComponents.distinct()
            .filter { it in appByName || it.contains('|') }.take(MAX_TOP_APPS).toMutableList()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val selectedContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val availableContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val manualContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            lateinit var render: () -> Unit
            render = {
                selectedContainer.removeAllViews()
                if (selected.isEmpty()) {
                    selectedContainer.addView(emptyHint(activity, activity.getString(R.string.selected_apps_empty)))
                } else {
                    selected.forEachIndexed { index, name ->
                        val option = appByName[name] ?: return@forEachIndexed
                        selectedContainer.addView(
                            draggableSelectedRow(activity, option, name, index, selected, repository) {
                                render()
                            },
                        )
                    }
                }

                availableContainer.removeAllViews()
                apps.forEachIndexed { index, option ->
                    val componentName = option.key
                    availableContainer.addView(appRow(activity, option, 42).apply {
                        addView(SwitchMaterial(activity).apply {
                            text = ""
                            isChecked = selected.contains(componentName)
                            contentDescription = if (isChecked) activity.getString(R.string.app_remove, option.label) else activity.getString(R.string.app_add, option.label)
                            setOnCheckedChangeListener { _, enabled ->
                                if (enabled && selected.size >= MAX_TOP_APPS) {
                                    isChecked = false
                                    Toast.makeText(activity, activity.getString(R.string.apps_limit, MAX_TOP_APPS), Toast.LENGTH_SHORT).show()
                                    return@setOnCheckedChangeListener
                                }
                                if (enabled) selected += componentName else selected.remove(componentName)
                                repository.setTopApps(selected)
                                render()
                            }
                        }, LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 48)))
                    })
                    if (index != apps.lastIndex) availableContainer.addView(divider(activity))
                }
            }

            addView(sectionCard(activity).apply {
                addView(sectionContent(activity).apply {
                    addView(sectionTitle(activity, activity.getString(R.string.app_strip_title)), matchWidth())
                    addView(sectionDescription(activity, activity.getString(R.string.app_strip_summary)), matchWidth())
                    addView(manualContainer.apply {
                        addView(subsectionTitle(activity, activity.getString(R.string.selected_apps)), matchWidth())
                        addView(selectedContainer, matchWidth())
                        addView(subsectionTitle(activity, activity.getString(R.string.all_apps)), matchWidth().apply {
                            topMargin = dp(activity, 16)
                        })
                        addView(availableContainer, matchWidth())
                    }, matchWidth())
                })
            }, matchWidth())

            render()
            LauncherAppCatalogContract.query(activity) { profiles ->
                if (profiles == null || activity.isDestroyed || activity.isFinishing) return@query
                val currentUserId = LauncherAppRepository(activity).currentUserId
                apps.removeAll { it.userId != currentUserId }
                apps.addAll(profiles.map { AppOption(it.key, it.userId, it.label, it.icon) })
                apps.sortBy { it.label.lowercase() }
                appByName.clear()
                appByName.putAll(apps.associateBy { it.key })
                selected.retainAll(appByName.keys)
                render()
            }
        }
    }

    private fun draggableSelectedRow(
        activity: AppCompatActivity,
        option: AppOption,
        componentName: String,
        index: Int,
        selected: MutableList<String>,
        repository: SettingsRepository,
        render: () -> Unit,
    ): View = appRow(activity, option, 38).apply {
        contentDescription = activity.getString(R.string.app_drag_description, option.label)
        background = roundedBackground(SettingsPalette.container(activity), dp(activity, 12).toFloat())
        setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 8), dp(activity, 5))
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.bottomMargin = dp(activity, 7)
        layoutParams = params
        isLongClickable = true
        setOnLongClickListener { row ->
            row.startDragAndDrop(
                ClipData.newPlainText("NeXtep App", componentName),
                View.DragShadowBuilder(row),
                componentName,
                0,
            )
        }
        setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                DragEvent.ACTION_DROP -> {
                    val dragged = event.localState as? String ?: return@setOnDragListener false
                    val from = selected.indexOf(dragged)
                    if (from >= 0 && from != index) {
                        selected.removeAt(from)
                        selected.add(index.coerceAtMost(selected.size), dragged)
                        repository.setTopApps(selected)
                        render()
                    }
                    true
                }
                else -> true
            }
        }
        addView(ImageView(activity).apply {
            setImageResource(io.github.lujinxin.nextep.R.drawable.ic_drag_handle)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = activity.getString(R.string.app_drag_position, option.label)
        }, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 48)))
    }

    private fun loadApps(activity: AppCompatActivity): List<AppOption> {
        // Profile keys come from the same privileged catalogue as the app strip;
        // local permission differences must not produce a second key for a clone.
        return LauncherAppRepository(activity).load(includeProfiles = false).asSequence()
            .map { AppOption(it.key, it.userId, it.label, it.icon) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun appRow(activity: AppCompatActivity, option: AppOption, iconSizeDp: Int) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 60)
            addView(ImageView(activity).apply {
                setImageDrawable(option.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(activity, iconSizeDp), dp(activity, iconSizeDp)))
            addView(TextView(activity).apply {
                text = option.label
                textSize = 15f
                setTextColor(SettingsPalette.text(activity))
                setPadding(dp(activity, 13), 0, dp(activity, 8), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    internal fun sectionCard(activity: AppCompatActivity) = MaterialCardView(activity).apply {
        radius = dp(activity, 16).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(SettingsPalette.surface(activity))
        strokeColor = SettingsPalette.outline(activity)
        strokeWidth = 0
    }

    internal fun settingRow(
        activity: AppCompatActivity,
        title: String,
        summary: String,
        control: View,
    ) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(activity, 72)
        setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(SettingsPalette.text(activity))
            }, matchWidth())
            addView(sectionDescription(activity, summary), matchWidth())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(activity, 16)
        })
        addView(control, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun settingSwitchRow(
        activity: AppCompatActivity,
        title: String,
        summary: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ): View {
        val toggle = SwitchMaterial(activity).apply {
            contentDescription = activity.getString(R.string.setting_description, title, summary)
            minimumWidth = dp(activity, 72)
            minimumHeight = dp(activity, 48)
            isChecked = checked
            setOnCheckedChangeListener { _, enabled -> onCheckedChange(enabled) }
        }
        return settingRow(activity, title, summary, toggle).apply {
            setOnClickListener { toggle.performClick() }
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun settingDivider(activity: AppCompatActivity) = View(activity).apply {
        setBackgroundColor(SettingsPalette.outline(activity))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply {
            marginStart = dp(activity, 16)
            marginEnd = dp(activity, 16)
        }
    }

    internal fun modeButton(activity: AppCompatActivity, value: String) =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = value
            textSize = 14f
            cornerRadius = dp(activity, 12)
            insetTop = 0
            insetBottom = 0
            setTextColor(SettingsPalette.text(activity))
        }

    internal fun sectionContent(activity: AppCompatActivity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18))
    }

    internal fun sectionTitle(activity: AppCompatActivity, value: String) = TextView(activity).apply {
        text = value
        textSize = 18f
        setTextColor(SettingsPalette.text(activity))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    internal fun sectionDescription(activity: AppCompatActivity, value: String) = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(SettingsPalette.secondary(activity))
        setPadding(0, dp(activity, 4), 0, 0)
    }

    internal fun subsectionTitle(activity: AppCompatActivity, value: String) = TextView(activity).apply {
        text = value
        textSize = 14f
        setTextColor(SettingsPalette.secondary(activity))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(activity, 14), 0, dp(activity, 9))
    }

    private fun emptyHint(activity: AppCompatActivity, value: String) = TextView(activity).apply {
        text = value
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(SettingsPalette.secondary(activity))
        setPadding(dp(activity, 12), dp(activity, 18), dp(activity, 12), dp(activity, 18))
        background = roundedBackground(SettingsPalette.container(activity), dp(activity, 12).toFloat())
    }

    private fun divider(activity: AppCompatActivity) = View(activity).apply {
        setBackgroundColor(SettingsPalette.outline(activity))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply { marginStart = dp(activity, 55) }
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    internal fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    internal fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private const val MAX_TOP_APPS = 10
}
