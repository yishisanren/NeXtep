package io.github.lujinxin.nextep.systemui

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WideAspectSamplerTest {
    private fun frame(barRows: Int, barColumns: Int, fill: Int = Color.GRAY): Bitmap {
        val bitmap = Bitmap.createBitmap(WideAspectSampler.COLUMNS, WideAspectSampler.ROWS, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (y in barRows until WideAspectSampler.ROWS - barRows) for (x in barColumns until WideAspectSampler.COLUMNS - barColumns) {
            bitmap.setPixel(x, y, fill)
        }
        return bitmap
    }

    @Test fun letterboxBarsNarrowTheAspectPillarboxBarsWidenItAndBlackFramesAreIgnored() {
        // A 16:9 slot (1216x684) showing a picture with 4 of 36 rows black top and bottom: 36/28 taller than wide.
        assertEquals(16.0 / 9 * 36 / 28, WideAspectSampler.measure(frame(4, 0), 1216, 684)!!, 0.01)
        // No bars: the slot's own aspect.
        assertEquals(16.0 / 9, WideAspectSampler.measure(frame(0, 0), 1216, 684)!!, 0.01)
        // Side bars on a 2.4:1 slot (1216x507): 48 of 64 columns carry the picture, 912x507.
        assertEquals(912.0 / 507, WideAspectSampler.measure(frame(0, 8), 1216, 507)!!, 0.01)
        // Wider bars than any picture: clamped at the minimum aspect.
        assertEquals(16.0 / 9, WideAspectSampler.measure(frame(0, 14), 1216, 507)!!, 0.01)
        assertNull(WideAspectSampler.measure(frame(16, 0), 1216, 684))
        assertNull(WideAspectSampler.measure(frame(0, 0, Color.BLACK), 1216, 684))
    }
}
