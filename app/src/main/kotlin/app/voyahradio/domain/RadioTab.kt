package app.voyahradio.domain

enum class RadioTab {
    BELARUS, RUSSIA, KAZAKHSTAN, INTERNATIONAL, FAVORITES;

    fun contains(station: Station, favorites: Set<String>): Boolean {
        return if (this == RadioTab.FAVORITES) favorites.contains(station.id) else station.group == this
    }

    companion object {
        fun parse(name: String?, fallback: RadioTab?): RadioTab? {
            if (name == null) return fallback
            try {
                return valueOf(name)
            } catch (ignored: IllegalArgumentException) {
                return fallback
            }
        }
    }
}
