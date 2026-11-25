package app.voyahradio.domain

data class Playback(
    val stationId: String,
    val status: Status,
    val track: Track? = null
) {
    enum class Status {
        READY, CONNECTING, BUFFERING, RECONNECTING, LIVE, PAUSED, STOPPED, STREAM_ERROR, NETWORK_ERROR, FOCUS_ERROR
    }

    fun active(): Boolean {
        return status == Status.CONNECTING || status == Status.BUFFERING || status == Status.RECONNECTING || status == Status.LIVE
    }

    fun failed(): Boolean {
        return status == Status.STREAM_ERROR || status == Status.NETWORK_ERROR || status == Status.FOCUS_ERROR
    }
}
