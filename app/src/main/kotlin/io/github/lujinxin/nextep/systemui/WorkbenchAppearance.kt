package io.github.lujinxin.nextep.systemui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared panel colours; task contents remain owned by their applications. */
internal enum class WorkbenchAppearance(
    val panel: Int, val panelEnd: Int, val card: Int,
    val text: Int, val secondary: Int, val outline: Int,
) {
    DARK(0xF20C131F.toInt(), 0xF2172230.toInt(), 0xFF222E3D.toInt(),
        Color.WHITE, 0xFFAFBAC9.toInt(), 0xFF3A485A.toInt()),
    LIGHT(0xF2EDF1F7.toInt(), 0xF2DDE5F0.toInt(), 0xFFF9FBFE.toInt(),
        0xFF182333.toInt(), 0xFF526176.toInt(), 0xFFC2CCDB.toInt());

    val accent: Int get() = if (this == DARK) 0xFFE8BE70.toInt() else 0xFF8C5D13.toInt()

    fun background(radius: Float = 0f, stroke: Int = 0) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(panel, panelEnd),
    ).apply {
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, outline)
    }
}

/** Round only our visual frame; never overwrite a task leash's native corner state. */
internal class WorkbenchFrameDrawable(
    private val maskColor: Int, private val borderColor: Int,
    private val radius: Float, private val stroke: Float,
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val mask = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
    private val frame = android.graphics.RectF()
    private var drawableAlpha = 255

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        frame.set(bounds)
        mask.reset()
        mask.fillType = android.graphics.Path.FillType.EVEN_ODD
        mask.addRect(frame, android.graphics.Path.Direction.CW)
        mask.addRoundRect(frame, radius, radius, android.graphics.Path.Direction.CW)
        frame.inset(stroke / 2, stroke / 2)
    }

    override fun draw(canvas: android.graphics.Canvas) {
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = maskColor
        paint.alpha = (android.graphics.Color.alpha(maskColor) * drawableAlpha / 255)
        canvas.drawPath(mask, paint)
        paint.color = borderColor
        paint.alpha = (android.graphics.Color.alpha(borderColor) * drawableAlpha / 255)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = stroke
        canvas.drawRoundRect(frame, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
