package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.lujinxin.nextep.display.SlotTaskDrag
import io.github.lujinxin.nextep.display.TaskSwitcherView
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

/** Keeps the original touch stream inside SystemUI. No Android global drag is
 * started, so OEM drag portals never receive or intercept this operation. */
internal class WorkspaceInternalDragController(
    private val context: Context,
    private val geometry: () -> WorkspaceGeometry,
    private val slots: () -> List<TaskSwitcherView>,
    private val onHover: (Target?) -> Unit,
    private val onDrop: (Payload, Target) -> Boolean,
    private val onFinished: () -> Unit,
) {
    sealed interface Payload {
        data class App(val intent: Intent) : Payload
        data class Slot(val drag: SlotTaskDrag) : Payload
    }
    sealed interface Target {
        data class Slot(val index: Int) : Target
        data object Main : Target
        data object Background : Target
    }
    private data class DropSlot(val target: Target.Slot, val view: TaskSwitcherView, val bounds: Rect)
    private data class Session(val source: View, val payload: Payload, val preview: Preview,
        val geometry: WorkspaceGeometry, val targets: List<DropSlot>)
    private val manager = context.getSystemService(WindowManager::class.java)
    private var session: Session? = null
    private var hovered: Target? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeout = Runnable { cancel() }
    private val frames = FrameCoalescer(
        post = { session?.preview?.postOnAnimation(it) },
        remove = { session?.preview?.removeCallbacks(it) },
    ) {
        session?.let { current ->
            current.preview.invalidate()
            val point = current.preview.point
            val target = targetAt(current, point.x, point.y)
            if (target != hovered) { hovered = target; onHover(target) }
        }
    }

    fun start(source: View, payload: Payload): Boolean {
        if (session != null || !source.isAttachedToWindow || source.width <= 0 || source.height <= 0) return false
        val bounds = Rect()
        if (source is TaskSwitcherView) source.getScreenVisibleRect(bounds) else {
            source.getGlobalVisibleRect(bounds)
            val origin = IntArray(2)
            source.rootView.getLocationOnScreen(origin)
            bounds.offset(origin[0], origin[1])
        }
        val bitmap = if (source is TaskSwitcherView) source.capturePreview() else captureTile(source)
        val view = Preview(bitmap, bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        view.point.set(bounds.exactCenterX(), bounds.exactCenterY())
        return runCatching {
            val screen = geometry()
            val params = WindowManager.LayoutParams(screen.screenWidth, screen.screenHeight,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                title = "NeXtepInternalDragPreview"
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            val targets = slots().mapIndexedNotNull { index, slot ->
                val slotBounds = Rect()
                if (slot.getScreenVisibleRect(slotBounds)) DropSlot(Target.Slot(index), slot, slotBounds) else null
            }
            manager.addView(view, params)
            session = Session(source, payload, view, screen, targets)
            source.parent?.requestDisallowInterceptTouchEvent(true)
            source.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            handler.postDelayed(timeout, 30_000L)
            true
        }.getOrElse {
            bitmap?.recycle()
            NeXtepLog.warn("slot_drag", "Internal drag could not start", it)
            false
        }
    }

    private fun captureTile(source: View): Bitmap? {
        var original: Bitmap? = null
        return runCatching {
            val captured = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            original = captured
            source.draw(Canvas(captured))
            if (!geometry().isLandscape) captured else {
                Bitmap.createBitmap(captured, 0, 0, captured.width, captured.height,
                    android.graphics.Matrix().apply { postRotate(90f) }, true).also { captured.recycle() }
            }
        }.getOrElse { original?.takeUnless { it.isRecycled }?.recycle(); null }
    }

    fun touch(source: View, event: MotionEvent): Boolean {
        val current = session?.takeIf { it.source === source } ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                current.preview.point.set(event.rawX, event.rawY)
                frames.request()
            }
            MotionEvent.ACTION_UP -> {
                (current.payload as? Payload.Slot)?.drag?.dropPoint = PointF(event.rawX, event.rawY)
                val target = targetAt(current, event.rawX, event.rawY)
                try {
                    val handled = target != null && onDrop(current.payload, target)
                    NeXtepLog.info("slot_drag", "Internal drop target=$target handled=$handled")
                } catch (error: Throwable) {
                    NeXtepLog.warn("slot_drag", "Internal drop failed", error)
                } finally { cancel() }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel()
        }
        return true
    }

    fun cancel() {
        handler.removeCallbacks(timeout)
        val current = session ?: return
        frames.cancel()
        session = null
        hovered = null
        onHover(null)
        onFinished()
        runCatching { manager.removeViewImmediate(current.preview) }
        current.preview.bitmap?.recycle()
        current.source.isPressed = false
        current.source.cancelLongPress()
        current.source.parent?.requestDisallowInterceptTouchEvent(false)
        (current.source as? TaskSwitcherView)?.finishInternalDrag()
    }

    private fun targetAt(current: Session, x: Float, y: Float): Target? {
        current.targets.forEach { slot ->
            if (slot.bounds.contains(x.toInt(), y.toInt()) &&
                (current.payload is Payload.Slot || slot.view.acceptsAppDrop())) return slot.target
        }
        if (current.payload !is Payload.Slot) return null
        val screen = current.geometry
        if (x >= screen.controlLeft && x < screen.controlLeft + screen.controlWidth &&
            y >= screen.controlTop && y < screen.controlTop + screen.controlHeight) return Target.Background
        if (x >= screen.contentLeft && x < screen.contentRight &&
            y >= screen.contentTop && y < screen.contentBottom) return Target.Main
        return null
    }

    private inner class Preview(val bitmap: Bitmap?, sourceWidth: Int, sourceHeight: Int) : View(context) {
        val point = PointF()
        private val previewWidth = sourceWidth * 0.88f
        private val previewHeight = sourceHeight * 0.88f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val destination = RectF()
        override fun onDraw(canvas: Canvas) {
            destination.set(point.x - previewWidth / 2, point.y - previewHeight / 2,
                point.x + previewWidth / 2, point.y + previewHeight / 2)
            if (bitmap != null && !bitmap.isRecycled) canvas.drawBitmap(bitmap, null, destination, paint)
            else { paint.color = Color.argb(150, 40, 94, 114); canvas.drawRoundRect(destination, 16f, 16f, paint) }
        }
    }
}
