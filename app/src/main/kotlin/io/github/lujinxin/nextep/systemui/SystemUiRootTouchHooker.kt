package io.github.lujinxin.nextep.systemui

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.libxposed.api.XposedInterface

class SystemUiRootTouchHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val view = chain.thisObject as? ViewGroup ?: return chain.proceed()
        val event = chain.getArg(0) as? MotionEvent ?: return chain.proceed()
        if (view.rootView !== view || view.width <= 0 || !isFullWidthRoot(view)) {
            return chain.proceed()
        }
        val depth = dispatchDepth.get() ?: 0
        if (depth > 0) return chain.proceed()
        dispatchDepth.set(depth + 1)
        return try {
            SystemUiRootTransformController.observeRoot(view)
            SystemUiRootGestureRuntime.dispatch(view, event) {
                val transform = SystemUiRootTransformController.mapToContent(view, event)
                try {
                    chain.proceed()
                } finally {
                    SystemUiRootTransformController.mapToScreen(event, transform)
                }
            }
        } finally {
            dispatchDepth.remove()
        }
    }

    private fun isFullWidthRoot(view: ViewGroup): Boolean {
        val screenWidth = view.resources.displayMetrics.widthPixels
        return view.width >= screenWidth - 2
    }

    private companion object {
        val dispatchDepth = ThreadLocal<Int>()
    }
}

private object SystemUiRootGestureRuntime {
    private var shadeDownX = 0f
    private var shadeDownY = 0f
    private var shadeTracking = false
    private var shadeTransformStarted = false

    fun dispatch(view: ViewGroup, event: MotionEvent, proceed: () -> Any?): Any? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!isTriggerAllowed(view.context)) return proceed()
            val topTrackingLimit = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                SHADE_TRACKING_TOP_DP,
                view.resources.displayMetrics,
            )
            shadeTracking = event.rawY <= topTrackingLimit
            shadeTransformStarted = false
            shadeDownX = event.rawX
            shadeDownY = event.rawY
        }

        if (shadeTracking && !shadeTransformStarted &&
            event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            val deltaX = event.rawX - shadeDownX
            val deltaY = event.rawY - shadeDownY
            val threshold = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                SHADE_ACTIVATION_DP,
                view.resources.displayMetrics,
            )
            if (deltaY >= threshold && deltaY > kotlin.math.abs(deltaX)) {
                shadeTransformStarted = if (
                    SystemUiRootTransformController.isNotificationShadeRoot(view)
                ) {
                    SystemUiRootTransformController.beginShadeTransform(view)
                } else {
                    SystemUiRootTransformController.beginObservedShadeTransform()
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            shadeTracking = false
        }

        return proceed()
    }

    private fun isTriggerAllowed(context: Context): Boolean = try {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        keyguard?.isKeyguardLocked != true && power?.isInteractive == true
    } catch (error: Throwable) {
        NeXtepLog.warn("systemui_root_touch", "Trigger-state query failed", error)
        false
    }

    private const val SHADE_ACTIVATION_DP = 12f
    private const val SHADE_TRACKING_TOP_DP = 64f
}
