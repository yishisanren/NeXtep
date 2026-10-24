package io.github.lujinxin.nextep.display

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.WorkbenchAppearance
import io.github.lujinxin.nextep.workspace.SidebarSide

class TaskSwitcherView(
    context: Context,
    private val slotIndex: Int,
) : FrameLayout(context) {
    interface Listener {
        fun onSlotClicked(slotIndex: Int)
        fun onIntentDropped(slotIndex: Int, intent: Intent): Boolean
        fun onSlotDragStarting(drag: SlotTaskDrag): Boolean
        fun onSlotDragStarted(source: TaskSwitcherView, drag: SlotTaskDrag): Boolean
        fun onSlotDragTouch(source: View, event: MotionEvent): Boolean
        fun onSlotBackgroundSwiped(drag: SlotTaskDrag): Boolean
        fun onSlotDragEnded()
        fun onSlotDropped(slotIndex: Int, drag: SlotTaskDrag): Boolean
        fun onContentDropped(slotIndex: Int, event: DragEvent): Boolean = false
        fun onContentDragStarted() {}
        fun onContentDragEntered(slotIndex: Int) {}
        fun onContentDragEnded(accepted: Boolean) {}
    }

    var textureView: SurfaceView = createSurfaceView()
        private set
    private var captureAllowed = false
    private var outputWidth = 0
    private var outputHeight = 0
    private var contentInput: SlotTouchForwarder? = null

    private var appearance = WorkbenchAppearance.DARK
    private val statusView = View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    // The last frame of a retained task, shown over the fresh (black) output until the app draws.
    private val coverView = android.widget.ImageView(context).apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_XY
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val clearCover = Runnable { clearCover(fade = true) }
    private var listener: Listener? = null
    private var busy = false
    private var currentState: SlotState = SlotState.Empty
    private var waiting = false
    private var stateRendered = false
    private var ownsDrag = false
    private var contentDrag = false
    private val backgroundSwipe = SlotBackgroundSwipeGesture(
        view = this,
        currentTask = {
            (currentState as? SlotState.Occupied)?.takeIf { !busy && !waiting && !ownsDrag }
                ?.let { SlotTaskDrag(slotIndex, it.taskId, it.displayId) }
        },
        onStarted = { listener?.onSlotDragStarting(it) == true },
        onDismiss = { listener?.onSlotBackgroundSwiped(it) == true },
        onEnded = { listener?.onSlotDragEnded() },
    )

    init {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(appearance.card)
            setStroke(dp(1), appearance.outline)
        }
        clipToOutline = true
        isClickable = true
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(statusView, LayoutParams(dp(WorkbenchAppearance.BADGE_SIZE_DP),
            dp(WorkbenchAppearance.BADGE_SIZE_DP), Gravity.CENTER))
        addView(coverView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        showState(SlotState.Empty)
        setOnDragListener { _, event -> handleDrag(event) }
        setOnClickListener {
            if (contentInput != null) return@setOnClickListener
            // The coordinator serializes exchanges and retains the latest selection.
            // Filtering busy taps here prevents that queue from ever receiving them.
            if (currentState is SlotState.Empty || currentState is SlotState.Ready || currentState is SlotState.Occupied ||
                currentState is SlotState.Failed) {
                listener?.onSlotClicked(slotIndex)
            }
        }
        setOnLongClickListener {
            if (contentInput != null) return@setOnLongClickListener false
            val occupied = currentState as? SlotState.Occupied
                ?: return@setOnLongClickListener false
            if (busy || waiting) return@setOnLongClickListener false
            val drag = SlotTaskDrag(slotIndex, occupied.taskId, occupied.displayId)
            if (listener?.onSlotDragStarting(drag) != true) return@setOnLongClickListener false
            ownsDrag = true
            val started = listener?.onSlotDragStarted(this, drag) == true
            if (!started) finishDrag()
            else {
                textureView.alpha = 0.55f
            }
            started
        }
        setOnTouchListener { view, event ->
            if (contentInput != null) {
                if (!busy && !waiting) contentInput?.touch(event, android.graphics.RectF(
                    textureView.left.toFloat(), textureView.top.toFloat(),
                    textureView.right.toFloat(), textureView.bottom.toFloat()), outputWidth, outputHeight)
                true
            } else if (listener?.onSlotDragTouch(view, event) == true) {
                backgroundSwipe.cancel()
                true
            } else backgroundSwipe.touch(event)
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    internal fun setContentInput(value: SlotTouchForwarder?) {
        if (contentInput === value) return
        contentInput?.cancel()
        backgroundSwipe.cancel()
        contentInput = value
    }

    fun finishInternalDrag() = finishDrag()
    fun setBackgroundSwipeEnabled(enabled: Boolean) = backgroundSwipe.setEnabled(enabled)
    fun setBackgroundSwipeSide(side: SidebarSide) = backgroundSwipe.setSide(side)
    fun cancelBackgroundSwipe() = backgroundSwipe.cancel()
    fun acceptsAppDrop() = !busy && (currentState is SlotState.Ready || currentState is SlotState.Occupied)
    fun setDropHighlighted(highlighted: Boolean) {
        foreground = if (highlighted) dropHighlight() else null
    }

    private fun dropHighlight() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor((appearance.accent and 0x00FFFFFF) or 0x23000000)
        setStroke(dp(2), appearance.accent)
    }

    fun showState(state: SlotState) {
        if (stateRendered && state == currentState && !waiting) return
        stateRendered = true
        if (state != currentState || waiting) backgroundSwipe.cancel()
        // A replaced output surface starts black until its display draws; fade it in too.
        val freshSurface = waiting
        waiting = false
        val previousState = currentState
        currentState = state
        when (state) {
            SlotState.Empty -> showReady(animated = previousState is SlotState.Occupied)
            is SlotState.Ready -> showReady(animated = previousState is SlotState.Occupied)
            is SlotState.Occupied -> {
                // Under a cover the fade is invisible, yet every frame of it sent a SurfaceView
                // alpha transaction while the apps were coming back; show the output at once.
                showOccupied(
                    animated = coverView.visibility != VISIBLE && (freshSurface ||
                        previousState !is SlotState.Occupied || previousState.taskId != state.taskId),
                )
            }
            is SlotState.Failed -> showReady(animated = previousState is SlotState.Occupied)
        }
        if (coverView.visibility == VISIBLE) {
            removeCallbacks(clearCover)
            when (state) {
                // shortcut: no first-frame signal from a SurfaceView; the rebound app has drawn
                // within this delay on the test phone. Replace with a frame callback if it shows black.
                is SlotState.Occupied -> postDelayed(clearCover, COVER_REVEAL_DELAY_MS)
                // The display is up; the retained task is on its way back, or never comes.
                is SlotState.Ready -> postDelayed(clearCover, COVER_FALLBACK_MS)
                else -> clearCover(fade = false)
            }
        }
    }

    /** Cover the output with [bitmap] until the slot's next occupant has drawn; the caller keeps the bitmap. */
    fun showCover(bitmap: Bitmap) {
        removeCallbacks(clearCover)
        coverView.setImageBitmap(bitmap)
        coverView.animate().cancel()
        coverView.alpha = 1f
        coverView.visibility = VISIBLE
        postDelayed(clearCover, COVER_FALLBACK_MS)
        NeXtepLog.info("slot_cover", "Shown slot=$slotIndex ${bitmap.width}x${bitmap.height}")
    }

    private fun clearCover(fade: Boolean) {
        removeCallbacks(clearCover)
        if (coverView.visibility != VISIBLE) return
        NeXtepLog.info("slot_cover", "Clearing slot=$slotIndex fade=$fade state=${currentState::class.simpleName}")
        coverView.animate().cancel()
        val release = Runnable {
            coverView.visibility = GONE
            coverView.setImageBitmap(null)
        }
        if (fade) coverView.animate().alpha(0f).setDuration(COVER_FADE_MS).withEndAction(release).start()
        else release.run()
    }

    fun showWaiting() {
        backgroundSwipe.cancel()
        waiting = true
        textureView.animate().cancel()
        textureView.animate()
            .alpha(0f)
            .scaleX(CONTENT_EXIT_SCALE)
            .scaleY(CONTENT_EXIT_SCALE)
            .setDuration(TRANSITION_OUT_DURATION_MS)
            .setInterpolator(transitionInterpolator)
            .start()
        statusView.animate().cancel()
        setStatusVisible(currentState !is SlotState.Occupied)
    }

    fun setBusy(isBusy: Boolean) {
        busy = isBusy
        if (isBusy) contentInput?.cancel()
        if (isBusy) backgroundSwipe.cancel()
        if (!isBusy) {
            showState(currentState)
        }
    }

    fun replaceTextureView(listener: SurfaceHolder.Callback): SurfaceView {
        backgroundSwipe.cancel()
        waiting = true
        outputSecurityStale = false
        val previous = textureView
        previous.animate().cancel()
        previous.holder.removeCallback(listener)
        removeView(previous)

        return createSurfaceView().also { replacement ->
            replacement.alpha = 0f
            replacement.holder.addCallback(listener)
            textureView = replacement
            addView(
                replacement,
                0,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
            )
        }
    }

    /** Rebuild the output layer in place and re-show the current state through it. */
    fun refreshOutputSurface(listener: SurfaceHolder.Callback): SurfaceView =
        replaceTextureView(listener).also { showState(currentState) }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    fun setOutputSize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        if (outputWidth == width && outputHeight == height) return
        contentInput?.cancel()
        outputWidth = width
        outputHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (outputWidth <= 0 || outputHeight <= 0) return
        val scale = minOf(measuredWidth.toFloat() / outputWidth, measuredHeight.toFloat() / outputHeight)
        val widthSpec = MeasureSpec.makeMeasureSpec((outputWidth * scale).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec((outputHeight * scale).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY)
        textureView.measure(widthSpec, heightSpec)
        coverView.measure(widthSpec, heightSpec)
    }

    fun capturePreview(): Bitmap? = if (captureAllowed) captureProtectedPreview() else null

    // Internal transitions use a secure overlay and immediately dispose the frame.
    // ScreenCapture still excludes any secure layers belonging to the source app.
    internal fun captureProtectedPreview(): Bitmap? = runCatching {
        val task = currentState as? SlotState.Occupied ?: return@runCatching null
        // The task fills this slot's canvas; that is the capture's crop.
        val crop = if (outputWidth > 0 && outputHeight > 0) Rect(0, 0, outputWidth, outputHeight) else null
        val bitmap = io.github.lujinxin.nextep.framework.TaskSurfaceCompat.capturePreview(task.taskId, crop)
            ?: return@runCatching null
        // TextureView returns its logical portrait buffer. Follow the rail's
        // actual transform so reverse-landscape drag/swap previews face correctly.
        val panelRotation = (parent as? View)?.rotation ?: 0f
        if (panelRotation != 0f) {
            try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                    android.graphics.Matrix().apply { postRotate(panelRotation) }, true)
            } finally { bitmap.recycle() }
        } else bitmap
    }.getOrNull()

    /** getGlobalVisibleRect is relative to the ViewRoot, not display 0. The
     * sidebar is a separate window whose physical offset must be included. */
    fun getScreenVisibleRect(bounds: Rect): Boolean {
        if (!getGlobalVisibleRect(bounds)) return false
        val windowOrigin = IntArray(2)
        rootView.getLocationOnScreen(windowOrigin)
        bounds.offset(windowOrigin[0], windowOrigin[1])
        return true
    }

    override fun onDetachedFromWindow() {
        contentInput?.cancel()
        backgroundSwipe.cancel()
        finishDrag()
        super.onDetachedFromWindow()
    }

    private fun finishDrag() {
        if (!ownsDrag) return
        ownsDrag = false
        listener?.onSlotDragEnded()
        if (!busy && currentState is SlotState.Occupied) textureView.alpha = 1f
    }

    private fun handleDrag(event: DragEvent): Boolean {
        return when (event.action) {
        DragEvent.ACTION_DRAG_STARTED -> {
            val localDrag = event.localState as? SlotTaskDrag
            val accepted = !busy && (localDrag != null &&
                currentState.inDragTargetStates() || currentState !is SlotState.Occupied &&
                AppDragContract.accepts(event.clipDescription) ||
                currentState is SlotState.Occupied && ContentDropCompat.accepts(event.clipDescription))
            contentDrag = accepted && localDrag == null && ContentDropCompat.accepts(event.clipDescription)
            if (contentDrag) listener?.onContentDragStarted()
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex started accepted=$accepted busy=$busy state=$currentState " +
                    "content=$contentDrag",
            )
            accepted
        }
        DragEvent.ACTION_DRAG_ENTERED -> {
            if (contentDrag && !busy) listener?.onContentDragEntered(slotIndex)
            if (!busy) {
                if (currentState is SlotState.Occupied) foreground = dropHighlight()
                else showReady(highlighted = true)
            }
            true
        }
        DragEvent.ACTION_DRAG_EXITED -> {
            showState(currentState)
            foreground = null
            true
        }
        DragEvent.ACTION_DROP -> {
            val localDrag = event.localState as? SlotTaskDrag
            if (localDrag != null && width > 0 && height > 0) {
                val bounds = Rect()
                getScreenVisibleRect(bounds)
                localDrag.dropPoint = if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels) {
                    android.graphics.PointF(bounds.right - event.y * bounds.width() / height,
                        bounds.top + event.x * bounds.height() / width)
                } else android.graphics.PointF(bounds.left + event.x, bounds.top + event.y)
            }
            val intent = AppDragContract.readLaunchIntent(event.clipData)
            val handled = !busy && if (localDrag != null) {
                listener?.onSlotDropped(slotIndex, localDrag) == true
            } else if (intent != null) listener?.onIntentDropped(slotIndex, intent) == true
            else listener?.onContentDropped(slotIndex, event) == true
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex drop handled=$handled component=${intent?.component}",
            )
            handled
        }
        DragEvent.ACTION_DRAG_ENDED -> {
            if (contentDrag) listener?.onContentDragEnded(event.result)
            contentDrag = false
            foreground = null
            if (!busy) showState(currentState)
            if ((event.localState as? SlotTaskDrag)?.slotIndex == slotIndex) finishDrag()
            true
        }
        else -> true
        }
    }

    private fun SlotState.inDragTargetStates(): Boolean = this is SlotState.Ready || this is SlotState.Occupied

    private fun showReady(highlighted: Boolean = false, animated: Boolean = false) {
        textureView.animate().cancel()
        if (animated) {
            textureView.animate()
                .alpha(0f)
                .scaleX(CONTENT_EXIT_SCALE)
                .scaleY(CONTENT_EXIT_SCALE)
                .setDuration(TRANSITION_OUT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            textureView.alpha = 0f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
        badgeHighlighted = highlighted
        statusView.background = appearance.emptyBadge(resources.displayMetrics.density, highlighted)
        statusView.animate().cancel()
        // Empty slots remain actionable when a buffer or animation is interrupted.
        setStatusVisible(true)
    }

    private fun showOccupied(animated: Boolean) {
        statusView.animate().cancel()
        setStatusVisible(false)

        textureView.animate().cancel()
        // A coordinated exchange already animates a protected preview. The live
        // destination must match its final size when that preview is revealed.
        if (animated && !busy) {
            textureView.alpha = 0f
            textureView.scaleX = CONTENT_ENTER_SCALE
            textureView.scaleY = CONTENT_ENTER_SCALE
            textureView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(TRANSITION_IN_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            textureView.alpha = 1f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
    }

    private fun setStatusVisible(visible: Boolean) {
        statusView.animate().cancel()
        statusView.visibility = if (visible) VISIBLE else GONE
        statusView.alpha = 1f
        statusView.scaleX = 1f
        statusView.scaleY = 1f
    }

    private var badgeHighlighted = false

    fun setCaptureAllowed(allowed: Boolean) {
        // setSecure reaches only surfaces created afterwards; the current one keeps its old flag.
        if (captureAllowed != allowed) outputSecurityStale = true
        captureAllowed = allowed
        textureView.setSecure(!allowed)
    }

    /** True once the capture policy changed after the current output view was created. */
    var outputSecurityStale = false
        private set

    fun isCaptureAllowed(): Boolean = captureAllowed

    internal fun setAppearance(value: WorkbenchAppearance) {
        appearance = value
        (background as? GradientDrawable)?.apply { setColor(value.card); setStroke(dp(1), value.outline) }
        statusView.background = value.emptyBadge(resources.displayMetrics.density, badgeHighlighted)
    }

    fun isShowingEmptyBadge(): Boolean = statusView.visibility == VISIBLE

    private fun createSurfaceView(): SurfaceView = SurfaceView(context).apply {
        // Protect this output layer from capture. Source apps' secure windows
        // remain subject to Android's separate virtual-display restrictions.
        setSecure(!captureAllowed)
        setZOrderMediaOverlay(true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val transitionInterpolator = DecelerateInterpolator(1.6f)

    private companion object {
        const val TRANSITION_OUT_DURATION_MS = 120L
        const val TRANSITION_IN_DURATION_MS = 220L
        const val COVER_REVEAL_DELAY_MS = 600L
        const val COVER_FALLBACK_MS = 2_500L
        const val COVER_FADE_MS = 200L
        const val CONTENT_ENTER_SCALE = 0.96f
        const val CONTENT_EXIT_SCALE = 0.98f
    }

}
