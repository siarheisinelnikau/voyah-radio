package app.voyahradio.domain

import java.net.URI
import java.text.Normalizer
import java.util.Collections
import java.util.Locale

class Station(
    id: String, name: String, label: String, aliases: String,
    group: RadioTab, streams: List<String>, trackSourceId: Int,
    countryCode: String, russianLabel: String, kazakhLabel: String
) {
    val id: String
    val name: String
    val group: RadioTab
    val trackSourceId: Int
    val streams: List<String>
    private val label: String
    private val russianLabel: String
    private val kazakhLabel: String
    private val country: Locale?
    private val searchText: String

    init {
        require(!(id.trim { it <= ' ' }.isEmpty() || name.trim { it <= ' ' }
            .isEmpty() || streams.isEmpty())) { "Incomplete station" }
        for (url in streams) {
            val uri = URI.create(url)
            require(
                !(!("http".equals(
                    uri.getScheme(),
                    ignoreCase = true
                ) || "https".equals(
                    uri.getScheme(),
                    ignoreCase = true
                )) || uri.getHost() == null || uri.getUserInfo() != null)
            ) { "Invalid stream URL: " + url }
        }
        this.id = id
        this.name = name
        this.label = label
        this.russianLabel = if (russianLabel.isEmpty()) label else russianLabel
        this.kazakhLabel = (if (kazakhLabel.isEmpty()) when (label) {
            "Chillout" -> "Баяу музыка"
            "Classical" -> "Классикалық музыка"
            "Country" -> "Кантри"
            "Dance" -> "Би музыкасы"
            "Jazz" -> "Джаз"
            "News" -> "Жаңалықтар"
            "Pop" -> "Поп"
            "Rock" -> "Рок"
            "Talk" -> "Әңгіме"
            "World" -> "Халық музыкасы"
            else -> label
        } else kazakhLabel)
        this.country = if (countryCode.isEmpty()) null else Locale("", countryCode)
        this.group = group
        this.trackSourceId = trackSourceId
        this.streams = streams.toList()
        this.searchText = Station.normalize(
            (name + " " + aliases + " " + displayLabel(Locale.ENGLISH)
                    + " " + displayLabel(Locale("ru")) + " " + displayLabel(Locale("kk")))
        )
    }

    fun displayLabel(locale: Locale): String {
        val detail = when (locale.getLanguage()) {
            "ru" -> russianLabel
            "kk" -> kazakhLabel
            else -> label
        }
        if (country == null) return detail
        val region = country.getDisplayCountry(locale)
        return if (detail.isEmpty()) region else region + " · " + detail
    }

    fun matches(words: Array<String>): Boolean {
        for (word in words) {
            if (!searchText.contains(word)) return false
        }
        return true
    }

    companion object {
        fun searchWords(text: String): Array<String> {
            return Station.normalize(text).split(" ".toRegex())
                .dropLastWhile { it.isEmpty() }.toTypedArray()
        }

        private fun normalize(text: String): String {
            return Normalizer.normalize(text.lowercase().replace('ё', 'е'), Normalizer.Form.NFD)
                .replace("\\p{M}+".toRegex(), "")
                .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
                .trim { it <= ' ' }
        }
    }
}
