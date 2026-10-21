package io.github.lujinxin.nextep.systemui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.widget.TextView

/** TextView's built-in marquee only runs on overflow. This ticker also moves
 * short text, while retaining TextView's font fallback and accessibility text. */
internal class LoopingTitleView(context: Context) : TextView(context) {
    private var scrolling = false
    private var running = false
    private var lastFrame = 0L
    private var offset = 0f
    private var line: StaticLayout? = null
    private var lineText = ""
    private var lineTextSize = 0f
    private var lineTypeface: android.graphics.Typeface? = null
    private var lineFakeBold = false

    fun setScrolling(enabled: Boolean, visible: Boolean) {
        if (scrolling == enabled && running == visible) return
        if (scrolling != enabled) { offset = 0f; line = null }
        scrolling = enabled
        running = visible
        lastFrame = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!scrolling || !ValueAnimator.areAnimatorsEnabled()) {
            super.onDraw(canvas)
            return
        }
        val value = text?.toString().orEmpty()
        if (value.isEmpty()) return
        paint.color = currentTextColor
        if (line == null || lineText != value || lineTextSize != paint.textSize ||
            lineTypeface !== paint.typeface || lineFakeBold != paint.isFakeBoldText) {
            lineText = value
            lineTextSize = paint.textSize
            lineTypeface = paint.typeface
            lineFakeBold = paint.isFakeBoldText
            line = StaticLayout.Builder.obtain(value, 0, value.length, paint,
                kotlin.math.ceil(Layout.getDesiredWidth(value, paint).toDouble()).toInt().coerceAtLeast(1))
                .setIncludePad(false).setMaxLines(1).build()
            offset = 0f
        }
        val layout = line ?: return
        val available = (width - paddingLeft - paddingRight).toFloat()
        if (available <= 0f) return
        val pitch = maxOf(layout.width + 32f * resources.displayMetrics.density, available)
        val now = SystemClock.uptimeMillis()
        if (running && isShown && windowVisibility == VISIBLE) {
            if (lastFrame != 0L) offset = (offset + (now - lastFrame).coerceAtMost(64L) *
                resources.displayMetrics.density * 0.028f) % pitch
            lastFrame = now
            postInvalidateOnAnimation()
        } else lastFrame = 0L
        val origin = maxOf(0f, (available - layout.width) / 2f) - offset
        val y = paddingTop + (height - paddingTop - paddingBottom - layout.height) / 2f
        val save = canvas.save()
        // View.draw offsets its canvas by TextView's internal horizontal scroll.
        // Our own ticker uses viewport coordinates and must undo that offset.
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        for (copy in -1..2) {
            val frame = canvas.save()
            canvas.translate(paddingLeft + origin + copy * pitch, y)
            layout.draw(canvas)
            canvas.restoreToCount(frame)
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        lastFrame = 0L
        super.onDetachedFromWindow()
    }
}
