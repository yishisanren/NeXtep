package io.github.lujinxin.nextep.systemui

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import io.github.lujinxin.nextep.apps.LauncherAppCatalogContract
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.config.WorkspaceConfigClient
import io.github.lujinxin.nextep.config.WorkspaceConfigContract
import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.workspace.WorkspaceController
import io.github.lujinxin.nextep.workspace.WorkspaceStateBridge
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SystemUiRuntime {
    private val initialized = AtomicBoolean(false)
    private lateinit var applicationContext: Context
    private lateinit var windowController: NeXtepWindowController
    private lateinit var workspaceController: WorkspaceController
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val configExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-WorkspaceConfig").apply { isDaemon = true }
    }
    @Volatile private var workspaceConfig = WorkspaceTopConfig()
    @Volatile private var workspaceConfigReady = false
    private val configRetry = Runnable { refreshWorkspaceConfig() }
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WorkspaceConfigContract.ACTION_CHANGED) refreshWorkspaceConfig()
        }
    }

    private fun refreshWorkspaceConfig() {
        lifecycleHandler.removeCallbacks(configRetry)
        configExecutor.execute {
            // Query the module even before the workspace windows exist.
            val queriedConfig = WorkspaceConfigClient.queryOrNull(applicationContext)
            if (queriedConfig == null) {
                // Credential-protected module settings are unavailable before first unlock.
                lifecycleHandler.postDelayed(configRetry, 2_000L)
                return@execute
            }
            workspaceConfig = queriedConfig
            AppLanguage.updateHostLanguage(queriedConfig.languageTag)
            workspaceConfigReady = true
            lifecycleHandler.post {
                if (::windowController.isInitialized) {
                    windowController.setSlotBackgroundSwipeEnabled(workspaceConfig.slotBackgroundSwipeEnabled)
                    windowController.setNavigationButtonsEnabled(workspaceConfig.navigationButtonsEnabled)
                }
            }
            NeXtepLog.info(
                "workspace_config",
                "Applied statusBarGesture=${workspaceConfig.statusBarGestureEnabled} " +
                    "slotBackgroundSwipe=${workspaceConfig.slotBackgroundSwipeEnabled} " +
                    "autoMinimizeMain=${workspaceConfig.autoMinimizeMainOnTopAppSwitch} " +
                    "navigationButtons=${workspaceConfig.navigationButtonsEnabled}",
            )
        }
    }

    fun isStatusBarGestureEnabled(): Boolean = workspaceConfigReady && workspaceConfig.statusBarGestureEnabled

    fun shouldAutoMinimizeMainOnTopAppSwitch(): Boolean = workspaceConfig.autoMinimizeMainOnTopAppSwitch
    private val lockStateObserver = object : Runnable {
        override fun run() {
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            if (!isScreenReady() || isKeyguardLocked(applicationContext)) {
                if (!workspaceController.isSuspended()) {
                    workspaceController.suspendForLock()
                    publishWorkspaceSurfaceState(false)
                }
            } else if (workspaceController.isSuspended()) {
                lifecycleHandler.removeCallbacks(resumeAfterUnlock)
                resumeAfterUnlock.run()
            }
            updateLockStateObservation()
        }
    }

    private fun updateLockStateObservation() {
        // State transitions can occur inside an observation callback. Remove any
        // queued copy before scheduling, so there is only one fallback chain.
        lifecycleHandler.removeCallbacks(lockStateObserver)
        if (::workspaceController.isInitialized && workspaceController.isActive()) {
            lifecycleHandler.postDelayed(lockStateObserver, 500L)
        } else {
            lifecycleHandler.removeCallbacks(resumeAfterUnlock)
        }
    }
    private val resumeAfterUnlock = object : Runnable {
        override fun run() {
            if (!::workspaceController.isInitialized || !workspaceController.isSuspended()) return
            if (!isScreenReady()) return
            if (!isKeyguardLocked(applicationContext) && workspaceController.resumeAfterLock()) {
                publishWorkspaceSurfaceState(true)
                return
            }
            lifecycleHandler.postDelayed(this, 500L)
        }
    }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            applicationContext = context.applicationContext ?: context
            LauncherAppCatalogContract.register(applicationContext)
            TaskSurfaceCompat.initializeTaskAccess(applicationContext)
            WorkspaceDisplayTransitionHook.initialize(applicationContext)
            val mainTaskPresenter = MainTaskPresentationCoordinator(applicationContext)
            windowController = NeXtepWindowController(
                context = applicationContext,
                mainTaskPresenter = mainTaskPresenter,
                onSidebarSideRequested = ::setSidebarSideFromTopBar,
                onSettingsRequested = ::openSettingsFromTopBar,
                onExitRequested = ::closeWorkspaceFromGesture,
                onViewportChanged = ::reconfigureForViewport,
            )
            workspaceController = WorkspaceController(
                applicationContext = applicationContext,
                windowController = windowController,
                mainTaskPresenter = mainTaskPresenter,
                onStateChanged = ::updateLockStateObservation,
            )

            val filter = IntentFilter().apply {
                addAction(TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_SET_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_QUERY_WORKSPACE)
                addAction(TriggerBroadcastContract.ACTION_LAUNCH_SLOT_TEST)
            }
            applicationContext.registerReceiver(
                controlReceiver,
                filter,
                TriggerBroadcastContract.CONTROL_PERMISSION,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            applicationContext.registerReceiver(
                launcherRequestReceiver,
                IntentFilter(TriggerBroadcastContract.ACTION_LAUNCHER_APP_REQUEST),
                Context.RECEIVER_EXPORTED,
            )
            applicationContext.registerReceiver(
                configReceiver,
                IntentFilter(WorkspaceConfigContract.ACTION_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            refreshWorkspaceConfig()
            val lifecycleFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
            runCatching {
                applicationContext.registerReceiver(
                    lifecycleReceiver,
                    lifecycleFilter,
                    Context.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { error ->
                NeXtepLog.warn("systemui_runtime", "Lifecycle receiver unavailable", error)
            }
            Handler(Looper.getMainLooper()).post {
                SystemUiBottomCornerGestureMonitor.install(applicationContext)
                SystemServerWorkspaceBridge.publish(applicationContext, false)
                WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
                // OWN_DISPLAY_GROUP virtual screens can keep the device globally interactive
                // after the physical screen locks, so SCREEN_OFF alone is insufficient.
                updateLockStateObservation()
            }
            NeXtepLog.info("systemui_runtime", "Initialized control receiver")
        } catch (error: Throwable) {
            initialized.set(false)
            NeXtepLog.error("systemui_runtime", "Initialization failed and remains retryable", error)
        }
    }

    fun isWorkspaceActive(): Boolean =
        ::workspaceController.isInitialized && workspaceController.isActive()

    fun toggleWorkspaceFromGesture() {
        if (!isStatusBarGestureEnabled() || !::workspaceController.isInitialized) return
        val target = !workspaceController.isActive()
        if (target && isKeyguardLocked(applicationContext)) return
        if (workspaceController.setActive(target)) {
            publishWorkspaceSurfaceState(target)
            if (!target) WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    fun closeWorkspaceFromGesture() {
        if (::workspaceController.isInitialized) {
            workspaceController.setActive(false)
        }
        if (::applicationContext.isInitialized) {
            TriggerBroadcastContract.systemUiSetIntent(applicationContext, false)?.let { intent ->
                applicationContext.sendBroadcast(intent)
            }
            applicationContext.sendBroadcast(
                TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, false),
            )
            WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    private fun publishWorkspaceSurfaceState(active: Boolean) {
        TriggerBroadcastContract.systemUiSetIntent(applicationContext, active)?.let {
            applicationContext.sendBroadcast(it)
        }
        applicationContext.sendBroadcast(
            TriggerBroadcastContract.assistantScreenSetIntent(applicationContext, active),
        )
    }

    private fun setSidebarSideFromTopBar(side: SidebarSide): Boolean {
        val applied = ::workspaceController.isInitialized && workspaceController.setSidebarSide(side)
        if (!applied) {
            NeXtepLog.warn("sidebar_side", "Unable to switch side=$side")
        }
        return applied
    }

    private fun reconfigureForViewport() {
        if (!::workspaceController.isInitialized || !workspaceController.isActive() ||
            workspaceController.isSuspended()) return
        // A manual navigation-mode change has already updated the cached viewport.
        if (!workspaceController.reconfigure(refreshViewport = false)) {
            publishWorkspaceSurfaceState(false)
            WorkspaceStateBridge.requestInactiveFromSystemUi(applicationContext)
        }
    }

    private fun openSettingsFromTopBar() {
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(TriggerBroadcastContract.MODULE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            NeXtepLog.warn("top_settings", "Module launch intent unavailable")
            return
        }
        if (::workspaceController.isInitialized && workspaceController.isActive()) {
            // Use the same task/display coordination as a launcher click, including
            // an existing NeXtep task in a slot. Opening settings must retain workspace.
            if (!workspaceController.openFromLauncher(intent)) {
                NeXtepLog.warn("top_settings", "Unable to open settings in workspace")
            }
            return
        }
        runCatching { applicationContext.startActivity(intent) }
            .onFailure { NeXtepLog.warn("top_settings", "Unable to open settings", it) }
    }

    private val lifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            SystemUiBottomCornerGestureMonitor.reset()
            if (intent.action == Intent.ACTION_USER_PRESENT) refreshWorkspaceConfig()
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lifecycleHandler.removeCallbacks(resumeAfterUnlock)
                    workspaceController.suspendForLock()
                    publishWorkspaceSurfaceState(false)
                    return
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    lifecycleHandler.removeCallbacks(resumeAfterUnlock)
                    lifecycleHandler.post(resumeAfterUnlock)
                    return
                }
            }
            val reason = when (intent.action) {
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    if (workspaceController.isSuspended()) {
                        // Select the recorded rotation/density profile at the actual
                        // configuration event, never during a task exchange or every wake.
                        windowController.currentGeometry(refreshViewport = true)
                        return
                    }
                    if (workspaceController.reconfigure()) {
                        NeXtepLog.info(
                            "systemui_runtime",
                            "Workspace retained across configuration change",
                        )
                        return
                    }
                    "configuration reconfigure failed"
                }
                else -> return
            }
            workspaceController.setActive(false)
            WorkspaceStateBridge.requestInactiveFromSystemUi(context)
            NeXtepLog.info("systemui_runtime", "Workspace closed fail-open: $reason")
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isOrderedBroadcast || !::workspaceController.isInitialized) return
            val shouldCollapseFirst = intent.getStringExtra(
                TriggerBroadcastContract.EXTRA_SOURCE,
            ) == io.github.lujinxin.nextep.trigger.TriggerSource.QUICK_SETTINGS_TILE.name &&
                requestedTarget(intent) == true
            if (shouldCollapseFirst) {
                val pendingResult = goAsync()
                SystemUiPanelCollapser.collapse(context)
                Handler(Looper.getMainLooper()).postDelayed({
                    val handled = handleControl(context, intent)
                    if (handled) {
                        pendingResult.setResultCode(TriggerBroadcastContract.RESULT_HANDLED)
                        pendingResult.setResultExtras(responseBundle(pendingResult.getResultExtras(true)))
                    }
                    pendingResult.finish()
                }, PANEL_COLLAPSE_DELAY_MS)
                return
            }
            val handled = handleControl(context, intent)
            if (!handled) return

            resultCode = TriggerBroadcastContract.RESULT_HANDLED
            setResultExtras(responseBundle(getResultExtras(true)))
        }
    }

    private fun requestedTarget(intent: Intent): Boolean? = when (intent.action) {
        TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE -> !workspaceController.isActive()
        TriggerBroadcastContract.ACTION_SET_WORKSPACE -> intent.getBooleanExtra(
            TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE,
            false,
        )
        else -> null
    }

    private fun handleControl(context: Context, intent: Intent): Boolean = when (intent.action) {
        TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE,
        TriggerBroadcastContract.ACTION_SET_WORKSPACE -> {
            val target = requestedTarget(intent) ?: false
            if (target && isKeyguardLocked(context)) false
            else workspaceController.setActive(target).also { handled ->
                // The tile/coordinated path must also restore the separate assistant panel.
                if (handled) publishWorkspaceSurfaceState(target)
            }
        }
        TriggerBroadcastContract.ACTION_QUERY_WORKSPACE -> true
        TriggerBroadcastContract.ACTION_LAUNCH_SLOT_TEST -> {
            val active = workspaceController.isActive() || workspaceController.setActive(true)
            active && workspaceController.launchInSlot(
                Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings"),
            )
        }
        else -> false
    }

    private fun responseBundle(existing: Bundle?): Bundle = (existing ?: Bundle()).apply {
        putBoolean(
            TriggerBroadcastContract.EXTRA_ACTIVE,
            workspaceController.isActive(),
        )
        putString(
            TriggerBroadcastContract.EXTRA_HOME_PACKAGE,
            TriggerBroadcastContract.SYSTEM_UI_PACKAGE,
        )
        putInt(
            TriggerBroadcastContract.EXTRA_RUNTIME_PROTOCOL_VERSION,
            TriggerBroadcastContract.RUNTIME_PROTOCOL_VERSION,
        )
        putString(
            TriggerBroadcastContract.EXTRA_SLOT_STATES,
            workspaceController.slotStatesDescription(),
        )
    }

    private val launcherRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::workspaceController.isInitialized || !workspaceController.isActive()) return
            val homePackage = TriggerBroadcastContract.resolveHomePackage(context) ?: return
            val launcherIdentity = intent.getParcelableExtra(
                TriggerBroadcastContract.EXTRA_LAUNCHER_IDENTITY,
                PendingIntent::class.java,
            )
            val verifiedSender = sentFromPackage ?: launcherIdentity?.creatorPackage
            if (verifiedSender != homePackage) {
                NeXtepLog.warn(
                    "launcher_redirect",
                    "Rejected sender=$sentFromPackage token=${launcherIdentity?.creatorPackage} " +
                        "uid=$sentFromUid expected=$homePackage",
                )
                return
            }
            val launchIntent = intent.getParcelableExtra(
                TriggerBroadcastContract.EXTRA_LAUNCH_INTENT,
                Intent::class.java,
            ) ?: return
            if (!workspaceController.openFromLauncher(launchIntent)) {
                NeXtepLog.warn(
                    "launcher_redirect",
                    "SystemUI could not handle component=${launchIntent.component}",
                )
            }
        }
    }

    private fun isKeyguardLocked(context: Context): Boolean = try {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    } catch (error: Throwable) {
        NeXtepLog.warn("systemui_runtime", "Keyguard query failed; activation denied", error)
        true
    }

    private fun isScreenReady(): Boolean =
        applicationContext.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            applicationContext.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    private const val PANEL_COLLAPSE_DELAY_MS = 380L
}
