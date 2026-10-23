package io.github.lujinxin.nextep.config

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.trigger.TriggerCoordinator

/**
 * Share-sheet target: a link opens in the chosen pane with the default browser, any other
 * text becomes a web search there. SystemUI places the resolved Activity; this only picks.
 */
class ShareToPaneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val target = text.takeIf { it.isNotEmpty() }?.let(::resolve)
        if (target == null) {
            Toast.makeText(this, R.string.share_unsupported, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val panes = listOf(getString(R.string.pane_main)) + (1..WORKSPACE_SLOT_COUNT).map { getString(R.string.pane_slot, it) }
        SystemDialogs.builder(this)
            .setTitle(R.string.share_pane_title)
            .setItems(panes.toTypedArray()) { _, which ->
                val value = "${which - 1}|${target.toUri(Intent.URI_INTENT_SCHEME)}"
                TriggerCoordinator.workbenchCommand(this, TriggerBroadcastContract.COMMAND_OPEN_IN_PANE, value) { result ->
                    if (result !is TriggerCoordinator.RequestResult.Ready) {
                        Toast.makeText(this, R.string.share_workspace_closed, Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /** SystemUI only places intents with an explicit Activity, so pick the default handler here. */
    private fun resolve(text: String): Intent? {
        val url = text.lines().firstOrNull { Patterns.WEB_URL.matcher(it.trim()).matches() }?.trim()
        val source = if (url != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(if (url.contains("://")) url else "https://$url"))
        } else {
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text)
        }
        val manager = packageManager
        val candidates = manager.queryIntentActivities(source, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo }.filter { it.packageName != packageName }
        val preferred = manager.resolveActivity(source, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?.let { info -> candidates.firstOrNull { it.packageName == info.packageName } }
        val chosen = preferred ?: candidates.firstOrNull() ?: return null
        return source.setClassName(chosen.packageName, chosen.name)
    }
}
