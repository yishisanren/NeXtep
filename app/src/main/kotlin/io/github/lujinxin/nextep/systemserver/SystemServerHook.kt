package io.github.lujinxin.nextep.systemserver

import android.content.res.Configuration
import android.util.Log
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.safety.FeatureGate
import io.github.lujinxin.nextep.xposed.HookGuard
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

object SystemServerHook {
    private val installed = AtomicBoolean(false)
    private val aspectClassCandidates = listOf(
        "com.android.server.wm.AppCompatAspectRatioOverrides",
        "com.android.server.wm.LetterboxUiController",
    )
    private val sizeCompatClassCandidates = listOf(
        "com.android.server.wm.ActivityRecord",
        "com.android.server.wm.AppCompatSizeCompatModePolicy",
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        HookGuard.run("workspace_ime_target") { WorkspaceImeTargetHook.install(module, classLoader) }
        HookGuard.run("workspace_ime_surface") { WorkspaceImeSurfaceHook.install(module, classLoader) }
        HookGuard.run("workspace_content_drag") { WorkspaceContentDragHook.install(module, classLoader) }
        HookGuard.run("workspace_navigation") { WorkspaceNavigationBarHook.install(module, classLoader) }
        HookGuard.run("slot_native_layout") { WorkspaceSlotNativeLayoutHook.install(module, classLoader) }
        val workspaceTasksInstalled = HookGuard.run("workspace_task_surface") {
            WorkspaceTaskSurfaceHook.install(module, classLoader)
        }
        val nativeLayerHooker = NativeFloatingWindowLayerHooker()
        val nativeLayersInstalled = HookGuard.run("native_floating_layer") {
            val method = classLoader.loadClass("com.android.server.wm.DisplayContent")
                .getDeclaredMethod("prepareSurfaces")
                .apply { isAccessible = true }
            module.hook(method).intercept(nativeLayerHooker)
        }
        val workspaceHierarchyInstalled = HookGuard.run("workspace_display_hierarchy") {
            WorkspaceDisplayHierarchyHook.install(module, classLoader, nativeLayerHooker)
        }
        val aspectInstalled = installAspectHook(module, classLoader)
        val sceneInstalled = HookGuard.run("workbench_scene") { WorkbenchTaskPolicyHook.installScenes(module, classLoader) }
        val recentsInstalled = HookGuard.run("workbench_recents") { WorkbenchTaskPolicyHook.installRecents(module, classLoader) }
        val sizeCompatInstalled = installSizeCompatHook(module, classLoader)
        val landscapeRotationInstalled = HookGuard.run("workspace_landscape_rotation") {
            val rotationClass = classLoader.loadClass("com.android.server.wm.DisplayRotation")
            val method = rotationClass.getDeclaredMethod(
                "rotationForOrientation",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            module.hook(method).intercept(WorkspaceLandscapeRotationHooker())
        }
        val slotVideoExitInstalled = HookGuard.run("slot_video_exit") {
            check(FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled)
            val activityClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")
            val method = activityClass.declaredMethods.single {
                it.name == "onDisplayChanged" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "com.android.server.wm.DisplayContent"
            }.apply { isAccessible = true }
            module.hook(method).intercept(SlotVideoFullscreenExitHooker { message ->
                module.log(Log.INFO, "NeXtep", message)
            })
        }
        val slotConfigInstalled = HookGuard.run("slot_display_config") {
            val displayClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
            val method = displayClass.declaredMethods.single {
                it.name == "computeScreenConfiguration" &&
                    it.parameterTypes.contentEquals(arrayOf(Configuration::class.java))
            }
            method.isAccessible = true
            module.hook(method).intercept(SlotDisplayConfigurationHooker())
            NeXtepLog.info("slot_display_config", "Installed package-independent slot capability matching")
        }
        if (!aspectInstalled && !sizeCompatInstalled &&
            !slotConfigInstalled && !slotVideoExitInstalled && !landscapeRotationInstalled && !nativeLayersInstalled && !workspaceTasksInstalled && !workspaceHierarchyInstalled && !sceneInstalled && !recentsInstalled
        ) {
            // Nothing was installed; keep the guard open for a potential future retry.
            installed.set(false)
            module.log(Log.ERROR, "NeXtep", "system_server hooks failed open: no compatible target found")
        } else {
            module.log(
                Log.INFO,
                "NeXtep",
                "system_server hooks installed: fixed-orientation aspect=$aspectInstalled " +
                    "size-compat insets=$sizeCompatInstalled " +
                    "slot config=$slotConfigInstalled slot video exit=$slotVideoExitInstalled " +
                    "landscape rotation=$landscapeRotationInstalled native floating layers=$nativeLayersInstalled " +
                    "workspace task fitting=$workspaceTasksInstalled display hierarchy=$workspaceHierarchyInstalled " +
                    "scene requests=$sceneInstalled recents privacy=$recentsInstalled",
            )
        }
    }

    private fun installAspectHook(module: XposedModule, classLoader: ClassLoader): Boolean {
        if (!FeatureGate.FIXED_ORIENTATION_LETTERBOX.defaultEnabled) return true
        return HookGuard.run("fixed_orientation_aspect") {
            val targetClass = aspectClassCandidates.firstNotNullOfOrNull { name ->
                runCatching { classLoader.loadClass(name) }.getOrNull()
            } ?: error("No fixed-orientation aspect policy class")
            val method = generateSequence(targetClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { candidate ->
                    candidate.name == "getFixedOrientationLetterboxAspectRatio" &&
                        candidate.parameterTypes.size == 1 &&
                        candidate.parameterTypes[0] == Configuration::class.java
                } ?: error("No compatible getFixedOrientationLetterboxAspectRatio method")
            method.isAccessible = true
            module.hook(method).intercept(FixedOrientationAspectRatioHooker())
            NeXtepLog.info(
                "fixed_orientation_aspect",
                "Installed ${targetClass.name}#${method.name}",
            )
        }
    }

    private fun installSizeCompatHook(module: XposedModule, classLoader: ClassLoader): Boolean {
        if (!FeatureGate.SIZE_COMPAT_DISPLAY_INSETS.defaultEnabled) return true
        return HookGuard.run("size_compat_display_insets") {
            val methods = sizeCompatClassCandidates.mapNotNull { name ->
                runCatching { classLoader.loadClass(name) }.getOrNull()
            }.flatMap { targetClass ->
                generateSequence(targetClass as Class<*>?) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .filter { method ->
                        method.name == "shouldCreateAppCompatDisplayInsets" &&
                            method.parameterTypes.isEmpty() &&
                            method.returnType == Boolean::class.javaPrimitiveType
                    }
                    .map { targetClass to it }
                    .toList()
            }
            if (methods.isEmpty()) error("No compatible size-compat display-insets method")
            methods.forEach { (targetClass, method) ->
                method.isAccessible = true
                module.hook(method).intercept(SizeCompatDisplayInsetsHooker())
                NeXtepLog.info(
                    "size_compat_display_insets",
                    "Installed ${targetClass.name}#${method.name}",
                )
            }
        }
    }
}
