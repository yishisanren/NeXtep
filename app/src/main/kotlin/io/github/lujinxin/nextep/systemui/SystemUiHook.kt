package io.github.lujinxin.nextep.systemui

import android.app.Application
import android.app.Instrumentation
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.xposed.HookGuard
import io.github.libxposed.api.XposedModule
import android.view.MotionEvent
import android.view.ViewGroup
import io.github.lujinxin.nextep.safety.FeatureGate

object SystemUiHook {
    private data class StartupCandidate(val className: String, val methodName: String)

    private val touchRootCandidates = listOf(
        "com.android.systemui.statusbar.window.StatusBarWindowView",
        "com.android.systemui.shade.NotificationShadeWindowView",
        "com.android.systemui.statusbar.phone.StatusBarWindowView",
        "com.android.systemui.statusbar.phone.PhoneStatusBarView",
    )

    private val startupCandidates = listOf(
        StartupCandidate("com.android.systemui.SystemUIApplication", "onCreate"),
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        HookGuard.run("workspace_display_transition") { WorkspaceDisplayTransitionHook.install(module, classLoader) }
        HookGuard.run("task_surface_ownership") {
            val organizerClass = classLoader.loadClass("com.android.wm.shell.ShellTaskOrganizer")
            val methods = organizerClass.declaredMethods.filter {
                it.name in setOf("onTaskAppeared", "onTaskInfoChanged", "onTaskVanished") &&
                    it.parameterTypes.firstOrNull() == android.app.ActivityManager.RunningTaskInfo::class.java
            }
            check(methods.map { it.name }.toSet().size == 3) { "Incomplete task ownership callbacks" }
            methods.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                    override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                        val info = chain.args.firstOrNull() as? android.app.ActivityManager.RunningTaskInfo
                        if (info != null) HookGuard.run("task_surface_ownership_update") {
                            if (method.name == "onTaskVanished") {
                                io.github.lujinxin.nextep.framework.TaskSurfaceCompat.forgetTaskSurface(info.taskId)
                            } else {
                                io.github.lujinxin.nextep.framework.TaskSurfaceCompat.observeTaskInfo(
                                    info, if (method.name == "onTaskAppeared") chain.args.getOrNull(1) else null,
                                )
                            }
                        }
                        return chain.proceed()
                    }
                })
            }
        }
        HookGuard.run("task_surface_transaction_guard") {
            val transactionClass = classLoader.loadClass("android.view.SurfaceControl\$Transaction")
            val methods = transactionClass.declaredMethods.filter {
                it.name == "apply" && !java.lang.reflect.Modifier.isNative(it.modifiers)
            }
            check(methods.isNotEmpty()) { "No compatible SurfaceControl.Transaction.apply method" }
            methods.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                    override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                        val transaction = chain.thisObject ?: return chain.proceed()
                        return io.github.lujinxin.nextep.framework.TaskSurfaceCompat
                            .interceptTransactionApply(transaction) { chain.proceed() }
                    }
                })
            }
            NeXtepLog.info("task_surface_transaction_guard", "Installed atomic task fitting at native commit")
            module.log(android.util.Log.INFO, "NeXtep", "Installed slot exchange settlement guard; protocol=46")
        }
        HookGuard.run("slot_retention_display_area") {
            classLoader.loadClass("com.android.wm.shell.RootTaskDisplayAreaOrganizer")
                .declaredMethods.filter {
                    it.name in setOf("onDisplayAreaAppeared", "onDisplayAreaInfoChanged", "onDisplayAreaVanished")
                }.forEach { method ->
                    module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                        override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                            HookGuard.run("slot_retention_display_area_info") {
                                chain.args.firstOrNull()?.let {
                                    if (method.name == "onDisplayAreaVanished") {
                                        io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat.forgetDisplayArea(it)
                                    } else {
                                        io.github.lujinxin.nextep.framework.WindowContainerTransactionCompat.observeDisplayArea(it)
                                    }
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
        }
        HookGuard.run("system_dialog_layout") {
            Class.forName("android.view.WindowManagerGlobal").declaredMethods
                .filter { it.name in setOf("addView", "updateViewLayout") &&
                    it.parameterTypes.firstOrNull() == android.view.View::class.java }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                        override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                            val view = chain.args.firstOrNull() as? android.view.View
                            val params = chain.args.getOrNull(1) as? android.view.WindowManager.LayoutParams
                            if (view != null && params != null) {
                                HookGuard.run("system_dialog_layout_apply") {
                                    SystemDialogLayoutController.beforeLayout(view, params)
                                    SystemUiShadeInputController.beforeLayout(view, params)
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
        }
        SystemUiBrightnessMirrorHook.install(module, classLoader)
        HookGuard.run("systemui_framework_startup") {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java,
            )
            method.isAccessible = true
            module.hook(method).intercept(SystemUiApplicationHooker())
            NeXtepLog.info(
                "systemui_framework_startup",
                "Installed framework Application bootstrap fallback",
            )
        }

        if (FeatureGate.TOP_RIGHT_STATUS_BAR_GESTURE.defaultEnabled) {
            HookGuard.run("systemui_root_touch") {
                val method = ViewGroup::class.java.getDeclaredMethod(
                    "dispatchTouchEvent",
                    MotionEvent::class.java,
                )
                method.isAccessible = true
                module.hook(method).intercept(SystemUiRootTouchHooker())
                NeXtepLog.info("systemui_root_touch", "Installed public root touch observer")
            }
            touchRootCandidates.forEach { className ->
                HookGuard.run("systemui_root_touch_override") {
                    val rootClass = classLoader.loadClass(className)
                    val method = rootClass.getDeclaredMethod(
                        "dispatchTouchEvent",
                        MotionEvent::class.java,
                    )
                    method.isAccessible = true
                    module.hook(method).intercept(SystemUiRootTouchHooker())
                    NeXtepLog.info(
                        "systemui_root_touch_override",
                        "Installed override observer on $className",
                    )
                }
            }
        }

        var installed = false
        for (candidate in startupCandidates) {
            val success = HookGuard.run("systemui_startup_candidate") {
                val startupClass = classLoader.loadClass(candidate.className)
                val method = startupClass.getDeclaredMethod(candidate.methodName)
                method.isAccessible = true
                module.hook(method).intercept(SystemUiStartupHooker())
                NeXtepLog.info(
                    "systemui_startup_candidate",
                    "Installed ${candidate.className}#${candidate.methodName}",
                )
            }
            if (success) {
                installed = true
                break
            }
        }
        if (!installed) {
            NeXtepLog.warn(
                "systemui_startup_candidate",
                "No startup candidate installed; device artifact analysis is required",
            )
        }
    }
}
