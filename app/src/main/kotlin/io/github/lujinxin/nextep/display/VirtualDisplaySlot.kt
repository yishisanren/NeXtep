package io.github.lujinxin.nextep.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.view.Surface
import android.view.SurfaceHolder
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.framework.UserTargetedActivityLauncher
import io.github.lujinxin.nextep.workspace.WorkspaceLayoutProfile

class VirtualDisplaySlot(
    context: Context,
    private val index: Int,
) : SurfaceHolder.Callback {
    private companion object {
        const val OUTPUT_BOUND_TIMEOUT_MS = 600L
        /** Two frames for a reconnected output to show the app. */
        const val OUTPUT_REBIND_SETTLE_MS = 40L
        const val PARKED_DISPLAY_RELEASE_MS = 60_000L
        val creator: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "NeXtep-VirtualDisplay") }
    }
    interface Listener {
        fun onDisplayReady(slotIndex: Int, displayId: Int)
        fun onDisplayReleased(slotIndex: Int, displayId: Int?, reason: String)
        fun onDisplayFailed(slotIndex: Int, message: String)
    }

    val view = TaskSwitcherView(context, index)

    private val applicationContext = context.applicationContext ?: context
    private val factory = VirtualDisplayFactory(applicationContext)
    private var active = false
    private var suspended = false
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var activeGeometry: SlotGeometry? = null
    private var layoutProfile: WorkspaceLayoutProfile? = null
    private var pendingLaunch: Intent? = null
    private var freshSurfaceRequired = false
    private var surfaceGeneration = 1
    private var listener: Listener? = null
    private var creating = false
    private var creation = 0
    private var outputBoundAction: (() -> Unit)? = null
    private var boundWidth = 0
    private var boundHeight = 0
    private val outputBoundFallback = Runnable {
        NeXtepLog.warn("virtual_display", "Output not bound in time slot=$index display=${displayId()}")
        runOutputBound()
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inputManager by lazy { applicationContext.getSystemService(android.hardware.input.InputManager::class.java) }
    private val injectInput by lazy { inputManager.javaClass.getMethod("injectInputEvent",
        android.view.InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val inputDisplay by lazy { android.view.InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }
    private val input = SlotTouchForwarder { event ->
        val id = displayId()
        if (!active || suspended || id == null || id <= 0) false else runCatching {
            inputDisplay.invoke(event, id)
            injectInput.invoke(inputManager, event, 0) == true
        }.onFailure { NeXtepLog.warn("wide_input", "Player input unavailable", it) }.getOrDefault(false)
    }

    fun setInteractive(value: Boolean) { view.setContentInput(if (value) input else null) }

    /** SurfaceView.setSecure only reaches a surface created after the call; rebuild the live one. */
    fun setCaptureAllowed(allowed: Boolean) {
        if (view.isCaptureAllowed() == allowed) return
        view.setCaptureAllowed(allowed)
        if (active && !suspended && virtualDisplay != null) {
            surfaceGeneration += 1
            view.refreshOutputSurface(this)
            NeXtepLog.info("virtual_display", "Rebuilt output slot=$index generation=$surfaceGeneration capture=$allowed")
        }
    }

    init {
        view.textureView.holder.addCallback(this)
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /** A parked display (hidden workspace) is not offered: to callers the slot has no display. */
    fun displayId(): Int? = if (parked) null else virtualDisplay?.display?.displayId

    fun geometry(): SlotGeometry? = activeGeometry

    fun setLayoutProfile(profile: WorkspaceLayoutProfile) {
        if (layoutProfile == profile) return
        layoutProfile = profile
        updateDisplay(view.textureView.holder)
    }

    fun sourceBounds(): WorkspaceLayoutProfile.Source = checkNotNull(layoutProfile).source(index)

    /**
     * Runs [action] once the current output layer feeds the display, immediately when it already
     * does. A reparented SurfaceView is recreated in its new window on the next traversal; moving a
     * task before that lands it on a sleeping display. The action still runs after a timeout.
     */
    fun whenOutputBound(action: () -> Unit) {
        mainHandler.removeCallbacks(outputBoundFallback)
        outputBoundAction = null
        val current = view.textureView.holder.surface
        if (virtualDisplay != null && surface === current && current.isValid) {
            action()
            return
        }
        outputBoundAction = action
        mainHandler.postDelayed(outputBoundFallback, OUTPUT_BOUND_TIMEOUT_MS)
    }

    /**
     * Connects the display to the current output layer again. After a move between windows the
     * display was once left on the old layer's queue (display on, nothing drawn) until the next
     * move; callers run this while something still covers the slot.
     */
    /**
     * Runs [done] once the output layer shows the display: when it holds no buffer at all, the
     * display is connected again first. A reconnect drops the last frame, so a paused or loading
     * app would show black until it draws again; a layer that already has a picture is left alone.
     */
    fun ensureOutputShowing(done: () -> Unit) {
        val output = view.textureView
        if (virtualDisplay == null || !active || suspended || !output.holder.surface.isValid) return done()
        val probe = android.graphics.Bitmap.createBitmap(16, 9, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(output, probe, { result ->
                probe.recycle()
                if (result == android.view.PixelCopy.ERROR_SOURCE_NO_DATA) {
                    rebindOutput()
                    mainHandler.postDelayed(done, OUTPUT_REBIND_SETTLE_MS)
                } else done()
            }, mainHandler)
        }.onFailure {
            probe.recycle()
            done()
        }
    }

    private fun rebindOutput() {
        val display = virtualDisplay ?: return
        if (!active || suspended) return
        val holder = view.textureView.holder
        val output = holder.surface.takeIf { it.isValid } ?: return
        runCatching {
            // Through the placeholder, not null: the display must not turn off in between.
            display.setSurface(placeholderOutput())
            display.setSurface(output)
            releasePlaceholder()
        }.onFailure { NeXtepLog.warn("virtual_display", "Output rebind failed slot=$index", it) }.onSuccess {
            surface = output
            boundWidth = holder.surfaceFrame.width()
            boundHeight = holder.surfaceFrame.height()
            NeXtepLog.info("virtual_display", "Rebound output slot=$index size=${boundWidth}x$boundHeight")
        }
    }

    private fun runOutputBound() {
        mainHandler.removeCallbacks(outputBoundFallback)
        val action = outputBoundAction ?: return
        outputBoundAction = null
        action()
    }

    fun isSuspended(): Boolean = suspended

    fun activate() {
        active = true
        // The output view survives a hidden workspace (its window stays parked); creating a new
        // one cost the entry frame a surface each. Replace it only when its protection is stale.
        val replace = freshSurfaceRequired && view.outputSecurityStale
        freshSurfaceRequired = false
        val textureView = if (replace) {
            surfaceGeneration += 1
            view.replaceTextureView(this).also {
                NeXtepLog.info(
                    "virtual_display",
                    "Replaced secure output slot=$index generation=$surfaceGeneration",
                )
            }
        } else {
            view.textureView
        }
        val reused = parked
        parked = false
        mainHandler.removeCallbacks(releaseParked)
        updateDisplay(textureView.holder)
        // A parked display is ready as soon as its output is bound again; no new display is created.
        if (reused) virtualDisplay?.display?.displayId?.let { displayId ->
            whenOutputBound { if (active && virtualDisplay?.display?.displayId == displayId) listener?.onDisplayReady(index, displayId) }
        }
    }

    fun deactivate() {
        input.cancel()
        active = false
        suspended = false
        pendingLaunch = null
        freshSurfaceRequired = true
        val display = virtualDisplay
        if (display == null || creating) {
            releaseDisplay("workspace hidden")
            return
        }
        // Keep the display, off and without output, for a quick re-entry: creating four displays
        // on entry stalled SystemUI's main thread on DisplayManager's lock during the slide-in.
        // The tasks already left it; a minute later it is released as before.
        mainHandler.removeCallbacks(outputBoundFallback)
        outputBoundAction = null
        runCatching { display.setSurface(null) }
        releasePlaceholder()
        surface = null
        parked = true
        view.showWaiting()
        mainHandler.postDelayed(releaseParked, PARKED_DISPLAY_RELEASE_MS)
        NeXtepLog.info("virtual_display", "Parked slot=$index displayId=${display.display.displayId}")
        listener?.onDisplayReleased(index, display.display.displayId, "workspace hidden")
    }

    private var parked = false
    private val releaseParked = Runnable {
        if (active || !parked) return@Runnable
        parked = false
        releaseDisplay("parked display expired")
    }

    fun setSuspended(value: Boolean) {
        if (value) {
            input.cancel()
            suspended = true
            creation++
            creating = false
            // A null Surface turns the display off without removing its tasks/windows.
            // Keep our Surface alive so unlock can reconnect the same display.
            runCatching { virtualDisplay?.setSurface(null) }.onFailure {
                NeXtepLog.warn("virtual_display", "Unable to suspend output slot=$index", it)
            }
            releasePlaceholder()
            return
        }
        if (!suspended) return
        virtualDisplay?.let { display ->
            val currentSurface = view.textureView.holder.surface.takeIf { it.isValid } ?: return
            surface = currentSurface
            check(currentSurface.isValid) { "Slot $index output Surface is invalid" }
            display.setSurface(currentSurface)
        }
        suspended = false
        updateDisplay(view.textureView.holder)
        virtualDisplay?.display?.displayId?.let { displayId ->
            pendingLaunch?.let { intent ->
                pendingLaunch = null
                launchNow(intent, displayId)
            }
        }
    }

    fun launch(intent: Intent): Result<Unit> {
        val displayId = virtualDisplay?.display?.displayId
        if (suspended || displayId == null) {
            pendingLaunch = Intent(intent)
            view.showWaiting()
            return Result.success(Unit)
        }
        return launchNow(intent, displayId)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NeXtepLog.info("virtual_display", "Surface created slot=$index display=${displayId()}")
        updateDisplay(holder, rebind = true)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NeXtepLog.info("virtual_display", "Surface changed slot=$index display=${displayId()} size=${width}x$height " +
            "bound=${boundWidth}x$boundHeight")
        // The holder keeps its Java Surface across a resize but replaces the producer; a change
        // at the bound size is the first callback after creation and must not reconnect the
        // display's queue a second time (that dropped the queued frames: NO_BUFFER_AVAILABLE).
        updateDisplay(holder, rebind = width != boundWidth || height != boundHeight)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // A lock, rotation or temporary reparent may replace the output layer.
        // Keep its tasks alive on the same display until the new layer arrives.
        NeXtepLog.info("virtual_display", "Surface destroyed slot=$index display=${displayId()}")
        val display = virtualDisplay
        // Without an output the display turns off; turned off and on again within a frame or two
        // (the wide pane's move into the top bar), the system once kept its tasks hidden. A
        // placeholder keeps it on until the new layer arrives.
        val placeholder = display?.takeIf { active && !suspended }?.let { placeholderOutput() }
        runCatching { display?.setSurface(placeholder) }.onFailure {
            NeXtepLog.warn("virtual_display", "Placeholder output failed slot=$index", it)
            runCatching { display?.setSurface(null) }
            releasePlaceholder()
        }
        surface = null
    }

    private var placeholder: android.media.ImageReader? = null

    /** An output that drops every frame, sized like the display. */
    private fun placeholderOutput(): Surface? = runCatching {
        releasePlaceholder()
        val geometry = activeGeometry ?: layoutProfile?.canvas(index) ?: return null
        android.media.ImageReader.newInstance(geometry.width, geometry.height, android.graphics.PixelFormat.RGBA_8888, 2)
            .also { reader ->
                reader.setOnImageAvailableListener({ runCatching { it.acquireLatestImage()?.close() } }, mainHandler)
                placeholder = reader
            }.surface
    }.onFailure { NeXtepLog.warn("virtual_display", "Placeholder unavailable slot=$index", it) }.getOrNull()

    private fun releasePlaceholder() {
        placeholder?.let { reader -> runCatching { reader.close() } }
        placeholder = null
    }

    private fun updateDisplay(holder: SurfaceHolder, rebind: Boolean = false) {
        val profile = layoutProfile ?: return
        val geometry = profile.canvas(index)
        view.setOutputSize(geometry.width, geometry.height)
        holder.setFixedSize(geometry.width, geometry.height)
        if (!active || suspended) return
        val output = holder.surface.takeIf { it.isValid }
        val display = virtualDisplay
        if (display == null) {
            if (output != null) createDisplay(output, geometry)
            return
        }
        if (output != null && (rebind || surface !== output)) {
            display.setSurface(output)
            releasePlaceholder()
            surface = output
            boundWidth = holder.surfaceFrame.width()
            boundHeight = holder.surfaceFrame.height()
            runOutputBound()
        }
        if (geometry == activeGeometry) {
            return
        }
        // The display resizes without an output layer; a task parked while the
        // layer is being recreated then lays out at the new canvas, not the old one.
        activeGeometry = geometry
        runCatching {
            display.resize(geometry.width, geometry.height, geometry.densityDpi)
        }.onSuccess {
            NeXtepLog.info(
                "virtual_display",
                "Resized slot=$index generation=$surfaceGeneration geometry=$geometry",
            )
        }.onFailure { error ->
            NeXtepLog.error(
                "virtual_display",
                "Resize/rebind failed slot=$index generation=$surfaceGeneration",
                error,
            )
            releaseDisplay("resize/rebind failed")
            if (output != null) createDisplay(output, geometry)
        }
    }

    private fun createDisplay(createdSurface: Surface, geometry: SlotGeometry) {
        if (!active || suspended || virtualDisplay != null || creating || !createdSurface.isValid) return
        activeGeometry = geometry
        creating = true
        val token = ++creation
        // createVirtualDisplay is a ~100ms binder round trip per slot. Off the UI thread, the
        // entry animation and the main task keep moving while the four displays appear.
        creator.execute {
            val result = factory.create(index, geometry, createdSurface)
            mainHandler.post {
                if (token != creation) { result.getOrNull()?.release(); return@post }
                creating = false
                if (!active || suspended || virtualDisplay != null) { result.getOrNull()?.release(); return@post }
                if (!createdSurface.isValid) {
                    // The holder replaced its Surface meanwhile; retry against the current one.
                    result.getOrNull()?.release()
                    updateDisplay(view.textureView.holder, rebind = true)
                    return@post
                }
                result.onSuccess { display ->
                    surface = createdSurface
                    boundWidth = view.textureView.holder.surfaceFrame.width()
                    boundHeight = view.textureView.holder.surfaceFrame.height()
                    virtualDisplay = display
                    listener?.onDisplayReady(index, display.display.displayId)
                    NeXtepLog.info(
                        "virtual_display",
                        "Created slot=$index generation=$surfaceGeneration " +
                            "displayId=${display.display.displayId} geometry=$geometry",
                    )
                    runOutputBound()
                    pendingLaunch?.let { intent ->
                        pendingLaunch = null
                        launchNow(intent, display.display.displayId)
                    }
                }.onFailure {
                    view.showWaiting()
                    listener?.onDisplayFailed(index, it.message ?: "display unavailable")
                }
            }
        }
    }

    private fun launchNow(intent: Intent, displayId: Int): Result<Unit> = runCatching {
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
        UserTargetedActivityLauncher.start(
            applicationContext,
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            options.toBundle(),
        ).getOrThrow()
        NeXtepLog.info("virtual_display", "Launched slot=$index displayId=$displayId action=${intent.action}")
    }.onFailure { error ->
        view.showWaiting()
        NeXtepLog.error("virtual_display", "Launch failed slot=$index displayId=$displayId", error)
    }

    private fun releaseDisplay(reason: String) {
        // Any display still being created for the old state is released on arrival.
        creation++
        creating = false
        mainHandler.removeCallbacks(outputBoundFallback)
        outputBoundAction = null
        val releasedDisplayId = virtualDisplay?.display?.displayId
        runCatching { virtualDisplay?.setSurface(null) }
        releasePlaceholder()
        virtualDisplay?.release()
        virtualDisplay = null
        activeGeometry = null
        // SurfaceHolder owns the output; releasing it here would invalidate a
        // newly attached layer during a workspace hide/show transition.
        surface = null
        view.showWaiting()
        NeXtepLog.info(
            "virtual_display",
            "Released slot=$index generation=$surfaceGeneration reason=$reason",
        )
        listener?.onDisplayReleased(index, releasedDisplayId, reason)
    }
}
