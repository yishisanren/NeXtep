package io.github.lujinxin.nextep.config

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.trigger.TriggerCoordinator
import io.github.lujinxin.nextep.trigger.TriggerSource

/** A Home-screen shortcut: open the workspace if needed, then restore the named scene. */
class SceneShortcutActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_SCENE)?.takeIf { it.isNotBlank() }
        if (name == null) { finish(); return }
        TriggerCoordinator.queryWorkbench(this) { result ->
            val ready = result as? TriggerCoordinator.RequestResult.Ready
            when {
                ready == null -> fail()
                ready.active -> apply(name, attempt = 1)
                else -> TriggerCoordinator.requestSet(this, true, TriggerSource.SYSTEM_RECOVERY) { entered ->
                    if (entered is TriggerCoordinator.RequestResult.Ready && entered.active) {
                        // The slots are still coming up right after entry; the first attempts may be refused.
                        handler.postDelayed({ apply(name, attempt = 1) }, ENTRY_SETTLE_MS)
                    } else fail()
                }
            }
        }
    }

    private fun apply(name: String, attempt: Int) {
        TriggerCoordinator.workbenchCommand(this, TriggerBroadcastContract.COMMAND_SCENE_APPLY, name) { result ->
            when {
                result is TriggerCoordinator.RequestResult.Ready -> finish()
                attempt < MAX_ATTEMPTS -> handler.postDelayed({ apply(name, attempt + 1) }, RETRY_MS)
                else -> fail()
            }
        }
    }

    private fun fail() {
        Toast.makeText(this, R.string.scene_shortcut_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_SCENE = "io.github.yishisanren.panes.extra.SCENE"
        private const val ENTRY_SETTLE_MS = 1500L
        private const val RETRY_MS = 1000L
        private const val MAX_ATTEMPTS = 5

        /** Ask the launcher to pin a shortcut for [name]; false when this launcher cannot pin. */
        fun pin(context: Context, name: String): Boolean {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
            if (!manager.isRequestPinShortcutSupported) return false
            val launch = Intent(context, SceneShortcutActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_SCENE, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val shortcut = ShortcutInfo.Builder(context, "scene:$name")
                .setShortLabel(name)
                .setLongLabel(context.getString(R.string.scene_shortcut_label, name))
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(launch)
                .build()
            return runCatching { manager.requestPinShortcut(shortcut, null) }.getOrDefault(false)
        }
    }
}
