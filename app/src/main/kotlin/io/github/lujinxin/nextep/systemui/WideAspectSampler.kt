package io.github.lujinxin.nextep.systemui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.view.PixelCopy
import android.view.SurfaceView
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceLayoutProfile

/**
 * Measures the picture inside the wide slot from its own output layer and reports the aspect
 * ratio the slot should take so the picture spans the full width without letterbox bars.
 * Two consecutive agreeing samples are needed before a change is reported.
 */
internal class WideAspectSampler(
    private val handler: Handler,
    private val surface: () -> SurfaceView?,
    private val onAspect: (Double) -> Unit,
) {
    private var running = false
    private var applied = WorkspaceLayoutProfile.DEFAULT_WIDE_ASPECT
    private var last: Double? = null
    private var agreeing = 0
    private val tick = Runnable { sample() }

    fun start() {
        stop()
        running = true
        applied = WorkspaceLayoutProfile.DEFAULT_WIDE_ASPECT
        handler.postDelayed(tick, FIRST_SAMPLE_MS)
    }

    fun stop() {
        running = false
        last = null
        agreeing = 0
        handler.removeCallbacks(tick)
    }

    private fun schedule() { if (running) handler.postDelayed(tick, SAMPLE_INTERVAL_MS) }

    private fun sample() {
        if (!running) return
        val view = surface()?.takeIf { it.width > 0 && it.height > 0 && it.holder.surface.isValid } ?: return schedule()
        val bitmap = Bitmap.createBitmap(COLUMNS, ROWS, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) consider(measure(bitmap, view.width, view.height))
                bitmap.recycle()
                schedule()
            }, handler)
        }.onFailure { bitmap.recycle(); schedule() }
    }

    private fun consider(aspect: Double?) {
        if (aspect == null) { last = null; agreeing = 0; return }
        val previous = last
        agreeing = if (previous != null && kotlin.math.abs(aspect - previous) / previous < TOLERANCE) agreeing + 1 else 1
        last = aspect
        if (agreeing >= 2 && kotlin.math.abs(aspect - applied) / applied >= TOLERANCE) {
            applied = aspect
            NeXtepLog.info("wide_layout", "Picture aspect ${"%.3f".format(aspect)}; resizing the wide slot")
            onAspect(aspect)
        }
    }

    companion object {
        const val FIRST_SAMPLE_MS = 2_500L
        const val SAMPLE_INTERVAL_MS = 1_500L
        const val TOLERANCE = 0.04
        const val COLUMNS = 64
        const val ROWS = 36
        private const val BAR_LUMA = 20

        /** Rows and columns that are black across their whole length are letterbox/pillarbox bars. */
        fun measure(bitmap: Bitmap, viewWidth: Int, viewHeight: Int): Double? {
            val w = bitmap.width; val h = bitmap.height
            val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            fun luma(p: Int) = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            fun rowDark(y: Int) = (0 until w).all { x -> luma(pixels[y * w + x]) < BAR_LUMA }
            fun colDark(x: Int) = (0 until h).all { y -> luma(pixels[y * w + x]) < BAR_LUMA }
            val top = (0 until h).takeWhile(::rowDark).count()
            val bottom = (h - 1 downTo 0).takeWhile(::rowDark).count()
            val left = (0 until w).takeWhile(::colDark).count()
            val right = (w - 1 downTo 0).takeWhile(::colDark).count()
            val rows = h - top - bottom
            val cols = w - left - right
            // A mostly black frame (loading, dark scene) says nothing about the picture.
            if (rows < h * 0.4 || cols < w * 0.4) return null
            val aspect = (viewWidth.toDouble() * cols / w) / (viewHeight.toDouble() * rows / h)
            return aspect.coerceIn(WorkspaceLayoutProfile.MIN_WIDE_ASPECT, WorkspaceLayoutProfile.MAX_WIDE_ASPECT)
        }
    }
}
