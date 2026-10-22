package io.github.lujinxin.nextep.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge

object TriggerCoordinator {
    sealed interface RequestResult {
        data class Ready(
            val active: Boolean,
            val homePackage: String,
            val runtimeProtocolVersion: Int,
            val slotStates: String? = null,
            val extras: Bundle? = null,
        ) : RequestResult
        data class NotReady(val reason: String) : RequestResult
    }

    fun requestToggle(
        context: Context,
        source: TriggerSource,
        onResult: (RequestResult) -> Unit,
    ) {
        val homePackage = TriggerBroadcastContract.resolveHomePackage(context)
        NeXtepLog.info(
            "trigger",
            "Coordinated toggle requested by $source home=$homePackage",
        )
        if (homePackage == null) {
            onResult(RequestResult.NotReady("No resolved HOME package"))
            return
        }

        queryTarget(context, homePackage) homeQuery@ { homeState ->
            if (homeState !is RequestResult.Ready) {
                onResult(homeState)
                return@homeQuery
            }
            runtimeMismatch("Launcher", homeState)?.let { mismatch ->
                onResult(mismatch)
                return@homeQuery
            }
            queryTarget(context, TriggerBroadcastContract.SYSTEM_UI_PACKAGE) systemUiQuery@ { systemUiState ->
                if (systemUiState !is RequestResult.Ready) {
                    onResult(systemUiState)
                    return@systemUiQuery
                }
                runtimeMismatch("SystemUI", systemUiState)?.let { mismatch ->
                    onResult(mismatch)
                    return@systemUiQuery
                }
                val requestedActive = !(homeState.active || systemUiState.active)
                setTarget(
                    context,
                    TriggerBroadcastContract.SYSTEM_UI_PACKAGE,
                    requestedActive,
                    source,
                ) systemUiSetResult@ { systemUiSet ->
                    if (systemUiSet !is RequestResult.Ready) {
                        onResult(systemUiSet)
                        return@systemUiSetResult
                    }
                    setTarget(context, homePackage, requestedActive, source) { homeSet ->
                        if (homeSet is RequestResult.Ready) {
                            onResult(homeSet)
                        } else {
                            setTarget(
                                context,
                                TriggerBroadcastContract.SYSTEM_UI_PACKAGE,
                                systemUiState.active,
                                source,
                            ) { rollback ->
                                NeXtepLog.warn(
                                    "trigger",
                                    "Launcher update failed; SystemUI rollback=$rollback",
                                )
                                onResult(homeSet)
                            }
                        }
                    }
                }
            }
        }
    }

