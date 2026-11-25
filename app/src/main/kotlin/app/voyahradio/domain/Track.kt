package app.voyahradio.domain

import java.util.Objects

class Track private constructor(val title: String, val artist: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Track) return false
        return title == other.title && artist == other.artist
    }

    override fun hashCode(): Int {
        return Objects.hash(title, artist)
    }

    companion object {
        fun from(title: CharSequence?, artist: CharSequence?, stationName: String?): Track? {
            var name: String = Track.clean(title)
            var performer: String = Track.clean(artist)
            if (name.isEmpty() || name.equals(stationName, ignoreCase = true)) return null
            if (performer.isEmpty()) {
                val parts: Array<String> =
                    name.split(" (?:-|–|—) ".toRegex(), limit = 2).toTypedArray()
                if (parts.size == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                    performer = parts[0]
                    name = parts[1]
                }
            }
            return Track(name, performer)
        }

        private fun clean(value: CharSequence?): String {
            if (value == null) return ""
            val line: String =
                value.toString().replace("[\\p{Cntrl}]+".toRegex(), " ").trim { it <= ' ' }
            val codePoints: Int = line.codePointCount(0, line.length)
            return if (codePoints > 180) line.substring(
                0,
                line.offsetByCodePoints(0, 180)
            ) else line
        }
    }
}
