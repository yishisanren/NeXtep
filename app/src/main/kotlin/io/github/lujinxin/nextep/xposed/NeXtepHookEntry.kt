package io.github.lujinxin.nextep.xposed

import android.util.Log
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.launcher.ColorOsLauncherHook
import io.github.lujinxin.nextep.multiapp.OplusMultiAppHook
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.safety.EmergencyDisable
import io.github.lujinxin.nextep.safety.FeatureGate
import io.github.lujinxin.nextep.systemui.SystemUiHook
import io.github.lujinxin.nextep.systemserver.SystemServerHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class NeXtepHookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        NeXtepLog.info("module", "NeXtepHookEntry loaded; systemServer=${param.isSystemServer}")
        if (param.isSystemServer) {
            log(Log.INFO, "NeXtep", "system_server module generation loaded")
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, "NeXtep", "system_server starting callback received")
        if (EmergencyDisable.isRequested() ||
            !FeatureGate.SYSTEM_SERVER_PATCHES.defaultEnabled
        ) {
            log(Log.WARN, "NeXtep", "system_server hooks disabled by safety gate")
            return
        }
        SystemServerHook.install(this, param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        val packageName = param.packageName
        NeXtepLog.info(
            "dispatcher",
            "Observed package=$packageName",
        )

        if (packageName == "com.android.systemui") {
            if (!EmergencyDisable.isRequested() && FeatureGate.SYSTEM_UI.defaultEnabled) {
                TaskSurfaceCompat.initialize(param.classLoader)
                SystemUiHook.install(this, param.classLoader)
            }
            return
        }

        if (packageName == "io.github.lujinxin.nextep") {
            return
        }

        if (packageName == "com.oplus.multiapp") {
            if (!EmergencyDisable.isRequested() &&
                FeatureGate.LAUNCHER_TRANSFORM.defaultEnabled
            ) {
                OplusMultiAppHook.install(this, param.classLoader)
            }
            return
        }

        if (EmergencyDisable.isRequested() || !FeatureGate.LAUNCHER_TRANSFORM.defaultEnabled) return

        ColorOsLauncherHook.install(this, packageName, param.classLoader)
    }
}
