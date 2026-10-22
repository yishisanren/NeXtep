package io.github.lujinxin.nextep.launcher

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.DecelerateInterpolator
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.Display
import android.view.WindowManager
import android.graphics.Matrix
import android.graphics.Rect
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.SystemDialogLayoutController
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object LauncherTransformController {
    private data class OriginalTransform(
        val pivotX: Float,
        val pivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
        val animationMatrix: Matrix?,
        val clipBounds: Rect?,
        val screenX: Int,
        val screenY: Int,
    )

    private var decorReference = WeakReference<View>(null)
    private var original: OriginalTransform? = null
    private val assistantRoots = WeakHashMap<View, OriginalTransform>()
    private val assistantTransforms = WeakHashMap<View, TouchCoordinateMapper.Transform>()
    private var appliedTransform: TouchCoordinateMapper.Transform? = null
    private val positionAnimators = WeakHashMap<View, ValueAnimator>()
    private val positionTargets = WeakHashMap<View, TouchCoordinateMapper.Transform>()
    // ColorOS also reads the workspace gate from its launcher animation thread.
    @Volatile
    private var active = false
    private var applyGeneration = 0
    private val handler = Handler(Looper.getMainLooper())

    fun attach(view: View) {
        ensureMainThread()
        if (!LauncherPackageResolver.isWorkspaceSurface(view.context)) return

        val decor = view.rootView ?: view
        if (LauncherPackageResolver.isAssistantScreen(decor.context) && !isAssistantPanel(decor)) {
            // Assistant Activities (profile/settings) are ordinary tasks. SystemUI fits
            // their task leash, including native input; another decor matrix doubles
            // both the scale and offset. Only the separate assistant panel needs this.
            assistantRoots.remove(decor)?.let { restore(decor, it) }
            assistantTransforms.remove(decor)
            positionAnimators.remove(decor)?.cancel()
            positionTargets.remove(decor)
            return
        }
        if (decor.width <= 0 || decor.height <= 0) {
            decor.post { attach(decor) }
            return
        }

        if (LauncherPackageResolver.isAssistantScreen(decor.context)) {
            assistantRoots.getOrPut(decor) { captureOriginal(decor) }
        } else if (decorReference.get() !== decor) {
            restoreCurrentDecor()
            decorReference = WeakReference(decor)
            original = captureOriginal(decor)
        }

        if (active) scheduleApply()
    }

    fun attach(activity: android.app.Activity) {
        activity.window?.decorView?.let(::attach)
    }

    fun attachExistingAssistantWindows(context: android.content.Context) {
        if (!LauncherPackageResolver.isAssistantScreen(context)) return
        runCatching {
            val managerClass = Class.forName("android.view.WindowManagerGlobal")
            val manager = managerClass.getDeclaredMethod("getInstance").apply {
                isAccessible = true
            }.invoke(null)
            val views = managerClass.getDeclaredField("mViews").apply {
                isAccessible = true
            }.get(manager) as? Collection<*> ?: emptyList<Any>()
            val roots = views.filterIsInstance<View>()
            NeXtepLog.info("assistant_root", "Discovered existing roots=${roots.size}")
            roots.forEach(::attach)
        }.onFailure { error ->
            NeXtepLog.warn("assistant_root", "Unable to enumerate existing roots", error)
        }
    }

    fun toggle(): Boolean {
        return setActive(!active)
    }

    fun setActive(requestedActive: Boolean): Boolean {
        ensureMainThread()
        active = requestedActive
        if (!active) {
            LauncherShortcutMenuController.dismissMenus()
            SystemDialogLayoutController.setGeometry(null)
        }
        applyGeneration += 1
        val decor = decorReference.get() ?: assistantRoots.keys.firstOrNull()
        if (decor == null) {
            NeXtepLog.info(
                "launcher_transform",
                "active=$active pending=true; waiting for workspace surface DecorView attach",
            )
            return true
        }
        if (active) scheduleApply() else restoreCurrentDecor(animated = true)
        NeXtepLog.info("launcher_transform", "active=$active decor=${decor.javaClass.name}")
        return true
    }

    fun isActive(): Boolean = active

    fun mapToContent(event: MotionEvent?): TouchCoordinateMapper.Transform? {
        val transform = if (active) appliedTransform else null
        return transform?.takeIf { TouchCoordinateMapper.toContent(event, it) }
    }

    fun mapToContent(view: View, event: MotionEvent?): TouchCoordinateMapper.Transform? {
        val transform = if (active) assistantTransforms[view.rootView ?: view] else null
        return transform?.takeIf { TouchCoordinateMapper.toContent(event, it) }
    }

    fun mapToScreen(event: MotionEvent?, transform: TouchCoordinateMapper.Transform?) {
        TouchCoordinateMapper.toScreen(event, transform)
    }

    private fun applyTo(decor: View) {
        if (LauncherPackageResolver.isAssistantScreen(decor.context) && !isAssistantPanel(decor)) return
        val displayMetrics = decor.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val geometry = SystemServerWorkspaceBridge.activeGeometry(decor.context) ?: WorkspaceGeometry.forDisplay(
            screenWidth,
            screenHeight,
            SystemServerWorkspaceBridge.sidebarSide(decor.context),
            density = displayMetrics.density,
            displayRotation = decor.display?.rotation ?: 0,
        )
        if (!LauncherPackageResolver.isAssistantScreen(decor.context)) {
            SystemDialogLayoutController.setGeometry(geometry)
        }
        val isAssistant = LauncherPackageResolver.isAssistantScreen(decor.context)
        val saved = assistantRoots[decor]
        val sourceBounds = if (isAssistant && saved != null) {
            // Use SystemUI's recorded navigation mode, including zero padding in
            // gesture mode. A page transition must not sample different live insets.
            val insets = geometry.navigationInsets
            Rect(insets.left, insets.top,
                decor.width - insets.right, decor.height - insets.bottom)
        } else Rect(geometry.availableLeft, geometry.availableTop,
            geometry.availableRight, geometry.availableBottom)
        if (sourceBounds.width() <= 0 || sourceBounds.height() <= 0) return
        decor.clipBounds = sourceBounds
        val transform = if (isAssistant && saved != null) {
            val scale = minOf(geometry.contentWidth.toFloat() / sourceBounds.width(),
                geometry.contentHeight.toFloat() / sourceBounds.height())
            TouchCoordinateMapper.Transform(
                scaleX = scale,
                scaleY = scale,
                // The assistant window itself is horizontally translated by Launcher while
                // swiping between pages. Its animation matrix must stay window-local; folding
                // the transient screen location (often -screenWidth while off-screen) into this
                // translation pushes the scaled page outside the workspace.
                translationX = geometry.contentLeft - sourceBounds.left * scale,
                translationY = geometry.contentTop - sourceBounds.top * scale,
            )
        } else {
            TouchCoordinateMapper.Transform(
                scaleX = geometry.contentScale,
                scaleY = geometry.contentScale,
                translationX = geometry.contentTranslationX,
                translationY = geometry.contentTranslationY,
            )
        }

        val current = if (isAssistant) assistantTransforms[decor] else appliedTransform
        // Delayed reapplication must not snap or restart an in-flight side switch.
        if (positionAnimators[decor]?.isRunning == true && positionTargets[decor] == transform) return
        positionAnimators.remove(decor)?.cancel()
        positionTargets[decor] = transform
        // The first fit shrinks the untransformed decor in step with SystemUI's 240ms panel
        // entry; later changes (side switch, control edge) move from the current fit.
        val start = current ?: TouchCoordinateMapper.Transform(1f, 1f, 0f, 0f)
        if (start == transform) writeTransform(decor, transform, isAssistant)
        else animateTransform(decor, start, transform, if (current == null) ENTER_DURATION_MS else SIDE_DURATION_MS,
            isAssistant) { positionTargets.remove(decor) }
    }

    private fun animateTransform(
        decor: View,
        from: TouchCoordinateMapper.Transform,
        to: TouchCoordinateMapper.Transform,
        durationMs: Long,
        isAssistant: Boolean,
        onEnd: () -> Unit,
    ) {
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = durationMs
        animator.interpolator = DecelerateInterpolator(1.6f)
        animator.addUpdateListener {
            val t = it.animatedValue as Float
            writeTransform(decor, TouchCoordinateMapper.Transform(
                lerp(from.scaleX, to.scaleX, t), lerp(from.scaleY, to.scaleY, t),
                lerp(from.translationX, to.translationX, t), lerp(from.translationY, to.translationY, t),
            ), isAssistant)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // A cancelled animator also ends; only the animator still registered may finish.
                if (positionAnimators[decor] === animation) {
                    positionAnimators.remove(decor)
                    writeTransform(decor, to, isAssistant)
                    onEnd()
                }
            }
        })
        positionAnimators[decor] = animator
        animator.start()
    }

    private fun writeTransform(
        decor: View,
        transform: TouchCoordinateMapper.Transform,
        isAssistant: Boolean,
    ) {
        if (isAssistant) {
            decor.animationMatrix = Matrix().apply {
                setValues(
                    floatArrayOf(
                        transform.scaleX, 0f, transform.translationX,
                        0f, transform.scaleY, transform.translationY,
                        0f, 0f, 1f,
                    ),
                )
            }
            assistantTransforms[decor] = transform
        } else {
            decor.pivotX = 0f
            decor.pivotY = 0f
            decor.scaleX = transform.scaleX
            decor.scaleY = transform.scaleY
            decor.translationX = transform.translationX
            decor.translationY = transform.translationY
        }
        if (!isAssistant) appliedTransform = transform
    }

    private fun scheduleApply() {
        val generation = ++applyGeneration
        REAPPLY_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                if (!active || generation != applyGeneration) return@postDelayed
                decorReference.get()?.let(::applyTo)
                assistantRoots.keys.toList().forEach { root ->
                    if (root.parent != null) applyTo(root)
                }
            }, delay)
        }
    }

    private fun restoreCurrentDecor(animated: Boolean = false) {
        positionAnimators.values.toList().forEach { it.cancel() }
        positionAnimators.clear()
        positionTargets.clear()
        val decor = decorReference.get()
        val saved = original
        val current = appliedTransform
        if (decor != null && saved != null) {
            if (animated && current != null) {
                // Grow back to full size while SystemUI fades its panels out (180ms).
                animateTransform(decor, current, TouchCoordinateMapper.Transform(
                    saved.scaleX, saved.scaleY, saved.translationX, saved.translationY), EXIT_DURATION_MS, false) {
                    restore(decor, saved)
                    appliedTransform = null
                }
            } else restore(decor, saved)
        }
        appliedTransform = null
        assistantRoots.toMap().forEach { (root, savedRoot) -> restore(root, savedRoot) }
        assistantRoots.clear()
        assistantTransforms.clear()
    }

    private fun captureOriginal(view: View): OriginalTransform {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return OriginalTransform(
            pivotX = view.pivotX,
            pivotY = view.pivotY,
            scaleX = view.scaleX,
            scaleY = view.scaleY,
            translationX = view.translationX,
            translationY = view.translationY,
            animationMatrix = view.animationMatrix?.let(::Matrix),
            clipBounds = view.clipBounds?.let(::Rect),
            screenX = location[0],
            screenY = location[1],
        )
    }

    private fun restore(view: View, saved: OriginalTransform) {
        view.pivotX = saved.pivotX
        view.pivotY = saved.pivotY
        view.scaleX = saved.scaleX
        view.scaleY = saved.scaleY
        view.translationX = saved.translationX
        view.translationY = saved.translationY
        view.animationMatrix = saved.animationMatrix
        view.clipBounds = saved.clipBounds
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Launcher transforms must run on the main thread"
        }
    }

    private fun isAssistantPanel(view: View): Boolean {
        if (view.display?.displayId != Display.DEFAULT_DISPLAY) return false
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return false
        // The ColorOS 17 panel is a TYPE_APPLICATION window attached to Launcher's
        // HOME token, with the package itself as its title. HOME's decor transform
        // does not affect that separate window. Profile/settings BASE_APPLICATION
        // windows have their own tasks and are fitted by SystemUI instead.
        return params.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW ||
            (params.type == WindowManager.LayoutParams.TYPE_APPLICATION &&
                params.title?.toString() == view.context.packageName)
    }

    private val REAPPLY_DELAYS_MS = longArrayOf(0L, 120L, 300L, 600L, 1_200L, 2_000L)
    // Mirrors NeXtepWindowController's panel entry/exit timing.
    private const val ENTER_DURATION_MS = 240L
    private const val EXIT_DURATION_MS = 180L
    private const val SIDE_DURATION_MS = 260L
}
