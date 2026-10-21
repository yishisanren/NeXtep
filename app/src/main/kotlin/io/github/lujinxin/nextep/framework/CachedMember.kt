package io.github.lujinxin.nextep.framework

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Cache ROM member discovery (including absence), never the changing task value. */
internal class CachedMember(
    private val fields: List<String>,
    private val methods: List<String> = emptyList(),
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val accessors = ConcurrentHashMap<Class<*>, List<AccessibleObject>>()

    fun read(target: Any): Any? {
        for (member in accessors.computeIfAbsent(target.javaClass, ::resolve)) {
            try {
                return when (member) {
                    is Field -> member.get(target)
                    is Method -> member.invoke(target)
                    else -> null
                }
            } catch (error: Throwable) {
                onFailure(member.toString(), error)
            }
        }
        return null
    }

    private fun resolve(runtimeType: Class<*>): List<AccessibleObject> {
        val result = mutableListOf<AccessibleObject>()
        fun collect(names: List<String>, method: Boolean) {
            var type: Class<*>? = runtimeType
            while (type != null) {
                val current = type
                for (name in names) {
                    try {
                        val member = if (method) current.getDeclaredMethod(name) else current.getDeclaredField(name)
                        member.isAccessible = true
                        result.add(member)
                    } catch (_: NoSuchFieldException) {
                        // Try the next ROM candidate.
                    } catch (_: NoSuchMethodException) {
                        // Try the next ROM candidate.
                    } catch (error: Throwable) {
                        onFailure("${current.name}#$name", error)
                    }
                }
                type = current.superclass
            }
        }
        collect(fields, false)
        collect(methods, true)
        return result
    }
}
