package io.github.lujinxin.nextep.systemui

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.lang.reflect.Proxy

object SystemUiBottomCornerGestureMonitor {
    private var monitor: Any? = null
    private var receiver: Any? = null

    fun install(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { install(context) }
            return
        }
        if (receiver != null) return
        var newMonitor: Any? = null
        var newReceiver: Any? = null
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY) ?: error("Default display unavailable")
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            val managerClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val inputMonitor = managerClass.getMethod(
                "monitorGestureInput", String::class.java, Int::class.javaPrimitiveType,
            ).invoke(manager, "NeXtep-bottom-corners", Display.DEFAULT_DISPLAY)
            newMonitor = inputMonitor
            val pilfer = inputMonitor.javaClass.getMethod("pilferPointers")
            val size = Point()
            var detector = BottomCornerGestureDetector(context.resources.displayMetrics.density)
            val receiverClass = Class.forName(
                "android.view.BatchedInputEventReceiver\$SimpleBatchedInputEventReceiver",
            )
            val listenerClass = Class.forName("${receiverClass.name}\$InputEventListener")
            val mainHandler = Handler(Looper.getMainLooper())
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader, arrayOf(listenerClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "NeXtepBottomCornerInputListener"
                    "onInputEvent" -> {
                        // The framework receiver acknowledges/recycles input even if a callback fails.
                        runCatching {
                            val event = args?.firstOrNull() as? MotionEvent
                            if (event == null || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
                                !SystemUiRuntime.isStatusBarGestureEnabled() ||
                                (event.actionMasked != MotionEvent.ACTION_DOWN && !detector.isObserving) ||
                                power?.isInteractive != true || keyguard?.isKeyguardLocked != false
                            ) {
                                detector.reset()
                                false
                            } else {
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                    detector = BottomCornerGestureDetector(context.resources.displayMetrics.density)
                                }
                                @Suppress("DEPRECATION")
                                display.getRealSize(size)
                                val result = detector.observe(
                                    event.actionMasked, event.rawX, event.rawY, event.pointerCount,
                                    event.eventTime, size.x, size.y, display.rotation,
                                )
                                if (result == BottomCornerGestureDetector.Result.CONFIRMED) {
                                    // Cancel app/navigation input only after the diagonal is confirmed.
                                    pilfer.invoke(inputMonitor)
                                    mainHandler.post {
                                        runCatching {
                                            if (power?.isInteractive == true && keyguard?.isKeyguardLocked == false) {
                                                SystemUiRuntime.toggleWorkspaceFromGesture()
                                            }
                                        }.onFailure { error ->
                                            NeXtepLog.warn("bottom_corner_gesture", "Workspace toggle failed", error)
                                        }
                                    }
                                    true
                                } else false
                            }
                        }.getOrElse { error ->
                            detector.reset()
                            NeXtepLog.warn("bottom_corner_gesture", "Input observation failed open", error)
                            false
                        }
                    }
                    else -> null
                }
            }
            newReceiver = receiverClass.getConstructor(
                Class.forName("android.view.InputChannel"), Looper::class.java,
                Choreographer::class.java, listenerClass,
            ).newInstance(
                inputMonitor.javaClass.getMethod("getInputChannel").invoke(inputMonitor),
                Looper.getMainLooper(), Choreographer.getInstance(), listener,
            )
            receiverClass.getMethod("setBatchingEnabled", Boolean::class.javaPrimitiveType)
                .invoke(newReceiver, false)
            monitor = inputMonitor
            receiver = newReceiver
            NeXtepLog.info("bottom_corner_gesture", "Installed default-display corner gesture monitor")
        }.onFailure { error ->
            for (resource in listOf(newReceiver, newMonitor)) {
                runCatching { resource?.javaClass?.getMethod("dispose")?.invoke(resource) }
            }
            NeXtepLog.warn("bottom_corner_gesture", "Unable to install gesture monitor", error)
        }
    }
}
