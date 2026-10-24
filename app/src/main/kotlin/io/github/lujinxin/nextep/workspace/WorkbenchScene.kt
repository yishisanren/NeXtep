package io.github.lujinxin.nextep.workspace

import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named workspace layout: the main app's package and one package (or none) per slot.
 * [icon] names a preset glyph; without one the scene shows its [name] as text. A [builtin]
 * scene is a tool (price comparison, payment) rather than a layout and keeps no packages.
 */
data class WorkbenchScene(
    val name: String,
    val main: String?,
    val slots: List<String?>,
    val icon: String? = null,
    val builtin: String? = null,
) {
    companion object {
        const val MAX_SCENES = 6
        const val BUILTIN_COMPARE = "compare"
        const val BUILTIN_PAYMENT = "payment"
        /** Preset glyphs offered for a scene, in picker order. */
        val PRESET_ICONS = listOf("work", "study", "game", "media", "read", "shop", "travel", "life")
        /** A text label takes at most this many width units: a CJK character counts two, others one. */
        const val TEXT_UNITS = 4

        fun builtins(): List<WorkbenchScene> = listOf(
            WorkbenchScene(BUILTIN_COMPARE, null, List(WORKSPACE_SLOT_COUNT) { null }, builtin = BUILTIN_COMPARE),
            WorkbenchScene(BUILTIN_PAYMENT, null, List(WORKSPACE_SLOT_COUNT) { null }, builtin = BUILTIN_PAYMENT),
        )

        fun encode(scenes: List<WorkbenchScene>): String = JSONArray().apply {
            scenes.forEach { scene ->
                put(JSONObject().put("name", scene.name).put("main", scene.main ?: JSONObject.NULL)
                    .put("slots", JSONArray(scene.slots.map { it ?: JSONObject.NULL }))
                    .put("icon", scene.icon ?: JSONObject.NULL).put("builtin", scene.builtin ?: JSONObject.NULL))
            }
        }.toString()

        fun decode(encoded: String?): List<WorkbenchScene> = runCatching {
            val array = JSONArray(encoded ?: return emptyList())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val slots = item.getJSONArray("slots")
                // optString renders a JSON null as the text "null"; test for null explicitly.
                fun nullable(key: String) = if (!item.has(key) || item.isNull(key)) null else item.getString(key)
                WorkbenchScene(item.getString("name"), nullable("main"),
                    (0 until WORKSPACE_SLOT_COUNT).map { if (slots.isNull(it)) null else slots.getString(it) },
                    nullable("icon")?.takeIf { it in PRESET_ICONS }, nullable("builtin"))
            }
        }.getOrDefault(emptyList())

        /**
         * Saving under an existing name replaces it in place; a new scene is appended, or refused
         * (the list comes back unchanged) once [MAX_SCENES] are saved. Nothing is ever dropped.
         */
        fun save(scenes: List<WorkbenchScene>, scene: WorkbenchScene): List<WorkbenchScene> {
            val index = scenes.indexOfFirst { it.name == scene.name }
            return when {
                index >= 0 -> scenes.toMutableList().apply { this[index] = scene }
                scenes.size >= MAX_SCENES -> scenes
                else -> scenes + scene
            }
        }

        /** [base], or "[base] 2", "[base] 3"… when the name is taken. */
        fun uniqueName(scenes: List<WorkbenchScene>, base: String): String =
            generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { name ->
                scenes.none { it.name == name }
            }

        /** First-run defaults go in front of whatever was saved before; later calls change nothing. */
        fun seeded(scenes: List<WorkbenchScene>): List<WorkbenchScene> =
            builtins().filter { builtin -> scenes.none { it.builtin == builtin.builtin } } + scenes

        /** Puts back the deleted default tools in front, as far as the limit allows. */
        fun restoreBuiltins(scenes: List<WorkbenchScene>): List<WorkbenchScene> {
            val missing = builtins().filter { builtin -> scenes.none { it.builtin == builtin.builtin } }
            return missing.take((MAX_SCENES - scenes.size).coerceAtLeast(0)) + scenes
        }

        /** Width units of a text label: CJK and other wide characters count two. */
        fun textUnits(text: CharSequence): Int = text.codePoints().toArray().sumOf { if (it >= 0x1100) 2 else 1 }

        /** What a text scene's button shows: the start of its name that fits [TEXT_UNITS]. */
        fun textLabel(name: String): String {
            var units = 0
            val out = StringBuilder()
            for (cp in name.trim().codePoints().toArray()) {
                units += if (cp >= 0x1100) 2 else 1
                if (units > TEXT_UNITS) break
                out.appendCodePoint(cp)
            }
            return out.toString()
        }

        /**
         * The saved layout the workspace currently shows: every app the scene names is where it
         * names it. Of several such scenes the most specific one wins; tools never match.
         */
        fun active(scenes: List<WorkbenchScene>, main: String?, slots: List<String?>): WorkbenchScene? =
            scenes.filter { it.builtin == null }.mapNotNull { scene ->
                val named = listOf(scene.main to main) + scene.slots.zip(slots)
                val required = named.filter { it.first != null }
                if (required.isEmpty() || required.any { it.first != it.second }) null else scene to required.size
            }.maxByOrNull { it.second }?.first
    }
}
