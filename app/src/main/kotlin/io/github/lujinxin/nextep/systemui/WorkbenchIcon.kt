package io.github.lujinxin.nextep.systemui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** One optical grid for controls, independent of the host's installed font. */
class WorkbenchIcon(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind { COMPARE, PAYMENT, SETTINGS, ADD, BACK, HOME, RECENTS, SCAN, CODE, EXPAND }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val color: Int get() = paint.color
    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val scale = minOf(bounds.width(), bounds.height()) / 24f
        canvas.translate((bounds.width() - 24 * scale) / 2, (bounds.height() - 24 * scale) / 2)
        canvas.scale(scale, scale)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        when (kind) {
            Kind.ADD -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            Kind.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3.2f, paint)
                canvas.drawCircle(12f, 12f, 7.2f, paint)
                for (step in 0 until 8) {
                    val angle = Math.toRadians(step * 45.0)
                    val dx = Math.cos(angle).toFloat()
                    val dy = Math.sin(angle).toFloat()
                    line(12f + 7.2f * dx, 12f + 7.2f * dy, 12f + 10f * dx, 12f + 10f * dy)
                }
            }
            Kind.BACK -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            Kind.COMPARE -> {
                canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); line(15.5f, 15.5f, 21f, 21f)
                line(7f, 9f, 14f, 9f); line(7f, 12f, 12f, 12f)
            }
            Kind.PAYMENT -> {
                canvas.drawRoundRect(3f, 5f, 21f, 19f, 3f, 3f, paint)
                line(3f, 9f, 21f, 9f); line(7f, 15f, 11f, 15f)
            }
            Kind.HOME -> {
                canvas.drawPath(Path().apply {
                    moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f)
                    moveTo(6f, 9f); lineTo(6f, 21f); lineTo(18f, 21f); lineTo(18f, 9f)
                }, paint)
            }
            Kind.RECENTS -> canvas.drawRoundRect(5f, 4f, 19f, 20f, 3f, 3f, paint)
            Kind.SCAN, Kind.EXPAND -> {
                canvas.drawPath(Path().apply {
                    moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
                    moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
                    moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
                    moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
                }, paint)
                if (kind == Kind.SCAN) line(8f, 12f, 16f, 12f)
            }
            Kind.CODE -> {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, paint)
                listOf(7f, 10f, 14f, 17f).forEach { line(it, 8f, it, 16f) }
            }
        }
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    // ImageView.imageTintList reaches drawables through setTintList; the base
    // Drawable ignores it, which left light-theme icons white.
    override fun setTintList(tint: ColorStateList?) {
        tint?.let { paint.color = it.defaultColor; invalidateSelf() }
    }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
