package com.example.tts

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * In-app Korean/English choice. Activities follow AppCompat's per-app locale. Below API 33 AppCompat applies it to
 * activities only, so services and receivers build their notification text through [wrap], which reads our own copy.
 */
object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /** Switches between Korean and English based on the language [context] is showing now. */
    fun toggle(context: Context) {
        val tag = if (context.resources.configuration.locales[0].language == "ko") "en" else "ko"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_TAG, tag) }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** [context] with the chosen language applied; unchanged when nothing was chosen or the system already applies it. */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null) ?: return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config)
    }
}
