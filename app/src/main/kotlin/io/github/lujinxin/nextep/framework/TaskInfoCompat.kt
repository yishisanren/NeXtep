package io.github.lujinxin.nextep.framework

import android.app.ActivityManager
import android.graphics.Rect
import android.os.UserHandle
import io.github.lujinxin.nextep.logging.NeXtepLog

object TaskInfoCompat {
    private fun member(fields: List<String>, methods: List<String> = emptyList()) =
        CachedMember(fields, methods) { candidate, error ->
            NeXtepLog.warn("task_info_compat", "Member $candidate failed", error)
        }
    private val displayIdMember = member(listOf("displayId", "mDisplayId"), listOf("getDisplayId"))
    private val configurationMember = member(listOf("configuration", "mConfiguration"), listOf("getConfiguration"))
    private val windowConfigurationMember = member(listOf("windowConfiguration", "mWindowConfiguration"), listOf("getWindowConfiguration"))
    private val boundsMember = member(emptyList(), listOf("getBounds"))
    private val windowingModeMember = member(listOf("windowingMode", "mWindowingMode"), listOf("getWindowingMode"))
    private val resizeModeMember = member(listOf("resizeMode", "mResizeMode"), listOf("getResizeMode"))
    private val densityMember = member(listOf("densityDpi"), listOf("getDensityDpi"))
    private val tokenMember = member(listOf("token", "mToken"), listOf("getToken"))
    private val extraMember = member(listOf("mOplusExtraConfiguration"))
    private val scenarioMember = member(listOf("mScenario"), listOf("getScenario"))
    private val userMember = member(listOf("userId", "mUserId"), listOf("getUserId"))
    private val userHandleMember = member(listOf("mHandle", "identifier"), listOf("getIdentifier"))
    data class WindowState(
        val displayId: Int,
        val bounds: Rect,
        val windowingMode: Int,
        val resizeMode: Int?,
        val densityDpi: Int,
        val token: Any?,
        val vendorWindowed: Boolean = false,
    )

    fun readWindowState(info: ActivityManager.RunningTaskInfo): WindowState? {
        val displayId = (displayIdMember.read(info) as? Number)?.toInt() ?: return missing("displayId", info)

        val configuration = configurationMember.read(info) ?: return missing("configuration", info)

        val windowConfiguration = windowConfigurationMember.read(configuration) ?: return missing("windowConfiguration", info)

        val bounds = (boundsMember.read(windowConfiguration) as? Rect)
            ?.let(::Rect)
            ?: Rect()
        val windowingMode = (windowingModeMember.read(windowConfiguration) as? Number)?.toInt() ?: 0
        val resizeMode = (resizeModeMember.read(info) as? Number)?.toInt()
        val densityDpi = (densityMember.read(configuration) as? Number)?.toInt() ?: return missing("densityDpi", info)
        val token = tokenMember.read(info)

        // ColorOS FlexibleWindow keeps WINDOWING_MODE_FULLSCREEN. Confirmed on
        // PLK110 16.0.10.500: OplusExtraConfiguration.getScenario(), with FlexibleWindowManager
        // LAUNCH_SCENARIO_FLEXIBLE=1 and LAUNCH_SCENARIO_CANVAS=2. Missing OEM fields on
        // other ROMs leave the standard windowing-mode checks in charge.
        val extra = extraMember.read(configuration)
        val scenario = extra?.let { (scenarioMember.read(it) as? Number)?.toInt() }
        val vendorWindowed = scenario == 1 || scenario == 2
        return WindowState(displayId, bounds, windowingMode, resizeMode, densityDpi, token, vendorWindowed)
    }

    fun readUserId(info: ActivityManager.RunningTaskInfo): Int? = (userMember.read(info) as? Number)?.toInt()

    fun userIdentifier(userHandle: UserHandle): Int? = (userHandleMember.read(userHandle) as? Number)?.toInt()

    private fun missing(
        member: String,
        info: ActivityManager.RunningTaskInfo,
    ): WindowState? {
        NeXtepLog.warn(
            "task_info_compat",
            "No $member candidate taskId=${info.taskId} runtime=${info.javaClass.name}",
        )
        return null
    }
}
