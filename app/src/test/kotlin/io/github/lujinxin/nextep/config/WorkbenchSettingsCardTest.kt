package io.github.lujinxin.nextep.config

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.trigger.TriggerCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkbenchSettingsCardTest {
    private fun state(appearance: String, side: String, bottom: Boolean, recents: Boolean, capture: Boolean, active: Boolean) =
        Bundle().apply {
            putString(TriggerBroadcastContract.EXTRA_APPEARANCE, appearance)
            putString(TriggerBroadcastContract.EXTRA_SIDEBAR_SIDE, side)
            putBoolean(TriggerBroadcastContract.EXTRA_CONTROL_BOTTOM, bottom)
            putBoolean(TriggerBroadcastContract.EXTRA_HIDE_RECENTS, recents)
            putBoolean(TriggerBroadcastContract.EXTRA_CAPTURE_ALLOWED, capture)
            putBoolean(TriggerBroadcastContract.EXTRA_ACTIVE, active)
        }

    private fun ready(extras: Bundle) = TriggerCoordinator.RequestResult.Ready(true, "home", 51, null, extras)

    @Test fun replyFromSystemUiIsMirroredAndEveryChangeBecomesExactlyOneCommand() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar)
        var reply = state("LIGHT", "LEFT", true, true, false, true)
        val sent = mutableListOf<Pair<String, String>>()
        // SystemUI answers on a later message, never inside the tap that sent the command.
        val replies = mutableListOf<() -> Unit>()
        fun drain() { replies.toList().forEach { replies.remove(it); it() } }
        val card = WorkbenchSettingsCard(activity, { done -> done(ready(reply)) }) { key, value, done ->
            sent += key to value
            reply = when (key) {
                TriggerBroadcastContract.COMMAND_APPEARANCE -> state(value, "LEFT", true, true, false, true)
                TriggerBroadcastContract.COMMAND_CONTROL_BOTTOM -> state("DARK", "LEFT", value.toBoolean(), true, false, true)
                else -> reply
            }
            replies += { done(ready(reply)) }
        }
        fun checkedTag(group: com.google.android.material.button.MaterialButtonToggleGroup) =
            group.checkedButtonId.takeIf { it != -1 }?.let { group.findViewById<MaterialButton>(it).tag }
        // Mirroring the reply must not send commands, and must show the actual selection.
        assertTrue(sent.isEmpty())
        assertEquals("LIGHT", checkedTag(card.appearance))
        assertEquals("LEFT", checkedTag(card.side))
        assertEquals("true", checkedTag(card.position))
        assertTrue(card.recents.isChecked)
        assertFalse(card.capture.isChecked)
        assertTrue(card.appearance.isEnabled && card.capture.isEnabled)
        (card.appearance.getChildAt(2) as MaterialButton).performClick()
        assertEquals(listOf(TriggerBroadcastContract.COMMAND_APPEARANCE to "DARK"), sent)
        drain()
        assertEquals("DARK", checkedTag(card.appearance))
        (card.appearance.getChildAt(0) as MaterialButton).performClick()
        assertEquals(TriggerBroadcastContract.COMMAND_APPEARANCE to "SYSTEM", sent.last())
        drain()
        assertEquals("SYSTEM", checkedTag(card.appearance))
        // Tapping the first button while the second is checked: only one may stay highlighted.
        (card.position.getChildAt(0) as MaterialButton).performClick()
        assertEquals(TriggerBroadcastContract.COMMAND_CONTROL_BOTTOM to "false", sent.last())
        assertTrue((card.position.getChildAt(0) as MaterialButton).isChecked)
        assertFalse((card.position.getChildAt(1) as MaterialButton).isChecked)
        // A second tap before the reply is ignored, not queued.
        (card.position.getChildAt(1) as MaterialButton).performClick()
        assertEquals(3, sent.size)
        drain()
        assertEquals("false", checkedTag(card.position))
        assertFalse((card.position.getChildAt(1) as MaterialButton).isChecked)
        assertEquals(3, sent.size)
        // No reply: controls are disabled and the capture grant cannot be requested.
        card.apply(null)
        assertFalse(card.appearance.isEnabled)
        assertFalse(card.capture.isEnabled)
        card.apply(state("LIGHT", "RIGHT", false, false, true, false))
        assertEquals("LIGHT", checkedTag(card.appearance))
        assertTrue(card.capture.isChecked)
        assertFalse(card.capture.isEnabled)
        assertEquals(3, sent.size)
    }

    @Test fun scenesAreListedWithRestoreHomeAndDeleteAndRestoreNeedsAnOpenWorkspace() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar)
        val scenes = io.github.lujinxin.nextep.workspace.WorkbenchScene.encode(listOf(
            io.github.lujinxin.nextep.workspace.WorkbenchScene("工作", "com.a.mail", listOf("com.a.chat", null, null, null))))
        fun withScenes(active: Boolean) = state("LIGHT", "LEFT", false, false, false, active).apply {
            putString(TriggerBroadcastContract.EXTRA_SCENES, scenes)
        }
        val sent = mutableListOf<Pair<String, String>>()
        val card = WorkbenchSettingsCard(activity, { done -> done(ready(withScenes(true))) }) { key, value, done ->
            sent += key to value; done(ready(withScenes(true)))
        }
        fun buttons(): List<MaterialButton> {
            val found = mutableListOf<MaterialButton>()
            fun walk(view: android.view.View) {
                if (view is MaterialButton) found += view
                if (view is android.view.ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
            }
            walk(card.scenesCard); return found
        }
        val labels = buttons().map { it.text.toString() }
        assertEquals(listOf(activity.getString(R.string.scene_save_action), activity.getString(R.string.scene_apply_action),
            activity.getString(R.string.scene_shortcut_action), activity.getString(R.string.scene_delete_action)), labels)
        buttons()[1].performClick()
        assertEquals(listOf(TriggerBroadcastContract.COMMAND_SCENE_APPLY to "工作"), sent)
        buttons()[3].performClick()
        assertEquals(TriggerBroadcastContract.COMMAND_SCENE_DELETE to "工作", sent.last())
        card.apply(withScenes(false))
        assertFalse(buttons()[0].isEnabled)
        assertFalse(buttons()[1].isEnabled)
        assertTrue(buttons()[3].isEnabled)
    }
}
