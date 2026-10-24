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
    enum class Kind {
        COMPARE, PAYMENT, SETTINGS, ADD, BACK, HOME, RECENTS, SCAN, CODE, EXPAND,
        SCENE_ADD, WORK, STUDY, GAME, MEDIA, READ, SHOP, TRAVEL, LIFE,
    }
    companion object {
        /** The glyph of a scene preset key (see WorkbenchScene.PRESET_ICONS). */
        fun preset(key: String?): Kind? = when (key) {
            "work" -> Kind.WORK; "study" -> Kind.STUDY; "game" -> Kind.GAME; "media" -> Kind.MEDIA
            "read" -> Kind.READ; "shop" -> Kind.SHOP; "travel" -> Kind.TRAVEL; "life" -> Kind.LIFE
            else -> null
        }
    }
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
                // Three sliders: each rail breaks around its knob.
                listOf(7f to 15f, 12f to 9f, 17f to 14f).forEach { (y, knob) ->
                    line(4f, y, knob - 3.2f, y); line(knob + 3.2f, y, 20f, y)
                    canvas.drawCircle(knob, y, 2.1f, paint)
                }
            }
            Kind.BACK -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            Kind.COMPARE -> {
                // Shopping cart: handle, basket, two wheels.
                canvas.drawPath(Path().apply {
                    moveTo(2.5f, 4f); lineTo(5.5f, 4f); lineTo(8f, 15f); lineTo(18.5f, 15f); lineTo(21f, 7.5f); lineTo(6.5f, 7.5f)
                }, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(9.5f, 19.5f, 1.6f, paint); canvas.drawCircle(17f, 19.5f, 1.6f, paint)
                paint.style = Paint.Style.STROKE
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
            Kind.SCENE_ADD -> {
                // Two stacked cards, a plus on the front one: one more scene on the stack.
                canvas.save()
                canvas.clipOutRect(2f, 6f, 18f, 21f)
                canvas.drawRoundRect(7f, 3f, 21f, 16f, 2.5f, 2.5f, paint)
                canvas.restore()
                canvas.drawRoundRect(3f, 7f, 17f, 20f, 2.5f, 2.5f, paint)
                line(10f, 10.5f, 10f, 16.5f); line(7f, 13.5f, 13f, 13.5f)
            }
            Kind.WORK -> {
                // Briefcase.
                canvas.drawRoundRect(3f, 7.5f, 21f, 19.5f, 2.5f, 2.5f, paint)
                canvas.drawPath(Path().apply { moveTo(9f, 7.5f); lineTo(9f, 5f); lineTo(15f, 5f); lineTo(15f, 7.5f) }, paint)
                line(3f, 12.5f, 21f, 12.5f); line(12f, 11.5f, 12f, 13.5f)
            }
            Kind.STUDY -> {
                // Open book.
                canvas.drawPath(Path().apply {
                    moveTo(12f, 6.5f); lineTo(4f, 5f); lineTo(4f, 18f); lineTo(12f, 19.5f)
                    lineTo(20f, 18f); lineTo(20f, 5f); lineTo(12f, 6.5f); lineTo(12f, 19.5f)
                }, paint)
            }
            Kind.GAME -> {
                // Gamepad: a cross and two buttons.
                canvas.drawRoundRect(2.5f, 7.5f, 21.5f, 17.5f, 5f, 5f, paint)
                line(6.5f, 12.5f, 10.5f, 12.5f); line(8.5f, 10.5f, 8.5f, 14.5f)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(15.5f, 11.3f, 1.2f, paint); canvas.drawCircle(18f, 13.8f, 1.2f, paint)
                paint.style = Paint.Style.STROKE
            }
            Kind.MEDIA -> {
                // Screen with a play mark.
                canvas.drawRoundRect(3f, 5f, 21f, 19f, 3f, 3f, paint)
                canvas.drawPath(Path().apply { moveTo(10f, 9f); lineTo(15.5f, 12f); lineTo(10f, 15f); close() }, paint)
            }
            Kind.READ -> {
                // Page of text.
                canvas.drawRoundRect(5f, 3f, 19f, 21f, 2.5f, 2.5f, paint)
                line(8.5f, 8f, 15.5f, 8f); line(8.5f, 12f, 15.5f, 12f); line(8.5f, 16f, 12.5f, 16f)
            }
            Kind.SHOP -> {
                // Shopping bag.
                canvas.drawRoundRect(4f, 8f, 20f, 21f, 2.5f, 2.5f, paint)
                canvas.drawPath(Path().apply { moveTo(9f, 10.5f); lineTo(9f, 6.5f); cubicTo(9f, 3.5f, 15f, 3.5f, 15f, 6.5f); lineTo(15f, 10.5f) }, paint)
            }
            Kind.TRAVEL -> {
                // Map pin.
                canvas.drawPath(Path().apply {
                    moveTo(12f, 21f); cubicTo(7f, 15.5f, 5f, 12.5f, 5f, 9.5f)
                    cubicTo(5f, 5.6f, 8.1f, 3f, 12f, 3f); cubicTo(15.9f, 3f, 19f, 5.6f, 19f, 9.5f)
                    cubicTo(19f, 12.5f, 17f, 15.5f, 12f, 21f)
                }, paint)
                canvas.drawCircle(12f, 9.5f, 2.5f, paint)
            }
            Kind.LIFE -> {
                // Cup with steam.
                canvas.drawRoundRect(4f, 10f, 16f, 20f, 3f, 3f, paint)
                canvas.drawArc(13f, 11.5f, 20f, 17.5f, -90f, 180f, false, paint)
                line(7.5f, 4f, 7.5f, 7f); line(10f, 3.5f, 10f, 7f); line(12.5f, 4f, 12.5f, 7f)
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
