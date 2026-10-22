package io.github.lujinxin.nextep.framework

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import android.view.SurfaceControl
import android.view.animation.DecelerateInterpolator
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.WorkspaceNavigationInsets
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflective SystemUI/WM Shell bridge for transforming a real task leash.
 *
 * The task remains fullscreen on display 0. Only its compositor leash is scaled and positioned,
 * matching the reference OneStep implementation and avoiding ColorOS freeform re-layout.
 */
object TaskSurfaceCompat {
    private data class SurfaceFrame(
        val scaleX: Float,
        val scaleY: Float,
        val positionX: Float,
        val positionY: Float,
        val crop: Rect? = null,
    )

    private data class ActivePresentation(
        val taskId: Int,
        val leash: Any,
        val scaleX: Float,
        val scaleY: Float,
        val positionX: Float,
        val positionY: Float,
        val reapplyUntil: Long,
        var lastAppliedAt: Long,
        val crop: Rect? = null,
        @Volatile var frame: SurfaceFrame = SurfaceFrame(scaleX, scaleY, positionX, positionY, crop),
    )

    private const val SYSTEM_UI_FACTORY =
        "com.android.systemui.SystemUIAppComponentFactoryBase"
    private const val INITIALIZER_FIELD = "systemUIInitializer"
    private const val ORGANIZER_PROVIDER_FIELD = "provideShellTaskOrganizerProvider"
    private const val TASKS_FIELD = "mTasks"
    private const val LOCK_FIELD = "mLock"

    @Volatile
    private var hostClassLoader: ClassLoader? = null
    @Volatile
    private var activityManager: ActivityManager? = null
    private val activePresentations = ConcurrentHashMap<Int, ActivePresentation>()
    private val preparedPresentations = ConcurrentHashMap<Int, ActivePresentation>()
    private data class SlotSurface(val displayId: Int, val leash: Any, val crop: Rect) {
        var lastAppliedAt = 0L
        val frame = SurfaceFrame(1f, 1f, 0f, 0f, crop)
    }
    private val slotSurfaces = ConcurrentHashMap<Int, SlotSurface>()
    private val displayExchanges = ConcurrentHashMap<Int, Long>()
    private val exchangeDisplays = ConcurrentHashMap<Int, Int>()
    private val observedStates = ConcurrentHashMap<Int, TaskInfoCompat.WindowState>()
    private val observedLeashes = ConcurrentHashMap<Int, Any>()
    private data class MethodKey(val type: Class<*>, val name: String, val arguments: List<Class<*>?>)
    private val methodCache = ConcurrentHashMap<MethodKey, Method>()
    private val cropMethods = ConcurrentHashMap<Class<*>, Method>()
    private val presentationAnimators = ConcurrentHashMap<Int, ValueAnimator>()
    private val transactionGuard = SurfaceTransactionGuard<Any>(
        appendOwnedTransforms = ::appendOwnedTransforms,
        shouldCompose = { activePresentations.isNotEmpty() || preparedPresentations.isNotEmpty() || slotSurfaces.isNotEmpty() },
        onFailure = { NeXtepLog.warn("task_surface", "Native transaction fitting failed open", it) },
    )

    fun <R> interceptTransactionApply(transaction: Any, proceed: () -> R): R =
        transactionGuard.intercept(transaction, proceed)

    fun initialize(classLoader: ClassLoader) {
        hostClassLoader = classLoader
        NeXtepLog.info("task_surface", "Host class loader captured: $classLoader")
    }

    fun initializeTaskAccess(context: Context) {
        activityManager = context.getSystemService(ActivityManager::class.java)
    }

    fun observeTaskInfo(info: ActivityManager.RunningTaskInfo, leash: Any? = null) {
        TaskInfoCompat.readWindowState(info)?.let { observeTaskState(info.taskId, it) }
        if (leash != null) observedLeashes[info.taskId] = leash
    }

    fun observeTaskState(taskId: Int, state: TaskInfoCompat.WindowState) {
        observedStates[taskId] = state
        slotSurfaces[taskId]?.let { slot ->
            if (slot.displayId != state.displayId || state.vendorWindowed ||
                state.windowingMode != WINDOWING_MODE_FULLSCREEN
            ) slotSurfaces.remove(taskId, slot)
        }
    }

