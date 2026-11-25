package app.voyahradio.playback

import app.voyahradio.R
import app.voyahradio.domain.Playback

object PlaybackText {
    fun label(status: Playback.Status): Int {
        return when (status) {
            Playback.Status.READY -> R.string.ready
            Playback.Status.CONNECTING -> R.string.connecting
            Playback.Status.BUFFERING -> R.string.buffering
            Playback.Status.RECONNECTING -> R.string.reconnecting
            Playback.Status.LIVE -> R.string.live
            Playback.Status.PAUSED -> R.string.paused
            Playback.Status.STOPPED -> R.string.stopped
            Playback.Status.STREAM_ERROR -> R.string.stream_error
            Playback.Status.NETWORK_ERROR -> R.string.network_error
            Playback.Status.FOCUS_ERROR -> R.string.focus_error
        }
    }
}
