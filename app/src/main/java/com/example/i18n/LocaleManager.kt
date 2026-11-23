package com.example.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale

/**
 * Persian-first locale handling without AppCompat: the tag is kept in SharedPreferences so it
 * can be applied in `attachBaseContext`, before any resources are resolved.
 */
object LocaleManager {

    private const val PREFS = "luxy_locale"
    private const val KEY_TAG = "tag"
    const val DEFAULT_TAG = "fa"

    /** Locales with a real translation today; ru/zh land with the P2 copy pass. */
    val available: List<String> = listOf("fa", "en")

    fun currentTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, null)
            ?.takeIf { it in available }
            ?: DEFAULT_TAG

    fun setTag(context: Context, tag: String) {
        val safe = tag.takeIf { it in available } ?: DEFAULT_TAG
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_TAG, safe)
        }
    }

    /** Wraps the base context with the stored locale and its layout direction. */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(currentTag(base))
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirections(locale)
        return base.createConfigurationContext(configuration)
    }

    private fun Configuration.setLayoutDirections(locale: Locale) {
        setLayoutDirection(locale)
    }
}
