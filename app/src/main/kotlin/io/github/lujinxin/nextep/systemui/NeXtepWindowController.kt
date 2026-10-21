package io.github.lujinxin.nextep.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.display.SlotState
import io.github.lujinxin.nextep.display.SlotTaskCoordinator
import io.github.lujinxin.nextep.display.SlotTaskDrag
import io.github.lujinxin.nextep.display.VirtualDisplaySlot
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.safety.FeatureGate
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

class NeXtepWindowController(
    private val context: Context,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val onSidebarSideRequested: (SidebarSide) -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onExitRequested: () -> Unit,
    private val onViewportChanged: () -> Unit,
) {
    private data class WindowRecord(val view: View, val title: String)

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val viewport = WorkspaceViewport(context, onViewportChanged)
    private val records = mutableListOf<WindowRecord>()
    private var initialized = false
    private var slotCoordinator: SlotTaskCoordinator? = null
    private var topAppStrip: TopAppStripView? = null
    private var sidebarView: FrameLayout? = null
    private var contentHighlightView: View? = null
    private var slotWindows: List<VirtualDisplaySlot> = emptyList()
    private var slotBackgroundSwipeEnabled = true
    private var frostStrength = 50
    private val swapAnimator = WorkspaceSwapAnimator(context)
    private val internalDrag = WorkspaceInternalDragController(
        context, ::geometry, { slotWindows.map { it.view } },
        ::highlightInternalDragTarget, ::dropInternalPayload,
        { topAppStrip?.finishInternalAppDrag() },
    )
    private val backdropGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val backdropWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-Backdrop")
    }
    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wallpaperBitmap: android.graphics.Bitmap? = null
    private var sidebarSide = SystemServerWorkspaceBridge.sidebarSide(context)
    private var suspended = false
    private var workspaceVisible = false
    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_WALLPAPER_CHANGED -> {
                    // Retain the displayed frame while the new wallpaper starts rendering.
                    // OEM live services may change content without changing component/id.
                    backdropGeneration.incrementAndGet()
                }
                Intent.ACTION_USER_PRESENT -> Unit
                else -> return
            }
            if (!suspended) refreshWallpaperBackdrop()
        }
    }

    init {
        // Warm the cache before the first workspace entry, including after unlocking.
        // Window creation is deliberately lazy; wallpaper observation must not be.
        runCatching {
            context.registerReceiver(
                wallpaperReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_WALLPAPER_CHANGED)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { NeXtepLog.warn("wallpaper_backdrop", "Wallpaper change listener unavailable", it) }
        backdropHandler.post { refreshWallpaperBackdrop() }
        mainTaskPresenter.onHomePresented = {
            backdropHandler.post {
                if (!suspended && workspaceVisible) refreshWallpaperBackdrop()
            }
        }
    }

    fun suspendForLock() {
        suspended = true
        cancelDragVisuals()
        backdropGeneration.incrementAndGet()
        slotCoordinator?.setSuspended(true)
        topAppStrip?.setWorkspaceVisible(false)
        // Keep TextureViews attached: GONE/removeView would destroy their virtual displays.
        records.forEach { record ->
            record.view.animate().cancel()
            record.view.alpha = 0f
            val params = record.view.layoutParams as WindowManager.LayoutParams
            params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            windowManager.updateViewLayout(record.view, params)
        }
    }

    fun resumeAfterLock(): WorkspaceGeometry {
        check(records.size == EXPECTED_WINDOW_COUNT) { "Retained SystemUI windows are unavailable" }
        suspended = false
        // Wake reuses attached windows and the recorded layout. Re-running show()
        // starts a fresh alpha=0 entry animation and repeats all display setup.
        val geometry = geometry()
        applyWindowGeometry(geometry)
        sidebarView?.let { applySlotLayout(it, geometry) }
        records.forEach { record ->
            record.view.animate().cancel()
            record.view.visibility = View.VISIBLE
            record.view.alpha = 1f
            record.view.translationX = 0f
            record.view.translationY = 0f
        }
        topAppStrip?.setWorkspaceVisible(true)
        slotCoordinator?.setSuspended(false)
        return geometry
    }

    fun show(): WorkspaceGeometry {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        workspaceVisible = true
        reconfigure().getOrThrow()
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.translationX = 0f
            view.alpha = 0f
            view.translationY = when (record.title) {
                "NeXtepTopBar" -> -dp(ENTER_OFFSET_DP)
                else -> dp(ENTER_OFFSET_DP)
            }
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(WORKSPACE_ENTER_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        }
        topAppStrip?.setWorkspaceVisible(true)
        topAppStrip?.refresh()
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotCoordinator?.activate()
        }
        return geometry()
    }

    fun hide() {
        workspaceVisible = false
        cancelDragVisuals()
        suspended = false
        backdropGeneration.incrementAndGet()
        slotCoordinator?.deactivate()
        topAppStrip?.setWorkspaceVisible(false)
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            windowManager.updateViewLayout(view, params)
            view.animate()
                .alpha(0f)
                .translationY(
                    if (record.title == "NeXtepTopBar") -dp(EXIT_OFFSET_DP)
                    else dp(EXIT_OFFSET_DP),
                )
                .setDuration(WORKSPACE_EXIT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .withEndAction {
                    view.visibility = View.GONE
                    view.translationX = 0f
                    view.translationY = 0f
                }
                .start()
        }
    }

    fun launchInSlot(intent: Intent): Result<Unit> {
        if (!FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            return Result.failure(IllegalStateException("VirtualDisplay slot feature disabled"))
        }
        return slotCoordinator?.launchInSlot(intent)
            ?: Result.failure(IllegalStateException("VirtualDisplay slot unavailable"))
    }

    fun openFromLauncher(intent: Intent): Result<Unit> = slotCoordinator
        ?.openFromLauncher(intent)
        ?: Result.failure(IllegalStateException("Slot coordinator unavailable"))

    fun currentGeometry(refreshViewport: Boolean = false): WorkspaceGeometry =
        viewport.geometry(sidebarSide, refreshViewport)

    fun setNavigationButtonsEnabled(enabled: Boolean) {
        viewport.setNavigationButtonsEnabled(enabled)
    }

    fun setSlotBackgroundSwipeEnabled(enabled: Boolean) {
        if (slotBackgroundSwipeEnabled == enabled) return
        slotBackgroundSwipeEnabled = enabled
        slotWindows.forEach { it.view.setBackgroundSwipeEnabled(enabled) }
    }

    fun reconfigure(): Result<WorkspaceGeometry> = runCatching {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        val geometry = geometry()
        applyWindowGeometry(geometry)
        sidebarView?.let { applySlotLayout(it, geometry) }
        refreshWallpaperBackdrop()
        NeXtepLog.info("workspace_configuration", "Reconfigured geometry=$geometry")
        geometry
    }

    fun setSidebarSide(side: SidebarSide): Result<WorkspaceGeometry> = runCatching {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        sidebarSide = side
        val geometry = geometry()
        applyWindowGeometry(geometry)
        topAppStrip?.setSidebarSide(side)
        sidebarView?.let { panel -> applySlotLayout(panel, geometry) }
        applySidebarBackdrop(geometry)
        records.firstOrNull { it.title == "NeXtepSidebar" }?.view?.let { sidebar ->
            sidebar.animate().cancel()
            sidebar.translationY = 0f
            sidebar.alpha = 0f
            sidebar.translationX = if (side == SidebarSide.LEFT) {
                -dp(SIDEBAR_ENTER_OFFSET_DP)
            } else {
                dp(SIDEBAR_ENTER_OFFSET_DP)
            }
            sidebar.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(SIDEBAR_SWITCH_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        }
        NeXtepLog.info("sidebar_side", "Applied side=$side geometry=$geometry")
        geometry
    }

    fun slotStatesDescription(): String = slotCoordinator
        ?.slotStates()
        ?.mapIndexed { index, state -> "${index + 1}:$state" }
        ?.joinToString(" | ")
        ?: "unavailable"

    private fun initializeIfNeeded() {
        if (initialized) return
        initialized = true
        val windowType = SystemUiWindowTypeResolver.resolve() ?: run {
            initialized = false
            return
        }
        val geometry = geometry()

        addWindow(
            title = "NeXtepTopBar",
            view = TopAppStripView(
                context = context,
                initialSidebarSide = sidebarSide,
                onAppClicked = { intent ->
                    slotCoordinator?.openInMain(
                        intent,
                        autoMinimizeCurrent = SystemUiRuntime.shouldAutoMinimizeMainOnTopAppSwitch(),
                    )?.onFailure { error ->
                        NeXtepLog.warn("top_apps", "Unable to open ${intent.component} in main", error)
                    }
                },
                onMediaClicked = { session ->
                    slotCoordinator?.openMediaInMain(session)?.onFailure { error ->
                        NeXtepLog.warn("media_control", "Unable to open media app in main", error)
                        android.widget.Toast.makeText(context, AppLanguage.string(context, R.string.media_open_failed), android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onSidebarSideRequested = onSidebarSideRequested,
                onSettingsRequested = onSettingsRequested,
                onExitRequested = onExitRequested,
                onFrostStrengthChanged = { strength ->
                    if (frostStrength != strength) {
                        frostStrength = strength
                        applyWallpaperBackdrop(geometry())
                    }
                },
                onAppDragStarting = { source, intent ->
                    workspaceVisible && !suspended && internalDrag.start(
                        source, WorkspaceInternalDragController.Payload.App(intent),
                    )
                },
                onAppDragTouch = internalDrag::touch,
            ).also { topAppStrip = it },
            width = geometry.screenWidth,
            height = geometry.topHeight,
            gravity = Gravity.TOP,
            touchable = true,
            windowType = windowType,
        )
        addWindow(
            title = "NeXtepSidebar",
            view = sidebarPanel(geometry),
            width = geometry.sidebarWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.START
            } else {
                Gravity.END
            },
            y = geometry.topHeight,
            touchable = FeatureGate.TASK_SWAP.defaultEnabled,
            windowType = windowType,
        )
        addWindow(
            title = "NeXtepContentPanel",
            view = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                addView(View(context).also { contentHighlightView = it })
            },
            width = geometry.contentWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.END
            } else {
                Gravity.START
            },
            y = geometry.topHeight,
            windowType = windowType,
        )
        if (records.size != EXPECTED_WINDOW_COUNT) {
            records.forEach { record ->
                try {
                    windowManager.removeView(record.view)
                } catch (error: Throwable) {
                    NeXtepLog.warn("systemui_window", "Cleanup failed for ${record.title}", error)
                }
            }
            records.clear()
            slotCoordinator = null
            topAppStrip = null
            sidebarView = null
            contentHighlightView = null
            slotWindows = emptyList()
            initialized = false
        }
    }

    private fun addWindow(
        title: String,
        view: View,
        width: Int,
        height: Int,
        gravity: Int,
        y: Int = 0,
        touchable: Boolean = false,
        windowType: Int,
    ) {
        val interactionFlags = if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                interactionFlags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.y = y
            this.title = title
        }
        val hostedView = if (title == "NeXtepContentPanel") view else WorkspacePanelHost(context, view)
        val record = WindowRecord(hostedView, title)
        configureWindow(record, params, geometry())
        hostedView.visibility = View.GONE
        try {
            windowManager.addView(hostedView, params)
            records += record
            NeXtepLog.info("systemui_window", "Added $title")
        } catch (error: Throwable) {
            NeXtepLog.error("systemui_window", "Could not add $title", error)
        }
    }

    private fun applyWindowGeometry(geometry: WorkspaceGeometry) {
        cancelDragVisuals()
        records.forEach { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            configureWindow(record, params, geometry)
            windowManager.updateViewLayout(record.view, params)
        }
        topAppStrip?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft)
    }

    private fun configureWindow(
        record: WindowRecord,
        params: WindowManager.LayoutParams,
        geometry: WorkspaceGeometry,
    ) {
        val touchable = !suspended && (record.title == "NeXtepTopBar" ||
            (record.title == "NeXtepSidebar" && FeatureGate.TASK_SWAP.defaultEnabled))
        params.flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // A foreground app normally hides live wallpaper, so screenshotWallpaper()
        // has no surface to read on entry. Keep that surface available behind the
        // workspace until exit/lock, without sending control gestures to it.
        params.flags = if (record.title == "NeXtepTopBar" && workspaceVisible && !suspended) {
            params.flags or WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        } else params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
        params.setWallpaperTouchEventsEnabled(false)
        (record.view as? WorkspacePanelHost)?.panelRotationDegrees = geometry.panelRotationDegrees
        // WorkspaceGeometry explicitly reserves navigation space in display coordinates.
        // FLAG_LAYOUT_IN_SCREEN alone does not disable inset fitting: a bottom navigation
        // inset can make WindowManager shift the entire full-height sidebar upward.
        // Apply this on both creation and reconfiguration, for either sidebar and rotation.
        params.setFitInsetsTypes(0)
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.gravity = Gravity.TOP or Gravity.LEFT
        when (record.title) {
            "NeXtepTopBar" -> {
                params.width = geometry.controlWidth
                params.height = geometry.controlHeight
                params.x = geometry.controlLeft
                params.y = geometry.controlTop
            }
            "NeXtepSidebar" -> {
                params.width = geometry.sidebarPhysicalWidth
                params.height = geometry.sidebarPhysicalHeight
                params.x = geometry.sidebarLeft
                params.y = geometry.sidebarTop
            }
            "NeXtepContentPanel" -> {
                // The transparent, non-touchable root observes full-display insets.
                // Its drag highlight child and visible tasks use the safe bounds.
                params.width = geometry.screenWidth
                params.height = geometry.screenHeight
                params.x = 0
                params.y = 0
                contentHighlightView?.layoutParams = FrameLayout.LayoutParams(
                    geometry.contentWidth, geometry.contentHeight, Gravity.TOP or Gravity.LEFT,
                ).apply {
                    leftMargin = geometry.contentLeft
                    topMargin = geometry.contentTop
                }
            }
        }
    }

    private fun sidebarPanel(geometry: WorkspaceGeometry): View = object : FrameLayout(context) {
        private val dividerPaint = android.graphics.Paint().apply {
            color = Color.argb(90, 255, 255, 255)
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            val thickness = resources.displayMetrics.density
            val edge = if (logicalSidebarSide(this@NeXtepWindowController.geometry()) == SidebarSide.LEFT) width - thickness else 0f
            canvas.drawRect(edge, 0f, edge + thickness, height.toFloat(), dividerPaint)
            for (index in 1 until slotWindows.size) {
                val y = slotWindows[index].view.top.toFloat()
                canvas.drawRect(0f, y - thickness, width.toFloat(), y, dividerPaint)
            }
        }
    }.apply {
        sidebarView = this
        setBackgroundColor(Color.TRANSPARENT)
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotWindows = List(WORKSPACE_SLOT_COUNT) { index -> VirtualDisplaySlot(context, index) }
            slotWindows.forEach { slot ->
                slot.view.setBackgroundSwipeEnabled(slotBackgroundSwipeEnabled)
                addView(slot.view)
            }
            applySlotLayout(this, geometry)
            slotCoordinator = SlotTaskCoordinator(
                context = context,
                slots = slotWindows,
                mainTaskPresenter = mainTaskPresenter,
                geometryProvider = ::geometry,
                onSwapAnimation = ::animateDragSwap,
                onInternalSlotDrag = { source, drag ->
                    internalDrag.start(source, WorkspaceInternalDragController.Payload.Slot(drag))
                },
                onInternalDragTouch = internalDrag::touch,
            )
        }
    }

    private fun cancelDragVisuals() {
        internalDrag.cancel()
        slotWindows.forEach { it.view.cancelBackgroundSwipe() }
        slotCoordinator?.onSlotDragEnded()
        slotCoordinator?.cancelPendingDragExchange()
        swapAnimator.cancel()
    }

    private fun highlightInternalDragTarget(target: WorkspaceInternalDragController.Target?) {
        slotWindows.forEachIndexed { index, slot ->
            slot.view.setDropHighlighted(target == WorkspaceInternalDragController.Target.Slot(index))
        }
        topAppStrip?.setDismissTargetHighlighted(target == WorkspaceInternalDragController.Target.Background)
        contentHighlightView?.background =
            if (target == WorkspaceInternalDragController.Target.Main) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.argb(25, 62, 190, 198))
                    setStroke(dp(2).toInt(), Color.rgb(80, 216, 224))
                    cornerRadius = dp(8)
                }
            } else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    }

    private fun dropInternalPayload(
        payload: WorkspaceInternalDragController.Payload,
        target: WorkspaceInternalDragController.Target,
    ): Boolean {
        val coordinator = slotCoordinator ?: return false
        return when (target) {
            is WorkspaceInternalDragController.Target.Slot -> when (payload) {
                is WorkspaceInternalDragController.Payload.Slot -> coordinator.onSlotDropped(target.index, payload.drag)
                is WorkspaceInternalDragController.Payload.App -> coordinator.onIntentDropped(target.index, payload.intent)
            }
            WorkspaceInternalDragController.Target.Main ->
                payload is WorkspaceInternalDragController.Payload.Slot && coordinator.dropSlotIntoMain(payload.drag)
            WorkspaceInternalDragController.Target.Background ->
                payload is WorkspaceInternalDragController.Payload.Slot && coordinator.dismissDraggedSlot(payload.drag)
        }
    }

    private fun animateDragSwap(drag: SlotTaskDrag, targetIndex: Int?, move: () -> Unit) {
        val geometry = geometry()
        val sourceView = slotWindows.getOrNull(drag.slotIndex)?.view
        val sourceRect = android.graphics.Rect()
        if (sourceView == null || !sourceView.getScreenVisibleRect(sourceRect)) {
            move()
            return
        }
        val targetRect = android.graphics.Rect()
        if (targetIndex != null) {
            val targetView = slotWindows.getOrNull(targetIndex)?.view
            if (targetView == null || !targetView.getScreenVisibleRect(targetRect)) {
                move()
                return
            }
        } else targetRect.set(geometry.contentLeft, geometry.contentTop, geometry.contentRight, geometry.contentBottom)
        val point = drag.dropPoint ?: android.graphics.PointF(targetRect.exactCenterX(), targetRect.exactCenterY())
        val halfWidth = sourceRect.width() * 0.45f
        val halfHeight = sourceRect.height() * 0.45f
        val startRect = android.graphics.RectF(point.x - halfWidth, point.y - halfHeight,
            point.x + halfWidth, point.y + halfHeight)
        val sourcePreview = sourceView.capturePreview()
        val targetPreview = if (targetIndex != null) {
            if (slotCoordinator?.slotStates()?.getOrNull(targetIndex) is SlotState.Occupied) {
                slotWindows[targetIndex].view.capturePreview()
            } else null
        } else mainTaskPresenter.currentTaskId()?.let {
            io.github.lujinxin.nextep.framework.TaskSurfaceCompat.capturePreview(it)
        }
        swapAnimator.animate(sourcePreview, targetPreview, startRect,
            android.graphics.RectF(sourceRect), android.graphics.RectF(targetRect),
            geometry.screenWidth, geometry.screenHeight, move)
    }

    private fun applySlotLayout(panel: FrameLayout, geometry: WorkspaceGeometry) {
        panel.invalidate()
        val profile = viewport.layout()
        val bounds = profile.slots
        slotWindows.forEachIndexed { index, slot ->
            slot.setLayoutProfile(profile)
            slot.view.setBackgroundSwipeSide(logicalSidebarSide(geometry))
            slot.view.layoutParams = FrameLayout.LayoutParams(
                bounds[index].width,
                bounds[index].height,
                Gravity.TOP,
            ).apply {
                marginStart = if (logicalSidebarSide(geometry) == SidebarSide.RIGHT) profile.gapPixels else 0
                marginEnd = if (logicalSidebarSide(geometry) == SidebarSide.LEFT) profile.gapPixels else 0
                topMargin = bounds[index].top
            }
        }
    }

    private fun refreshWallpaperBackdrop(attempt: Int = 0) {
        val geometry = geometry()
        // Use the last frame immediately; surface capture runs off the UI thread.
        applyWallpaperBackdrop(geometry)
        val previous = wallpaperBitmap
        val generation = backdropGeneration.incrementAndGet()
        backdropWorker.execute {
            if (generation != backdropGeneration.get()) return@execute
            val identity = WorkspaceWallpaperBackdrop.identity(context)
            val captured = WorkspaceWallpaperBackdrop.capture(
                context, geometry.screenWidth, geometry.screenHeight,
            )
            val currentIdentity = WorkspaceWallpaperBackdrop.identity(context)
            // Do not associate a frame taken during a wallpaper change with the
            // new wallpaper. The next retry will capture the settled surface.
            val bitmap = captured?.takeUnless {
                identity != null && currentIdentity != null && identity != currentIdentity
            }
            if (bitmap !== captured) captured?.recycle()
            val fitted = if (bitmap == null && previous != null &&
                (previous.width != geometry.screenWidth || previous.height != geometry.screenHeight)
            ) WorkspaceWallpaperBackdrop.fit(previous, geometry.screenWidth, geometry.screenHeight)
            else null
            backdropHandler.post {
                if (generation != backdropGeneration.get() || !WorkspaceWallpaperBackdrop.canCapture(context)) {
                    bitmap?.recycle()
                    fitted?.recycle()
                    return@post
                }
                if (bitmap != null) {
                    wallpaperBitmap = bitmap
                } else if (fitted != null) {
                    wallpaperBitmap = fitted
                }
                // A failed capture or identity query must not erase a valid frame.
                applyWallpaperBackdrop(geometry)
                // A waking live engine can briefly expose its initial frame even
                // when capture succeeds. Refresh again after it has started drawing.
                if (attempt < BACKDROP_SETTLE_REFRESHES ||
                    (bitmap == null && attempt < BACKDROP_MAX_RETRIES)
                ) {
                    backdropHandler.postDelayed({
                        if (generation == backdropGeneration.get() && !suspended &&
                            workspaceVisible
                        ) refreshWallpaperBackdrop(attempt + 1)
                    }, minOf(1_000L, 200L * (attempt + 1)))
                }
            }
        }
    }

    private fun applyWallpaperBackdrop(geometry: WorkspaceGeometry) {
        records.firstOrNull { it.title == "NeXtepTopBar" }?.view?.let { view ->
            val background = WorkspaceWallpaperBackdrop.crop(wallpaperBitmap,
                geometry.controlLeft, geometry.controlTop, frostStrength, view.background)
            if (view.background !== background) view.background = background
        }
        topAppStrip?.setMediaWallpaperLight(WorkspaceWallpaperBackdrop.isLight(
            wallpaperBitmap, geometry.controlLeft, geometry.controlTop, geometry.controlWidth, geometry.controlHeight,
        ))
        applySidebarBackdrop(geometry)
    }

    private fun applySidebarBackdrop(geometry: WorkspaceGeometry) {
        records.firstOrNull { it.title == "NeXtepSidebar" }?.view?.let { view ->
            val background = WorkspaceWallpaperBackdrop.crop(wallpaperBitmap,
                geometry.sidebarLeft, geometry.sidebarTop, frostStrength, view.background)
            if (view.background !== background) view.background = background
        }
    }

    private fun logicalSidebarSide(geometry: WorkspaceGeometry): SidebarSide =
        geometry.panelSidebarSide

    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density

    private fun geometry(): WorkspaceGeometry = currentGeometry()


    private val transitionInterpolator = DecelerateInterpolator(1.6f)

    private companion object {
        const val EXPECTED_WINDOW_COUNT = 3
        const val BACKDROP_SETTLE_REFRESHES = 2
        const val BACKDROP_MAX_RETRIES = 8
        const val WORKSPACE_ENTER_DURATION_MS = 240L
        const val WORKSPACE_EXIT_DURATION_MS = 180L
        const val SIDEBAR_SWITCH_DURATION_MS = 220L
        const val ENTER_OFFSET_DP = 12
        const val EXIT_OFFSET_DP = 8
        const val SIDEBAR_ENTER_OFFSET_DP = 28
    }
}
