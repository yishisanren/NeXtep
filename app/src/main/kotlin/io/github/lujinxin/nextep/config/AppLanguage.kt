package io.github.lujinxin.nextep.config

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/** Uses Android's persisted app language; host resources are never changed. */
object AppLanguage {
    private const val MODULE_PACKAGE = "io.github.yishisanren.panes"
    val languageTags = listOf("", "zh-Hans", "zh-Hant", "en", "ja", "ko")

    @Volatile private var hostLanguageTag = ""
    private var moduleContext: Context? = null
    private var localizedContext: Context? = null
    private var cachedConfiguration: Configuration? = null

    fun selectedTag(context: Context): String {
        val locales = checkNotNull(context.getSystemService(LocaleManager::class.java)).applicationLocales
        return if (locales.isEmpty) "" else locales[0]?.let(::supportedTag).orEmpty()
    }

    fun select(context: Context, languageTag: String) {
        require(languageTag in languageTags)
        checkNotNull(context.getSystemService(LocaleManager::class.java)).applicationLocales =
            LocaleList.forLanguageTags(languageTag)
        notifyChanged(context)
    }

    fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(WorkspaceConfigContract.ACTION_CHANGED)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).setPackage("com.android.systemui"))
    }

    fun updateHostLanguage(languageTag: String) {
        hostLanguageTag = languageTag.takeIf { it in languageTags }.orEmpty()
    }

    fun hostTag(): String = hostLanguageTag

    fun locale(context: Context): Locale {
        val explicit = if (context.packageName == MODULE_PACKAGE) selectedTag(context) else hostLanguageTag
        if (explicit.isNotEmpty()) return Locale.forLanguageTag(explicit)
        val locales = context.resources.configuration.locales
        for (index in 0 until locales.size()) {
            locales[index]?.let(::supportedTag)?.let { return Locale.forLanguageTag(it) }
        }
        return Locale.ENGLISH
    }

    fun string(context: Context, @StringRes resource: Int, vararg arguments: Any): String {
        if (context.packageName == MODULE_PACKAGE) return context.getString(resource, *arguments)
        return synchronized(this) {
            // Load the module APK rather than interpreting its IDs in SystemUI/Launcher.
            val base = moduleContext ?: context.createPackageContext(MODULE_PACKAGE, 0).also {
                moduleContext = it
            }
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(locale(context))
            }
            if (localizedContext == null || cachedConfiguration != configuration) {
                localizedContext = base.createConfigurationContext(configuration)
                cachedConfiguration = configuration
            }
            checkNotNull(localizedContext).getString(resource, *arguments)
        }
    }

    private fun supportedTag(locale: Locale): String? = when (locale.language) {
        "zh" -> if (locale.script == "Hant" ||
            (locale.script != "Hans" && locale.country in setOf("TW", "HK", "MO"))) "zh-Hant" else "zh-Hans"
        "en", "ja", "ko" -> locale.language
        else -> null
    }
}
