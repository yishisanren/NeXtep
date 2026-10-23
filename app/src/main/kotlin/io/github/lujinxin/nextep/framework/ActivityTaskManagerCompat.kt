package io.github.lujinxin.nextep.framework

import android.graphics.Rect
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.lang.reflect.Method

object ActivityTaskManagerCompat {
    private val service: Any? by lazy {
        runCatching {
            val type = Class.forName("android.app.ActivityTaskManager")
            val getService = type.getDeclaredMethod("getService").apply { isAccessible = true }
            getService.invoke(null)
        }.onFailure { error ->
            NeXtepLog.error("activity_task_manager", "getService unavailable", error)
        }.getOrNull()
    }

    fun resizeTask(taskId: Int, bounds: Rect): Result<Unit> = invokeTaskMethod(
        names = listOf("resizeTask"),
        taskId = taskId,
        secondArgument = Rect(bounds),
    )

    fun setTaskWindowingMode(taskId: Int, windowingMode: Int): Result<Unit> = invokeTaskMethod(
        names = listOf("setTaskWindowingMode", "setTaskWindowingModeSplitScreenPrimary"),
        taskId = taskId,
        secondArgument = windowingMode,
    )

    fun setTaskResizeable(taskId: Int, resizeMode: Int): Result<Unit> = invokeTaskMethod(
        names = listOf("setTaskResizeable"),
        taskId = taskId,
        secondArgument = resizeMode,
    )

    fun moveTaskToDisplay(taskId: Int, displayId: Int): Result<Unit> = invokeTaskMethod(
        names = listOf("moveRootTaskToDisplay", "moveTaskToDisplay"),
        taskId = taskId,
        secondArgument = displayId,
    )

    fun removeTask(taskId: Int): Result<Unit> = runCatching {
        val target = checkNotNull(service) { "ActivityTaskManager service unavailable" }
        val method = findSingleIntMethod(target.javaClass, listOf("removeTask"))
            ?: error("No compatible removeTask candidate")
        val removed = method.invoke(target, taskId)
        check(removed !is Boolean || removed) { "removeTask rejected taskId=$taskId" }
        NeXtepLog.info("activity_task_manager", "Removed taskId=$taskId")
    }

    fun resolveWindowingMode(fieldName: String): Int? = runCatching {
        val type = Class.forName("android.app.WindowConfiguration")
        type.getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)
    }.onFailure { error ->
        NeXtepLog.warn("activity_task_manager", "Windowing mode $fieldName unavailable", error)
    }.getOrNull()

    private fun invokeTaskMethod(
        names: List<String>,
        taskId: Int,
        secondArgument: Any,
    ): Result<Unit> = runCatching {
        val target = checkNotNull(service) { "ActivityTaskManager service unavailable" }
        val method = findCompatibleMethod(target.javaClass, names, secondArgument)
            ?: error("No compatible ${names.joinToString()} candidate")
        val arguments = buildArguments(method, taskId, secondArgument)
        method.invoke(target, *arguments)
        NeXtepLog.info(
            "activity_task_manager",
            "Invoked ${method.name} taskId=$taskId signature=${method.parameterTypes.joinToString()}",
        )
    }

    private fun findCompatibleMethod(
        startType: Class<*>,
        names: List<String>,
        secondArgument: Any,
    ): Method? {
        var type: Class<*>? = startType
        while (type != null) {
            val match = type.declaredMethods.firstOrNull { method ->
                method.name in names &&
                    method.parameterTypes.size in 2..3 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    wraps(method.parameterTypes[1]).isAssignableFrom(secondArgument.javaClass)
            }
            if (match != null) return match.apply { isAccessible = true }
            type = type.superclass
        }
        return null
    }

    private fun findSingleIntMethod(startType: Class<*>, names: List<String>): Method? {
        var type: Class<*>? = startType
        while (type != null) {
            val match = type.declaredMethods.firstOrNull { method ->
                method.name in names &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (match != null) return match.apply { isAccessible = true }
            type = type.superclass
        }
        return null
    }

    private fun buildArguments(method: Method, taskId: Int, secondArgument: Any): Array<Any> {
        val arguments = mutableListOf<Any>(taskId, secondArgument)
        if (method.parameterTypes.size == 3) {
            arguments += when (method.parameterTypes[2]) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> error("Unsupported third parameter ${method.parameterTypes[2].name}")
            }
        }
        return arguments.toTypedArray()
    }

    private fun wraps(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> type
    }
}
