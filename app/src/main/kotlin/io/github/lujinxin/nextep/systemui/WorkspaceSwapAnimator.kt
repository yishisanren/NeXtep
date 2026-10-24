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
    /** A frame without a bitmap draws as a card; an empty one also carries the plus badge.
     * A [topFill] frame fills its width from the top, so a portrait page shows its player in a wide pane. */
    data class Frame(val bitmap: Bitmap?, val from: RectF, var to: RectF, val empty: Boolean = false,
                     val topFill: Boolean = false)
    private val manager = context.getSystemService(WindowManager::class.java)
    private var overlay: SwapPreviewView? = null
    private var animator: ValueAnimator? = null
    private var generation = 0
    private var frozen = false
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
        if (show(frames, covers, screenWidth, screenHeight)) animateOverlay(move) else move {}
    }

    /** Hold [frames] at their start positions over the live windows until [play]. */
    fun freeze(frames: List<Frame>, covers: List<RectF>, screenWidth: Int, screenHeight: Int): Boolean =
        show(frames, covers, screenWidth, screenHeight).also { frozen = it }

    /** Runs [action] once, when the overlay's first frame is on screen (at once without an overlay). */
    fun whenShown(action: () -> Unit) {
        val preview = overlay ?: return action()
        var done = false
        fun once() {
            if (!done) { done = true; action() }
        }
        preview.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                preview.viewTreeObserver.removeOnPreDrawListener(this)
                // A new window shows a frame after its commit and the window manager's next pass;
                // two vsyncs after the draw were once one short, and the layout change showed first.
                val choreographer = android.view.Choreographer.getInstance()
                preview.viewTreeObserver.registerFrameCommitCallback {
                    choreographer.postFrameCallback { choreographer.postFrameCallback { once() } }
                }
                return true
            }
        })
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(::once, SHOW_TIMEOUT_MS)
    }

    /** Hold one more still frame over the frozen ones, such as a card laid out after the freeze. */
    fun holdAlso(frame: Frame) {
        val preview = overlay?.takeIf { frozen } ?: return
        preview.frames = preview.frames + frame
        preview.invalidate()
    }

    /** Move the frozen frames to [targets] behind [covers]; [move] gets the fade into the live windows. */
    fun play(targets: List<RectF>, covers: List<RectF>, replace: Map<Int, Bitmap?> = emptyMap(),
             move: (() -> Unit) -> Unit = { reveal -> reveal() }) {
        val preview = overlay?.takeIf { frozen } ?: return move {}
        frozen = false
        // A frame can travel with a fresher picture of its app, such as the page laid out after the freeze.
        preview.frames = preview.frames.mapIndexed { index, frame ->
            val fresh = replace[index] ?: return@mapIndexed frame
            frame.bitmap?.recycle()
            frame.copy(bitmap = fresh)
        }
        preview.frames.zip(targets).forEach { (frame, target) -> frame.to = target }
        preview.covers = covers
        animateOverlay(move)
    }

    private fun show(frames: List<Frame>, covers: List<RectF>, screenWidth: Int, screenHeight: Int): Boolean {
        cancel()
        val windowType = SystemUiWindowTypeResolver.resolve()
        if (windowType == null) {
            frames.forEach { it.bitmap?.recycle() }
            return false
        }
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
            return false
        }
        overlay = preview
        return true
    }

    private fun animateOverlay(move: (() -> Unit) -> Unit) {
        val preview = overlay ?: return move {}
        val token = ++generation
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

    /** Fade the frames held by [freeze] into the live windows beneath, where they are. */
    fun fadeFrozen() {
        val preview = overlay?.takeIf { frozen } ?: return
        frozen = false
        val token = ++generation
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 120L
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

    /** Drop frames held by [freeze] that will not play; a running exchange keeps its overlay. */
    fun dismissFrozen() {
        if (frozen) cancel()
    }

    fun cancel() {
        generation += 1
        frozen = false
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

    private companion object {
        /** A frozen overlay that never draws must not hold up the transition behind it. */
        const val SHOW_TIMEOUT_MS = 250L
    }
}

internal class SwapPreviewView(
    context: Context,
    var frames: List<WorkspaceSwapAnimator.Frame>,
    var covers: List<RectF>,
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
        frames.forEach { drawFrame(canvas, it) }
    }

    private fun drawFrame(canvas: Canvas, frame: WorkspaceSwapAnimator.Frame) {
        val (bitmap, from, to, empty) = frame
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
            val scale = if (frame.topFill) destination.width() / bitmap.width
                else minOf(destination.width() / bitmap.width, destination.height() / bitmap.height)
            val imageWidth = bitmap.width * scale
            val imageHeight = bitmap.height * scale
            val top = if (frame.topFill) destination.top else destination.centerY() - imageHeight / 2
            val fitted = RectF(destination.centerX() - imageWidth / 2, top,
                destination.centerX() + imageWidth / 2, top + imageHeight)
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