    fun queryState(context: Context, onResult: (RequestResult) -> Unit) {
        val homePackage = TriggerBroadcastContract.resolveHomePackage(context)
        if (homePackage == null) {
            onResult(RequestResult.NotReady("No resolved HOME package"))
            return
        }
        // SystemUI owns the state. The tile used to force both hosts inactive whenever the
        // Settings.Global flag read 0, which raced a workspace that was still entering and
        // closed it again; only a Launcher left transformed after SystemUI went inactive is reset.
        queryTarget(context, TriggerBroadcastContract.SYSTEM_UI_PACKAGE) systemUiQuery@ { systemUiState ->
            if (systemUiState !is RequestResult.Ready) {
                onResult(systemUiState)
                return@systemUiQuery
            }
            runtimeMismatch("SystemUI", systemUiState)?.let { mismatch ->
                onResult(mismatch)
                return@systemUiQuery
            }
            if (!systemUiState.active) {
                setTarget(context, homePackage, false, TriggerSource.SYSTEM_RECOVERY) { homeSet ->
                    onResult(if (homeSet is RequestResult.Ready) homeSet.copy(
                        runtimeProtocolVersion = systemUiState.runtimeProtocolVersion,
                        slotStates = systemUiState.slotStates,
                    ) else homeSet)
                }
                return@systemUiQuery
            }
            queryTarget(context, homePackage) { homeState ->
                if (homeState !is RequestResult.Ready) {
                    onResult(homeState)
                } else {
                    val mismatch = runtimeMismatch("Launcher", homeState)
                    when {
                        mismatch != null -> onResult(mismatch)
                        !homeState.active ->
                            onResult(RequestResult.NotReady("Launcher and SystemUI state mismatch"))
                        else -> onResult(
                            homeState.copy(
                                runtimeProtocolVersion = systemUiState.runtimeProtocolVersion,
                                slotStates = systemUiState.slotStates,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** SystemUI alone owns the workbench preferences; the reply carries their current values. */
    fun queryWorkbench(context: Context, onResult: (RequestResult) -> Unit) =
        queryTarget(context, TriggerBroadcastContract.SYSTEM_UI_PACKAGE, onResult)

    fun workbenchCommand(context: Context, key: String, value: String, onResult: (RequestResult) -> Unit) =
        send(context, TriggerBroadcastContract.workbenchCommandIntent(key, value), onResult)

    fun requestSet(
        context: Context,
        active: Boolean,
        source: TriggerSource,
        onResult: (RequestResult) -> Unit,
    ) {
        val homePackage = TriggerBroadcastContract.resolveHomePackage(context)
        if (homePackage == null) {
            onResult(RequestResult.NotReady("No resolved HOME package"))
            return
        }
        setTarget(
            context,
            TriggerBroadcastContract.SYSTEM_UI_PACKAGE,
            active,
            source,
        ) systemUiSetResult@ { systemUiSet ->
            if (systemUiSet !is RequestResult.Ready) {
                onResult(systemUiSet)
                return@systemUiSetResult
            }
            setTarget(context, homePackage, active, source) { homeSet ->
                if (homeSet !is RequestResult.Ready) {
                    NeXtepLog.warn(
                        "trigger",
                        "Coordinated set left SystemUI fail-open active=$active; Launcher unavailable",
                    )
                }
                onResult(homeSet)
            }
        }
    }

    private fun runtimeMismatch(
        target: String,
        state: RequestResult.Ready,
    ): RequestResult.NotReady? = if (
        state.runtimeProtocolVersion != TriggerBroadcastContract.RUNTIME_PROTOCOL_VERSION
    ) {
        RequestResult.NotReady(
            "$target runtime outdated (${state.runtimeProtocolVersion}); reboot the phone",
        )
    } else {
        null
    }

    private fun queryTarget(
        context: Context,
        targetPackage: String,
        onResult: (RequestResult) -> Unit,
    ) = send(context, TriggerBroadcastContract.queryIntent(targetPackage), onResult)

    private fun setTarget(
        context: Context,
        targetPackage: String,
        active: Boolean,
        source: TriggerSource,
        onResult: (RequestResult) -> Unit,
    ) = send(context, TriggerBroadcastContract.setIntent(targetPackage, active, source), onResult)

    private fun send(
        context: Context,
        intent: Intent,
        onResult: (RequestResult) -> Unit,
    ) {
        val applicationContext = context.applicationContext ?: context
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, receivedIntent: Intent?) {
                if (resultCode != TriggerBroadcastContract.RESULT_HANDLED) {
                    onResult(
                        RequestResult.NotReady(
                            "Target ${intent.`package`} did not handle the request",
                        ),
                    )
                    return
                }

                val extras = getResultExtras(false)
                val homePackage = extras
                    ?.getString(TriggerBroadcastContract.EXTRA_HOME_PACKAGE)
                    ?: intent.`package`.orEmpty()
                val active = extras?.getBoolean(TriggerBroadcastContract.EXTRA_ACTIVE) == true
                val runtimeProtocolVersion = extras?.getInt(
                    TriggerBroadcastContract.EXTRA_RUNTIME_PROTOCOL_VERSION,
                    0,
                ) ?: 0
                val slotStates = extras?.getString(TriggerBroadcastContract.EXTRA_SLOT_STATES)
                onResult(
                    RequestResult.Ready(
                        active,
                        homePackage,
                        runtimeProtocolVersion,
                        slotStates,
                        extras,
                    ),
                )
            }
        }

        applicationContext.sendOrderedBroadcast(
            intent,
            null,
            resultReceiver,
            Handler(Looper.getMainLooper()),
            TriggerBroadcastContract.RESULT_NOT_READY,
            null,
            Bundle(),
        )
    }
}
