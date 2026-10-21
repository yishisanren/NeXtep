package io.github.lujinxin.nextep.config

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import io.github.lujinxin.nextep.R
import java.io.File

object TopTextStyle {
    const val DEFAULT_FAMILY = "default"
    const val DEFAULT_SIZE_SP = 16
    const val MIN_SIZE_SP = 10
    const val MAX_SIZE_SP = 28
    private val genericFamilies = listOf(
        DEFAULT_FAMILY to R.string.font_default,
        "sans-serif" to R.string.font_sans,
        "serif" to R.string.font_serif,
        "monospace" to R.string.font_monospace,
        "sans-serif-condensed" to R.string.font_condensed,
    )
    data class SystemFont(val id: String, val label: String, val file: File, val index: Int)

    // Enumerate public system fonts, plus readable OEM font files which some
    // ROMs omit from SystemFonts. No theme-store/private storage is traversed.
    val systemFonts: List<SystemFont> by lazy {
        val available = runCatching { SystemFonts.getAvailableFonts().toList() }.getOrDefault(emptyList())
        val files = (available.mapNotNull { it.file } +
            listOf("/system/fonts", "/product/fonts", "/system_ext/fonts").flatMap { path ->
                File(path).listFiles()?.filter { it.canRead() && it.extension.lowercase() in setOf("ttf", "otf", "ttc") }.orEmpty()
            }).distinctBy { it.absolutePath }
        files.filterNot { it.name.contains("emoji", true) || it.name.contains("symbols", true) }
            .filterNot { it.name.contains("Bold", true) || it.name.contains("Italic", true) || it.name.contains("Thin", true) }
            .map { file ->
                val index = if (file.name.contains("CJK") && file.extension == "ttc") 2
                    else available.firstOrNull { it.file == file }?.ttcIndex ?: 0
                SystemFont("system:${file.absolutePath}#$index", fallbackFontLabel(file.nameWithoutExtension), file, index)
            }.sortedWith(compareBy<SystemFont> { if (it.file.name.contains("CJK") || it.file.name.contains("OPPO") || it.file.name.contains("OSans") || it.file.name.contains("Oplus")) 0 else 1 }
                .thenBy { it.label })
    }
    fun families(context: Context): List<Pair<String, String>> {
        val generic = genericFamilies.map { (id, label) -> id to context.getString(label) }
        return generic.take(1) + systemFonts.map { it.id to fontLabel(context, it.file.nameWithoutExtension) } + generic.drop(1)
    }
    private val typefaces = java.util.concurrent.ConcurrentHashMap<String, Typeface>()

    private fun fontLabel(context: Context, name: String): String = when (name) {
        "NotoSansCJK-Regular" -> context.getString(R.string.font_noto_sans_cjk)
        "NotoSerifCJK-Regular" -> context.getString(R.string.font_noto_serif_cjk)
        "OSans-RC-Regular" -> context.getString(R.string.font_oppo_chinese)
        "OSans-Ext-Regular" -> context.getString(R.string.font_oppo_extended)
        "Oplus-Serif" -> context.getString(R.string.font_coloros_serif)
        "SysSans-Hans-Regular" -> context.getString(R.string.font_system_hans)
        "SysSans-Hant-Regular" -> context.getString(R.string.font_system_hant)
        else -> fallbackFontLabel(name)
    }

    private fun fallbackFontLabel(name: String): String = name.removeSuffix("-Regular").removeSuffix("-VF")

    fun familyLabel(context: Context, value: String): String = families(context).firstOrNull { it.first == value }?.second
        ?: context.getString(R.string.font_default)

    fun resolveFamily(value: String?): String {
        // Most config queries use a generic font. Do not scan all OEM font files
        // on the module's main thread just to acknowledge that preference.
        if (value == null) return DEFAULT_FAMILY
        if (genericFamilies.any { it.first == value }) return value
        return value.takeIf { family -> systemFonts.any { it.id == family } } ?: DEFAULT_FAMILY
    }

    fun typeface(family: String, bold: Boolean): Typeface {
        val resolved = resolveFamily(family)
        return typefaces.getOrPut("$resolved|$bold") {
            val base = if (resolved.startsWith("system:")) systemFonts.firstOrNull { it.id == resolved }?.let { option ->
                runCatching {
                    val font = Font.Builder(option.file).setTtcIndex(option.index).build()
                    Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                        .setSystemFallback("sans-serif").setStyle(FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT)).build()
                }.getOrNull()
            } else null
            val selected = base ?: Typeface.create(if (resolved == DEFAULT_FAMILY || resolved.startsWith("system:")) null else resolved, Typeface.NORMAL)
            Typeface.create(selected, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
