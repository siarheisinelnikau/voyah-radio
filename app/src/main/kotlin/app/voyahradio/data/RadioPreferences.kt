package app.voyahradio.data

import android.content.Context
import app.voyahradio.domain.Availability
import app.voyahradio.domain.CatalogOptions
import app.voyahradio.domain.RadioTab
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class RadioPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun lastStation() = prefs.getString("last_station", "").orEmpty()
    fun rememberStation(id: String) = prefs.edit().putString("last_station", id).apply()
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
    fun saveFavorites(ids: Set<String>) = prefs.edit().putStringSet("favorites", ids.toSet()).apply()
    fun language() = prefs.getString("language", "ru") ?: "ru"
    fun setLanguage(language: String) = prefs.edit().putString("language", language).apply()
    fun theme() = prefs.getString("theme", "graphite") ?: "graphite"
    fun setTheme(theme: String) = prefs.edit().putString("theme", theme).apply()
    fun visualization() = prefs.getString("visualization", "spectrum") ?: "spectrum"
    fun setVisualization(mode: String) = prefs.edit().putString("visualization", mode).apply()
    fun smoothVisualization() = prefs.getBoolean("smooth_visualization", true)
    fun setSmoothVisualization(enabled: Boolean) = prefs.edit().putBoolean("smooth_visualization", enabled).apply()

    fun catalogOptions(): CatalogOptions {
        val hiddenTabs = prefs.getStringSet("hidden_tabs", emptySet()).orEmpty()
            .mapNotNull { RadioTab.parse(it, null) }.toSet()
        val hiddenStations = prefs.getStringSet("hidden_stations", emptySet()).orEmpty()
        val now = System.currentTimeMillis()
        val unavailable = prefs.all.mapNotNull { (key, raw) ->
            val timestamp = raw as? Long ?: return@mapNotNull null
            val age = now + timestamp
            if (key.startsWith(AVAILABILITY_PREFIX) && timestamp < 0 && age in 0 until AVAILABILITY_MAX_AGE) {
                key.removePrefix(AVAILABILITY_PREFIX)
            } else null
        }.toSet()
        return CatalogOptions(hiddenTabs, hiddenStations, unavailable, prefs.getBoolean("hide_unavailable", false),
            readOrder("station_order"), readOrder("favorite_order"))
    }

    private fun readOrder(key: String): List<String> = prefs.getString(key, "").orEmpty()
        .takeIf(String::isNotEmpty)?.split(',').orEmpty()

    fun saveHiddenTabs(tabs: Set<RadioTab>) = prefs.edit().putStringSet("hidden_tabs", tabs.map { it.name }.toSet()).apply()
    fun saveHiddenStations(ids: Set<String>) = prefs.edit().putStringSet("hidden_stations", ids.toSet()).apply()
    fun saveOrder(ids: List<String>) = prefs.edit().putString("station_order", ids.joinToString(",")).apply()
    fun saveFavoriteOrder(ids: List<String>) = prefs.edit().putString("favorite_order", ids.joinToString(",")).apply()
    fun setHideUnavailable(enabled: Boolean) = prefs.edit().putBoolean("hide_unavailable", enabled).apply()

    fun recordAvailability(id: String, availability: Availability, observedAt: Long) {
        if (availability == Availability.UNKNOWN) return
        val key = AVAILABILITY_PREFIX + id
        if (observedAt < abs(prefs.getLong(key, 0))) return
        val value = if (availability == Availability.AVAILABLE) observedAt else -observedAt
        prefs.edit().putLong(key, value).apply()
    }

    private companion object {
        const val AVAILABILITY_PREFIX = "availability."
        val AVAILABILITY_MAX_AGE = TimeUnit.HOURS.toMillis(24)
    }
}
