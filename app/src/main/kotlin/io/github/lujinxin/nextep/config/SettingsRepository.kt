package io.github.lujinxin.nextep.config

import android.content.Context

class SettingsRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("nextep_settings", Context.MODE_PRIVATE)

    data class TopBarSettings(
        val title: String,
        val contentMode: String,
        val showSeconds: Boolean,
        val frostStrength: Int,
        val manualAppOrder: Boolean,
        val appComponents: List<String>,
        val textScroll: Boolean,
        val textFontFamily: String,
        val textSizeSp: Int,
        val textBold: Boolean,
        val statusBarGestureEnabled: Boolean,
        val slotBackgroundSwipeEnabled: Boolean,
        val autoMinimizeMainOnTopAppSwitch: Boolean,
        val navigationButtonsEnabled: Boolean,
    )

    fun topBarSettings(): TopBarSettings = TopBarSettings(
        title = preferences.getString(KEY_TOP_TITLE, DEFAULT_TOP_TITLE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_TOP_TITLE,
        contentMode = TopContentMode.resolve(preferences.getString("top_content_mode", null), preferences.getString(KEY_TOP_TITLE, "").orEmpty()),
        showSeconds = preferences.getBoolean("top_show_seconds", false),
        frostStrength = preferences.getInt("frost_strength", 50).coerceIn(0, 100),
        manualAppOrder = preferences.getBoolean(KEY_MANUAL_APP_ORDER, false),
        appComponents = preferences.getString(KEY_TOP_APPS, null)
            ?.split(COMPONENT_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty(),
        textScroll = preferences.getBoolean("top_text_scroll", false),
        textFontFamily = TopTextStyle.resolveFamily(preferences.getString("top_text_font", null)),
        textSizeSp = preferences.getInt("top_text_size", TopTextStyle.DEFAULT_SIZE_SP)
            .coerceIn(TopTextStyle.MIN_SIZE_SP, TopTextStyle.MAX_SIZE_SP),
        textBold = preferences.getBoolean("top_text_bold", false),
        // Preserve the existing preference so disabling the old gesture also disables its replacement.
        statusBarGestureEnabled = preferences.getBoolean("status_bar_gesture_enabled", true),
        slotBackgroundSwipeEnabled = preferences.getBoolean("slot_background_swipe_enabled", true),
        autoMinimizeMainOnTopAppSwitch = preferences.getBoolean("auto_minimize_main_on_top_app_switch", false),
        navigationButtonsEnabled = preferences.getBoolean("navigation_buttons_enabled", false),
    )

    fun setNavigationButtonsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("navigation_buttons_enabled", enabled).apply()
        notifyTopContentChanged()
    }

    fun setStatusBarGestureEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("status_bar_gesture_enabled", enabled).apply()
        notifyTopContentChanged()
    }

    fun setAutoMinimizeMainOnTopAppSwitch(enabled: Boolean) {
        preferences.edit().putBoolean("auto_minimize_main_on_top_app_switch", enabled).apply()
        notifyTopContentChanged()
    }

    fun setSlotBackgroundSwipeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("slot_background_swipe_enabled", enabled).apply()
        notifyTopContentChanged()
    }

    private fun notifyTopContentChanged() {
        context.sendBroadcast(android.content.Intent(WorkspaceConfigContract.ACTION_CHANGED)
            .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            .setPackage("com.android.systemui"))
    }

    fun setTopTitle(title: String) {
        preferences.edit()
            .putString(KEY_TOP_TITLE, title.trim().ifEmpty { DEFAULT_TOP_TITLE })
            .putString("top_content_mode", if (title.isBlank()) TopContentMode.ICON else TopContentMode.TEXT)
            .apply()
        notifyTopContentChanged()
    }

    fun topTitleDraft(): String =
        preferences.getString("top_title_draft", preferences.getString(KEY_TOP_TITLE, "")).orEmpty()

    fun setTopTitleDraft(value: String) {
        preferences.edit().putString("top_title_draft", value).apply()
    }

    fun setTopContentMode(mode: String) {
        preferences.edit().putString("top_content_mode", TopContentMode.resolve(mode, "")).apply()
        notifyTopContentChanged()
    }

    fun setTopShowSeconds(enabled: Boolean) {
        preferences.edit().putBoolean("top_show_seconds", enabled).apply()
        notifyTopContentChanged()
    }

    fun setTopTextScroll(enabled: Boolean) {
        preferences.edit().putBoolean("top_text_scroll", enabled).apply()
        notifyTopContentChanged()
    }

    fun setTopTextFontFamily(family: String) {
        preferences.edit().putString("top_text_font", TopTextStyle.resolveFamily(family)).apply()
        notifyTopContentChanged()
    }

    fun setTopTextSize(sizeSp: Int) {
        preferences.edit().putInt("top_text_size", sizeSp.coerceIn(
            TopTextStyle.MIN_SIZE_SP, TopTextStyle.MAX_SIZE_SP,
        )).apply()
        notifyTopContentChanged()
    }

    fun setTopTextBold(enabled: Boolean) {
        preferences.edit().putBoolean("top_text_bold", enabled).apply()
        notifyTopContentChanged()
    }

    fun setFrostStrength(value: Int) {
        preferences.edit().putInt("frost_strength", value.coerceIn(0, 100)).apply()
        notifyTopContentChanged()
    }

    fun setTopApps(components: List<String>) {
        preferences.edit()
            .putString(
                KEY_TOP_APPS,
                components.distinct().take(MAX_TOP_APPS).joinToString(COMPONENT_SEPARATOR),
            )
            .apply()
        notifyTopContentChanged()
    }

    fun setManualAppOrder(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_MANUAL_APP_ORDER, enabled).apply()
        notifyTopContentChanged()
    }

    private companion object {
        const val KEY_TOP_TITLE = "top_title"
        const val KEY_TOP_APPS = "top_apps"
        const val KEY_MANUAL_APP_ORDER = "manual_app_order"
        const val DEFAULT_TOP_TITLE = ""
        const val COMPONENT_SEPARATOR = "\n"
        const val MAX_TOP_APPS = 36
    }
}