    fun forgetTaskSurface(taskId: Int) {
        observedStates.remove(taskId)
        observedLeashes.remove(taskId)
        slotSurfaces.remove(taskId)
        // An OEM reparent may vanish/reappear with a replacement leash. Preserve
        // the target transform until reapply binds that leash or the owner restores
        // the task. Missing observed ownership prevents commits to the old surface.
    }

    /** Slot tasks render in display-local coordinates. Moving a fitted main task
     * must clear its matrix and position as well as its physical-display crop. */
    fun fitSlotSurface(taskId: Int, displayId: Int, crop: Rect,
                       finishExchange: Boolean = false): Result<Boolean> = runCatching {
        require(displayId != Display.DEFAULT_DISPLAY)
        // Do not overwrite the outgoing display-0 snapshot before Shell's
        // reparent. Settlement admits fitting only after destination metadata is ready;
        // the recent-exchange marker remains available to Shell's finish policy.
        if (isDisplayExchanging(taskId) &&
            (!finishExchange || exchangeDisplays[taskId] != displayId)
        ) return@runCatching false
        val observed = observedStates[taskId]
        val cached = slotSurfaces[taskId]?.takeIf {
            it.displayId == displayId && it.crop == crop && observed?.displayId == displayId &&
                observedLeashes[taskId] === it.leash && isValid(it.leash)
        }
        val slot = cached ?: run {
            val state = findLiveTaskState(taskId) ?: return@runCatching false
            if (state.displayId != displayId || state.vendorWindowed ||
                state.windowingMode != WINDOWING_MODE_FULLSCREEN
            ) return@runCatching false
            val leash = findTaskLeash(taskId, requireMainDisplay = false) ?: return@runCatching false
            if (!isValid(leash)) return@runCatching false
            SlotSurface(displayId, leash, Rect(crop)).also { slotSurfaces[taskId] = it }
        }
        val now = SystemClock.uptimeMillis()
        if (slot.lastAppliedAt != 0L && now - slot.lastAppliedAt < STEADY_REAPPLY_INTERVAL_MS) return@runCatching true
        transactionGuard.ownedTransaction {
            SurfaceControl.Transaction().use { transaction ->
                writeFrame(transaction, slot.leash, slot.frame)
                transaction.apply()
                slot.lastAppliedAt = now
            }
        }
        true
    }.onFailure { NeXtepLog.warn("slot_surface", "Unable to restore slot coordinates taskId=$taskId", it) }

