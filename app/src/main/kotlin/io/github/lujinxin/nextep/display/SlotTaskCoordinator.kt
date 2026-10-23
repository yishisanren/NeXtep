package io.github.lujinxin.nextep.display

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.framework.UserTargetedActivityLauncher
import io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.task.TaskRepository
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.RecentTaskSelection
import io.github.lujinxin.nextep.workspace.RecentTaskSelectionContract
import io.github.lujinxin.nextep.workspace.SelectedRecentTask
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry

class SlotTaskCoordinator(
    context: Context,
    private val slots: List<VirtualDisplaySlot>,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val geometryProvider: () -> WorkspaceGeometry,
    private val onDragActiveChanged: (Boolean) -> Unit = {},
    private val onSwapAnimation: (SlotTaskDrag, Int?, (() -> Unit) -> Unit) -> Unit = { _, _, move -> move {} },
    private val onInternalSlotDrag: (TaskSwitcherView, SlotTaskDrag) -> Boolean = { _, _ -> false },
    private val onInternalDragTouch: (android.view.View, android.view.MotionEvent) -> Boolean = { _, _ -> false },
    private val onTasksObserved: (List<TaskRepository.TaskSnapshot>, List<SlotState>) -> Unit = { _, _ -> },
    private val onContentDragStarted: () -> Unit = {},
    private val onContentDragEntered: (Int) -> Unit = {},
    private val onContentDragEnded: (Boolean) -> Unit = {},
) : VirtualDisplaySlot.Listener, TaskSwitcherView.Listener {
    internal data class ContentExchange(val slot: Int, val sourceTask: Int, val targetTask: Int)

    internal fun contentExchangeTarget(index: Int): ContentExchange? {
        if (!validActiveSlot(index) || transitionInProgress || recentSelectionPending || appLaunch.running) return null
        val side = slotRecords[index].state as? SlotState.Occupied ?: return null
        val main = taskRepository.foregroundTask()?.takeIf(::isExchangeableMainTask) ?: return null
        return ContentExchange(index, main.taskId, side.taskId)
    }

    internal fun exchangeContent(target: ContentExchange, restoring: Boolean,
                                 complete: (Result<Unit>) -> Unit): Boolean {
        val expected = contentExchangeTarget(target.slot) ?: return false
        val source = if (restoring) target.targetTask else target.sourceTask
        val destination = if (restoring) target.sourceTask else target.targetTask
        if (expected.sourceTask != source || expected.targetTask != destination) return false
        return runTransition("content exchange restoring=$restoring", completed = complete) {
            swapWithMain(target.slot, slotRecords[target.slot].state as SlotState.Occupied)
        }.isSuccess
    }

    override fun onContentDragStarted() = onContentDragStarted.invoke()
    override fun onContentDragEntered(slotIndex: Int) = onContentDragEntered.invoke(slotIndex)
    override fun onContentDragEnded(accepted: Boolean) = onContentDragEnded.invoke(accepted)
    private data class SlotRecord(
        var state: SlotState = SlotState.Empty,
        var retainedTaskId: Int? = null,
        var exitTask: TaskRepository.RetainedTask? = null,
        var pendingComponent: ComponentName? = null,
        var pendingAttempts: Int = 0,
        var originalLayout: TaskLayout? = null,
        var mismatchSince: Long = 0L,
        var migrationRecoveryComponent: ComponentName? = null,
        var migrationRecoveryDeadline: Long = 0L,
        var migrationRecoveryAttempts: Int = 0,
    )

    private data class TaskLayout(
        val bounds: Rect,
        val densityDpi: Int,
        val windowingMode: Int,
        val resizeMode: Int?,
        val vendorWindowed: Boolean = false,
    )

    private val applicationContext = context.applicationContext ?: context
    private val packageManager = applicationContext.packageManager
    private val taskRepository = TaskRepository(applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val settlement = io.github.lujinxin.nextep.workspace.WorkspaceSettlement(
        schedule = { delay, action -> handler.postDelayed({ action() }, delay) },
        now = SystemClock::uptimeMillis,
    )
    private data class DeferredMove(val ready: () -> Boolean, val rollback: () -> Unit)
    private var deferredMove: DeferredMove? = null
    private val slotRecords = MutableList(slots.size) { SlotRecord() }
    private data class LaunchRequest(
        val intent: Intent, var slot: Int?, var displayId: Int?,
        val search: Boolean = false, val emptyOnly: Boolean = false,
    ) {
        var observed: TaskRepository.TaskSnapshot? = null
        val searchReadiness = io.github.lujinxin.nextep.workspace.WorkspaceSearchReadiness()
        var existingTasks: Set<Int> = emptySet()
        val routedTasks = mutableSetOf<Int>()
        var redirectedTask: Int? = null
    }
    private val desktopSelection = io.github.lujinxin.nextep.workspace.WorkspaceSlotSelection()
    private var desktopParkedTask: Int? = null
    val selectingDesktopApp: Boolean get() = desktopSelection.target != null
    val launchingApps: Boolean get() = appLaunch.running
    val movingTasks: Boolean get() = transitionInProgress
    private val appLaunch = io.github.lujinxin.nextep.workspace.WorkspaceLaunchSequence<LaunchRequest>(
        schedule = { delay, action -> handler.postDelayed({ action() }, delay) },
        start = ::startAppRequest, ready = ::observeAppRequest, commit = ::commitAppRequest,
        prepare = ::prepareAppRequests,
        busy = { busy ->
            slots.forEach { it.view.setBusy(busy || suspended || recentSelectionPending || transitionInProgress) }
            if (!busy && active && !suspended) {
                reconcile("application launch finished")
                dispatchPendingSelection()
            }
        },
    )

    fun cancelApplicationLaunch() = appLaunch.cancel()

    private fun clearDesktopSelection() {
        desktopSelection.target?.index?.let { index ->
            slots[index].view.setDropHighlighted(false)
            slots[index].view.showState(slotRecords[index].state)
        }
        desktopSelection.clear()
        desktopParkedTask = null
    }

    private fun beginDesktopSelection(index: Int, displayId: Int?) {
        val same = desktopSelection.target?.index == index
        clearDesktopSelection()
        if (same) return
        val target = desktopSelection.toggle(index, displayId)
        try {
            fun selectFromHome() {
                if (!active || suspended || desktopSelection.target != target) return
                showHomeOnDefaultDisplay()
                slots[index].view.setDropHighlighted(true)
                showMessage(AppLanguage.string(applicationContext, R.string.workbench_choose_main_app))
                handler.postDelayed({ if (desktopSelection.target == target) clearDesktopSelection() }, 120_000L)
            }
            val main = taskRepository.foregroundTask()?.takeIf(::isExchangeableMainTask)
            if (main != null) {
                val ready = slotRecords[index].state as? SlotState.Ready
                    ?: error("Destination is not ready; retaining main app")
                desktopParkedTask = main.taskId
                runTransition("preserve main for desktop selection slot=$index", completed = { result ->
                    if (result.isSuccess) runCatching { selectFromHome() }.onFailure {
                        clearDesktopSelection()
                        NeXtepLog.warn("slot_picker", "Unable to show HOME after preserving main", it)
                    } else clearDesktopSelection()
                }) { parkMainTaskInSlot(index, ready, main) }.getOrThrow()
            } else selectFromHome()
        } catch (error: Throwable) {
            clearDesktopSelection()
            showMessage(AppLanguage.string(applicationContext, R.string.workbench_launch_failed))
            NeXtepLog.warn("slot_picker", "Unable to show HOME", error)
        }
    }

    private fun startAppRequest(request: LaunchRequest) {
        check(active && !suspended)
        mainSelectionGeneration++
        if (request.search) request.existingTasks = taskRepository.runningTasks().map { it.taskId }.toSet()
        val index = request.slot
        if (index != null) {
            check(slots[index].displayId() == request.displayId) { "Slot display changed" }
            if (request.search) replaceSearchDestination(index, request.intent)
            // Reuse the original direct-to-display path. A new side-window app
            // must never depend on first becoming a presentable main-screen task.
            assignIntent(index, request.intent, external = request.search).getOrThrow()
            return
        }
        val component = checkNotNull(request.intent.component)
        val existing = taskRepository.findTaskForComponent(component,
            request.intent.targetUser() ?: android.os.Process.myUserHandle())
        if (existing != null && existing.displayId != Display.DEFAULT_DISPLAY) promoteExistingToMain(existing)
        val options = android.app.ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
        UserTargetedActivityLauncher.start(applicationContext, request.intent, options.toBundle()).getOrThrow()
        NeXtepLog.info("app_launch", "Started package=" + component.packageName + " slot=" + request.slot)
    }

    private fun observeAppRequest(request: LaunchRequest): Boolean {
        check(active && !suspended)
        fun searchReady(task: TaskRepository.TaskSnapshot?): Boolean {
            if (!request.search) return true
            return request.searchReadiness.observe(task?.taskId, task?.component?.className,
                task != null && TaskSurfaceCompat.hasPresentableSurface(task.taskId, requireMainDisplay = request.slot == null),
                SystemClock.uptimeMillis(), request.intent.data?.host == "s.taobao.com")
        }
        val index = request.slot
        if (index != null) {
            check(slots[index].displayId() == request.displayId) { "Slot display changed" }
            val user = io.github.lujinxin.nextep.framework.TaskInfoCompat.userIdentifier(
                request.intent.targetUser() ?: android.os.Process.myUserHandle())
            val tasks = taskRepository.runningTasks()
            val task = tasks.firstOrNull { it.displayId == request.displayId }
            if (request.search) {
                task?.takeIf { it.component.packageName == request.intent.component?.packageName && it.userId == user }
                    ?.let { request.routedTasks.add(it.taskId) }
                // ActivityOptions only targets the entry Activity. A router may
                // create its result as a new default-display task. Adopt only this
                // request's new/observed task, never an unrelated existing app.
                val redirected = tasks.firstOrNull {
                    it.displayId == Display.DEFAULT_DISPLAY && it.userId == user && !it.vendorWindowed &&
                        it.component.packageName == request.intent.component?.packageName &&
                        request.searchReadiness.belongsToRequest(it.taskId, request.existingTasks, request.routedTasks)
                }
                if (redirected != null && request.redirectedTask != redirected.taskId) {
                    val canvas = checkNotNull(slots[index].geometry())
                    val display = checkNotNull(request.displayId)
                    mainTaskPresenter.restoreForMove(redirected.taskId).getOrThrow()
                    ActivityTaskManagerCompat.setTaskResizeable(redirected.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
                    TaskSurfaceCompat.markDisplayExchange(redirected.taskId, display)
                    WindowContainerTransactionCompat.moveTasks(listOf(WindowContainerTransactionCompat.TaskMove(
                        checkNotNull(redirected.token), display, Rect(), canvas.densityDpi, fullscreenWindowingMode(),
                    ))).getOrThrow()
                    if (slotRecords[index].originalLayout == null) slotRecords[index].originalLayout = redirected.toLayout()
                    slotRecords[index].retainedTaskId = redirected.taskId
                    request.redirectedTask = redirected.taskId
                    request.routedTasks.add(redirected.taskId)
                    searchReady(null)
                    NeXtepLog.info("app_launch", "Adopted routed result task=${redirected.taskId} slot=$index")
                    return false
                }
            }
            if (task == null) { searchReady(null); return false }
            if (task.displayId != request.displayId || task.userId != user ||
                task.component.packageName != request.intent.component?.packageName) {
                searchReady(null)
                return false
            }
            if (!searchReady(task)) return false
            reconcile("observe direct slot launch")
            val occupied = slotRecords[index].state as? SlotState.Occupied ?: return false
            if (occupied.taskId != task.taskId) return false
            request.observed = task
            return true
        }
        val foreground = taskRepository.foregroundTask() ?: run { searchReady(null); return false }
        val user = io.github.lujinxin.nextep.framework.TaskInfoCompat.userIdentifier(
            request.intent.targetUser() ?: android.os.Process.myUserHandle())
        if (foreground.component.packageName != request.intent.component?.packageName ||
            foreground.userId != user || !isExchangeableMainTask(foreground)) {
            searchReady(null)
            return false
        }
        if (!TaskSurfaceCompat.hasPresentableSurface(foreground.taskId)) {
            searchReady(null)
            return false
        }
        if (!searchReady(foreground)) return false
        request.observed = foreground
        return true
    }

    private fun commitAppRequest(request: LaunchRequest) {
        val task = checkNotNull(request.observed)
        val index = request.slot
        if (index == null) {
            mainTaskPresenter.presentTask(task.taskId, geometryProvider()).getOrThrow()
        }
        NeXtepLog.info("app_launch", "Task observed and placed slot=" + index + " task=" + task.taskId)
    }

    private fun launchApplicationInSlot(
        index: Int, source: Intent, expectedDisplay: Int? = slots.getOrNull(index)?.displayId(),
        emptyOnly: Boolean = false,
    ): Result<Unit> = runCatching {
        check(validActiveSlot(index) && !recentSelectionPending && !appLaunch.running)
        val normalized = checkNotNull(normalizeLaunchIntent(source)) { "No exported launcher Activity" }
        clearDesktopSelection()
        appLaunch.begin(listOf(LaunchRequest(normalized, index, expectedDisplay, emptyOnly = emptyOnly))) { result ->
            result.onFailure {
                if (it !is java.util.concurrent.CancellationException) {
                    NeXtepLog.warn("app_launch", "Unable to place application in slot=" + index, it)
                    showMessage(AppLanguage.string(applicationContext,
                        if (it is io.github.lujinxin.nextep.workspace.WorkspaceLaunchSequence.PreparationTimeout)
                            R.string.workbench_window_timeout else R.string.workbench_launch_failed))
                }
            }
        }.getOrThrow()
    }

    private fun searchKey(intent: Intent): String = checkNotNull(intent.component).packageName + "@" +
        io.github.lujinxin.nextep.framework.TaskInfoCompat.userIdentifier(
            intent.targetUser() ?: android.os.Process.myUserHandle())

    /** Surface creation/recovery is asynchronous. Reserve against the reconciled
     * owner, never convert a failed-but-occupied display into an empty destination. */
    private fun prepareAppRequests(requests: List<LaunchRequest>): Boolean {
        check(active && !suspended)
        if (transitionInProgress || recentSelectionPending) return false
        val search = requests.first().search
        val indices = if (search && requests.size > 1) slots.indices.toList() else requests.mapNotNull { it.slot }.distinct()
        indices.forEach { index -> if (slots[index].displayId() == null) slots[index].activate() }
        reconcile("prepare application destinations")
        if (search) {
            val first = requests.first().intent
            val task = taskRepository.findTaskForComponent(checkNotNull(first.component),
                first.targetUser() ?: android.os.Process.myUserHandle())
            val promoted = task?.let { found -> slotRecords.indexOfFirst {
                (it.state as? SlotState.Occupied)?.taskId == found.taskId
            }.takeIf { it >= 0 } }
            val destinations = try {
                io.github.lujinxin.nextep.workspace.WorkspaceSearchPlacement.plan(
                    requests.map { searchKey(it.intent) }, slotRecords.map { record ->
                        when (val state = record.state) {
                            is SlotState.Occupied -> state.component.packageName + "@" +
                                taskRepository.findTask(state.taskId)?.userId
                            is SlotState.Ready -> if (record.pendingComponent == null &&
                                record.retainedTaskId == null) null else "reserved"
                            else -> "unavailable"
                        }
                    }, promoted, slotRecords.indices.filter { index ->
                        val record = slotRecords[index]
                        record.pendingComponent != null ||
                            record.state !is SlotState.Occupied && record.state !is SlotState.Ready ||
                            record.state is SlotState.Ready && record.retainedTaskId != null
                    }.toSet(),
                )
            } catch (error: io.github.lujinxin.nextep.workspace.WorkspaceSearchPlacement.NoSpace) {
                if (slotRecords.any { it.state !is SlotState.Occupied &&
                    (it.state !is SlotState.Ready || it.pendingComponent != null || it.retainedTaskId != null) })
                    return false
                throw error
            }
            requests.forEachIndexed { index, request ->
                request.slot = if (index == 0) null else destinations[index - 1]
                request.displayId = request.slot?.let { checkNotNull(slots[it].displayId()) }
            }
        }
        return requests.all { request ->
            val index = request.slot ?: return@all true
            val display = slots[index].displayId() ?: return@all false
            check(request.displayId == null || request.displayId == display) { "Selected display changed" }
            val record = slotRecords[index]
            check(!request.emptyOnly || record.state !is SlotState.Occupied) { "Selected window now contains an app" }
            if (record.pendingComponent != null || record.state !is SlotState.Ready && record.state !is SlotState.Occupied ||
                record.state is SlotState.Ready && record.retainedTaskId != null) return@all false
            request.displayId = display
            true
        }
    }

    /** Preserve the old task in recents, below the main app. Never force-stop
     * or remove it just to make room for an explicitly requested search. */
    private fun replaceSearchDestination(index: Int, intent: Intent) {
        val display = checkNotNull(slots[index].displayId())
        val targetUser = io.github.lujinxin.nextep.framework.TaskInfoCompat.userIdentifier(
            intent.targetUser() ?: android.os.Process.myUserHandle())
        val tasks = taskRepository.runningTasks().filter { it.displayId == display }
        if (tasks.any { it.component.packageName == intent.component?.packageName && it.userId == targetUser }) return
        tasks.forEach { task ->
            val record = slotRecords[index]
            val layout = if (record.retainedTaskId == task.taskId) record.originalLayout else null
            val original = layout ?: defaultMainLayout(task.resizeMode)
            WindowContainerTransactionCompat.parkInDefaultDisplay(checkNotNull(task.token),
                original.defaultDisplayBounds(), original.densityDpi, original.windowingMode).getOrThrow()
            original.resizeMode?.let { ActivityTaskManagerCompat.setTaskResizeable(task.taskId, it).getOrThrow() }
        }
        check(taskRepository.runningTasks().none { it.displayId == display }) { "Previous slot task is still attached" }
        clearSlotAssignment(index)
        slotRecords[index].exitTask = null
        setState(index, SlotState.Ready(display), "search replaced previous window")
    }

    private var active = false
    private var suspended = false
    private var transitionInProgress = false
    private var recentSelectionPending = false
    private var recentSelectionGeneration = 0
    private var activeDrag: SlotTaskDrag? = null
    private var dragTransitionPending = false
    private var reconciliationTick = 0
    private var pendingSelection: (() -> Unit)? = null
    private var mainSelectionGeneration = 0
    private var promotedRecoveryGeneration = 0
    private var promotedRecoveryComponent: ComponentName? = null
    private var promotedRecoveryDeadline = 0L
    private var promotedRecoveryAttempts = 0

    private var reconcilePaused = false

    /** Stop foreground fitting while the workspace exits; the deferred deactivate follows. */
    fun pauseReconciliation() {
        reconcilePaused = true
        handler.removeCallbacks(taskChangeReconcile)
        handler.removeCallbacks(reconciliation)
    }

    private val reconciliation = object : Runnable {
        override fun run() {
            if (!active || suspended || reconcilePaused) return
            if (!transitionInProgress) {
                reconcileForeground("periodic")
                reconciliationTick += 1
                if (reconciliationTick >= FULL_RECONCILE_TICKS) {
                    reconciliationTick = 0
                    reconcile("periodic")
                }
            }
            handler.postDelayed(this, FOREGROUND_RECONCILE_INTERVAL_MS)
        }
    }
    // Shell's onTaskAppeared/InfoChanged/Vanished drive the foreground reconcile; several
    // callbacks in one frame coalesce into a single pass.
    private val taskChangeReconcile = Runnable {
        if (active && !suspended && !reconcilePaused && !transitionInProgress) reconcileForeground("task changed")
    }
    private fun onTaskChanged() {
        handler.removeCallbacks(taskChangeReconcile)
        handler.postDelayed(taskChangeReconcile, TASK_CHANGE_COALESCE_MS)
    }

    init {
        require(slots.size == WORKSPACE_SLOT_COUNT) {
            "P6 requires exactly $WORKSPACE_SLOT_COUNT slots"
        }
        slots.forEach { slot ->
            slot.setListener(this)
            slot.view.setListener(this)
        }
    }

    fun activate() {
        ensureMainThread()
        if (active) return
        validateExitedTasks()
        active = true
        reconcilePaused = false
        reconciliationTick = 0
        TaskSurfaceCompat.taskChangeListener = ::onTaskChanged
        slots.forEach(VirtualDisplaySlot::activate)
        handler.removeCallbacks(reconciliation)
        handler.postDelayed(reconciliation, FOREGROUND_RECONCILE_INTERVAL_MS)
        NeXtepLog.info("slot_coordinator", "Activated retained=${retainedTaskDescription()}")
    }

    fun deactivate() {
        ensureMainThread()
        if (!active) return
        clearDesktopSelection()
        appLaunch.cancel()
        // Stop timers and settlements before parking: when a park failed, the reconciliation
        // loop kept running and re-shrank the restored foreground app behind hidden panels.
        settlement.cancel()
        deferredMove = null
        active = false
        TaskSurfaceCompat.taskChangeListener = null
        cancelPendingDragExchange()
        onSlotDragEnded()
        recentSelectionGeneration += 1
        recentSelectionPending = false
        suspended = false
        transitionInProgress = false
        pendingSelection = null
        mainSelectionGeneration += 1
        clearPromotedTaskRecovery()
        handler.removeCallbacksAndMessages(null)
        slots.forEach { it.view.setBusy(false) }
        slotRecords.forEach { record ->
            record.pendingComponent = null
            record.pendingAttempts = 0
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            record.migrationRecoveryAttempts = 0
        }
        // Never destroy a display while a task failed to leave it: the caller keeps the
        // displays alive and the next activate() reuses them.
        parkTasksBeforeExit()
        slots.forEach { it.deactivate() }
        NeXtepLog.info("slot_coordinator", "Deactivated retained=${retainedTaskDescription()}")
    }

    private fun parkTasksBeforeExit() {
        val tasks = taskRepository.runningTasks()
        val saved = mutableSetOf<Int>()
        slots.forEachIndexed { index, slot ->
            val record = slotRecords[index]
            record.exitTask = null
            val task = tasks.firstOrNull { it.displayId == slot.displayId() }
            record.retainedTaskId = null
            if (task == null || !saved.add(task.taskId)) return@forEachIndexed
            runCatching {
                val layout = record.originalLayout ?: defaultMainLayout(task.resizeMode)
                WindowContainerTransactionCompat.parkInDefaultDisplay(
                    checkNotNull(task.token), layout.defaultDisplayBounds(), layout.densityDpi, layout.windowingMode,
                ).getOrThrow()
                check(taskRepository.findTask(task.taskId)?.displayId == Display.DEFAULT_DISPLAY) {
                    "Task ${task.taskId} did not return to the default display"
                }
                layout.resizeMode?.let { ActivityTaskManagerCompat.setTaskResizeable(task.taskId, it).getOrThrow() }
                val recent = taskRepository.recentTasks(checkNotNull(task.userId)).getOrThrow()
                    .firstOrNull { it.taskId == task.taskId }
                record.exitTask = recent
                record.retainedTaskId = recent?.taskId
                NeXtepLog.info("slot_retention", "Parked slot=$index task=${recent?.taskId}")
            }.onFailure {
                NeXtepLog.warn("slot_retention", "Could not retain slot=$index task=${task.taskId}", it)
            }
            // Some apps open secondary tasks on the same display. Preserve those in recents
            // too, while only the top task owns this slot's restore reservation.
            tasks.filter { it.displayId == slot.displayId() && it.taskId != task.taskId }
                .forEach { secondary ->
                    val layout = defaultMainLayout(secondary.resizeMode)
                    WindowContainerTransactionCompat.parkInDefaultDisplay(
                        checkNotNull(secondary.token), layout.defaultDisplayBounds(), layout.densityDpi, layout.windowingMode,
                    ).getOrThrow()
                }
        }
        val displays = slots.mapNotNull { it.displayId() }.toSet()
        check(taskRepository.runningTasks().none { it.displayId in displays }) {
            "Slot tasks are still attached; refusing to destroy their displays"
        }
    }

    private fun validateExitedTasks() {
        val queries = slotRecords.mapNotNull { it.exitTask?.userId }.distinct()
            .associateWith { taskRepository.recentTasks(it).getOrNull() }
        val foreground = taskRepository.foregroundTask()?.taskId
        slotRecords.forEach { record ->
            val saved = record.exitTask ?: return@forEach
            val current = queries[saved.userId]?.firstOrNull { it.taskId == saved.taskId }
            val live = taskRepository.findTask(saved.taskId)
            if (current != saved || foreground == saved.taskId ||
                (live != null && (live.displayId != Display.DEFAULT_DISPLAY || live.vendorWindowed))) {
                record.exitTask = null
                record.retainedTaskId = null
                record.originalLayout = null
                NeXtepLog.info("slot_retention", "Discarded changed/removed task=${saved.taskId}")
            }
        }
    }

    fun setSuspended(value: Boolean) {
        ensureMainThread()
        if (!active || suspended == value) return
        if (value) {
            settlement.cancel()
            deferredMove = null
            suspended = true
            clearDesktopSelection()
            appLaunch.cancel()
            cancelPendingDragExchange()
            onSlotDragEnded()
            recentSelectionGeneration += 1
            recentSelectionPending = false
            handler.removeCallbacksAndMessages(null)
            transitionInProgress = false
            pendingSelection = null
            mainSelectionGeneration += 1
            clearPromotedTaskRecovery()
            slots.forEach {
                it.view.setBusy(true)
                it.setSuspended(true)
            }
        } else {
            slots.forEach { slot ->
                runCatching { slot.setSuspended(false) }.onFailure {
                    // One delayed/invalid preview Surface must not keep HOME and
                    // all SystemUI panels hidden. Retry that slot in reconciliation.
                    NeXtepLog.warn("virtual_display", "Output resume pending display=${slot.displayId()}", it)
                }
            }
            suspended = false
            slots.forEach { it.view.setBusy(false) }
            handler.removeCallbacks(reconciliation)
            handler.post(reconciliation)
        }
    }

    fun launchInSlot(intent: Intent, slotIndex: Int = 0): Result<Unit> {
        ensureMainThread()
        return launchApplicationInSlot(slotIndex, intent)
    }

    /** The main pane's package (null for HOME/SystemUI) and one package or null per slot. */
    fun scenePackages(): Pair<String?, List<String?>> {
        ensureMainThread()
        val main = taskRepository.foregroundTask()?.component?.packageName?.takeIf {
            it != TriggerBroadcastContract.resolveHomePackage(applicationContext) &&
                it != TriggerBroadcastContract.SYSTEM_UI_PACKAGE && it != TriggerBroadcastContract.MODULE_PACKAGE
        }
        return main to slotRecords.map { (it.state as? SlotState.Occupied)?.component?.packageName }
    }

    /** Launch every slot's app into its slot, then the main app; slots without a package keep what they have. */
    fun applyScene(main: String?, slots: List<String?>, complete: (Result<Unit>) -> Unit): Result<Unit> = runCatching {
        ensureMainThread()
        check(active && !suspended && !transitionInProgress && !recentSelectionPending && !appLaunch.running)
        fun launcher(pkg: String) = packageManager.getLaunchIntentForPackage(pkg)?.let(::normalizeLaunchIntent)
        clearDesktopSelection()
        val requests = slots.withIndex().mapNotNull { (index, pkg) ->
            val intent = pkg?.let(::launcher)?.takeIf { validActiveSlot(index) } ?: return@mapNotNull null
            // A slot already showing this app stays; any other occupant is parked first, as searches do.
            if ((slotRecords[index].state as? SlotState.Occupied)?.component?.packageName == pkg) return@mapNotNull null
            replaceSearchDestination(index, intent)
            LaunchRequest(intent, index, slots(index))
        }
        val mainIntent = main?.let(::launcher)
        fun openMain(result: Result<Unit>) {
            complete(result.mapCatching { mainIntent?.let { openInMain(it).getOrThrow() } ?: Unit })
        }
        if (requests.isEmpty()) openMain(Result.success(Unit)) else appLaunch.begin(requests, ::openMain).getOrThrow()
    }

    /** Open external content (a link, a search) in one slot, resolved like the compare searches. */
    fun openExternalInSlot(source: Intent, index: Int): Result<Unit> = runCatching {
        ensureMainThread()
        check(validActiveSlot(index) && !recentSelectionPending && !appLaunch.running)
        val normalized = checkNotNull(normalizeExternalIntent(source)) { "External content has no Activity" }
        clearDesktopSelection()
        // The chosen pane shows the content; whatever was there is parked, as a search would do.
        replaceSearchDestination(index, normalized)
        appLaunch.begin(listOf(LaunchRequest(normalized, index, slots(index)))) { result ->
            result.onFailure {
                if (it !is java.util.concurrent.CancellationException) {
                    NeXtepLog.warn("app_launch", "Unable to open content in slot=$index", it)
                    showMessage(AppLanguage.string(applicationContext, R.string.workbench_launch_failed))
                }
            }
        }.getOrThrow()
    }

    private fun slots(index: Int): Int? = slots.getOrNull(index)?.displayId()

    fun openInMain(sourceIntent: Intent, autoMinimizeCurrent: Boolean = false): Result<Unit> {
        ensureMainThread()
        if (!active || suspended || appLaunch.running) {
            return Result.failure(IllegalStateException("Workspace transition is busy"))
        }
        if (transitionInProgress || recentSelectionPending) {
            val selected = Intent(sourceIntent)
            pendingSelection = { openInMain(selected, autoMinimizeCurrent) }
            return Result.success(Unit)
        }
        val normalized = normalizeLaunchIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("App does not resolve to a launcher Activity"))
        val component = checkNotNull(normalized.component)
        val existing = taskRepository.findTaskForComponent(component, normalized.targetUser())

        return runTransition("open main component=$component") {
            val currentMain = taskRepository.foregroundTask()
            // Reserve only a genuinely empty slot before promoting the target app.
            // A full sidebar must not turn this click into an exchange with an occupied slot.
            val minimizeSlot = if (autoMinimizeCurrent &&
                currentMain != null && currentMain.taskId != existing?.taskId &&
                isExchangeableMainTask(currentMain)
            ) {
                val tasks = taskRepository.runningTasks()
                slotRecords.indexOfFirst { record ->
                    val ready = record.state as? SlotState.Ready ?: return@indexOfFirst false
                    record.pendingComponent == null && record.retainedTaskId == null &&
                        !shouldRecoverMigratedTask(record) &&
                        tasks.none { it.displayId == ready.displayId }
                }
            } else -1

            fun minimizePreviousMain() {
                if (minimizeSlot < 0 || currentMain == null) return
                val ready = slotRecords[minimizeSlot].state as? SlotState.Ready ?: return
                val previous = taskRepository.findTask(currentMain.taskId)
                    ?.takeIf(::isExchangeableMainTask) ?: return
                runCatching {
                    parkMainTaskInSlot(minimizeSlot, ready, previous, leaveOverview = false)
                }.onFailure { error ->
                    // Parking performs its own rollback; keep the requested app switch usable.
                    NeXtepLog.warn("top_apps", "Unable to auto-minimize task=${currentMain.taskId}", error)
                }
            }

            if (existing != null) {
                promoteExistingToMain(existing, ::minimizePreviousMain)
            } else {
                val options = android.app.ActivityOptions.makeBasic().apply {
                    setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                }
                UserTargetedActivityLauncher.start(applicationContext, normalized, options.toBundle()).getOrThrow()
                minimizePreviousMain()
                val generation = mainSelectionGeneration
                handler.postDelayed({
                    presentLaunchedMain(component, attempt = 1, generation = generation,
                        targetUser = normalized.targetUser())
                }, MAIN_LAUNCH_POLL_MS)
            }
        }
    }

    private fun promoteExistingToMain(existing: TaskRepository.TaskSnapshot, beforePresent: () -> Unit = {}) {
        val sourceSlot = slotRecords.indexOfFirst { (it.state as? SlotState.Occupied)?.taskId == existing.taskId }
        val sourceLayout = sourceSlot.takeIf { it >= 0 }?.let { slotRecords[it].originalLayout }
            ?: defaultMainLayout(existing.resizeMode)
        if (existing.displayId != Display.DEFAULT_DISPLAY) {
            ActivityTaskManagerCompat.setTaskResizeable(existing.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
            moveTaskToDisplayAndWait(existing.taskId, Display.DEFAULT_DISPLAY)
            restoreTaskLayout(existing, sourceLayout, restoreResizeMode = false)
        }
        if (sourceSlot >= 0) {
            val record = slotRecords[sourceSlot]
            record.retainedTaskId = null
            record.pendingComponent = null
            record.pendingAttempts = 0
            record.originalLayout = null
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            record.migrationRecoveryAttempts = 0
            record.mismatchSince = 0L
            setState(sourceSlot, slots[sourceSlot].displayId()?.let(SlotState::Ready) ?: SlotState.Empty,
                "top app promoted from slot")
        }
        taskRepository.bringTaskToFront(existing.taskId).getOrThrow()
        beforePresent()
        mainTaskPresenter.presentTask(existing.taskId, geometryProvider()).getOrThrow()
    }

    fun openMediaInMain(session: android.media.session.MediaController): Result<Unit> {
        ensureMainThread()
        if (!active || suspended || appLaunch.running) {
            return Result.failure(IllegalStateException("Workspace transition is busy"))
        }
        if (transitionInProgress || recentSelectionPending) {
            pendingSelection = { openMediaInMain(session) }
            return Result.success(Unit)
        }
        val targetUser = session.sessionActivity?.creatorUserHandle ?: android.os.Process.myUserHandle()
        val userId = io.github.lujinxin.nextep.framework.TaskInfoCompat.userIdentifier(targetUser)
        val sourceSlot = slotRecords.indexOfFirst { record ->
            val occupied = record.state as? SlotState.Occupied ?: return@indexOfFirst false
            occupied.component.packageName == session.packageName &&
                taskRepository.findTask(occupied.taskId)?.userId == userId
        }
        if (sourceSlot >= 0) {
            val occupied = slotRecords[sourceSlot].state as SlotState.Occupied
            return runTransition("media swap slot=$sourceSlot task=${occupied.taskId}",
                SlotTaskDrag(sourceSlot, occupied.taskId, occupied.displayId) to null) {
                swapWithMain(sourceSlot, occupied)
            }
        }
        val launcherIntent = packageManager.getLaunchIntentForPackage(session.packageName)
            ?.putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER, targetUser)
        if (launcherIntent != null) return openInMain(launcherIntent)

        // Some media sessions have a player Activity but no launcher entry.
        val pendingIntent = session.sessionActivity
            ?: return Result.failure(IllegalStateException("Media app has no launch target"))
        if (!pendingIntent.isActivity) {
            return Result.failure(IllegalStateException("Media session launch target is not an Activity"))
        }
        return runTransition("media session main package=${session.packageName}") {
            val options = android.app.ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
            pendingIntent.send(applicationContext, 0, null, null, null, null, options.toBundle())
            handler.postDelayed({ reconcileForeground("media session launched") }, MAIN_LAUNCH_POLL_MS)
        }
    }

    fun openExternalInMain(sourceIntent: Intent): Result<Unit> {
        ensureMainThread()
        clearDesktopSelection()
        if (!active || suspended || appLaunch.running) {
            return Result.failure(IllegalStateException("Workspace transition is busy"))
        }
        if (transitionInProgress || recentSelectionPending) {
            val selected = Intent(sourceIntent)
            pendingSelection = { openExternalInMain(selected) }
            return Result.success(Unit)
        }
        val normalized = normalizeExternalIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("External content has no Activity"))
        val component = checkNotNull(normalized.component)
        return runTransition("open external component=$component") {
            val existing = taskRepository.findTaskForComponent(component, normalized.targetUser())
            if (existing != null && existing.displayId != Display.DEFAULT_DISPLAY) promoteExistingToMain(existing)
            val options = android.app.ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
            UserTargetedActivityLauncher.start(applicationContext, normalized, options.toBundle()).getOrThrow()
            val generation = mainSelectionGeneration
            handler.postDelayed({
                presentLaunchedMain(component, attempt = 1, generation = generation,
                    targetUser = normalized.targetUser())
            }, MAIN_LAUNCH_POLL_MS)
        }
    }

    fun openFromLauncher(sourceIntent: Intent): Result<Unit> {
        ensureMainThread()
        desktopSelection.target?.let { target ->
            val parkedTask = desktopParkedTask
            clearDesktopSelection()
            if (parkedTask != null) {
                if ((slotRecords[target.index].state as? SlotState.Occupied)?.taskId != parkedTask)
                    return Result.failure(IllegalStateException("Preserved app changed"))
                return openInMain(sourceIntent, autoMinimizeCurrent = false)
            }
            val display = slots[target.index].displayId()
            if (target.displayId != null && display != null && display != target.displayId ||
                slotRecords[target.index].state is SlotState.Occupied) {
                showMessage(AppLanguage.string(applicationContext, R.string.window_layout_pending))
                return Result.failure(IllegalStateException("Selected side window changed"))
            }
            return launchApplicationInSlot(target.index, sourceIntent, target.displayId, emptyOnly = true).onFailure {
                showMessage(AppLanguage.string(applicationContext, R.string.workbench_launch_failed))
            }
        }
        if (!active) return Result.failure(IllegalStateException("Workspace is inactive"))
        val normalized = normalizeLaunchIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("App does not resolve to a launcher Activity"))
        val component = checkNotNull(normalized.component)
        val existing = taskRepository.findTaskForComponent(component, normalized.targetUser())
        val sourceSlot = existing?.let { task ->
            slotRecords.indexOfFirst { record ->
                (record.state as? SlotState.Occupied)?.taskId == task.taskId
            }
        } ?: -1
        if (sourceSlot >= 0) {
            onSlotClicked(sourceSlot)
            NeXtepLog.info(
                "launcher_redirect",
                "Converted repeated launch into slot exchange slot=$sourceSlot taskId=${existing?.taskId}",
            )
            return Result.success(Unit)
        }
        return openInMain(normalized)
    }

    private fun presentLaunchedMain(component: ComponentName, attempt: Int, generation: Int,
        targetUser: android.os.UserHandle? = null) {
        if (!active || suspended || generation != mainSelectionGeneration) return
        val launched = taskRepository.findTaskForComponent(component, targetUser)
            ?.takeIf { it.displayId == Display.DEFAULT_DISPLAY }
        if (launched == null) {
            if (attempt < MAX_MAIN_LAUNCH_POLLS) {
                handler.postDelayed(
                    { presentLaunchedMain(component, attempt + 1, generation, targetUser) },
                    MAIN_LAUNCH_POLL_MS,
                )
            } else {
                NeXtepLog.warn(
                    "top_apps",
                    "Main task did not appear component=$component attempts=$attempt",
                )
            }
            return
        }
        if (taskRepository.foregroundTask()?.taskId != launched.taskId) return
        mainTaskPresenter.presentTask(launched.taskId, geometryProvider())
            .onFailure { error ->
                NeXtepLog.error(
                    "top_apps",
                    "Main presentation failed taskId=${launched.taskId}",
                    error,
                )
            }
    }

    fun slotStates(): List<SlotState> = slotRecords.map { it.state }

    fun parkMainForWide(taskId: Int, index: Int): Result<Unit> = runCatching {
        ensureMainThread()
        check(active && !suspended && !transitionInProgress && validActiveSlot(index))
        val current = checkNotNull(taskRepository.foregroundTask())
        check(current.taskId == taskId && isExchangeableMainTask(current))
        val occupied = slotRecords[index].state as? SlotState.Occupied
        runTransition("landscape task to wide slot=$index",
            occupied?.let { SlotTaskDrag(index, it.taskId, it.displayId) to null }) {
            when (val state = slotRecords[index].state) {
                is SlotState.Ready -> parkMainTaskInSlot(index, state, current)
                is SlotState.Occupied -> swapWithMain(index, state)
                else -> error("Wide slot is not ready")
            }
        }.getOrThrow()
    }

    fun openSearches(sources: List<Intent>, complete: (Result<Unit>) -> Unit): Result<Unit> = runCatching {
        ensureMainThread()
        check(active && !suspended && !transitionInProgress && !recentSelectionPending && !appLaunch.running)
        require(sources.size in 1..5)
        val intents = sources.map { checkNotNull(normalizeExternalIntent(it)) { "No supported search Activity" } }
        val requests = intents.map { LaunchRequest(it, null, null, search = true) }
        clearDesktopSelection()
        appLaunch.begin(requests, complete).getOrThrow()
    }

    override fun onContentDropped(slotIndex: Int, event: android.view.DragEvent): Boolean {
        // A release before expansion finishes is rejected. Never substitute a share
        // Activity or deliver the payload to the source app now occupying this slot.
        return false
    }

    override fun onDisplayReady(slotIndex: Int, displayId: Int) {
        ensureMainThread()
        if (!active) return
        validateExitedTasks()
        setState(slotIndex, SlotState.Ready(displayId), "display ready")
        val retainedTaskId = slotRecords[slotIndex].retainedTaskId
        if (retainedTaskId != null) {
            val exited = slotRecords[slotIndex].exitTask
            if (exited != null && taskRepository.findTask(retainedTaskId) == null) {
                taskRepository.restoreRecentTask(retainedTaskId, displayId).onFailure {
                    NeXtepLog.warn("slot_retention", "Recent task restore failed task=$retainedTaskId", it)
                }
            }
            slotRecords[slotIndex].exitTask = null
            val retained = taskRepository.findTask(retainedTaskId)
            if (retained == null) {
                slotRecords[slotIndex].retainedTaskId = null
                slotRecords[slotIndex].originalLayout = null
            } else {
                val wasPresented = mainTaskPresenter.currentTaskId() == retainedTaskId
                runCatching {
                    mainTaskPresenter.restoreForMove(retainedTaskId).getOrThrow()
                    ActivityTaskManagerCompat.setTaskResizeable(
                        retainedTaskId,
                        TASK_RESIZE_MODE_RESIZEABLE,
                    ).getOrThrow()
                    moveTaskToDisplayAndWait(retainedTaskId, displayId)
                    ensureSlotLayout(slotIndex, retained)
                }
                    .onSuccess {
                        setState(
                            slotIndex,
                            SlotState.Occupied(displayId, retained.taskId, retained.component),
                            "retained task rebound",
                        )
                    }
                    .onFailure { error ->
                        if (wasPresented) {
                            mainTaskPresenter.presentTask(retainedTaskId, geometryProvider())
                                .onFailure(error::addSuppressed)
                        }
                        slotRecords[slotIndex].retainedTaskId = null
                        slotRecords[slotIndex].originalLayout = null
                        setState(
                            slotIndex,
                            SlotState.Failed("restore failed"),
                            "retained task rebind failed: ${error.message}",
                        )
                    }
            }
        }
        handler.postDelayed({ reconcile("display ready") }, SETTLE_DELAY_MS)
    }

    override fun onDisplayReleased(slotIndex: Int, displayId: Int?, reason: String) {
        ensureMainThread()
        setState(slotIndex, SlotState.Empty, "display released id=$displayId reason=$reason")
    }

    override fun onDisplayFailed(slotIndex: Int, message: String) {
        ensureMainThread()
        setState(slotIndex, SlotState.Failed(message), "display failed")
    }

    override fun onSlotClicked(slotIndex: Int) {
        if (!active || suspended || slotIndex !in slots.indices || appLaunch.running) return
        if (transitionInProgress || recentSelectionPending) {
            val selectedTaskId = (slotRecords[slotIndex].state as? SlotState.Occupied)?.taskId
            pendingSelection = {
                // Keep the tapped app if an in-flight move changes its slot.
                val selectedSlot = if (selectedTaskId == null) slotIndex else
                    slotRecords.indexOfFirst { (it.state as? SlotState.Occupied)?.taskId == selectedTaskId }
                if (selectedSlot >= 0) onSlotClicked(selectedSlot)
            }
            NeXtepLog.info("slot_coordinator", "Queued slot tap slot=$slotIndex")
            return
        }
        when (val state = slotRecords[slotIndex].state) {
            is SlotState.Occupied -> {
                clearDesktopSelection()
                runTransition("swap slot=$slotIndex taskId=${state.taskId}",
                    SlotTaskDrag(slotIndex, state.taskId, state.displayId) to null) { swapWithMain(slotIndex, state) }
            }
            is SlotState.Ready -> {
                val record = slotRecords[slotIndex]
                val main = taskRepository.foregroundTask()
                if (!selectingDesktopApp && record.pendingComponent == null && record.retainedTaskId == null &&
                    main != null && main.component.packageName == TriggerBroadcastContract.resolveHomePackage(applicationContext))
                    requestRecentTask(slotIndex, state, main.taskId)
                else beginDesktopSelection(slotIndex, slots[slotIndex].displayId() ?: state.displayId)
            }
            else -> beginDesktopSelection(slotIndex, slots[slotIndex].displayId())
        }
    }

    private fun requestRecentTask(slotIndex: Int, ready: SlotState.Ready, homeTaskId: Int) {
        pendingSelection = null
        recentSelectionPending = true
        val generation = ++recentSelectionGeneration
        slots.forEach { it.view.setBusy(true) }
        RecentTaskSelectionContract.query(applicationContext) { selection ->
            if (generation != recentSelectionGeneration) return@query
            recentSelectionPending = false
            dispatchPendingSelection()
            if (!active || suspended) return@query
            slots.forEach { it.view.setBusy(false) }
            // A more recent selection supersedes this asynchronous overview query.
            if (pendingSelection != null) return@query
            // Reject a delayed result after another app took the foreground or
            // this slot/display was repurposed by another workspace operation.
            if (slotRecords[slotIndex].state != ready || slots[slotIndex].displayId() != ready.displayId ||
                taskRepository.foregroundTask()?.taskId != homeTaskId || transitionInProgress
            ) return@query
            when (selection) {
                RecentTaskSelection.NotVisible -> beginDesktopSelection(slotIndex, ready.displayId)
                is RecentTaskSelection.Unavailable -> beginDesktopSelection(slotIndex, ready.displayId)
                is RecentTaskSelection.Selected -> runTransition(
                    "center recent task=${selection.task.taskId} slot=$slotIndex",
                ) { assignRecentTask(slotIndex, ready, selection.task) }.onFailure {
                    showMessage(AppLanguage.string(applicationContext, R.string.recent_add_failed))
                }
            }
        }
    }

    private fun assignRecentTask(slotIndex: Int, ready: SlotState.Ready, selected: SelectedRecentTask) {
        val retained = taskRepository.recentTasks(selected.userId).getOrThrow()
            .firstOrNull { it.taskId == selected.taskId && it.baseComponent.packageName == selected.component.packageName }
            ?: error("Selected recent task changed or was removed")
        val live = taskRepository.findTask(retained.taskId)
        if (live != null) {
            check(live.userId == selected.userId) { "Selected task user changed" }
            // ColorOS includes tasks on our virtual displays in overview. Move
            // that exact task between slots instead of rejecting it or launching
            // a second instance. The destination is still the queried empty slot.
            val sourceSlot = slotRecords.indexOfFirst { record ->
                (record.state as? SlotState.Occupied)?.taskId == live.taskId
            }
            if (sourceSlot >= 0) {
                check(sourceSlot != slotIndex && slots[sourceSlot].displayId() == live.displayId) {
                    "Selected slot task changed"
                }
                swapSlots(sourceSlot, slotIndex)
                return
            }
            check(isExchangeableMainTask(live)) {
                "Selected task is no longer on the default display"
            }
            parkMainTaskInSlot(slotIndex, ready, live, leaveOverview = false)
            return
        }
        // Restore the exact retained task, including its user and back stack; do not
        // launch by package (that could open a different card or a fresh Activity).
        val record = slotRecords[slotIndex]
        var restored: TaskRepository.TaskSnapshot? = null
        var original = defaultMainLayout(null)
        try {
            taskRepository.restoreRecentTask(retained.taskId, ready.displayId).getOrThrow()
            val task = checkNotNull(taskRepository.findTask(retained.taskId)) { "Restored task is unavailable" }
            restored = task
            check(task.userId == selected.userId && task.component.packageName == selected.component.packageName)
            original = defaultMainLayout(task.resizeMode)
            ActivityTaskManagerCompat.setTaskResizeable(task.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
            if (task.displayId != ready.displayId) moveTaskToDisplayAndWait(task.taskId, ready.displayId)
            record.originalLayout = original
            ensureSlotLayout(slotIndex, task, original)
            record.retainedTaskId = task.taskId
            setState(slotIndex, SlotState.Occupied(ready.displayId, task.taskId, task.component), "center recent restored")
        } catch (error: Throwable) {
            record.originalLayout = null
            record.retainedTaskId = null
            restored?.let { task ->
                runCatching {
                    WindowContainerTransactionCompat.parkInDefaultDisplay(
                        checkNotNull(task.token), original.defaultDisplayBounds(), original.densityDpi, original.windowingMode,
                    ).getOrThrow()
                    original.resizeMode?.let { mode ->
                        ActivityTaskManagerCompat.setTaskResizeable(task.taskId, mode).getOrThrow()
                    }
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun showMessage(message: String) {
        android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onIntentDropped(slotIndex: Int, intent: Intent): Boolean =
        launchApplicationInSlot(slotIndex, intent).onFailure {
            NeXtepLog.warn("app_drop", "Application drop rejected slot=" + slotIndex, it)
            showMessage(AppLanguage.string(applicationContext, R.string.workbench_launch_failed))
        }.isSuccess

    override fun onSlotDragStarting(drag: SlotTaskDrag): Boolean {
        if (!active || suspended || appLaunch.running || transitionInProgress || recentSelectionPending || activeDrag != null ||
            !matchesDrag(drag)
        ) return false
        activeDrag = drag
        onDragActiveChanged(true)
        return true
    }

    override fun onSlotDragEnded() {
        activeDrag = null
        onDragActiveChanged(false)
    }

    override fun onSlotDragStarted(source: TaskSwitcherView, drag: SlotTaskDrag) = onInternalSlotDrag(source, drag)
    override fun onSlotDragTouch(source: android.view.View, event: android.view.MotionEvent) = onInternalDragTouch(source, event)

    override fun onSlotBackgroundSwiped(drag: SlotTaskDrag) = dismissDraggedSlot(drag)

    /** Return below existing tasks in one transaction, keeping the main app and
     * overview visible. This releases slot ownership without closing the task. */
    fun dismissDraggedSlot(drag: SlotTaskDrag): Boolean {
        if (activeDrag != drag || !matchesDrag(drag) || !active || suspended || recentSelectionPending) return false
        return runTransition("background slot=${drag.slotIndex} task=${drag.taskId}") {
            val task = checkNotNull(taskRepository.findTask(drag.taskId))
            val record = slotRecords[drag.slotIndex]
            val layout = record.originalLayout ?: defaultMainLayout(task.resizeMode)
            try {
                WindowContainerTransactionCompat.parkInDefaultDisplay(
                    checkNotNull(task.token), layout.defaultDisplayBounds(), layout.densityDpi, layout.windowingMode,
                ).getOrThrow()
                check(taskRepository.findTask(task.taskId)?.displayId == Display.DEFAULT_DISPLAY)
                layout.resizeMode?.let { ActivityTaskManagerCompat.setTaskResizeable(task.taskId, it).getOrThrow() }
                record.exitTask = null
                updateDraggedSlot(drag.slotIndex, SlotState.Ready(drag.displayId), null)
                showMessage(AppLanguage.string(applicationContext, R.string.window_backgrounded))
            } catch (error: Throwable) {
                runCatching {
                    moveTaskToDisplayAndWait(task.taskId, drag.displayId)
                    ensureSlotLayout(drag.slotIndex, task, layout)
                }.onFailure(error::addSuppressed)
                throw error
            }
        }.isSuccess
    }

    override fun onSlotDropped(slotIndex: Int, drag: SlotTaskDrag): Boolean {
        if (activeDrag != drag || !validActiveSlot(slotIndex) || !matchesDrag(drag)) return false
        if (slotIndex == drag.slotIndex) return true
        val target = slotRecords[slotIndex].state
        if (target !is SlotState.Ready && target !is SlotState.Occupied) return false
        return runTransition("drag slot=${drag.slotIndex} to slot=$slotIndex", drag to slotIndex) {
            check(matchesDrag(drag) && slotRecords[slotIndex].state == target) { "Drag target changed" }
            swapSlots(drag.slotIndex, slotIndex)
        }.isSuccess
    }

    fun dropSlotIntoMain(drag: SlotTaskDrag): Boolean {
        if (activeDrag != drag || !matchesDrag(drag) || !active || suspended || recentSelectionPending) return false
        val mainTaskId = taskRepository.foregroundTask()?.taskId
        return runTransition("drag slot=${drag.slotIndex} to main", drag to null) {
            check(matchesDrag(drag) && taskRepository.foregroundTask()?.taskId == mainTaskId) { "Main task changed during drag" }
            swapWithMain(drag.slotIndex, slotRecords[drag.slotIndex].state as SlotState.Occupied)
        }.isSuccess
    }

    private fun matchesDrag(drag: SlotTaskDrag): Boolean {
        val state = slotRecords.getOrNull(drag.slotIndex)?.state as? SlotState.Occupied ?: return false
        return state.taskId == drag.taskId && state.displayId == drag.displayId &&
            slots[drag.slotIndex].displayId() == drag.displayId &&
            taskRepository.findTask(drag.taskId)?.displayId == drag.displayId
    }

    fun cancelPendingDragExchange() {
        if (!dragTransitionPending) return
        dragTransitionPending = false
        transitionInProgress = false
        pendingSelection = null
        slots.forEach { it.view.setBusy(false) }
    }

    private fun swapSlots(sourceIndex: Int, targetIndex: Int) {
        val sourceState = slotRecords[sourceIndex].state as SlotState.Occupied
        val targetState = slotRecords[targetIndex].state
        val sourceTask = checkNotNull(taskRepository.findTask(sourceState.taskId))
        val targetOccupied = targetState as? SlotState.Occupied
        val targetTask = targetOccupied?.let { checkNotNull(taskRepository.findTask(it.taskId)) }
        val targetDisplay = checkNotNull(slots[targetIndex].displayId())
        check(sourceTask.displayId == sourceState.displayId)
        check(targetTask == null || targetTask.displayId == targetDisplay)
        val sourceLayout = slotRecords[sourceIndex].originalLayout ?: defaultMainLayout(sourceTask.resizeMode)
        val targetLayout = targetTask?.let { slotRecords[targetIndex].originalLayout ?: defaultMainLayout(it.resizeMode) }
        val sourceCanvas = checkNotNull(slots[sourceIndex].geometry())
        val targetCanvas = checkNotNull(slots[targetIndex].geometry())
        fun move(task: TaskRepository.TaskSnapshot, displayId: Int, canvas: SlotGeometry) =
            WindowContainerTransactionCompat.TaskMove(checkNotNull(task.token), displayId, Rect(),
                canvas.densityDpi, fullscreenWindowingMode())
        fun settled(task: TaskRepository.TaskSnapshot, displayId: Int, canvas: SlotGeometry): Boolean {
            val current = taskRepository.findTask(task.taskId) ?: error("Task ${task.taskId} exited")
            return current.displayId == displayId && current.bounds == Rect(0, 0, canvas.width, canvas.height) &&
                current.densityDpi == canvas.densityDpi && current.windowingMode == fullscreenWindowingMode()
        }
        fun fitted(task: TaskRepository.TaskSnapshot, index: Int, displayId: Int): Boolean {
            val source = slots[index].sourceBounds()
            return TaskSurfaceCompat.fitSlotSurface(task.taskId, displayId,
                Rect(source.left, source.top, source.right, source.bottom), finishExchange = true).getOrThrow()
        }
        var submitted = false
        fun rollback() {
            TaskSurfaceCompat.cancelDisplayExchange(sourceTask.taskId)
            targetTask?.let { TaskSurfaceCompat.cancelDisplayExchange(it.taskId) }
            if (submitted) WindowContainerTransactionCompat.moveTasks(buildList {
                add(move(sourceTask, sourceState.displayId, sourceCanvas))
                targetTask?.let { add(move(it, targetDisplay, targetCanvas)) }
            }).getOrThrow()
            updateDraggedSlot(sourceIndex, sourceState, sourceLayout)
            updateDraggedSlot(targetIndex, targetState, targetLayout)
        }
        try {
            TaskSurfaceCompat.markDisplayExchange(sourceTask.taskId, targetDisplay)
            TaskSurfaceCompat.cancelPreparedPromotion(sourceTask.taskId)
            targetTask?.let {
                TaskSurfaceCompat.markDisplayExchange(it.taskId, sourceState.displayId)
                TaskSurfaceCompat.cancelPreparedPromotion(it.taskId)
            }
            // One transaction reparents both tasks onto their new slot canvases; settlement
            // confirms the result asynchronously instead of sleeping on this thread.
            WindowContainerTransactionCompat.moveTasks(buildList {
                targetTask?.let { add(move(it, sourceState.displayId, sourceCanvas)) }
                add(move(sourceTask, targetDisplay, targetCanvas))
            }).getOrThrow()
            submitted = true
            updateDraggedSlot(targetIndex, SlotState.Occupied(targetDisplay, sourceTask.taskId, sourceTask.component), sourceLayout)
            updateDraggedSlot(sourceIndex, if (targetTask != null) {
                SlotState.Occupied(sourceState.displayId, targetTask.taskId, targetTask.component)
            } else SlotState.Ready(sourceState.displayId), targetLayout)
            deferredMove = DeferredMove(ready = ready@{
                if (!settled(sourceTask, targetDisplay, targetCanvas)) return@ready false
                if (targetTask != null && !settled(targetTask, sourceState.displayId, sourceCanvas)) return@ready false
                fitted(sourceTask, targetIndex, targetDisplay) &&
                    (targetTask == null || fitted(targetTask, sourceIndex, sourceState.displayId))
            }, rollback = ::rollback)
        } catch (error: Throwable) {
            runCatching { rollback() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun updateDraggedSlot(index: Int, state: SlotState, layout: TaskLayout?) {
        val record = slotRecords[index]
        record.retainedTaskId = (state as? SlotState.Occupied)?.taskId
        record.originalLayout = if (state is SlotState.Occupied) layout else null
        record.pendingComponent = null
        record.pendingAttempts = 0
        record.migrationRecoveryComponent = null
        record.migrationRecoveryDeadline = 0L
        record.migrationRecoveryAttempts = 0
        record.mismatchSince = 0L
        setState(index, state, "drag exchange")
    }

    private fun assignIntent(slotIndex: Int, sourceIntent: Intent, external: Boolean = false): Result<Unit> {
        if (!validActiveSlot(slotIndex)) {
            return Result.failure(IllegalStateException("Slot $slotIndex is unavailable"))
        }
        val normalized = (if (external) normalizeExternalIntent(sourceIntent) else normalizeLaunchIntent(sourceIntent))
            ?: return Result.failure(IllegalArgumentException("Drop does not resolve to an App"))
        val component = checkNotNull(normalized.component)
        if (slotRecords[slotIndex].pendingComponent != null) {
            return Result.failure(IllegalStateException("Slot $slotIndex launch is pending"))
        }
        val targetDisplayId = slots[slotIndex].displayId()
            ?: return Result.failure(IllegalStateException("Slot $slotIndex display is pending"))
        val existing = taskRepository.findTaskForComponent(component, normalized.targetUser())
        val occupied = slotRecords[slotIndex].state as? SlotState.Occupied

        if (existing?.displayId == targetDisplayId) {
            slotRecords[slotIndex].retainedTaskId = existing.taskId
            val layoutResult = runCatching { ensureSlotLayout(slotIndex, existing) }
            if (layoutResult.isFailure) {
                return Result.failure(checkNotNull(layoutResult.exceptionOrNull()))
            }
            setState(
                slotIndex,
                SlotState.Occupied(targetDisplayId, existing.taskId, existing.component),
                "drop matched target task",
            )
            return if (external) slots[slotIndex].launch(normalized) else Result.success(Unit)
        }

        if (occupied != null) {
            val foreground = taskRepository.foregroundTask()
            if (existing != null && foreground?.taskId == existing.taskId) {
                return runTransition("drop main into occupied slot=$slotIndex",
                    SlotTaskDrag(slotIndex, occupied.taskId, occupied.displayId) to null) {
                    swapWithMain(slotIndex, occupied)
                }
            }
            slots[slotIndex].view.showState(occupied)
            return Result.failure(IllegalStateException("Slot $slotIndex is occupied"))
        }

        if (existing != null) {
            return runTransition("move dropped taskId=${existing.taskId} slot=$slotIndex") {
                val wasPresented = mainTaskPresenter.currentTaskId() == existing.taskId
                val sourceSlot = slotRecords.indexOfFirst { record ->
                    (record.state as? SlotState.Occupied)?.taskId == existing.taskId
                }
                var restoredTask = existing
                var originalLayout = sourceSlot.takeIf { it >= 0 }
                    ?.let { slotRecords[it].originalLayout }
                    ?: defaultMainLayout(existing.resizeMode)
                var moved = false
                try {
                    mainTaskPresenter.restoreForMove(existing.taskId).getOrThrow()
                    restoredTask = taskRepository.findTask(existing.taskId) ?: existing
                    originalLayout = if (sourceSlot >= 0) {
                        slotRecords[sourceSlot].originalLayout
                    } else {
                        restoredTask.toLayout()
                    } ?: defaultMainLayout(restoredTask.resizeMode)
                    ActivityTaskManagerCompat.setTaskResizeable(
                        existing.taskId,
                        TASK_RESIZE_MODE_RESIZEABLE,
                    ).getOrThrow()
                    moveTaskToDisplayAndWait(existing.taskId, targetDisplayId)
                    moved = true
                    slotRecords[slotIndex].originalLayout = originalLayout
                    ensureSlotLayout(slotIndex, restoredTask, originalLayout)
                } catch (error: Throwable) {
                    slotRecords[slotIndex].originalLayout = null
                    if (moved) {
                        runCatching {
                            moveTaskToDisplayAndWait(
                                existing.taskId,
                                existing.displayId,
                            )
                            if (sourceSlot >= 0) {
                                ensureSlotLayout(sourceSlot, restoredTask, originalLayout)
                            } else {
                                restoreTaskLayout(restoredTask, originalLayout)
                            }
                        }.onFailure(error::addSuppressed)
                    }
                    if (wasPresented) {
                        mainTaskPresenter.presentTask(existing.taskId, geometryProvider())
                            .onFailure(error::addSuppressed)
                    }
                    throw error
                }
                if (sourceSlot >= 0 && sourceSlot != slotIndex) {
                    clearSlotAssignment(sourceSlot)
                    val sourceDisplayId = slots[sourceSlot].displayId()
                    setState(
                        sourceSlot,
                        sourceDisplayId?.let { SlotState.Ready(it) } ?: SlotState.Empty,
                        "task moved to another slot",
                    )
                }
                slotRecords[slotIndex].retainedTaskId = existing.taskId
                setState(
                    slotIndex,
                    SlotState.Occupied(targetDisplayId, existing.taskId, existing.component),
                    "existing task assigned",
                )
                if (external) slots[slotIndex].launch(normalized).getOrThrow()
            }
        }

        slotRecords[slotIndex].pendingComponent = component
        slotRecords[slotIndex].pendingAttempts = 0
        slots[slotIndex].view.showWaiting()
        return runTransition("launch component=$component slot=$slotIndex") {
            try {
                slots[slotIndex].launch(normalized).getOrThrow()
                handler.postDelayed({ reconcile("launch requested") }, SETTLE_DELAY_MS)
            } catch (error: Throwable) {
                slotRecords[slotIndex].pendingComponent = null
                setState(
                    slotIndex,
                    SlotState.Failed("launch failed"),
                    error.message ?: "launch failed",
                )
                throw error
            }
        }
    }

    private fun parkMainTaskInSlot(
        slotIndex: Int,
        ready: SlotState.Ready,
        mainTask: TaskRepository.TaskSnapshot,
        leaveOverview: Boolean = true,
    ) {
        val targetDisplayId = slots[slotIndex].displayId()
            ?: error("Slot $slotIndex display is unavailable")
        check(targetDisplayId == ready.displayId) {
            "Slot $slotIndex display changed ${ready.displayId}->$targetDisplayId"
        }
        val record = slotRecords[slotIndex]
        val wasPresented = mainTaskPresenter.currentTaskId() == mainTask.taskId
        val live = taskRepository.findTask(mainTask.taskId) ?: mainTask
        val mainOriginalLayout = live.toLayout()
        val canvas = checkNotNull(slots[slotIndex].geometry())
        val slotBounds = Rect(0, 0, canvas.width, canvas.height)
        val token = checkNotNull(mainTask.token) { "Task ${mainTask.taskId} has no token" }
        var submitted = false
        var restored = false
        var homeShown = false
        fun rollback() {
            TaskSurfaceCompat.cancelDisplayExchange(mainTask.taskId)
            record.retainedTaskId = null
            record.originalLayout = null
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            record.migrationRecoveryAttempts = 0
            val errors = mutableListOf<Throwable>()
            if (submitted) runCatching {
                WindowContainerTransactionCompat.moveTasks(listOf(WindowContainerTransactionCompat.TaskMove(token,
                    Display.DEFAULT_DISPLAY, mainOriginalLayout.defaultDisplayBounds(), mainOriginalLayout.densityDpi,
                    mainOriginalLayout.windowingMode))).getOrThrow()
                mainOriginalLayout.resizeMode?.let { ActivityTaskManagerCompat.setTaskResizeable(mainTask.taskId, it).getOrThrow() }
            }.onFailure(errors::add)
            if (wasPresented && (restored || !submitted)) {
                mainTaskPresenter.presentTask(mainTask.taskId, geometryProvider()).onFailure(errors::add)
            }
            if (errors.isEmpty()) {
                setState(slotIndex, ready, "empty slot parking rolled back")
            } else {
                setState(slotIndex, SlotState.Failed("rollback failed"), "empty slot parking rollback incomplete")
                throw errors.first().also { first -> errors.drop(1).forEach(first::addSuppressed) }
            }
        }
        try {
            if (live.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                ActivityTaskManagerCompat.setTaskResizeable(mainTask.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
            }
            record.migrationRecoveryComponent = mainTask.component
            record.migrationRecoveryDeadline = SystemClock.uptimeMillis() + MIGRATION_RECOVERY_WINDOW_MS
            record.migrationRecoveryAttempts = 0
            TaskSurfaceCompat.markDisplayExchange(mainTask.taskId, targetDisplayId)
            TaskSurfaceCompat.cancelPreparedPromotion(mainTask.taskId)
            // One transaction reparents the task and lays it out for the slot canvas. Settlement
            // confirms it asynchronously; the presentation transform is dropped only once the
            // task has left display 0, so its identity size is never exposed there.
            WindowContainerTransactionCompat.moveTasks(listOf(WindowContainerTransactionCompat.TaskMove(
                token, targetDisplayId, Rect(), canvas.densityDpi, fullscreenWindowingMode()))).getOrThrow()
            submitted = true
            record.originalLayout = mainOriginalLayout
            slotRecords.forEachIndexed { index, otherRecord ->
                val occupiedTaskId = (otherRecord.state as? SlotState.Occupied)?.taskId
                if (index != slotIndex &&
                    (otherRecord.retainedTaskId == mainTask.taskId || occupiedTaskId == mainTask.taskId)
                ) {
                    otherRecord.retainedTaskId = null
                    otherRecord.pendingComponent = null
                    otherRecord.pendingAttempts = 0
                    otherRecord.originalLayout = null
                    val displayId = slots[index].displayId()
                    setState(index, displayId?.let(SlotState::Ready) ?: SlotState.Empty,
                        "duplicate parked task ownership cleared")
                }
            }
            record.retainedTaskId = mainTask.taskId
            setState(slotIndex, SlotState.Occupied(targetDisplayId, mainTask.taskId, mainTask.component),
                "main task parked in empty slot")
            deferredMove = DeferredMove(ready = ready@{
                val current = taskRepository.findTask(mainTask.taskId) ?: error("Task ${mainTask.taskId} exited")
                if (current.displayId != targetDisplayId) return@ready false
                if (!restored) {
                    restored = true
                    mainTaskPresenter.restoreForMove(mainTask.taskId).getOrThrow()
                }
                if (leaveOverview && !homeShown) {
                    homeShown = true
                    showHomeOnDefaultDisplay()
                }
                if (current.bounds != slotBounds || current.densityDpi != canvas.densityDpi ||
                    current.windowingMode != fullscreenWindowingMode()) return@ready false
                val source = slots[slotIndex].sourceBounds()
                TaskSurfaceCompat.fitSlotSurface(mainTask.taskId, targetDisplayId,
                    Rect(source.left, source.top, source.right, source.bottom), finishExchange = true).getOrThrow()
            }, rollback = ::rollback)
        } catch (error: Throwable) {
            runCatching { rollback() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun showHomeOnDefaultDisplay() {
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
            ?: error("No resolved HOME package")
        // Moving the foreground task off display 0 normally resumes HOME already.
        // Starting it again can create a ColorOS whole-display change transition.
        if (taskRepository.foregroundTask()?.component?.packageName == homePackage) return
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(homePackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val options = android.app.ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
        applicationContext.startActivity(homeIntent, options.toBundle())
    }

    private fun isExchangeableMainTask(task: TaskRepository.TaskSnapshot?): Boolean {
        val mainTask = task ?: return false
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
            ?: return false
        val packageName = mainTask.component.packageName
        return mainTask.displayId == Display.DEFAULT_DISPLAY &&
            !mainTask.vendorWindowed && mainTask.windowingMode == fullscreenWindowingMode() &&
            packageName != homePackage &&
            packageName != TriggerBroadcastContract.SYSTEM_UI_PACKAGE
    }

    private fun swapWithMain(slotIndex: Int, occupied: SlotState.Occupied) {
        val tasks = taskRepository.runningTasks()
        val slotTask = tasks.firstOrNull { it.taskId == occupied.taskId }
            ?: error("Slot task ${occupied.taskId} exited")
        val mainTask = tasks.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
            ?.takeIf { isExchangeableMainTask(it) && it.taskId != slotTask.taskId }
        val record = slotRecords[slotIndex]
        val slotOriginal = record.originalLayout ?: defaultMainLayout(slotTask.resizeMode)
        val mainOriginal = mainTask?.toLayout()
        val canvas = checkNotNull(slots[slotIndex].geometry())
        val slotBounds = Rect(0, 0, canvas.width, canvas.height)
        val mainBounds = geometryProvider().let { Rect(0, 0, it.screenWidth, it.screenHeight) }
        fun move(task: TaskRepository.TaskSnapshot, display: Int, layout: TaskLayout) =
            WindowContainerTransactionCompat.TaskMove(checkNotNull(task.token), display,
                if (layout.windowingMode == fullscreenWindowingMode()) Rect() else layout.bounds,
                layout.densityDpi, layout.windowingMode)
        val sideLayout = TaskLayout(slotBounds, canvas.densityDpi, fullscreenWindowingMode(),
            TASK_RESIZE_MODE_RESIZEABLE)
        var submitted = false
        fun rollback() {
            clearPromotedTaskRecovery()
            TaskSurfaceCompat.cancelDisplayExchange(slotTask.taskId)
            mainTask?.let { TaskSurfaceCompat.cancelDisplayExchange(it.taskId) }
            if (submitted) {
                mainTaskPresenter.restoreForMove(slotTask.taskId).getOrThrow()
                WindowContainerTransactionCompat.moveTasks(buildList {
                    add(move(slotTask, occupied.displayId, sideLayout))
                    mainTask?.let { add(move(it, Display.DEFAULT_DISPLAY, checkNotNull(mainOriginal))) }
                }).getOrThrow()
            }
            listOfNotNull(slotTask, mainTask).forEach { task ->
                task.resizeMode?.let { ActivityTaskManagerCompat.setTaskResizeable(task.taskId, it).getOrThrow() }
            }
            record.originalLayout = slotOriginal
            record.retainedTaskId = slotTask.taskId
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            setState(slotIndex, occupied, "exchange rolled back")
            mainTask?.let { mainTaskPresenter.presentTask(it.taskId, geometryProvider()) }
        }
        try {
            listOfNotNull(slotTask, mainTask).forEach { task ->
                if (task.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                    ActivityTaskManagerCompat.setTaskResizeable(task.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
                }
            }
            TaskSurfaceCompat.markDisplayExchange(slotTask.taskId, Display.DEFAULT_DISPLAY)
            TaskSurfaceCompat.preparePromotion(slotTask.taskId, geometryProvider()).onFailure {
                NeXtepLog.warn("task_surface", "Early exchange fitting pending", it)
            }
            mainTask?.let {
                TaskSurfaceCompat.markDisplayExchange(it.taskId, occupied.displayId)
                mainTaskPresenter.restoreForMove(it.taskId).getOrThrow()
            }
            WindowContainerTransactionCompat.moveTasks(buildList {
                mainTask?.let { add(move(it, occupied.displayId, sideLayout)) }
                add(move(slotTask, Display.DEFAULT_DISPLAY, slotOriginal))
            }).getOrThrow()
            submitted = true
            // The server transaction changes ownership atomically. Keep the saved
            // originals valid even if the user closes/locks before surfaces arrive.
            clearSlotAssignment(slotIndex)
            if (mainTask != null) {
                record.originalLayout = mainOriginal
                record.retainedTaskId = mainTask.taskId
                setState(slotIndex, SlotState.Occupied(occupied.displayId, mainTask.taskId,
                    mainTask.component), "exchange submitted")
            } else setState(slotIndex, SlotState.Ready(occupied.displayId), "promotion submitted")
            deferredMove = DeferredMove(ready = ready@{
                val current = taskRepository.runningTasks()
                val promoted = current.firstOrNull { it.taskId == slotTask.taskId }
                val demoted = mainTask?.let { old -> current.firstOrNull { it.taskId == old.taskId } }
                val mainReady = promoted?.displayId == Display.DEFAULT_DISPLAY &&
                    promoted.bounds == mainBounds && promoted.densityDpi == slotOriginal.densityDpi &&
                    promoted.windowingMode == slotOriginal.windowingMode
                val sideReady = mainTask == null || demoted?.let {
                    it.displayId == occupied.displayId && it.bounds == slotBounds &&
                        it.densityDpi == canvas.densityDpi && it.windowingMode == fullscreenWindowingMode()
                } == true
                if (!mainReady || !sideReady || !TaskSurfaceCompat.hasPresentableSurface(slotTask.taskId)) false
                else {
                    if (mainTask != null) {
                        val source = slots[slotIndex].sourceBounds()
                        // Task metadata is ready before the old main transform is cleared.
                        // Admit the destination fit now, retaining Shell exchange classification.
                        if (!TaskSurfaceCompat.fitSlotSurface(mainTask.taskId, occupied.displayId,
                            Rect(source.left, source.top, source.right, source.bottom),
                            finishExchange = true).getOrThrow()) return@ready false
                    }
                    mainTaskPresenter.presentTask(slotTask.taskId, geometryProvider()).getOrThrow()
                    true
                }
            }, rollback = ::rollback)
        } catch (error: Throwable) {
            runCatching { rollback() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun runTransition(label: String, animation: Pair<SlotTaskDrag, Int?>? = null,
                              completed: (Result<Unit>) -> Unit = {}, operation: () -> Unit): Result<Unit> {
        if (transitionInProgress || recentSelectionPending) {
            NeXtepLog.warn("slot_coordinator", "Ignored concurrent transition $label")
            return Result.failure(IllegalStateException("Another slot transition is settling"))
        }
        transitionInProgress = true
        pendingSelection = null
        mainSelectionGeneration += 1
        clearPromotedTaskRecovery()
        slots.forEach { it.view.setBusy(true) }
        NeXtepLog.info("slot_coordinator", "Transition started $label")
        fun finish(result: Result<Unit>) {
            result.onFailure { error ->
                NeXtepLog.error("slot_coordinator", "Transition failed $label", error)
                showMessage(AppLanguage.string(applicationContext, R.string.window_swap_incomplete))
            }
            transitionInProgress = false
            slots.forEach { it.view.setBusy(suspended || recentSelectionPending || appLaunch.running) }
            dispatchPendingSelection()
            handler.postDelayed({
                reconcile("settled $label")
                NeXtepLog.info("slot_coordinator", "Transition settled $label")
            }, SETTLE_DELAY_MS)
            // Cancellation may start a reverse exchange in this callback.
            completed(result)
        }
        fun performMove(reveal: () -> Unit = {}): Result<Unit> {
            val result = runCatching {
                check(active && !suspended) { "Workspace is no longer interactive" }
                operation()
            }
            val pending = deferredMove
            deferredMove = null
            if (result.isSuccess && pending != null) {
                settlement.begin(pending.ready) { settled ->
                    settled.onFailure { error ->
                        runCatching { pending.rollback() }.onFailure(error::addSuppressed)
                    }
                    finish(settled)
                    reveal()
                }
            } else {
                finish(result)
                reveal()
            }
            return result
        }
        if (animation == null) return performMove()
        dragTransitionPending = true
        return runCatching {
            onSwapAnimation(animation.first, animation.second) { reveal ->
                if (dragTransitionPending) {
                    dragTransitionPending = false
                    performMove(reveal)
                }
            }
        }.onFailure {
            dragTransitionPending = false
            transitionInProgress = false
            slots.forEach { it.view.setBusy(false) }
            dispatchPendingSelection()
        }
    }

    private fun dispatchPendingSelection() {
        handler.post {
            if (!active || suspended || transitionInProgress || recentSelectionPending) return@post
            val selected = pendingSelection ?: return@post
            pendingSelection = null
            selected()
        }
    }

    private fun reconcile(reason: String) {
        ensureMainThread()
        if (!active || suspended || transitionInProgress) return
        val tasks = taskRepository.runningTasks()
        val assignedTaskIds = mutableSetOf<Int>()

        slots.forEachIndexed { index, slot ->
            if (slot.isSuspended()) {
                runCatching { slot.setSuspended(false) }.onFailure {
                    NeXtepLog.warn("virtual_display", "Output reattachment pending slot=$index", it)
                }
            }
            val displayId = slot.displayId() ?: run {
                setState(index, SlotState.Empty, "$reason: no display")
                return@forEachIndexed
            }
            val displayTasks = tasks.filter { it.displayId == displayId }
            val displayTask = displayTasks.firstOrNull()
            if (displayTasks.size > 1) {
                NeXtepLog.warn(
                    "slot_state",
                    "slot=$index displayId=$displayId stackedTasks=${displayTasks.map { it.taskId }}",
                )
            }
            val retainedTask = slotRecords[index].retainedTaskId
                ?.let { taskId -> tasks.firstOrNull { it.taskId == taskId } }
            val record = slotRecords[index]
            val recoveringSameComponent = record.migrationRecoveryComponent
                ?.packageName == displayTask?.component?.packageName &&
                SystemClock.uptimeMillis() <= record.migrationRecoveryDeadline

            when {
                displayTask != null && assignedTaskIds.add(displayTask.taskId) -> {
                    slotRecords[index].mismatchSince = 0L
                    if (record.retainedTaskId != null &&
                        record.retainedTaskId != displayTask.taskId &&
                        !recoveringSameComponent
                    ) {
                        record.originalLayout = null
                    }
                    try {
                        ensureSlotLayout(index, displayTask)
                    } catch (error: Throwable) {
                        setState(
                            index,
                            SlotState.Failed("layout rejected"),
                            "$reason: ${error.message}",
                        )
                        return@forEachIndexed
                    }
                    record.retainedTaskId = displayTask.taskId
                    record.pendingComponent = null
                    record.pendingAttempts = 0
                    setState(
                        index,
                        SlotState.Occupied(displayId, displayTask.taskId, displayTask.component),
                        "$reason: adopted display task",
                    )
                }
                displayTask != null -> {
                    slotRecords[index].retainedTaskId = null
                    slotRecords[index].originalLayout = null
                    setState(index, SlotState.Failed("duplicate task"), "$reason: duplicate record")
                }
                retainedTask != null && !assignedTaskIds.add(retainedTask.taskId) -> {
                    slotRecords[index].retainedTaskId = null
                    slotRecords[index].originalLayout = null
                    setState(index, SlotState.Failed("duplicate task"), "$reason: retained duplicate")
                }
                retainedTask != null -> {
                    val foregroundTaskId = tasks.firstOrNull {
                        it.displayId == Display.DEFAULT_DISPLAY
                    }?.taskId
                    if (retainedTask.displayId == Display.DEFAULT_DISPLAY &&
                        retainedTask.taskId == foregroundTaskId
                    ) {
                        val originalLayout = slotRecords[index].originalLayout
                            ?: defaultMainLayout(retainedTask.resizeMode)
                        runCatching {
                            restoreExternalFullscreenLayout(retainedTask, originalLayout)
                            clearSlotAssignment(index)
                            setState(
                                index,
                                SlotState.Ready(displayId),
                                "$reason: task externally promoted to main",
                            )
                            mainTaskPresenter.reconcileForeground(geometryProvider()).getOrThrow()
                        }.onFailure { error ->
                            setState(
                                index,
                                SlotState.Failed("main promotion failed"),
                                "$reason: ${error.message}",
                            )
                        }
                        return@forEachIndexed
                    }
                    val now = SystemClock.uptimeMillis()
                    if (record.mismatchSince == 0L) record.mismatchSince = now
                    if (now - record.mismatchSince >= SLOT_MISMATCH_GRACE_MS) {
                        assignedTaskIds -= retainedTask.taskId
                        clearSlotAssignment(index)
                        setState(
                            index,
                            SlotState.Ready(displayId),
                            "$reason: stale slot ownership cleared",
                        )
                    }
                }
                shouldRecoverMigratedTask(record) -> {
                    recoverMigratedTask(index, record)
                }
                slotRecords[index].pendingComponent != null &&
                    slotRecords[index].pendingAttempts < MAX_PENDING_POLLS -> {
                    slotRecords[index].pendingAttempts += 1
                    slot.view.showWaiting()
                }
                else -> {
                    slotRecords[index].retainedTaskId = null
                    slotRecords[index].originalLayout = null
                    val launchTimedOut = slotRecords[index].pendingComponent != null
                    slotRecords[index].pendingComponent = null
                    slotRecords[index].pendingAttempts = 0
                    setState(
                        index,
                        if (launchTimedOut) SlotState.Failed("launch timed out")
                        else SlotState.Ready(displayId),
                        "$reason: empty",
                    )
                }
            }
        }
        mainTaskPresenter.reconcileForeground(geometryProvider())
            .onFailure { error ->
                NeXtepLog.warn(
                    "main_task_presenter",
                    "Foreground reconciliation failed reason=$reason",
                    error,
                )
            }
        runCatching { onTasksObserved(tasks, slotStates()) }
            .onFailure { NeXtepLog.warn("workbench_scene", "Optional scene observation failed open", it) }
    }

    private fun shouldRecoverMigratedTask(record: SlotRecord): Boolean =
        record.migrationRecoveryComponent != null &&
            record.migrationRecoveryAttempts < MAX_MIGRATION_RECOVERY_ATTEMPTS &&
            SystemClock.uptimeMillis() <= record.migrationRecoveryDeadline

    private fun schedulePromotedTaskRecovery() {
        postPromotedRecovery(PROMOTED_RECOVERY_DELAY_MS)
    }

    private fun postPromotedRecovery(delayMillis: Long) {
        val generation = promotedRecoveryGeneration
        handler.postDelayed({
            if (generation == promotedRecoveryGeneration) recoverPromotedTaskIfNeeded()
        }, delayMillis)
    }

    private fun recoverPromotedTaskIfNeeded() {
        val component = promotedRecoveryComponent ?: return
        if (!active || SystemClock.uptimeMillis() > promotedRecoveryDeadline) {
            clearPromotedTaskRecovery()
            return
        }
        val promotedTask = taskRepository.findTaskForComponent(component)
            ?.takeIf { task -> task.displayId == Display.DEFAULT_DISPLAY }
        if (promotedTask != null) {
            if (taskRepository.foregroundTask()?.taskId != promotedTask.taskId) {
                clearPromotedTaskRecovery()
                return
            }
            mainTaskPresenter.presentTask(promotedTask.taskId, geometryProvider())
                .onSuccess {
                    NeXtepLog.info(
                        "slot_coordinator",
                        "Promoted task settled taskId=${promotedTask.taskId} component=$component",
                    )
                    clearPromotedTaskRecovery()
                }
                .onFailure { error ->
                    NeXtepLog.warn(
                        "slot_coordinator",
                        "Promoted task presentation still settling taskId=${promotedTask.taskId}",
                        error,
                    )
                    postPromotedRecovery(PROMOTED_RECOVERY_POLL_MS)
                }
            return
        }
        if (promotedRecoveryAttempts >= MAX_MIGRATION_RECOVERY_ATTEMPTS) {
            NeXtepLog.warn(
                "slot_coordinator",
                "Promoted task recovery exhausted component=$component",
            )
            clearPromotedTaskRecovery()
            return
        }
        val intent = normalizeLaunchIntent(Intent().setComponent(component))
        if (intent == null) {
            NeXtepLog.warn(
                "slot_coordinator",
                "Promoted task recovery has no launcher Activity component=$component",
            )
            clearPromotedTaskRecovery()
            return
        }
        promotedRecoveryAttempts += 1
        runCatching { applicationContext.startActivity(intent) }
            .onSuccess {
                NeXtepLog.warn(
                    "slot_coordinator",
                    "Relaunched promoted task after process death component=$component",
                )
                postPromotedRecovery(PROMOTED_RECOVERY_POLL_MS)
            }
            .onFailure { error ->
                NeXtepLog.error(
                    "slot_coordinator",
                    "Promoted task recovery launch failed component=$component",
                    error,
                )
                clearPromotedTaskRecovery()
            }
    }

    private fun clearPromotedTaskRecovery() {
        promotedRecoveryGeneration += 1
        promotedRecoveryComponent = null
        promotedRecoveryDeadline = 0L
        promotedRecoveryAttempts = 0
    }

    private fun recoverMigratedTask(slotIndex: Int, record: SlotRecord) {
        val component = checkNotNull(record.migrationRecoveryComponent)
        val intent = normalizeLaunchIntent(Intent().setComponent(component))
        if (intent == null) {
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            setState(
                slotIndex,
                SlotState.Failed("migration recovery unavailable"),
                "No launcher Activity for $component",
            )
            return
        }
        record.migrationRecoveryAttempts += 1
        record.retainedTaskId = null
        record.pendingComponent = component
        record.pendingAttempts = 0
        slots[slotIndex].view.showWaiting()
        slots[slotIndex].launch(intent)
            .onSuccess {
                NeXtepLog.warn(
                    "slot_coordinator",
                    "Relaunched migrated task after process death slot=$slotIndex " +
                        "component=$component attempt=${record.migrationRecoveryAttempts}",
                )
            }
            .onFailure { error ->
                record.pendingComponent = null
                NeXtepLog.error(
                    "slot_coordinator",
                    "Migration recovery launch failed slot=$slotIndex component=$component",
                    error,
                )
            }
    }

    private fun reconcileForeground(reason: String) {
        val tasks = taskRepository.runningTasks()
        val foreground = tasks.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY } ?: return
        val promotedSlot = slotRecords.indexOfFirst { record ->
            record.retainedTaskId == foreground.taskId &&
                foreground.displayId == Display.DEFAULT_DISPLAY
        }
        if (promotedSlot >= 0) {
            val record = slotRecords[promotedSlot]
            val originalLayout = record.originalLayout
                ?: defaultMainLayout(foreground.resizeMode)
            runCatching {
                restoreExternalFullscreenLayout(foreground, originalLayout)
                clearSlotAssignment(promotedSlot)
                val displayId = slots[promotedSlot].displayId()
                setState(
                    promotedSlot,
                    displayId?.let(SlotState::Ready) ?: SlotState.Empty,
                    "$reason: foreground task left slot",
                )
            }.onFailure { error ->
                NeXtepLog.warn(
                    "slot_state",
                    "Unable to adopt externally promoted taskId=${foreground.taskId}",
                    error,
                )
                return
            }
        }
        // Reuse this pass's snapshot. A fullscreen promotion restores layout, so that path
        // needs a fresh query before the presenter reads windowing mode/bounds.
        val presentationTasks = if (promotedSlot >= 0) taskRepository.runningTasks() else tasks
        mainTaskPresenter.reconcileForeground(geometryProvider(), presentationTasks)
            .onFailure { error ->
                NeXtepLog.warn(
                    "main_task_presenter",
                    "Fast foreground reconciliation failed reason=$reason",
                    error,
                )
            }
    }

    private fun normalizeLaunchIntent(source: Intent): Intent? {
        val component = source.component ?: packageManager.resolveActivity(source, 0)
            ?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
            ?: return null
        val info = runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull()
            ?: return null
        // getActivityInfo already hides disabled components; ActivityInfo.enabled is only the
        // manifest default, which is false for seasonal launcher aliases enabled at runtime.
        if (!info.exported) return null
        return Intent(source)
            .setComponent(component)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun restoreExternalFullscreenLayout(task: TaskRepository.TaskSnapshot, layout: TaskLayout) {
        // A notification or native window gesture may promote a slot task into
        // freeform/PiP. Shell already chose its bounds, density and windowing mode.
        // Relinquish the slot without restoring the former fullscreen layout.
        if (task.vendorWindowed || task.windowingMode != fullscreenWindowingMode()) return
        restoreTaskLayout(task, layout, restoreResizeMode = false)
    }

    private fun normalizeExternalIntent(source: Intent): Intent? =
        io.github.lujinxin.nextep.framework.WorkspaceIntentResolver.resolve(packageManager, source)

    private fun Intent.targetUser() = getParcelableExtra(
        TriggerBroadcastContract.EXTRA_TARGET_USER,
        android.os.UserHandle::class.java,
    )

    private fun ensureSlotLayout(
        slotIndex: Int,
        task: TaskRepository.TaskSnapshot,
        originalLayout: TaskLayout? = null,
    ) {
        val slotGeometry = slots[slotIndex].geometry()
            ?: error("Slot $slotIndex geometry is unavailable")
        val fullscreenMode = fullscreenWindowingMode()
        val targetBounds = Rect(0, 0, slotGeometry.width, slotGeometry.height)
        val record = slotRecords[slotIndex]
        if (record.originalLayout == null) {
            record.originalLayout = originalLayout ?: if (task.displayId == Display.DEFAULT_DISPLAY) {
                task.toLayout()
            } else {
                defaultMainLayout(task.resizeMode)
            }
        }
        // Callers may hold the snapshot from before reparenting. Compare against
        // the destination task, otherwise every exchange forces another relayout.
        val current = if (task.displayId != slots[slotIndex].displayId()) {
            taskRepository.findTask(task.taskId) ?: error("Task ${task.taskId} exited")
        } else task
        val resizeModeMismatch = current.resizeMode != TASK_RESIZE_MODE_RESIZEABLE
        val layoutMismatch = current.bounds != targetBounds ||
            current.densityDpi != slotGeometry.densityDpi ||
            current.windowingMode != fullscreenMode
        if (resizeModeMismatch) {
            ActivityTaskManagerCompat.setTaskResizeable(
                task.taskId,
                TASK_RESIZE_MODE_RESIZEABLE,
            ).getOrThrow()
        }
        if (layoutMismatch) {
            val token = current.token ?: error("Task ${task.taskId} has no slot layout token")
            WindowContainerTransactionCompat.applyTaskLayout(
                token = token,
                // Fullscreen inherits this virtual display's canvas. An explicit
                // slot-sized override survives promotion and forces a second resize.
                bounds = Rect(),
                densityDpi = slotGeometry.densityDpi,
                windowingMode = fullscreenMode,
            ).getOrThrow()
            waitForTaskLayout(
                taskId = task.taskId,
                bounds = targetBounds,
                densityDpi = slotGeometry.densityDpi,
                windowingMode = fullscreenMode,
            )
            NeXtepLog.info(
                "slot_layout",
                "Applied slot=$slotIndex taskId=${task.taskId} bounds=$targetBounds " +
                    "density=${slotGeometry.densityDpi}",
            )
        }
        slots[slotIndex].displayId()?.let { displayId ->
            val source = slots[slotIndex].sourceBounds()
            TaskSurfaceCompat.fitSlotSurface(task.taskId, displayId,
                Rect(source.left, source.top, source.right, source.bottom)).getOrThrow()
        }
    }

    private fun restoreTaskLayout(
        task: TaskRepository.TaskSnapshot,
        layout: TaskLayout,
        restoreResizeMode: Boolean = true,
    ) {
        val token = task.token ?: error("Task ${task.taskId} has no restore token")
        val current = taskRepository.findTask(task.taskId)
        val mainFullscreen = current?.displayId == Display.DEFAULT_DISPLAY &&
            layout.windowingMode == fullscreenWindowingMode()
        val expectedBounds = if (mainFullscreen) geometryProvider().let {
            Rect(0, 0, it.screenWidth, it.screenHeight)
        } else layout.bounds
        if (mainFullscreen &&
            current?.bounds == expectedBounds && current.densityDpi == layout.densityDpi &&
            current.windowingMode == layout.windowingMode) {
            if (restoreResizeMode) layout.resizeMode?.let {
                ActivityTaskManagerCompat.setTaskResizeable(task.taskId, it).getOrThrow()
            }
            return
        }
        // Fullscreen tasks inherit display bounds. Clear the slot override once;
        // restoring a saved resolved Rect and clearing it again adds another layout.
        WindowContainerTransactionCompat.applyTaskLayout(
            token = token,
            bounds = if (mainFullscreen) Rect() else layout.bounds,
            densityDpi = layout.densityDpi,
            windowingMode = layout.windowingMode,
        ).getOrThrow()
        waitForTaskLayout(
            taskId = task.taskId,
            bounds = expectedBounds,
            densityDpi = layout.densityDpi,
            windowingMode = layout.windowingMode,
        )
        if (restoreResizeMode) {
            layout.resizeMode?.let { resizeMode ->
                ActivityTaskManagerCompat.setTaskResizeable(task.taskId, resizeMode).getOrThrow()
            }
        }
        NeXtepLog.info(
            "slot_layout",
            "Restored taskId=${task.taskId} bounds=${layout.bounds} density=${layout.densityDpi} " +
                "restoreResizeMode=$restoreResizeMode",
        )
    }

    private fun TaskRepository.TaskSnapshot.toLayout() = TaskLayout(
        bounds = Rect(bounds),
        densityDpi = densityDpi,
        windowingMode = windowingMode,
        resizeMode = resizeMode,
        vendorWindowed = vendorWindowed,
    )

    private fun TaskLayout.defaultDisplayBounds(): Rect {
        // Match foreground restoration in the same reparent transaction: fullscreen
        // inherits the destination's current rotation/size, not a saved landscape Rect.
        // ColorOS floating tasks can report fullscreen; retain their native bounds.
        return if (!vendorWindowed && windowingMode == fullscreenWindowingMode()) Rect() else Rect(bounds)
    }

    private fun defaultMainLayout(resizeMode: Int?): TaskLayout {
        val geometry = geometryProvider()
        return TaskLayout(
            bounds = Rect(0, 0, geometry.screenWidth, geometry.screenHeight),
            densityDpi = applicationContext.resources.displayMetrics.densityDpi,
            windowingMode = fullscreenWindowingMode(),
            resizeMode = resizeMode,
        )
    }

    private fun fullscreenWindowingMode(): Int = ActivityTaskManagerCompat
        .resolveWindowingMode("WINDOWING_MODE_FULLSCREEN")
        ?: WINDOWING_MODE_FULLSCREEN_FALLBACK

    private fun moveTaskToDisplayAndWait(taskId: Int, displayId: Int) {
        TaskSurfaceCompat.markDisplayExchange(taskId, displayId)
        if (displayId == Display.DEFAULT_DISPLAY) {
            TaskSurfaceCompat.preparePromotion(taskId, geometryProvider()).onFailure {
                NeXtepLog.warn("task_surface", "Early promotion fitting unavailable taskId=$taskId", it)
            }
        } else {
            TaskSurfaceCompat.cancelPreparedPromotion(taskId)
        }
        try {
            ActivityTaskManagerCompat.moveTaskToDisplay(taskId, displayId).getOrThrow()
        } catch (error: Throwable) {
            TaskSurfaceCompat.cancelDisplayExchange(taskId)
            throw error
        }
        val deadline = SystemClock.uptimeMillis() + DISPLAY_MOVE_TIMEOUT_MS
        var observedDisplayId = taskRepository.findTask(taskId)?.displayId
        while (observedDisplayId != displayId && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(DISPLAY_MOVE_POLL_MS)
            observedDisplayId = taskRepository.findTask(taskId)?.displayId
        }
        check(observedDisplayId == displayId) {
            "Task $taskId display move did not settle: expected=$displayId " +
                "actual=$observedDisplayId"
        }
    }

    private fun waitForTaskLayout(
        taskId: Int,
        bounds: Rect,
        densityDpi: Int,
        windowingMode: Int,
    ) {
        val deadline = SystemClock.uptimeMillis() + TASK_LAYOUT_TIMEOUT_MS
        var snapshot = taskRepository.findTask(taskId)
        while (snapshot != null &&
            (snapshot.bounds != bounds ||
                snapshot.densityDpi != densityDpi ||
                snapshot.windowingMode != windowingMode) &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(TASK_LAYOUT_POLL_MS)
            snapshot = taskRepository.findTask(taskId)
        }
        check(snapshot != null &&
            snapshot.bounds == bounds &&
            snapshot.densityDpi == densityDpi &&
            snapshot.windowingMode == windowingMode
        ) {
            "Task $taskId layout did not settle: expected=$bounds/$densityDpi/$windowingMode " +
                "actual=${snapshot?.bounds}/${snapshot?.densityDpi}/${snapshot?.windowingMode}"
        }
    }

    private fun clearSlotAssignment(index: Int) {
        val record = slotRecords[index]
        record.retainedTaskId = null
        record.originalLayout = null
        record.pendingComponent = null
        record.pendingAttempts = 0
        record.migrationRecoveryComponent = null
        record.migrationRecoveryDeadline = 0L
        record.migrationRecoveryAttempts = 0
        record.mismatchSince = 0L
    }

    private fun setState(index: Int, state: SlotState, reason: String) {
        val previous = slotRecords[index].state
        slotRecords[index].state = state
        if (state !is SlotState.Occupied) slotRecords[index].mismatchSince = 0L
        slots[index].view.showState(state)
        if (previous != state) {
            NeXtepLog.info(
                "slot_state",
                "slot=$index previous=$previous current=$state reason=$reason",
            )
        }
    }

    private fun validActiveSlot(index: Int): Boolean =
        active && !suspended && index in slots.indices && !transitionInProgress

    private fun retainedTaskDescription(): String =
        slotRecords.map { it.retainedTaskId }.toString()

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Slot state changes must run on the main thread"
        }
    }

    private companion object {
        const val SETTLE_DELAY_MS = 700L
        const val MAIN_LAUNCH_POLL_MS = 200L
        const val MAX_MAIN_LAUNCH_POLLS = 3
        const val FOREGROUND_RECONCILE_INTERVAL_MS = 500L
        const val FULL_RECONCILE_TICKS = 2
        const val TASK_CHANGE_COALESCE_MS = 16L
        const val MAX_PENDING_POLLS = 4
        const val TASK_RESIZE_MODE_RESIZEABLE = 2
        const val WINDOWING_MODE_FULLSCREEN_FALLBACK = 1
        const val DISPLAY_MOVE_TIMEOUT_MS = 700L
        const val DISPLAY_MOVE_POLL_MS = 30L
        const val TASK_LAYOUT_TIMEOUT_MS = 900L
        const val TASK_LAYOUT_POLL_MS = 30L
        const val SLOT_MISMATCH_GRACE_MS = 2_400L
        const val MIGRATION_RECOVERY_WINDOW_MS = 5_000L
        const val MAX_MIGRATION_RECOVERY_ATTEMPTS = 1
        const val PROMOTED_RECOVERY_DELAY_MS = 900L
        const val PROMOTED_RECOVERY_POLL_MS = 350L
    }
}
