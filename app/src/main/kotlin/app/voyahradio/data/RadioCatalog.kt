package app.voyahradio.data

import android.content.Context
import app.voyahradio.domain.RadioTab
import app.voyahradio.domain.Station
import org.json.JSONArray

class RadioCatalog(context: Context) {
    private val stations = try {
        val json = context.assets.open("stations.json").bufferedReader().use { it.readText() }
        val data = JSONArray(json)
        val ids = mutableSetOf<String>()
        List(data.length()) { index ->
            val item = data.getJSONObject(index)
            val links = item.getJSONArray("streams")
            val station = Station(
                item.getString("id"), item.getString("name"), item.getString("label"),
                item.optString("aliases"), RadioTab.valueOf(item.getString("group")),
                List(links.length()) { links.getString(it) },
                item.optInt("trackSourceId", 0), item.optString("countryCode"),
                item.optString("labelRu"), item.optString("labelKk"),
            )
            require(ids.add(station.id)) { "Duplicate station: ${station.id}" }
            station
        }.also { require(it.isNotEmpty()) { "Empty radio catalog" } }
    } catch (error: Exception) {
        throw IllegalStateException("Unable to read the bundled radio catalog", error)
    }

    fun all(): List<Station> = stations

    fun find(id: String?): Station? = stations.firstOrNull { it.id == id }
}
