package app.voyahradio.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import app.voyahradio.data.RadioPreferences
import java.util.Locale

object RadioLocale {
    fun apply(context: Context, preferences: RadioPreferences): Context? {
        val language = preferences.language()
        if (context.getResources().getConfiguration().getLocales().get(0)
                .getLanguage() == language
        ) {
            return context
        }
        val locale = Locale.forLanguageTag(language)
        val override = Configuration()
        override.setLocales(LocaleList(locale))
        return context.createConfigurationContext(override)
    }
}
