package io.github.lujinxin.nextep.config

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.github.lujinxin.nextep.R
import java.util.concurrent.Executors

internal object UpdateSection {
    fun create(activity: AppCompatActivity): View {
        val installed = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
        val version = installed.versionName.orEmpty()
        val endpoint = activity.getString(R.string.update_manifest_url).trim()
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val padding = dp(16)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, padding / 2, 0, padding)
        }
        val status = TextView(activity).apply {
            text = activity.getString(R.string.update_current_version, version, installed.longVersionCode)
            textSize = 14f
            setTextColor(SettingsPalette.secondary(activity))
            setPadding(0, padding / 2, 0, padding / 2)
        }
        container.addView(TextView(activity).apply {
            text = activity.getString(R.string.update_title)
            textSize = 18f
            setTextColor(SettingsPalette.text(activity))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        container.addView(status)
        var downloadUrl = UpdateChecker.RELEASES_URL
        val download = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.update_releases)
            setOnClickListener { openBrowser(activity, downloadUrl) }
        }
        val check = MaterialButton(activity).apply { text = activity.getString(R.string.update_check) }
        val executor = Executors.newSingleThreadExecutor()
        var disposed = false
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                disposed = true
                executor.shutdownNow()
            }
        })
        check.setOnClickListener {
            if (disposed) return@setOnClickListener
            check.isEnabled = false
            check.text = activity.getString(R.string.update_checking)
            status.text = activity.getString(if (endpoint.isBlank()) R.string.update_connecting_github else R.string.update_connecting_server)
            executor.execute {
                val result = runCatching { UpdateChecker.check(endpoint, installed.longVersionCode, version) }
                activity.runOnUiThread {
                    if (disposed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    check.isEnabled = true
                    check.text = activity.getString(R.string.update_check)
                    result.onSuccess { release ->
                        if (release == null) {
                            status.text = activity.getString(R.string.update_up_to_date, version)
                            downloadUrl = UpdateChecker.RELEASES_URL
                            download.text = activity.getString(R.string.update_releases)
                        } else {
                            downloadUrl = release.downloadUrl
                            download.text = activity.getString(R.string.update_download_version, release.version)
                            status.text = activity.getString(R.string.update_available_status, version, release.version)
                            SystemDialogs.builder(activity)
                                .setTitle(activity.getString(R.string.update_available_title, release.version))
                                .setMessage(release.notes.ifBlank { activity.getString(R.string.update_notes_empty) } + activity.getString(R.string.update_install_hint))
                                .setPositiveButton(activity.getString(R.string.update_download)) { _, _ -> openBrowser(activity, release.downloadUrl) }
                                .setNegativeButton(activity.getString(R.string.update_later), null)
                                .show()
                        }
                    }.onFailure {
                        status.text = activity.getString(R.string.update_failed)
                    }
                }
            }
        }
        listOf(check, download, MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.update_source)
            setOnClickListener { openBrowser(activity, UpdateChecker.REPOSITORY_URL) }
        }).forEachIndexed { index, button ->
            button.minHeight = dp(48)
            button.insetTop = dp(4)
            button.insetBottom = dp(4)
            container.addView(button, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
        return container
    }

    internal fun openBrowser(activity: AppCompatActivity, address: String) {
        try {
            // A browser selector avoids handing GitHub links to the GitHub app.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.requireHttps(address))).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            }
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.browser_unavailable), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(activity, activity.getString(R.string.browser_open_failed), Toast.LENGTH_LONG).show()
        }
    }
}
