package io.github.lujinxin.nextep.config

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
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
        val card = WorkbenchSettingsCard(activity, { done -> done(ready(reply)) }) { key, value, done ->
            sent += key to value
            reply = when (key) {
                TriggerBroadcastContract.COMMAND_APPEARANCE -> state(value, "LEFT", true, true, false, true)
                TriggerBroadcastContract.COMMAND_CONTROL_BOTTOM -> state("DARK", "LEFT", value.toBoolean(), true, false, true)
                else -> reply
            }
            done(ready(reply))
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
        (card.appearance.getChildAt(1) as MaterialButton).performClick()
        assertEquals(listOf(TriggerBroadcastContract.COMMAND_APPEARANCE to "DARK"), sent)
        assertEquals("DARK", checkedTag(card.appearance))
        (card.position.getChildAt(0) as MaterialButton).performClick()
        assertEquals(TriggerBroadcastContract.COMMAND_CONTROL_BOTTOM to "false", sent.last())
        assertEquals("false", checkedTag(card.position))
        assertEquals(2, sent.size)
        // No reply: controls are disabled and the capture grant cannot be requested.
        card.apply(null)
        assertFalse(card.appearance.isEnabled)
        assertFalse(card.capture.isEnabled)
        card.apply(state("LIGHT", "RIGHT", false, false, true, false))
        assertEquals("LIGHT", checkedTag(card.appearance))
        assertTrue(card.capture.isChecked)
        assertFalse(card.capture.isEnabled)
        assertEquals(2, sent.size)
    }
}
