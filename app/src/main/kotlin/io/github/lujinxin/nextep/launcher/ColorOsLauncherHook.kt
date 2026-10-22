package io.github.lujinxin.nextep.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.os.Process
import android.os.UserHandle
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.SystemDialogLayoutController
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.xposed.HookGuard
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object ColorOsLauncherHook {
    fun install(module: XposedModule, packageName: String, classLoader: ClassLoader) {
        HookGuard.run("launcher_menu_geometry") { LauncherMenuGeometryController.install(module, classLoader) }
        HookGuard.run("launcher_shortcut_menu") {
            LauncherShortcutMenuController.install(module)
        }
        HookGuard.run("launcher_dialog_layout") {
            Class.forName("android.view.WindowManagerGlobal").declaredMethods
                .filter { method ->
                    method.name in setOf("addView", "updateViewLayout") &&
                        method.parameterTypes.firstOrNull() == View::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val view = chain.getArg(0) as? View
                            val params = chain.args.getOrNull(1) as? WindowManager.LayoutParams
                            if (view != null && params != null &&
                                LauncherPackageResolver.isCurrentHome(view.context)
                            ) {
                                HookGuard.run("launcher_dialog_layout_apply") {
                                    LauncherShortcutMenuController.beforeLayout(params)
                                    // Dialogs have their own windows and do not inherit the
                                    // Activity decor transform. Resize the actual window so
                                    // its text, buttons and native input share the same bounds.
                                    SystemDialogLayoutController.beforeLayout(
                                        view, params, includeApplicationDialogs = true,
                                    )
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
        }
        if (packageName == "com.android.launcher") {
            HookGuard.run("launcher_workspace_app_animation") {
                // ColorOS AppLauncher accepts a null source View: it skips icon
                // preloading/remote animation but retains its startup and user checks.
                val method = classLoader.loadClass("com.android.launcher3.BaseDraggingActivity")
                    .getDeclaredMethod("startActivitySafely", View::class.java, Intent::class.java,
                        classLoader.loadClass("com.android.launcher3.model.data.ItemInfo"))
                check(method.returnType == Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val intent = chain.args.getOrNull(1) as? Intent
                        if (!WorkspaceLauncherAnimationPolicy.useStaticSource(
                                LauncherTransformController.isActive(), intent)) return chain.proceed()
                        val args = chain.args.toTypedArray()
                        args[0] = null
                        return chain.proceed(args)
                    }
                })
                NeXtepLog.info("launcher_workspace_app_animation", "Installed static workspace launch source")
                module.log(android.util.Log.INFO, "NeXtep", "Installed static workspace launch source")
            }
            HookGuard.run("launcher_recent_task_selection") {
                val recents = classLoader.loadClass("com.android.quickstep.views.RecentsView")
                listOf(
                    recents.getDeclaredMethod("onAttachedToWindow"),
                    recents.getDeclaredMethod("setOverviewStateEnabled", Boolean::class.javaPrimitiveType),
                ).forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            (chain.thisObject as? View)?.let(LauncherRecentTaskSelection::observe)
                            return result
                        }
                    })
                }
            }
            HookGuard.run("launcher_workspace_home_gesture_animation") {
                // Gesture HOME uses OplusLauncherSwipeHandlerV2Impl, not liteAppCloseAnim.
                // Its native light-animation branch disables the floating-icon spring while
                // retaining the OEM gesture factory, completion callbacks and surface cleanup.
                // The spring's icon coordinates assume an unscaled Launcher root.
                val features = classLoader.loadClass("com.android.common.util.AppFeatureUtils")
                val method = features.getDeclaredMethod("isHomeGestureLightAnimation")
                check(method.returnType == Boolean::class.javaPrimitiveType) {
                    "Unexpected ColorOS home-gesture animation signature"
                }
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!LauncherTransformController.isActive()) return chain.proceed()
                        return true
                    }
                })
                NeXtepLog.info(
                    "launcher_workspace_home_gesture_animation",
                    "Native light HOME gesture hook installed",
                )
            }
            HookGuard.run("launcher_workspace_close_animation") {
                // ColorOS already provides an icon-free animated return for special surfaces.
                // Its icon spring assumes an unscaled Launcher and is invalid in our workspace.
                val manager = classLoader.loadClass(
                    "com.android.launcher3.OplusQuickstepTransitionManagerImpl",
                )
                val method = manager.declaredMethods.single {
                    it.name == "liteAppCloseAnim" && it.returnType == Boolean::class.javaPrimitiveType &&
                        it.parameterTypes.size == 1 && it.parameterTypes[0].isArray
                }
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!LauncherTransformController.isActive()) return chain.proceed()
                        NeXtepLog.info("launcher_workspace_close_animation", "Using native light return animation")
                        return true
                    }
                })
            }
        }
        HookGuard.run("launcher_hooks") {
            val onPostResume = Activity::class.java.getDeclaredMethod("onPostResume")
            val dispatchTouchEvent = Activity::class.java.getDeclaredMethod(
                "dispatchTouchEvent",
                MotionEvent::class.java,
            )
            onPostResume.isAccessible = true
            dispatchTouchEvent.isAccessible = true
            module.hook(onPostResume).intercept(LauncherLifecycleHooker())
            module.hook(dispatchTouchEvent).intercept(LauncherTouchHooker())
            if (packageName == ASSISTANT_SCREEN_PACKAGE) {
                installAssistantWindowHooks(module)
            }
            Instrumentation::class.java.declaredMethods
                .filter { method ->
                    method.name == "execStartActivity" &&
                        method.parameterTypes.any { it == Intent::class.java }
                }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(LauncherStartActivityHooker())
                }
            NeXtepLog.info("launcher_hooks", "Public Activity hooks installed for $packageName")
        }
    }

    private fun installAssistantWindowHooks(module: XposedModule) {
        HookGuard.run("assistant_attach_hook") {
            val onAttachedToWindow = View::class.java.getDeclaredMethod("onAttachedToWindow")
            onAttachedToWindow.isAccessible = true
            module.hook(onAttachedToWindow).intercept(WorkspaceSurfaceAttachHooker())
        }
        HookGuard.run("assistant_touch_hook") {
            val rootDispatchTouch = ViewGroup::class.java.getDeclaredMethod(
                "dispatchTouchEvent",
                MotionEvent::class.java,
            )
            rootDispatchTouch.isAccessible = true
            module.hook(rootDispatchTouch).intercept(WorkspaceSurfaceTouchHooker())
        }
        HookGuard.run("assistant_window_add_hook") {
            Class.forName("android.view.WindowManagerGlobal").declaredMethods
                .filter { method ->
                    method.name == "addView" &&
                        method.parameterTypes.firstOrNull() == View::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(AssistantWindowAddHooker())
                }
        }
        HookGuard.run("assistant_view_root_hook") {
            Class.forName("android.view.ViewRootImpl").declaredMethods
                .filter { method ->
                    method.name == "setView" &&
                        method.parameterTypes.firstOrNull() == View::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(AssistantViewRootHooker())
                }
        }
    }
}

class AssistantWindowAddHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val view = chain.getArg(0) as? View
        val result = chain.proceed()
        view?.post {
            LauncherRuntime.onWorkspaceSurfaceAttached(view.context)
            LauncherTransformController.attach(view)
            LauncherTransformController.setActive(
                SystemServerWorkspaceBridge.isWorkspaceActive(view.context),
            )
        }
        return result
    }
}

class AssistantViewRootHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val view = chain.getArg(0) as? View
        val result = chain.proceed()
        if (view != null) {
            view.post {
                NeXtepLog.info(
                    "assistant_root",
                    "Attached ${view.javaClass.name} size=${view.width}x${view.height}",
                )
                LauncherRuntime.onWorkspaceSurfaceAttached(view.context)
                LauncherTransformController.attach(view)
                LauncherTransformController.setActive(
                    SystemServerWorkspaceBridge.isWorkspaceActive(view.context),
                )
            }
        }
        return result
    }
}

private const val ASSISTANT_SCREEN_PACKAGE = "com.coloros.assistantscreen"

class LauncherStartActivityHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val intent = chain.args.firstOrNull { it is Intent } as? Intent
        val context = chain.args.firstOrNull { it is Context } as? Context
        val targetUser = chain.args.firstOrNull { it is UserHandle } as? UserHandle
            ?: Process.myUserHandle()
        if (intent != null && context != null &&
            LauncherRuntime.redirectActivityLaunch(context, intent, targetUser)
        ) {
            return null
        }
        return chain.proceed()
    }
}

class LauncherLifecycleHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val activity = chain.thisObject as? Activity
        val result = chain.proceed()
        if (activity != null) {
            HookGuard.run("launcher_lifecycle") {
                LauncherRuntime.onHomeResumed(activity)
            }
        }
        return result
    }
}

class LauncherTouchHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val activity = chain.thisObject as? Activity
        if (activity != null && LauncherPackageResolver.isAssistantScreen(activity)) {
            return chain.proceed()
        }
        val event = chain.getArg(0) as? MotionEvent
        val transform = LauncherTransformController.mapToContent(event)
        return try {
            chain.proceed()
        } finally {
            LauncherTransformController.mapToScreen(event, transform)
        }
    }
}

class WorkspaceSurfaceAttachHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val view = chain.thisObject as? View
        val result = chain.proceed()
        if (view != null && view.rootView === view) {
            view.post {
                LauncherRuntime.onWorkspaceSurfaceAttached(view.context)
                LauncherTransformController.attach(view)
                LauncherTransformController.setActive(
                    SystemServerWorkspaceBridge.isWorkspaceActive(view.context),
                )
            }
        }
        return result
    }
}

class WorkspaceSurfaceTouchHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val view = chain.thisObject as? ViewGroup ?: return chain.proceed()
        if (view.rootView !== view) return chain.proceed()
        val event = chain.getArg(0) as? MotionEvent
        val transform = LauncherTransformController.mapToContent(view, event)
        return try {
            chain.proceed()
        } finally {
            LauncherTransformController.mapToScreen(event, transform)
        }
    }
}
