package io.github.lujinxin.nextep.config

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import io.github.lujinxin.nextep.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    private fun descendants(view: View): Sequence<View> = sequenceOf(view) +
        ((view as? ViewGroup)?.let { group -> (0 until group.childCount).asSequence().flatMap { descendants(group.getChildAt(it)) } } ?: emptySequence())

    @Test fun thePageHasSevenGroupsAndNoUpstreamOnlyControls() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val texts = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
        assertEquals(7, descendants(root).count { it is MaterialCardView })
        for (title in listOf(R.string.app_strip_title, R.string.section_scenes, R.string.section_layout, R.string.section_open, R.string.section_privacy,
                R.string.section_support, R.string.navigation_about, R.string.about_based_on, R.string.about_tagline)) {
            assertTrue(activity.getString(title), activity.getString(title) in texts)
        }
        assertTrue(texts.none { it.contains("NeXtep") && !it.contains("lujinxin") })
    }
}