    /** Reserve fitting before a display move, without touching the small-window surface. */
    fun preparePromotion(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> = runCatching {
        val leash = observedLeashes[taskId]?.takeIf(::isValid)
            ?: findTaskLeash(taskId, requireMainDisplay = false)
            ?: error("WM Shell task leash unavailable for promotion taskId=$taskId")
        check(isValid(leash)) { "Invalid promotion leash taskId=$taskId" }
        preparedPresentations[taskId] = ActivePresentation(
            taskId, leash,
            geometry.contentScale, geometry.contentScale,
            geometry.contentTranslationX, geometry.contentTranslationY,
            SystemClock.uptimeMillis() + REAPPLY_WINDOW_MS, 0L,
            crop = sourceCrop(geometry),
        )
    }

    fun cancelPreparedPromotion(taskId: Int) {
        preparedPresentations.remove(taskId)
    }

    fun hasPreparedPromotion(taskId: Int): Boolean = preparedPresentations.containsKey(taskId)

    fun markDisplayExchange(taskId: Int, destinationDisplayId: Int) {
        // A new move revokes the former slot transform before Shell reparents it.
        slotSurfaces.remove(taskId)
        val now = SystemClock.uptimeMillis()
        displayExchanges.entries.removeIf {
            if (it.value >= now) false else {
                exchangeDisplays.remove(it.key)
                true
            }
        }
        exchangeDisplays[taskId] = destinationDisplayId
        displayExchanges[taskId] = now + REAPPLY_WINDOW_MS
    }

    fun isDisplayExchanging(taskId: Int): Boolean =
        (displayExchanges[taskId] ?: 0L) >= SystemClock.uptimeMillis()

    /** ColorOS may omit the parked task from its HOME/display transition. */
    fun hasRecentSlotMove(): Boolean = exchangeDisplays.any { (taskId, destination) ->
        destination != Display.DEFAULT_DISPLAY && isDisplayExchanging(taskId)
    }

    fun cancelDisplayExchange(taskId: Int) {
        displayExchanges.remove(taskId)
        exchangeDisplays.remove(taskId)
        cancelPreparedPromotion(taskId)
    }

    /** Transient exchange preview, using only this task's layers. Secure layers
     * remain excluded by ScreenCapture's defaults; failure just omits the preview. */
    fun capturePreview(taskId: Int): android.graphics.Bitmap? = runCatching {
        val leash = findTaskLeash(taskId, requireMainDisplay = false) ?: return@runCatching null
        val surfaceType = Class.forName("android.view.SurfaceControl")
        val captureType = Class.forName("android.window.ScreenCaptureInternal")
        val builderType = Class.forName("android.window.ScreenCaptureInternal\$LayerCaptureArgs\$Builder")
        val builder = builderType.getConstructor(surfaceType).newInstance(leash)
        builderType.getMethod("setFrameScale", Float::class.javaPrimitiveType).invoke(builder, 0.5f)
        builderType.getMethod("setChildrenOnly", Boolean::class.javaPrimitiveType).invoke(builder, true)
        val args = builderType.getMethod("build").invoke(builder)
        val buffer = captureType.methods.firstOrNull {
            it.name == "captureLayers" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(args)
        }?.invoke(null, args) ?: return@runCatching null
        val bitmap = buffer.javaClass.getMethod("asBitmap").invoke(buffer) as? android.graphics.Bitmap
            ?: return@runCatching null
        try { bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }
        finally { bitmap.recycle() }
    }.onFailure { NeXtepLog.warn("task_preview", "Task preview unavailable taskId=$taskId", it) }.getOrNull()

    /** Task-list delivery can precede Shell's surface callback on a cold launch. */
    fun hasPresentableSurface(taskId: Int, requireMainDisplay: Boolean = true): Boolean =
        findTaskLeash(taskId, requireMainDisplay)?.let(::isValid) == true

    fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> = runCatching {
        require(taskId > 0) { "Invalid taskId=$taskId" }
        // Recovery polling must not restart the entry fade on an already presented task.
        if (activePresentations.containsKey(taskId)) {
            reapply(taskId, geometry).getOrThrow()
            return@runCatching
        }
        val leash = findTaskLeash(taskId)
            ?: error("WM Shell task leash unavailable for taskId=$taskId")
        check(isValid(leash)) { "WM Shell task leash is invalid for taskId=$taskId" }

        val scaleX = geometry.contentScale
        val scaleY = scaleX
        val now = SystemClock.uptimeMillis()
        val presentation = ActivePresentation(
            taskId = taskId,
            leash = leash,
            scaleX = scaleX,
            scaleY = scaleY,
            positionX = geometry.contentTranslationX,
            positionY = geometry.contentTranslationY,
            reapplyUntil = now + REAPPLY_WINDOW_MS,
            lastAppliedAt = now,
            crop = sourceCrop(geometry),
        )
        val prepared = preparedPresentations[taskId]
        activePresentations[taskId] = presentation
        preparedPresentations.remove(taskId)
        if (prepared != null || geometry.navigationInsets != WorkspaceNavigationInsets()) {
            // Promotion and recorded-button cold launches fit their native first
            // frame already. Do not shrink it again with a second entry zoom.
            applyPresentation(presentation)
        } else animatePresentation(
            // Shrink from the full-screen task the user is looking at, in step with the panels.
            taskId = taskId,
            from = presentation.copy(scaleX = 1f, scaleY = 1f, positionX = 0f, positionY = 0f),
            to = presentation,
            fadeIn = true,
        )
        NeXtepLog.info(
            "task_surface",
            "Presented taskId=$taskId scale=$scaleX,$scaleY " +
                "position=${geometry.contentLeft},${geometry.contentTop}",
        )
    }.onFailure { error ->
        NeXtepLog.error("task_surface", "Presentation failed taskId=$taskId", error)
    }

    fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> = runCatching {
        val previous = activePresentations[taskId]
            ?: error("No active surface presentation for taskId=$taskId")
        val now = SystemClock.uptimeMillis()
        val scaleX = geometry.contentScale
        val scaleY = scaleX
        val crop = sourceCrop(geometry)
        val geometryChanged = previous.scaleX != scaleX ||
            previous.scaleY != scaleY ||
            previous.positionX != geometry.contentTranslationX ||
            previous.positionY != geometry.contentTranslationY || previous.crop != crop
        if (!geometryChanged && now - previous.lastAppliedAt < MIN_REAPPLY_INTERVAL_MS) {
            return@runCatching
        }
        if (!geometryChanged && now > previous.reapplyUntil &&
            now - previous.lastAppliedAt < STEADY_REAPPLY_INTERVAL_MS
        ) {
            return@runCatching
        }
        val currentLeash = findTaskLeash(taskId)
            ?: error("WM Shell task leash unavailable for taskId=$taskId")
        check(isValid(currentLeash)) {
            "WM Shell task leash is invalid for taskId=$taskId"
        }
        val leashChanged = currentLeash !== previous.leash
        val presentation = if (leashChanged || geometryChanged) {
            ActivePresentation(
                taskId = taskId,
                leash = currentLeash,
                scaleX = scaleX,
                scaleY = scaleY,
                positionX = geometry.contentTranslationX,
                positionY = geometry.contentTranslationY,
                reapplyUntil = now + REAPPLY_WINDOW_MS,
                lastAppliedAt = 0L,
                crop = crop,
            ).also { activePresentations[taskId] = it }
        } else {
            previous
        }
        if (leashChanged) {
            // A replaced leash must never be overwritten by an older animator's final frame.
            cancelPresentationAnimation(taskId)
            applyPresentation(presentation)
        } else if (geometryChanged) {
            animatePresentation(taskId, previous, presentation, fadeIn = false)
        } else if (presentationAnimators[taskId]?.isRunning != true) {
            applyPresentation(presentation)
        }
        presentation.lastAppliedAt = now
        if (leashChanged) {
            NeXtepLog.info("task_surface", "Rebound changed leash taskId=$taskId")
        }
    }.onFailure { error ->
        NeXtepLog.error("task_surface", "Reapply failed taskId=$taskId", error)
    }

    fun restore(taskId: Int, animated: Boolean = false): Result<Unit> = runCatching {
        // Remove ownership before cancellation so no final frame can revive the transform.
        val pending = preparedPresentations.remove(taskId)
        val previous = activePresentations.remove(taskId)
        val leash = previous?.leash ?: pending?.leash ?: return@runCatching
        cancelPresentationAnimation(taskId)
        if (!isValid(leash)) {
            NeXtepLog.warn(
                "task_surface",
                "Restore skipped because task leash is gone taskId=$taskId",
            )
            return@runCatching
        }
        if (animated && previous != null) {
            // Grow back to full screen over the panel fade; ownership ends with the last frame.
            val now = SystemClock.uptimeMillis()
            val full = ActivePresentation(taskId, leash, 1f, 1f, 0f, 0f, now, now, crop = null)
            activePresentations[taskId] = full
            animatePresentation(taskId, previous, full, fadeIn = false, durationMs = RESTORE_DURATION_MS) {
                if (activePresentations[taskId] === full) activePresentations.remove(taskId)
            }
            NeXtepLog.info("task_surface", "Restoring taskId=$taskId animated")
            return@runCatching
        }
        applyPresentation(
            taskId = taskId,
            leash = leash,
            scaleX = 1f,
            scaleY = 1f,
            positionX = 0f,
            positionY = 0f,
        )
        NeXtepLog.info("task_surface", "Restored taskId=$taskId")
    }.onFailure { error ->
        NeXtepLog.error("task_surface", "Restore failed taskId=$taskId", error)
    }

    private fun applyPresentation(presentation: ActivePresentation) {
        applyPresentation(
            taskId = presentation.taskId,
            leash = presentation.leash,
            scaleX = presentation.scaleX,
            scaleY = presentation.scaleY,
            positionX = presentation.positionX,
            positionY = presentation.positionY,
            crop = presentation.crop,
        )
    }

    private fun animatePresentation(
        taskId: Int,
        from: ActivePresentation,
        to: ActivePresentation,
        fadeIn: Boolean,
        durationMs: Long = if (fadeIn) PRESENT_DURATION_MS else REPOSITION_DURATION_MS,
        onEnd: () -> Unit = {},
    ) {
        cancelPresentationAnimation(taskId)
        applyPresentation(
            taskId = taskId,
            leash = to.leash,
            scaleX = from.scaleX,
            scaleY = from.scaleY,
            positionX = from.positionX,
            positionY = from.positionY,
            crop = to.crop,
        )
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = durationMs
        animator.interpolator = DecelerateInterpolator(1.6f)
        animator.addUpdateListener { valueAnimator ->
            if (activePresentations[taskId] !== to) {
                cancelPresentationAnimation(taskId)
                return@addUpdateListener
            }
            val progress = valueAnimator.animatedValue as Float
            runCatching {
                applyPresentation(
                    taskId = taskId,
                    leash = to.leash,
                    scaleX = lerp(from.scaleX, to.scaleX, progress),
                    scaleY = lerp(from.scaleY, to.scaleY, progress),
                    positionX = lerp(from.positionX, to.positionX, progress),
                    positionY = lerp(from.positionY, to.positionY, progress),
                    crop = to.crop,
                )
            }.onFailure { error ->
                valueAnimator.cancel()
                NeXtepLog.warn(
                    "task_surface_animation",
                    "Frame apply failed taskId=$taskId",
                    error,
                )
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (presentationAnimators.remove(taskId, animator) &&
                    activePresentations[taskId] === to
                ) {
                    runCatching { applyPresentation(to) }.onFailure { error ->
                        NeXtepLog.warn(
                            "task_surface_animation",
                            "Final frame apply failed taskId=$taskId",
                            error,
                        )
                    }
                    onEnd()
                }
            }
        })
        presentationAnimators[taskId] = animator
        animator.start()
    }

