package app.voyahradio.data

import app.voyahradio.domain.Track
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class NowPlayingFeed {
    fun read(stationId: Int, stationName: String?): Track? {
        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL("https://radioplayer.by/api/songs/current/" + stationId)
            connection = endpoint.openConnection() as HttpURLConnection?
            connection!!.setConnectTimeout(4000)
            connection.setReadTimeout(4000)
            connection.setInstanceFollowRedirects(false)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", StreamProbe.USER_AGENT)
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null
            connection.getInputStream().use { input ->
                return parse(input, stationName)
            }
        } catch (ignored: IOException) {
            return null
        } catch (ignored: JSONException) {
            return null
        } catch (ignored: DateTimeParseException) {
            return null
        } finally {
            if (connection != null) connection.disconnect()
        }
    }

    @kotlin.Throws(IOException::class, JSONException::class)
    private fun parse(input: InputStream, stationName: String?): Track? {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var count: Int
        while ((input.read(buffer).also { count = it }) != -1) {
            if (body.size() + count > NowPlayingFeed.MAX_RESPONSE_BYTES) return null
            body.write(buffer, 0, count)
        }
        val track = JSONObject(String(body.toByteArray(), StandardCharsets.UTF_8))
        if (!track.optBoolean("hasData") || track.optInt("id", -1) <= 0) return null
        val duration = track.optInt("duration", 0)
        if (duration <= 0 || duration > 6 * 60 * 60) return null
        val start = OffsetDateTime.parse(track.getString("lastStartPlaying")).toInstant()
        val now = Instant.now()
        if (start.isAfter(now.plusSeconds(30)) || start.plusSeconds((duration + 25).toLong())
                .isBefore(now)
        ) return null
        return Track.from(track.getString("name"), track.optString("artist"), stationName)
    }

    companion object {
        private val MAX_RESPONSE_BYTES = 16 * 1024
    }
}
