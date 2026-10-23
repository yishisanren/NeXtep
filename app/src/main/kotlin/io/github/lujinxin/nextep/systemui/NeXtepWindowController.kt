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
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

class NeXtepWindowController(
    private val context: Context,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val onSidebarSideRequested: (SidebarSide) -> Boolean,
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
    private val swapAnimator = WorkspaceSwapAnimator(context) { appearance }
    private var contentTarget: SlotTaskCoordinator.ContentExchange? = null
    private var contentOriginSide = SidebarSide.RIGHT
    private var contentLayoutChanging = false
    private var appliedGeometry: WorkspaceGeometry? = null
    private val contentDrag = io.github.lujinxin.nextep.workspace.WorkspaceContentDragSession(
        ::expandContentTarget, ::restoreContentTarget, { contentTarget = null },
    )
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
    private var wideSlotIndex: Int? = null
    private var wideTaskId: Int? = null
    private var pinPending = false
    private var seenSceneTime = -1L
    private var observedTasks: List<io.github.lujinxin.nextep.task.TaskRepository.TaskSnapshot> = emptyList()
    private var observedStates: List<SlotState> = emptyList()
    // A fullscreen request can arrive between task observations; re-evaluate the pin as soon as
    // system_server publishes it instead of waiting for the next task change.
    private val sceneObserver = object : android.database.ContentObserver(backdropHandler) {
        override fun onChange(selfChange: Boolean) {
            NeXtepLog.info("workbench_scene", "Scene changed visible=$workspaceVisible suspended=$suspended " +
                "scene=${SystemServerWorkspaceBridge.scene(context)}")
            if (workspaceVisible && !suspended) observeTasks(observedTasks, observedStates)
        }
    }
    private val workbenchPreferences = runCatching {
        context.createDeviceProtectedStorageContext().getSharedPreferences("nextep_workbench_v1", Context.MODE_PRIVATE)
    }.getOrNull()
    private var appearance = runCatching {
        WorkbenchAppearance.valueOf(workbenchPreferences?.getString("appearance", "DARK") ?: "DARK")
    }.getOrDefault(WorkbenchAppearance.DARK)
    private var recentsHidden = workbenchPreferences?.getBoolean("hide_recents", false) ?: false
    private val capturePolicy = io.github.lujinxin.nextep.workspace.WorkspaceCapturePolicy()
    private var controlAtBottom = workbenchPreferences?.getBoolean("control_bottom", false) ?: false
    private val tools = WorkbenchToolsController(context, { appearance }, { capturePolicy.captureAllowed },
        { intent -> slotCoordinator?.openExternalInMain(intent)
            ?: Result.failure(IllegalStateException("Workspace unavailable")) },
        { intents, done -> slotCoordinator?.openSearches(intents, done)
            ?: Result.failure(IllegalStateException("Workspace unavailable")) },
        { slotCoordinator?.cancelApplicationLaunch() })

    /** Settings-page view of the SystemUI-owned workbench preferences and session state. */
    fun workbenchState(): android.os.Bundle = android.os.Bundle().apply {
        putString(TriggerBroadcastContract.EXTRA_APPEARANCE, appearance.name)
        putString(TriggerBroadcastContract.EXTRA_SIDEBAR_SIDE, sidebarSide.name)
        putBoolean(TriggerBroadcastContract.EXTRA_HIDE_RECENTS, recentsHidden)
        putBoolean(TriggerBroadcastContract.EXTRA_CONTROL_BOTTOM, controlAtBottom)
        putBoolean(TriggerBroadcastContract.EXTRA_CAPTURE_ALLOWED, capturePolicy.captureAllowed)
    }

    /** Commands arrive only through the signature-protected control receiver. */
    fun applyWorkbenchCommand(key: String, value: String?): Boolean = when (key) {
        TriggerBroadcastContract.COMMAND_APPEARANCE -> runCatching { WorkbenchAppearance.valueOf(checkNotNull(value)) }
            .map { selected ->
                appearance = selected
                workbenchPreferences?.edit()?.putString("appearance", selected.name)?.apply()
                applyAppearance()
                true
            }.getOrDefault(false)
        TriggerBroadcastContract.COMMAND_SIDEBAR_SIDE -> runCatching { SidebarSide.valueOf(checkNotNull(value)) }
            .map { side -> if (workspaceVisible && !suspended) onSidebarSideRequested(side) else { sidebarSide = side; true } }
            .getOrDefault(false)
        TriggerBroadcastContract.COMMAND_HIDE_RECENTS -> {
            recentsHidden = value.toBoolean()
            workbenchPreferences?.edit()?.putBoolean("hide_recents", recentsHidden)?.apply()
            publishPrivacy()
            true
        }
        TriggerBroadcastContract.COMMAND_CONTROL_BOTTOM -> {
            setControlAtBottom(value.toBoolean())
            true
        }
        TriggerBroadcastContract.COMMAND_CAPTURE -> {
            setCaptureAllowed(value.toBoolean())
            capturePolicy.captureAllowed == value.toBoolean()
        }
        TriggerBroadcastContract.COMMAND_EXPORT_DIAGNOSTICS -> tools.exportDiagnostics()
        else -> false
    }

    private fun setControlAtBottom(bottom: Boolean) {
        if (controlAtBottom == bottom) return
        controlAtBottom = bottom
        workbenchPreferences?.edit()?.putBoolean("control_bottom", bottom)?.apply()
        viewport.setControlAtBottom(bottom)
        onViewportChanged()
    }

    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_WALLPAPER_CHANGED -> {
                    // Retain the displayed frame while the new wallpaper starts rendering.
                    // OEM live services may change content without changing component/id.
                    backdropGeneration.incrementAndGet()
                }
                Intent.ACTION_USER_PRESENT -> Unit
                Intent.ACTION_SCREEN_OFF -> { setCaptureAllowed(false); tools.dismiss(); return }
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    if (intent.getStringExtra("reason") in setOf("homekey", "recentapps")) {
                        setCaptureAllowed(false)
                        tools.dismiss()
                    }
                    return
                }
                else -> return
            }
            if (!suspended && workspaceVisible) refreshWallpaperBackdrop()
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
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { NeXtepLog.warn("wallpaper_backdrop", "Wallpaper change listener unavailable", it) }
        // The frame is captured on entry; holding a full-screen bitmap while the workspace
        // is closed only costs SystemUI memory.
        runCatching {
            context.contentResolver.registerContentObserver(SystemServerWorkspaceBridge.sceneUri(), false, sceneObserver)
        }.onFailure { NeXtepLog.warn("workbench_scene", "Scene observer unavailable", it) }
        mainTaskPresenter.onHomePresented = {
            backdropHandler.post {
                if (!suspended && workspaceVisible) refreshWallpaperBackdrop()
            }
        }
    }

    fun suspendForLock() {
        runPendingDeactivate()
        setCaptureAllowed(false)
        tools.dismiss()
        suspended = true
        publishPrivacy()
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
        publishPrivacy()
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

    private var pendingDeactivate: Runnable? = null
    private var panelGeneration = 0

    private fun runPendingDeactivate() {
        pendingDeactivate?.let { backdropHandler.removeCallbacks(it); it.run() }
    }

    fun show(): WorkspaceGeometry {
        runPendingDeactivate()
        capturePolicy.reset()
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        workspaceVisible = true
        publishPrivacy()
        val geometry = reconfigure().getOrThrow()
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            view.visibility = View.VISIBLE
            if (record.title == "NeXtepSidebar") {
                // Fading the rail builds a hardware layer, and SurfaceView holes inside a layer
                // show through as black. Slide the rail in from its edge at full alpha instead.
                val (dx, dy) = sidebarEdgeOffset(geometry)
                view.alpha = 1f
                view.translationX = dx
                view.translationY = dy
                view.animate().translationX(0f).translationY(0f)
                    .setDuration(WORKSPACE_ENTER_DURATION_MS).setInterpolator(transitionInterpolator).start()
                return@forEach
            }
            view.translationX = 0f
            view.alpha = 0f
            view.translationY = when (record.title) {
                "NeXtepTopBar" -> if (controlAtBottom) dp(ENTER_OFFSET_DP) else -dp(ENTER_OFFSET_DP)
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
        // Rapid exit/enter cycles have left the main task scaled with every panel hidden. Whatever
        // cancels the entry animation, the panels must end visible while the workspace is active.
        val generation = ++panelGeneration
        backdropHandler.postDelayed({
            if (generation != panelGeneration || !workspaceVisible || suspended) return@postDelayed
            records.forEach { record ->
                val view = record.view
                if (view.visibility != View.VISIBLE || view.alpha < 1f || view.translationX != 0f || view.translationY != 0f) {
                    NeXtepLog.warn("workspace", "Panel recovered ${record.title} visibility=${view.visibility} alpha=${view.alpha}")
                    view.animate().cancel()
                    view.visibility = View.VISIBLE
                    view.alpha = 1f
                    view.translationX = 0f
                    view.translationY = 0f
                }
            }
        }, WORKSPACE_ENTER_DURATION_MS + 80)
        return geometry()
    }

    fun hide() {
        workspaceVisible = false
        panelGeneration++
        releaseWallpaperAfterExit()
        tools.dismiss()
        setCaptureAllowed(false)
        publishPrivacy()
        cancelDragVisuals()
        suspended = false
        backdropGeneration.incrementAndGet()
        // Keep the four live slots on screen while the panels fade; parking the tasks and
        // releasing their displays first blanked every slot a frame before the fade began.
        runPendingDeactivate()
        pendingDeactivate = Runnable {
            pendingDeactivate = null
            runCatching { slotCoordinator?.deactivate() }
                .onFailure { NeXtepLog.warn("workspace", "Deferred slot deactivation failed; displays retained", it) }
        }.also { backdropHandler.postDelayed(it, WORKSPACE_EXIT_DURATION_MS) }
        setWideLayout(null, null)
        SystemServerWorkspaceBridge.clearScene(context)
        topAppStrip?.setWorkspaceVisible(false)
        val exitGeometry = geometry()
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            windowManager.updateViewLayout(view, params)
            val slideOut = record.title == "NeXtepSidebar"
            val (dx, dy) = if (slideOut) sidebarEdgeOffset(exitGeometry) else 0f to 0f
            view.animate()
                .alpha(if (slideOut) 1f else 0f)
                .translationX(dx)
                .translationY(
                    if (slideOut) dy
                    else if (record.title == "NeXtepTopBar" && !controlAtBottom) -dp(EXIT_OFFSET_DP)
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
        viewport.geometry(sidebarSide, controlAtBottom, refreshViewport)

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
        sidebarView?.let { panel -> applySlotLayout(panel, geometry) }
        applySidebarBackdrop(geometry)
        records.firstOrNull { it.title == "NeXtepSidebar" }?.view?.takeUnless { contentLayoutChanging }?.let { sidebar ->
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
                onAppClicked = { intent ->
                    val result = if (slotCoordinator?.selectingDesktopApp == true) slotCoordinator?.openFromLauncher(intent)
                    else slotCoordinator?.openInMain(
                        intent,
                        autoMinimizeCurrent = SystemUiRuntime.shouldAutoMinimizeMainOnTopAppSwitch(),
                    )
                    result?.onFailure { error ->
                        NeXtepLog.warn("top_apps", "Unable to open ${intent.component} in main", error)
                    }
                },
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
                onPaymentRequested = tools::showPayment,
                onToolsRequested = { setCaptureAllowed(false); onSettingsRequested() },
                onCompareRequested = tools::showSearch,
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
        applyAppearance()
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
                WindowManager.LayoutParams.FLAG_SECURE or
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
        // A re-application of the same geometry (the viewport callback after a wide
        // layout change) must not abort the slot exchange that change just started.
        if (!contentLayoutChanging && geometry != appliedGeometry) cancelDragVisuals()
        appliedGeometry = geometry
        records.forEach { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            configureWindow(record, params, geometry)
            windowManager.updateViewLayout(record.view, params)
        }
        topAppStrip?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft, geometry.controlAtBottom)
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
                    (geometry.availableWidth * geometry.contentScale).toInt(),
                    (geometry.availableHeight * geometry.contentScale).toInt(), Gravity.TOP or Gravity.LEFT,
                ).apply {
                    leftMargin = (geometry.availableLeft * geometry.contentScale + geometry.contentTranslationX).toInt()
                    topMargin = (geometry.availableTop * geometry.contentScale + geometry.contentTranslationY).toInt()
                }
            }
        }
    }

    private fun sidebarPanel(geometry: WorkspaceGeometry): View = FrameLayout(context).apply {
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
                onTasksObserved = ::observeTasks,
                onContentDragStarted = { if (workspaceVisible && !suspended) contentDrag.begin() },
                onContentDragEntered = { index ->
                    // Defer reparent until ViewGroup has finished dispatching ENTERED.
                    backdropHandler.post {
                        runCatching { contentDrag.enter(index) }.onFailure {
                            NeXtepLog.warn("content_drag", "Content expansion unavailable", it)
                            swapAnimator.cancel()
                            contentDrag.abandon()
                        }
                    }
                },
                onContentDragEnded = { accepted -> backdropHandler.post {
                    runCatching { contentDrag.end(accepted) }.onFailure {
                        NeXtepLog.warn("content_drag", "Content drag recovery unavailable", it)
                        contentDrag.abandon()
                    }
                } },
            )
        }
    }

    private fun cancelDragVisuals() {
        contentDrag.abandon()
        internalDrag.cancel()
        slotWindows.forEach { it.view.cancelBackgroundSwipe() }
        slotCoordinator?.onSlotDragEnded()
        slotCoordinator?.cancelPendingDragExchange()
        swapAnimator.cancel()
    }

    private fun contentSide(side: SidebarSide): Boolean {
        contentLayoutChanging = true
        return try { onSidebarSideRequested(side) } finally { contentLayoutChanging = false }
    }

    private fun expandContentTarget(index: Int, complete: (Boolean) -> Unit): Boolean {
        if (!workspaceVisible || suspended || wideSlotIndex != null) return false
        val coordinator = slotCoordinator ?: return false
        val target = coordinator.contentExchangeTarget(index) ?: return false
        contentOriginSide = sidebarSide
        contentTarget = target
        return animateSlotExpansion(index, target.sourceTask, { contentDrag.active },
            { complete(false) }) { reveal ->
            var reported = false
            fun settled(success: Boolean) {
                if (reported) return
                reported = true
                if (!success) contentSide(contentOriginSide)
                reveal()
                complete(success)
                NeXtepLog.info("content_drag", "Expansion settled success=$success")
            }
            if (!coordinator.exchangeContent(target, restoring = false) { settled(it.isSuccess) }) settled(false)
        }
    }

    private fun animateSlotExpansion(index: Int, sourceTask: Int?, valid: () -> Boolean,
                                     rejected: () -> Unit, move: (() -> Unit) -> Unit): Boolean {
        val old = geometry()
        val destinationSide = if (sidebarSide == SidebarSide.RIGHT) SidebarSide.LEFT else SidebarSide.RIGHT
        val next = old.copy(sidebarSide = destinationSide)
        val bounds = slotWindows.map { slot -> android.graphics.Rect().also {
            if (!slot.view.getScreenVisibleRect(it)) return false
        } }
        val main = android.graphics.RectF(old.contentLeft.toFloat(), old.contentTop.toFloat(),
            old.contentRight.toFloat(), old.contentBottom.toFloat())
        val frames = mutableListOf<WorkspaceSwapAnimator.Frame>()
        val states = slotCoordinator?.slotStates().orEmpty()
        bounds.forEachIndexed { i, rect ->
            val from = android.graphics.RectF(rect)
            val to = android.graphics.RectF(rect).apply {
                offset((next.sidebarLeft - old.sidebarLeft).toFloat(),
                    (next.sidebarTop - old.sidebarTop).toFloat())
            }
            if (i == index) frames.add(WorkspaceSwapAnimator.Frame(
                sourceTask?.let { io.github.lujinxin.nextep.framework.TaskSurfaceCompat.capturePreview(it) }, main, to))
            else frames.add(WorkspaceSwapAnimator.Frame(slotWindows[i].view.captureProtectedPreview(), from, to,
                empty = states.getOrNull(i) !is SlotState.Occupied))
        }
        // Target expands on its own side while the other tasks cross to the opposite rail.
        frames.add(WorkspaceSwapAnimator.Frame(slotWindows[index].view.captureProtectedPreview(),
            android.graphics.RectF(bounds[index]), android.graphics.RectF(next.contentLeft.toFloat(),
                next.contentTop.toFloat(), next.contentRight.toFloat(), next.contentBottom.toFloat())))
        swapAnimator.animateFrames(frames, listOf(main) + bounds.map { android.graphics.RectF(it) },
            old.screenWidth, old.screenHeight) { reveal ->
            if (!valid() || !workspaceVisible || suspended || !contentSide(destinationSide)) {
                reveal(); rejected()
            } else move(reveal)
        }
        return true
    }

    private fun restoreContentTarget(index: Int, complete: () -> Unit) {
        val target = contentTarget?.takeIf { it.slot == index }
        if (target == null || !workspaceVisible || suspended) { complete(); return }
        var reported = false
        fun restored() {
            if (reported) return
            reported = true
            contentSide(contentOriginSide)
            complete()
        }
        if (slotCoordinator?.exchangeContent(target, restoring = true) {
                if (it.isFailure) NeXtepLog.warn("content_drag", "Cancellation task restore unavailable", it.exceptionOrNull())
                restored()
            } != true) restored()
    }

    private fun highlightInternalDragTarget(target: WorkspaceInternalDragController.Target?) {
        slotWindows.forEachIndexed { index, slot ->
            slot.view.setDropHighlighted(target == WorkspaceInternalDragController.Target.Slot(index))
        }
        topAppStrip?.setDismissTargetHighlighted(target == WorkspaceInternalDragController.Target.Background)
        contentHighlightView?.background =
            if (target == WorkspaceInternalDragController.Target.Main) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor((appearance.accent and 0x00FFFFFF) or 0x19000000)
                    setStroke(dp(2).toInt(), appearance.accent)
                    cornerRadius = dp(14)
                }
            } else mainFrame()
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

    private fun animateDragSwap(drag: SlotTaskDrag, targetIndex: Int?, move: (() -> Unit) -> Unit) {
        // A tap swaps the slot with the main window in place; the rail keeps its side.
        // (Content drag still expands the target on its own side, see expandContentTarget.)
        val geometry = geometry()
        val sourceView = slotWindows.getOrNull(drag.slotIndex)?.view
        val sourceRect = android.graphics.Rect()
        if (sourceView == null || !sourceView.getScreenVisibleRect(sourceRect)) {
            move {}
            return
        }
        val targetRect = android.graphics.Rect()
        if (targetIndex != null) {
            val targetView = slotWindows.getOrNull(targetIndex)?.view
            if (targetView == null || !targetView.getScreenVisibleRect(targetRect)) {
                move {}
                return
            }
        } else targetRect.set(geometry.contentLeft, geometry.contentTop, geometry.contentRight, geometry.contentBottom)
        val point = drag.dropPoint
        val halfWidth = sourceRect.width() * 0.45f
        val halfHeight = sourceRect.height() * 0.45f
        val startRect = if (point == null) android.graphics.RectF(sourceRect) else
            android.graphics.RectF(point.x - halfWidth, point.y - halfHeight,
                point.x + halfWidth, point.y + halfHeight)
        val sourcePreview = sourceView.captureProtectedPreview()
        val targetOccupied = targetIndex == null ||
            slotCoordinator?.slotStates()?.getOrNull(targetIndex) is SlotState.Occupied
        val targetPreview = if (targetIndex != null) {
            if (targetOccupied) slotWindows[targetIndex].view.captureProtectedPreview() else null
        } else mainTaskPresenter.currentTaskId()?.let {
            io.github.lujinxin.nextep.framework.TaskSurfaceCompat.capturePreview(it)
        }
        swapAnimator.animate(sourcePreview, targetPreview, !targetOccupied, startRect,
            android.graphics.RectF(sourceRect), android.graphics.RectF(targetRect),
            geometry.screenWidth, geometry.screenHeight, move)
    }

    private fun applySlotLayout(panel: FrameLayout, geometry: WorkspaceGeometry) {
        panel.invalidate()
        val profile = viewport.layout()
        val bounds = profile.slots
        topAppStrip?.setWideSlot(profile.wideSlotIndex?.let { slotWindows[it].view })
        slotWindows.forEachIndexed { index, slot ->
            slot.setInteractive(index == profile.wideSlotIndex)
            slot.setLayoutProfile(profile)
            slot.view.setBackgroundSwipeEnabled(slotBackgroundSwipeEnabled && index != profile.wideSlotIndex)
            if (index == profile.wideSlotIndex) return@forEachIndexed
            if (slot.view.parent !== panel) {
                (slot.view.parent as? android.view.ViewGroup)?.removeView(slot.view)
                panel.addView(slot.view)
            }
            slot.view.setBackgroundSwipeSide(logicalSidebarSide(geometry))
            slot.view.layoutParams = FrameLayout.LayoutParams(
                bounds[index].width,
                bounds[index].height,
                Gravity.TOP,
            ).apply {
                val slack = (geometry.sidebarWidth - profile.gapPixels - bounds[index].width).coerceAtLeast(0) / 2
                marginStart = slack + if (logicalSidebarSide(geometry) == SidebarSide.RIGHT) profile.gapPixels else 0
                marginEnd = slack + if (logicalSidebarSide(geometry) == SidebarSide.LEFT) profile.gapPixels else 0
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
                val replaced = wallpaperBitmap
                if (bitmap != null) {
                    wallpaperBitmap = bitmap
                } else if (fitted != null) {
                    wallpaperBitmap = fitted
                }
                // A failed capture or identity query must not erase a valid frame.
                applyWallpaperBackdrop(geometry)
                if (replaced != null && replaced !== wallpaperBitmap) replaced.recycle()
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

    /** The panels fade for 180 ms; afterwards the frame is dropped until the next entry. */
    private fun releaseWallpaperAfterExit() {
        backdropGeneration.incrementAndGet()
        backdropHandler.postDelayed({
            if (workspaceVisible) return@postDelayed
            val released = wallpaperBitmap ?: return@postDelayed
            wallpaperBitmap = null
            applyWallpaperBackdrop(geometry())
            released.recycle()
        }, WORKSPACE_EXIT_DURATION_MS + 50)
    }

    private fun applyWallpaperBackdrop(geometry: WorkspaceGeometry) {
        records.firstOrNull { it.title == "NeXtepTopBar" }?.view?.let { view ->
            val background = WorkspaceWallpaperBackdrop.crop(wallpaperBitmap,
                geometry.controlLeft, geometry.controlTop, frostStrength, view.background)
            if (view.background !== background) view.background = background
        }
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

    private fun mainFrame() = WorkbenchFrameDrawable(appearance.panelEnd or 0xFF000000.toInt(),
        appearance.accent, dp(14), dp(1).coerceAtLeast(1f))

    private fun applyAppearance() {
        topAppStrip?.setAppearance(appearance)
        sidebarView?.background = appearance.background()
        slotWindows.forEach { it.view.setAppearance(appearance) }
        contentHighlightView?.background = mainFrame()
    }

    private fun setCaptureAllowed(allowed: Boolean) {
        if (allowed && (!workspaceVisible || suspended)) return
        // Home/recents/lock reset an already protected session; rebuilding every
        // output layer again would blank the slots for nothing.
        if (!allowed && !capturePolicy.captureAllowed) return
        if (allowed) capturePolicy.allowForSession() else capturePolicy.reset()
        runCatching { applyCaptureFlags(allowed) }.onFailure {
            capturePolicy.reset()
            runCatching { applyCaptureFlags(false) }
            NeXtepLog.warn("workspace_privacy", "Capture mode update failed; restoring protection", it)
        }
    }

    private fun applyCaptureFlags(allowed: Boolean) {
        var failure: Throwable? = null
        fun failed(error: Throwable) { if (failure == null) failure = error else failure?.addSuppressed(error) }
        slotWindows.forEach { slot -> runCatching { slot.setCaptureAllowed(allowed) }.onFailure(::failed) }
        records.forEach { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            params.flags = if (allowed) params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                else params.flags or WindowManager.LayoutParams.FLAG_SECURE
            runCatching { windowManager.updateViewLayout(record.view, params) }.onFailure(::failed)
        }
        failure?.let { throw it }
    }

    private fun publishPrivacy() {
        val privateIds = if (workspaceVisible && !suspended && recentsHidden) {
            val displays = slotWindows.mapNotNull { it.displayId() }.toSet()
            observedTasks.filter { it.displayId in displays }.mapTo(mutableSetOf()) { it.taskId }.apply {
                observedTasks.firstOrNull { it.displayId == android.view.Display.DEFAULT_DISPLAY }
                    ?.takeIf { it.component.packageName != context.packageName }
                    ?.let { add(it.taskId) }
            }
        } else emptySet()
        SystemServerWorkspaceBridge.publishPrivateTasks(context, privateIds)
    }

    private fun observeTasks(tasks: List<io.github.lujinxin.nextep.task.TaskRepository.TaskSnapshot>, states: List<SlotState>) {
        observedTasks = tasks
        observedStates = states
        publishPrivacy()
        if (!workspaceVisible || suspended || contentDrag.active || slotCoordinator?.launchingApps == true ||
            slotCoordinator?.movingTasks == true) return
        val scene = SystemServerWorkspaceBridge.scene(context)
        if (scene != null && scene.time > seenSceneTime && scene.taskId == wideTaskId && scene.releasesWide()) {
            seenSceneTime = scene.time
            returnWideToMain()
            return
        }
        wideSlotIndex?.let { index ->
            val current = states.getOrNull(index)
            // A task pulled back to the main display (or gone) leaves the wide slot empty;
            // keeping the wide layout then shows a black top with the app squeezed below it.
            val lost = current !is SlotState.Occupied || current.taskId != wideTaskId
            if (!pinPending && lost || wideTaskId != null && tasks.none { it.taskId == wideTaskId }) {
                NeXtepLog.info("wide_layout", "Wide slot lost task=$wideTaskId state=$current pending=$pinPending")
                setWideLayout(null, null)
            }
        }
        val main = tasks.firstOrNull { it.displayId == android.view.Display.DEFAULT_DISPLAY } ?: return
        if (scene != null && scene.time > seenSceneTime && scene.taskId == main.taskId &&
            wideSlotIndex == null && scene.fresh(android.os.SystemClock.uptimeMillis())) {
            val pin = scene.shouldPin(android.os.SystemClock.uptimeMillis())
            NeXtepLog.info("workbench_scene", "Scene task=${main.taskId} orientation=${scene.orientation} " +
                "fullscreen=${scene.fullscreen} pin=$pin")
            if (pin && pinTask(main.taskId)) seenSceneTime = scene.time
        } else if (scene != null && scene.time > seenSceneTime && scene.taskId != main.taskId) {
            NeXtepLog.info("workbench_scene", "Scene ignored task=${scene.taskId} main=${main.taskId} wide=$wideSlotIndex")
        }
    }

    private fun pinCurrentLandscape(): Boolean {
        if (wideSlotIndex != null) { returnWideToMain(); return true }
        val main = observedTasks.firstOrNull { it.displayId == android.view.Display.DEFAULT_DISPLAY } ?: return false
        val scene = SystemServerWorkspaceBridge.scene(context)
        if (scene?.taskId != main.taskId || !scene.shouldPin(android.os.SystemClock.uptimeMillis())) return false
        return pinTask(main.taskId)
    }

    private fun returnWideToMain() {
        val index = wideSlotIndex ?: return
        val task = wideTaskId
        setWideLayout(null, null)
        // The slot view is back in the rail but laid out only on the next traversal; swap
        // once its output layer is bound so the exchange animates from the rail card.
        slotWindows[index].whenOutputBound {
            if ((slotCoordinator?.slotStates()?.getOrNull(index) as? SlotState.Occupied)?.taskId == task)
                slotCoordinator?.onSlotClicked(index)
        }
    }

    private fun pinTask(taskId: Int): Boolean {
        val coordinator = slotCoordinator ?: return false
        val states = coordinator.slotStates()
        val index = wideSlotIndex ?: states.indexOfFirst { it is SlotState.Ready }.takeIf { it >= 0 }
            ?: states.indexOfFirst { it is SlotState.Occupied }.takeIf { it >= 0 } ?: return false
        val previousIndex = wideSlotIndex
        val previousTask = wideTaskId
        setWideLayout(index, taskId)
        pinPending = true
        // The slot's output layer moves into the top bar and is recreated there on the next
        // traversal. Park the task once the display feeds that layer, so the move never lands
        // on a sleeping display that still has the rail canvas.
        slotWindows[index].whenOutputBound {
            pinPending = false
            if (wideSlotIndex != index || wideTaskId != taskId) return@whenOutputBound
            val result = coordinator.parkMainForWide(taskId, index)
            result.onFailure {
                NeXtepLog.warn("wide_layout", "Pin failed task=$taskId slot=$index", it)
                setWideLayout(previousIndex, previousTask)
            }
        }
        return true
    }

    private fun setWideLayout(index: Int?, taskId: Int?) {
        if (wideSlotIndex == index && wideTaskId == taskId) return
        NeXtepLog.info("wide_layout", "Wide layout index=$index task=$taskId display=${index?.let { slotWindows.getOrNull(it)?.displayId() }}")
        if (index == null) pinPending = false
        wideSlotIndex = index
        wideTaskId = taskId
        viewport.setWideSlot(index)
        SystemServerWorkspaceBridge.publishWideDisplay(context, index?.let { slotWindows.getOrNull(it)?.displayId() })
        val geometry = geometry()
        applyWindowGeometry(geometry)
        sidebarView?.let { applySlotLayout(it, geometry) }
    }

    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density

    /** Translation that parks the rail just outside the display edge it hangs from. */
    private fun sidebarEdgeOffset(geometry: WorkspaceGeometry): Pair<Float, Float> =
        if (geometry.isLandscape) 0f to (if (geometry.isSidebarAtTop) -1f else 1f) * geometry.sidebarPhysicalHeight
        else (if (geometry.sidebarSide == SidebarSide.LEFT) -1f else 1f) * geometry.sidebarWidth to 0f

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