    private fun cancelPresentationAnimation(taskId: Int) {
        presentationAnimators.remove(taskId)?.cancel()
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    private fun applyPresentation(
        taskId: Int,
        leash: Any,
        scaleX: Float,
        scaleY: Float,
        positionX: Float,
        positionY: Float,
        crop: Rect? = null,
    ) {
        // Frame commits use observed ownership, never Binder or the organizer lock.
        if (ownedTaskLeash(taskId) !== leash) return
        val frame = SurfaceFrame(scaleX, scaleY, positionX, positionY, crop)
        activePresentations[taskId]?.takeIf { it.leash === leash }?.frame = frame
        transactionGuard.ownedTransaction {
            SurfaceControl.Transaction().use { transaction ->
                writeFrame(transaction, leash, frame)
                transaction.apply()
            }
        }
    }

    private fun appendOwnedTransforms(transaction: Any) {
        // WM Shell's animation and finish transactions can be queued before presentation.
        // Fit at commit, after their writes, so no identity-sized frame reaches the display
        // between polling corrections. Use the current animation frame, not its final target.
        activePresentations.values.forEach { presentation ->
            if (activePresentations[presentation.taskId] !== presentation ||
                ownedTaskLeash(presentation.taskId) !== presentation.leash ||
                !isValid(presentation.leash)
            ) return@forEach
            writeFrame(transaction, presentation.leash, presentation.frame)
        }
        preparedPresentations.values.forEach { presentation ->
            if (SystemClock.uptimeMillis() > presentation.reapplyUntil) {
                preparedPresentations.remove(presentation.taskId, presentation)
                return@forEach
            }
            // Never scale its virtual-display surface. The live state changes before
            // WM Shell submits the start transaction that makes the promoted task visible.
            if (preparedPresentations[presentation.taskId] !== presentation ||
                ownedTaskLeash(presentation.taskId) !== presentation.leash ||
                !isValid(presentation.leash)
            ) return@forEach
            writeFrame(transaction, presentation.leash, presentation.frame)
        }
        slotSurfaces.forEach { (taskId, slot) ->
            val state = observedStates[taskId] ?: return@forEach
            if (slotSurfaces[taskId] != slot || state.displayId != slot.displayId ||
                state.vendorWindowed || state.windowingMode != WINDOWING_MODE_FULLSCREEN ||
                observedLeashes[taskId] !== slot.leash || !isValid(slot.leash) ||
                (isDisplayExchanging(taskId) && exchangeDisplays[taskId] != slot.displayId)
            ) return@forEach
            // A queued Shell finish may still contain the former main transform.
            // Preserve its visibility/layer writes, but commit local slot coordinates.
            writeFrame(transaction, slot.leash, slot.frame)
        }
    }

    private fun ownedTaskLeash(taskId: Int): Any? {
        val state = observedStates[taskId] ?: return null
        if (state.vendorWindowed || state.displayId != Display.DEFAULT_DISPLAY ||
            state.windowingMode != WINDOWING_MODE_FULLSCREEN ||
            (isDisplayExchanging(taskId) && exchangeDisplays[taskId] != Display.DEFAULT_DISPLAY)
        ) return null
        return observedLeashes[taskId]
    }

    private fun writeFrame(transaction: Any, leash: Any, frame: SurfaceFrame) {
        val nativeTransaction = transaction as SurfaceControl.Transaction
        val surface = leash as SurfaceControl
        // Keep native visibility, fade, layer order and parenting intact.
        nativeTransaction.setCrop(surface, frame.crop)
            .setScale(surface, frame.scaleX, frame.scaleY)
            .setPosition(surface, frame.positionX, frame.positionY)
    }

    private fun sourceCrop(geometry: WorkspaceGeometry) = Rect(
        geometry.availableLeft, geometry.availableTop, geometry.availableRight, geometry.availableBottom,
    )

    private fun setCrop(transaction: Any, leash: Any, crop: Rect?) {
        val candidate = cropMethods[transaction.javaClass] ?: allMethods(transaction.javaClass).firstOrNull { method ->
            method.name in setOf("setWindowCrop", "setCrop") &&
                method.parameterTypes.size == 2 &&
                !method.parameterTypes[1].isPrimitive
        }?.also { cropMethods[transaction.javaClass] = it } ?: return
        runCatching {
            candidate.isAccessible = true
            candidate.invoke(transaction, leash, crop)
        }.onFailure { error ->
            NeXtepLog.warn("task_surface", "Unable to set task crop", error)
        }
    }

    private fun findTaskLeash(taskId: Int, requireMainDisplay: Boolean = true): Any? = runCatching {
        val loader = hostClassLoader ?: error("SystemUI host class loader is unavailable")
        val factoryClass = loader.loadClass(SYSTEM_UI_FACTORY)
        val initializer = findField(factoryClass, INITIALIZER_FIELD).get(null)
            ?: error("SystemUI initializer is null")
        val wmComponent = invokeRequired(initializer, "getWMComponent")
            ?: error("SystemUI WM component is null")
        val organizer = resolveTaskOrganizer(wmComponent)
            ?: error("ShellTaskOrganizer provider is unavailable")
        val tasks = findField(organizer.javaClass, TASKS_FIELD).get(organizer)
            ?: error("ShellTaskOrganizer task map is null")
        val lock = allFields(organizer.javaClass)
            .firstOrNull { it.name == LOCK_FIELD }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(organizer)
                }.getOrNull()
            }
            ?: organizer
        val appearedInfo = synchronized(lock) {
            invokeRequired(tasks, "get", taskId)
        } ?: error("Task $taskId is absent from ShellTaskOrganizer")
        // Shell's TaskAppearedInfo is delivered asynchronously. After a display move its
        // cached TaskInfo can still describe the slot, causing a successful promotion to
        // roll back. The same cache can also outlive a move into an OEM floating window.
        // Bootstrap with a live query; commits use callback and coordinator observations.
        if (requireMainDisplay) {
            val state = findLiveTaskState(taskId) ?: return@runCatching null
            if (state.vendorWindowed || state.displayId != Display.DEFAULT_DISPLAY ||
                state.windowingMode != WINDOWING_MODE_FULLSCREEN
            ) {
                return@runCatching null
            }
        }
        val leash = invokeRequired(appearedInfo, "getLeash")
            ?: error("Task $taskId leash is null")
        observedLeashes[taskId] = leash
        leash
    }.onFailure { error ->
        NeXtepLog.warn("task_surface", "Task leash lookup failed taskId=$taskId", error)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun findLiveTaskState(taskId: Int): TaskInfoCompat.WindowState? {
        val manager = checkNotNull(activityManager) { "Live task access is unavailable" }
        val taskInfo = manager.getRunningTasks(MAX_TASK_QUERY).firstOrNull { it.taskId == taskId }
            ?: return null
        return TaskInfoCompat.readWindowState(taskInfo)?.also { observeTaskState(taskId, it) }
    }

    private fun resolveTaskOrganizer(wmComponent: Any): Any? {
        runCatching {
            val provider = findField(wmComponent.javaClass, ORGANIZER_PROVIDER_FIELD)
                .get(wmComponent)
            val organizer = provider?.let { invokeRequired(it, "get") }
            if (organizer != null && hasField(organizer.javaClass, TASKS_FIELD)) {
                return organizer
            }
        }

        allFields(wmComponent.javaClass).forEach { field ->
            val value = runCatching {
                field.isAccessible = true
                field.get(wmComponent)
            }.getOrNull() ?: return@forEach
            if (hasField(value.javaClass, TASKS_FIELD)) return value
            if (field.name.contains("TaskOrganizer", ignoreCase = true) ||
                value.javaClass.name.contains("Provider", ignoreCase = true)
            ) {
                val provided = runCatching { invokeRequired(value, "get") }.getOrNull()
                if (provided != null && hasField(provided.javaClass, TASKS_FIELD)) {
                    return provided
                }
            }
        }

        allMethods(wmComponent.javaClass)
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.name.contains("TaskOrganizer", ignoreCase = true)
            }
            .forEach { method ->
                val organizer = runCatching {
                    method.isAccessible = true
                    method.invoke(wmComponent)
                }.getOrNull()
                if (organizer != null && hasField(organizer.javaClass, TASKS_FIELD)) {
                    return organizer
                }
            }
        return null
    }

    private fun isValid(leash: Any): Boolean =
        (leash as? SurfaceControl)?.isValid == true

    private fun invokeRequired(target: Any, name: String, vararg arguments: Any?): Any? {
        val method = findCompatibleMethod(target.javaClass, name, arguments)
            ?: error("No compatible ${target.javaClass.name}#$name candidate")
        method.isAccessible = true
        return method.invoke(target, *arguments)
    }

    private fun invokeOptional(target: Any, name: String, vararg arguments: Any?): Boolean {
        val method = findCompatibleMethod(target.javaClass, name, arguments) ?: return false
        return runCatching {
            method.isAccessible = true
            method.invoke(target, *arguments)
        }.isSuccess
    }

    private fun findCompatibleMethod(
        startType: Class<*>,
        name: String,
        arguments: Array<out Any?>,
    ): Method? {
        val key = MethodKey(startType, name, arguments.map { it?.javaClass })
        methodCache[key]?.let { return it }
        return allMethods(startType).firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterTypes.size == arguments.size &&
                candidate.parameterTypes.indices.all { index ->
                    val argument = arguments[index]
                    if (argument == null) {
                        !candidate.parameterTypes[index].isPrimitive
                    } else {
                        wraps(candidate.parameterTypes[index]).isAssignableFrom(argument.javaClass)
                    }
                }
        }?.also { methodCache[key] = it }
    }

    private fun findField(type: Class<*>, name: String): Field =
        allFields(type).firstOrNull { it.name == name }?.apply { isAccessible = true }
            ?: error("No field ${type.name}#$name")

    private fun hasField(type: Class<*>, name: String): Boolean =
        allFields(type).any { it.name == name }

    private fun allFields(startType: Class<*>): Sequence<Field> = sequence {
        var type: Class<*>? = startType
        while (type != null && type != Any::class.java) {
            yieldAll(type.declaredFields.asSequence())
            type = type.superclass
        }
    }

    private fun allMethods(startType: Class<*>): Sequence<Method> = sequence {
        var type: Class<*>? = startType
        while (type != null && type != Any::class.java) {
            yieldAll(type.declaredMethods.asSequence())
            type = type.superclass
        }
    }

    private fun wraps(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> type
    }

    private const val REAPPLY_WINDOW_MS = 2_000L
    private const val MAX_TASK_QUERY = 64
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val MIN_REAPPLY_INTERVAL_MS = 120L
    private const val STEADY_REAPPLY_INTERVAL_MS = 900L
    private const val PRESENT_DURATION_MS = 240L
    private const val REPOSITION_DURATION_MS = 260L
    private const val RESTORE_DURATION_MS = 180L
}
