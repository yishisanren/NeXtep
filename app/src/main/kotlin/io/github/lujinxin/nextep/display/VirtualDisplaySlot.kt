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

    init {
        view.textureView.holder.addCallback(this)
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun displayId(): Int? = virtualDisplay?.display?.displayId

    fun geometry(): SlotGeometry? = activeGeometry

    fun setLayoutProfile(profile: WorkspaceLayoutProfile) {
        if (layoutProfile == profile) return
        layoutProfile = profile
        updateDisplay(view.textureView.holder)
    }

    fun sourceBounds(): WorkspaceLayoutProfile.Source = checkNotNull(layoutProfile).source(index)

    fun isSuspended(): Boolean = suspended

    fun activate() {
        active = true
        val textureView = if (freshSurfaceRequired) {
            freshSurfaceRequired = false
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
        updateDisplay(textureView.holder)
    }

    fun deactivate() {
        input.cancel()
        active = false
        suspended = false
        pendingLaunch = null
        releaseDisplay("workspace hidden")
        freshSurfaceRequired = true
    }

    fun setSuspended(value: Boolean) {
        if (value) {
            input.cancel()
            suspended = true
            // A null Surface turns the display off without removing its tasks/windows.
            // Keep our Surface alive so unlock can reconnect the same display.
            runCatching { virtualDisplay?.setSurface(null) }.onFailure {
                NeXtepLog.warn("virtual_display", "Unable to suspend output slot=$index", it)
            }
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

    override fun surfaceCreated(holder: SurfaceHolder) = updateDisplay(holder, rebind = true)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = updateDisplay(holder, rebind = true)

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // A lock, rotation or temporary reparent may replace the output layer.
        // Keep its tasks alive on the same display until the new layer arrives.
        runCatching { virtualDisplay?.setSurface(null) }
        surface = null
    }

    private fun updateDisplay(holder: SurfaceHolder, rebind: Boolean = false) {
        val profile = layoutProfile ?: return
        val geometry = profile.canvas(index)
        view.setOutputSize(geometry.width, geometry.height)
        holder.setFixedSize(geometry.width, geometry.height)
        if (!active || suspended) return
        val output = holder.surface.takeIf { it.isValid } ?: return
        val display = virtualDisplay
        if (display == null) {
            createDisplay(output, geometry)
            return
        }
        if (rebind || surface !== output) {
            // SurfaceHolder can retain its Java Surface while replacing the
            // underlying producer on a resize; identity alone cannot detect it.
            display.setSurface(output)
            surface = output
        }
        if (virtualDisplay != null && geometry == activeGeometry) {
            return
        }
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
            createDisplay(output, geometry)
        }
    }

    private fun createDisplay(createdSurface: Surface, geometry: SlotGeometry) {
        if (!active || suspended || virtualDisplay != null || !createdSurface.isValid) return
        activeGeometry = geometry
        factory.create(index, geometry, createdSurface)
            .onSuccess { display ->
                surface = createdSurface
                virtualDisplay = display
                listener?.onDisplayReady(index, display.display.displayId)
                NeXtepLog.info(
                    "virtual_display",
                    "Created slot=$index generation=$surfaceGeneration " +
                        "displayId=${display.display.displayId} geometry=$geometry",
                )
                pendingLaunch?.let { intent ->
                    pendingLaunch = null
                    launchNow(intent, display.display.displayId)
                }
            }
            .onFailure {
                view.showWaiting()
                listener?.onDisplayFailed(index, it.message ?: "display unavailable")
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
        val releasedDisplayId = virtualDisplay?.display?.displayId
        runCatching { virtualDisplay?.setSurface(null) }
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
