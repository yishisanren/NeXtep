package io.github.lujinxin.nextep.config

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/**
 * A Home-screen shortcut. It only hands the scene name to the broker and leaves at once:
 * while this translucent Activity is in front, SystemUI has no task surface to present,
 * so the broker opens the workspace after the launcher is back in front.
 */
class SceneShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(TriggerBroadcastContract.EXTRA_SCENE)?.takeIf { it.isNotBlank() }?.let { name ->
            sendBroadcast(TriggerBroadcastContract.hostSceneIntent(name))
        }
        finish()
    }

    companion object {
        /** Ask the launcher to pin a shortcut for [name]; false when this launcher cannot pin. */
        fun pin(context: Context, name: String): Boolean {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
            if (!manager.isRequestPinShortcutSupported) return false
            val launch = Intent(context, SceneShortcutActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(TriggerBroadcastContract.EXTRA_SCENE, name)
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
