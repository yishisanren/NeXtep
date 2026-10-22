package io.github.lujinxin.nextep.config

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import io.github.lujinxin.nextep.R

/** Second-level page: choose up to ten favourite apps and drag them into order. */
class FavoriteAppsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !SettingsPalette.isDark(this@FavoriteAppsActivity)
            isAppearanceLightNavigationBars = !SettingsPalette.isDark(this@FavoriteAppsActivity)
        }
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(SettingsPalette.page(this@FavoriteAppsActivity))
            addView(TextView(this@FavoriteAppsActivity).apply {
                text = getString(R.string.app_strip_title)
                textSize = 28f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(SettingsPalette.text(this@FavoriteAppsActivity))
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
            addView(SettingsScreen.favoriteAppsEditor(this@FavoriteAppsActivity, SettingsRepository(this@FavoriteAppsActivity)),
                LinearLayout.LayoutParams(-1, -2))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setBackgroundColor(SettingsPalette.page(this@FavoriteAppsActivity))
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = safe.left, top = safe.top, right = safe.right, bottom = safe.bottom)
            insets
        }
        setContentView(scroll)
    }
}
