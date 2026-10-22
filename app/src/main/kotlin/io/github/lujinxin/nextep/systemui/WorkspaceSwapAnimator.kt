package io.github.lujinxin.nextep.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import io.github.lujinxin.nextep.logging.NeXtepLog

/** Animate disposable previews first; perform the display move while the final
 * preview covers relayout, then fade to the live buffers. Nothing retains an app
 * frame after the transition ends or the workspace is hidden/locked. */
internal class WorkspaceSwapAnimator(private val context: Context, private val appearance: () -> WorkbenchAppearance) {
    /** A frame without a bitmap draws as a card; an empty one also carries the plus badge. */
    data class Frame(val bitmap: Bitmap?, val from: RectF, val to: RectF, val empty: Boolean = false)
    private val manager = context.getSystemService(WindowManager::class.java)
    private var overlay: SwapPreviewView? = null
    private var animator: ValueAnimator? = null
    private var generation = 0
    fun animate(
        source: Bitmap?, target: Bitmap?, targetEmpty: Boolean,
        start: RectF, sourceBounds: RectF, targetBounds: RectF,
        screenWidth: Int, screenHeight: Int,
        move: (() -> Unit) -> Unit,
    ) = animateFrames(listOf(
        // The vacated card travels back as well, so no bare panel shows where it lands.
        Frame(target, targetBounds, sourceBounds, empty = targetEmpty),
        Frame(source, start, targetBounds),
    ), listOf(sourceBounds, targetBounds), screenWidth, screenHeight, move)

    fun animateFrames(frames: List<Frame>, covers: List<RectF>, screenWidth: Int, screenHeight: Int,
                      move: (() -> Unit) -> Unit) {
        cancel()
        val windowType = SystemUiWindowTypeResolver.resolve()
        if (windowType == null) {
            frames.forEach { it.bitmap?.recycle() }
            move {}
            return
        }
        val token = ++generation
        val preview = SwapPreviewView(context, frames, covers, appearance())
        val params = WindowManager.LayoutParams(
            screenWidth, screenHeight, windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            title = "NeXtepSwapPreview"
            gravity = Gravity.TOP or Gravity.LEFT
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val added = runCatching { manager.addView(preview, params) }.isSuccess
        if (!added) {
            preview.release()
            move {}
            return
        }
        overlay = preview
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                preview.progress = it.animatedValue as Float
                preview.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != token) return
                    animator = null
                    fun reveal() {
                        if (generation != token) return
                        animator = ValueAnimator.ofFloat(1f, 0f).apply {
                            duration = 100L
                            addUpdateListener { preview.alpha = it.animatedValue as Float }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    if (generation == token) {
                                        animator = null
                                        this@WorkspaceSwapAnimator.cancel()
                                    }
                                }
                            })
                            start()
                        }
                    }
                    runCatching { move(::reveal) }.onFailure {
                        NeXtepLog.warn("slot_drag", "Exchange callback failed", it)
                        this@WorkspaceSwapAnimator.cancel()
                    }
                }
            })
            start()
        }
    }

    fun cancel() {
        generation += 1
        animator?.cancel()
        animator = null
        val previous = overlay
        overlay = null
        previous?.animate()?.cancel()
        if (previous != null) {
            runCatching { manager.removeViewImmediate(previous) }
            previous.release()
        }
    }
}

internal class SwapPreviewView(
    context: Context,
    private val frames: List<WorkspaceSwapAnimator.Frame>,
    private val covers: List<RectF>,
    appearance: WorkbenchAppearance,
) : View(context) {
    var progress = 0f
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Covers mimic the panel behind moving cards; cards mimic a live slot, so the
    // fade into the real windows changes nothing visible in either theme.
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.opaquePanel }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appearance.card }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = appearance.outline
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val badge = appearance.emptyBadge(resources.displayMetrics.density)
    private val badgeSize = (WorkbenchAppearance.BADGE_SIZE_DP * resources.displayMetrics.density).toInt()
    private val destination = RectF()
    private val sourceRect = Rect()
    private val clipPath = android.graphics.Path()
    private val cornerRadius = resources.displayMetrics.density * 14f
    private var released = false

    override fun onDraw(canvas: Canvas) {
        if (released) return
        // The live tasks stay put until the compositor move. Cover their old
        // positions so a shrinking preview cannot expose a second stationary app.
        covers.forEach { canvas.drawRoundRect(it, cornerRadius, cornerRadius, coverPaint) }
        frames.forEach { drawFrame(canvas, it.bitmap, it.from, it.to, it.empty) }
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap?, from: RectF, to: RectF, empty: Boolean) {
        fun interpolate(a: Float, b: Float) = a + (b - a) * progress
        destination.set(interpolate(from.left, to.left), interpolate(from.top, to.top),
            interpolate(from.right, to.right), interpolate(from.bottom, to.bottom))
        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(destination, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawRoundRect(destination, cornerRadius, cornerRadius, cardPaint)
        if (empty) {
            val left = (destination.centerX() - badgeSize / 2f).toInt()
            val top = (destination.centerY() - badgeSize / 2f).toInt()
            badge.setBounds(left, top, left + badgeSize, top + badgeSize)
            badge.draw(canvas)
        } else if (bitmap != null) {
            val scale = minOf(destination.width() / bitmap.width, destination.height() / bitmap.height)
            val imageWidth = bitmap.width * scale
            val imageHeight = bitmap.height * scale
            val fitted = RectF(destination.centerX() - imageWidth / 2, destination.centerY() - imageHeight / 2,
                destination.centerX() + imageWidth / 2, destination.centerY() + imageHeight / 2)
            sourceRect.set(0, 0, bitmap.width, bitmap.height)
            canvas.drawBitmap(bitmap, sourceRect, fitted, bitmapPaint)
        }
        canvas.restoreToCount(save)
        canvas.drawRoundRect(destination, cornerRadius, cornerRadius, borderPaint)
    }

    fun release() {
        if (released) return
        released = true
        frames.forEach { it.bitmap?.recycle() }
    }
}
