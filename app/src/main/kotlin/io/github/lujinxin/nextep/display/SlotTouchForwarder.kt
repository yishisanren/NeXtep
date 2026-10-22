package io.github.lujinxin.nextep.display

import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent

/** Only the top player is interactive; side cards remain task-selection targets. */
internal class SlotTouchForwarder(private val send: (MotionEvent) -> Boolean) {
    private var last: MotionEvent? = null

    fun touch(event: MotionEvent, bounds: RectF, width: Int, height: Int): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            if (!bounds.contains(event.x, event.y)) return false
        } else if (last == null) return false
        if (bounds.width() <= 0 || bounds.height() <= 0 || width <= 0 || height <= 0) {
            cancel(); return false
        }
        val mapped = MotionEvent.obtain(event)
        mapped.transform(Matrix().apply {
            setTranslate(-bounds.left, -bounds.top)
            postScale(width / bounds.width(), height / bounds.height())
        })
        val accepted = try { send(mapped) } catch (_: Exception) { false }
        if (!accepted) {
            mapped.recycle()
            cancel()
            return false
        }
        last?.recycle()
        last = if (event.actionMasked != MotionEvent.ACTION_UP &&
            event.actionMasked != MotionEvent.ACTION_CANCEL) MotionEvent.obtain(mapped) else null
        mapped.recycle()
        return accepted
    }

    fun cancel() {
        val previous = last ?: return
        last = null
        previous.action = MotionEvent.ACTION_CANCEL
        runCatching { send(previous) }
        previous.recycle()
    }
}
