package app.voyahradio.domain

enum class Availability {
    AVAILABLE, UNAVAILABLE, UNKNOWN;

    fun merge(other: Availability?): Availability {
        if (this == Availability.AVAILABLE || other == Availability.AVAILABLE) return Availability.AVAILABLE
        if (this == Availability.UNKNOWN || other == Availability.UNKNOWN) return Availability.UNKNOWN
        return Availability.UNAVAILABLE
    }

    companion object {
        fun isDefinitiveHttpFailure(status: Int): Boolean {
            return status == 404 || status == 410 || status == 451
        }
    }
}
