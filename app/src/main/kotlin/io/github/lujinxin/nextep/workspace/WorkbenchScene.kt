package io.github.lujinxin.nextep.workspace

import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import org.json.JSONArray
import org.json.JSONObject

/** A named workspace layout: the main app's package and one package (or none) per slot. */
data class WorkbenchScene(val name: String, val main: String?, val slots: List<String?>) {
    companion object {
        const val MAX_SCENES = 8

        fun encode(scenes: List<WorkbenchScene>): String = JSONArray().apply {
            scenes.forEach { scene ->
                put(JSONObject().put("name", scene.name).put("main", scene.main ?: JSONObject.NULL)
                    .put("slots", JSONArray(scene.slots.map { it ?: JSONObject.NULL })))
            }
        }.toString()

        fun decode(encoded: String?): List<WorkbenchScene> = runCatching {
            val array = JSONArray(encoded ?: return emptyList())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val slots = item.getJSONArray("slots")
                // optString renders a JSON null as the text "null"; test for null explicitly.
                WorkbenchScene(item.getString("name"), if (item.isNull("main")) null else item.getString("main"),
                    (0 until WORKSPACE_SLOT_COUNT).map { if (slots.isNull(it)) null else slots.getString(it) })
            }
        }.getOrDefault(emptyList())

        /** Saving under an existing name replaces it; the list keeps at most [MAX_SCENES], oldest dropped. */
        fun save(scenes: List<WorkbenchScene>, scene: WorkbenchScene): List<WorkbenchScene> =
            (scenes.filterNot { it.name == scene.name } + scene).takeLast(MAX_SCENES)
    }
}
