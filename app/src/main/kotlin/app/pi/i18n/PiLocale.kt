package app.pi.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.pi.protocol.ProtocolLocale
import java.util.Locale

/**
 * User-selectable display language (`app.appearance.language`).
 *
 *  - [System]: follows the Android device locale (Simplified Chinese on `zh*` devices, English otherwise).
 *  - [ZhCn]: Simplified Chinese (`zh-CN`).
 *  - [En]: English (`en`).
 */
enum class AppLanguage(val wire: String) {
    System("system"),
    ZhCn("zh"),
    En("en");

    companion object {
        fun fromWire(raw: String?): AppLanguage = when (raw?.trim()?.lowercase()) {
            "zh", "zh-cn", "zh_cn" -> ZhCn
            "en", "en-us", "en_us" -> En
            else -> System
        }
    }
}

/**
 * Zero-restart in-app localization controller.
 *
 * Backed by Compose snapshot state ([language] and [isZh]) so any `@Composable`
 * calling [tr] or [piStr] recomposes instantaneously when the user changes language
 * in Settings → Appearance → Language — without recreating `MainActivity`, dropping
 * the running engine, or resetting navigation/scroll state.
 *
 * Non-Compose callers (ViewModels, foreground service, notifications, registries)
 * safely read the same snapshot state via [tr] and [PiStrings.get].
 */
object PiLocale {
    private const val PREFS_NAME = "pi_locale"
    private const val KEY_LANGUAGE = "language"
    const val SETTING_KEY = "app.appearance.language"

    var language: AppLanguage by mutableStateOf(AppLanguage.System)
        private set

    var isZh: Boolean by mutableStateOf(isLocaleChinese(Locale.getDefault()))
        private set

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedResources: Resources? = null

    @Volatile
    private var onLanguageApplied: (() -> Unit)? = null

    fun init(context: Context, onChanged: (() -> Unit)? = null) {
        val app = context.applicationContext
        appContext = app
        if (onChanged != null) onLanguageApplied = onChanged
        val saved = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        apply(app, AppLanguage.fromWire(saved), persist = false)
    }

    fun setLanguage(context: Context, next: AppLanguage) {
        val app = context.applicationContext
        appContext = app
        apply(app, next, persist = true)
    }

    fun onSystemConfigurationChanged(context: Context, newConfig: Configuration) {
        val app = context.applicationContext
        appContext = app
        if (language == AppLanguage.System) {
            val sysLocale = primaryLocale(newConfig)
            val nextZh = isLocaleChinese(sysLocale)
            isZh = nextZh
            ProtocolLocale.isChinese = nextZh
            cachedResources = buildResources(app, AppLanguage.System, newConfig)
            onLanguageApplied?.invoke()
        }
    }

    fun localizedResources(context: Context, lang: AppLanguage = language): Resources {
        val app = context.applicationContext
        return buildResources(app, lang, app.resources.configuration)
    }

    fun currentResources(): Resources {
        cachedResources?.let { return it }
        val app = appContext
        if (app != null) {
            val built = buildResources(app, language, app.resources.configuration)
            cachedResources = built
            return built
        }
        return Resources.getSystem()
    }

    private fun apply(app: Context, next: AppLanguage, persist: Boolean) {
        if (persist) {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, next.wire)
                .apply()
        }
        val sysLocale = primaryLocale(app.resources.configuration)
        val nextZh = when (next) {
            AppLanguage.System -> isLocaleChinese(sysLocale)
            AppLanguage.ZhCn -> true
            AppLanguage.En -> false
        }
        language = next
        isZh = nextZh
        ProtocolLocale.isChinese = nextZh
        cachedResources = buildResources(app, next, app.resources.configuration)
        onLanguageApplied?.invoke()
    }

    private fun buildResources(app: Context, lang: AppLanguage, baseConfig: Configuration): Resources {
        val targetLocale = when (lang) {
            AppLanguage.System -> primaryLocale(baseConfig)
            AppLanguage.ZhCn -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.En -> Locale.ENGLISH
        }
        val config = Configuration(baseConfig)
        config.setLocale(targetLocale)
        return app.createConfigurationContext(config).resources
    }

    private fun primaryLocale(config: Configuration): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !config.locales.isEmpty) {
            config.locales.get(0) ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            config.locale ?: Locale.getDefault()
        }
    }

    private fun isLocaleChinese(locale: Locale): Boolean =
        locale.language.equals("zh", ignoreCase = true)
}

val LocalPiResources = staticCompositionLocalOf<Resources> {
    PiLocale.currentResources()
}

/** Resolve an Android `@StringRes` using the active in-app language (reactive in Compose). */
@Composable
fun piStr(@StringRes id: Int, vararg args: Any): String {
    val res = LocalPiResources.current
    return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
}

/** Resolve an Android `@StringRes` outside Compose (ViewModels, Services, helpers). */
object PiStrings {
    fun get(@StringRes id: Int, vararg args: Any): String {
        val res = PiLocale.currentResources()
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }
}

/**
 * Bilingual string selector backed by Compose snapshot state ([PiLocale.isZh]).
 *
 * Inside any `@Composable`, calling `tr(zh, en)` automatically subscribes to
 * instant language switches; outside Compose it safely returns the active language.
 */
fun tr(zh: String, en: String): String = if (PiLocale.isZh) zh else en
