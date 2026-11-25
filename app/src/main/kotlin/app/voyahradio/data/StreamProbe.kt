package app.voyahradio.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.voyahradio.domain.Availability
import app.voyahradio.domain.Station
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class StreamProbe(context: Context) {
    private val connectivity: ConnectivityManager

    init {
        connectivity =
            context.getSystemService<ConnectivityManager?>(ConnectivityManager::class.java)
    }

    fun validatedNetwork(): Network? {
        val network = connectivity.getActiveNetwork()
        if (network == null) return null
        val capabilities = connectivity.getNetworkCapabilities(network)
        return if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            network
        else
            null
    }

    fun isCurrent(network: Network?): Boolean {
        return network != null && network == validatedNetwork()
    }

    fun check(station: Station, network: Network): Availability {
        var combined = Availability.UNAVAILABLE
        for (stream in station.streams) {
            if (Thread.currentThread()
                    .isInterrupted() || !isCurrent(network)
            ) return Availability.UNKNOWN
            val result = checkStream(stream, network)
            if (result == Availability.AVAILABLE) return if (isCurrent(network)) result else Availability.UNKNOWN
            combined = combined.merge(result)
        }
        return if (isCurrent(network)) combined else Availability.UNKNOWN
    }

    private fun checkStream(address: String, network: Network): Availability {
        try {
            var url = URL(address)
            var redirect = 0
            while (redirect <= 5 && !Thread.currentThread().isInterrupted()) {
                if ("http" != url.getProtocol() && "https" != url.getProtocol()) break
                val connection = network.openConnection(url) as HttpURLConnection
                try {
                    connection.setConnectTimeout(3500)
                    connection.setReadTimeout(3500)
                    connection.setInstanceFollowRedirects(false)
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    val status = connection.getResponseCode()
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        val location = connection.getHeaderField("Location")
                        if (location == null) break
                        url = URL(url, location)
                        redirect++
                        continue
                    }
                    if (Availability.isDefinitiveHttpFailure(status)) return Availability.UNAVAILABLE
                    if (status != 200 && status != 206) break
                    var type = connection.getContentType()
                    if (type == null) type = ""
                    type = type.lowercase()
                    connection.getInputStream().use { input ->
                        if (type.startsWith("audio/") || connection.getHeaderField("icy-name") != null) {
                            return if (input.read() >= 0) Availability.AVAILABLE else Availability.UNKNOWN
                        }
                        if (type.contains("mpegurl") || url.getPath().lowercase()
                                .endsWith(".m3u8")
                        ) {
                            val prefix = ByteArray(7)
                            var count = 0
                            while (count < prefix.size) {
                                val read: Int = input.read(prefix, count, prefix.size - count)
                                if (read < 0) break
                                count += read
                            }
                            if (count == prefix.size
                                && String(prefix, StandardCharsets.US_ASCII) == "#EXTM3U"
                            ) {
                                return Availability.AVAILABLE
                            }
                        }
                    }
                    break
                } finally {
                    connection.disconnect()
                }
            }
        } catch (ignored: IOException) {
            // A transport failure provides no evidence about the station.
        }
        return Availability.UNKNOWN
    }

    companion object {
        const val USER_AGENT: String = "VoyahRadio/0.3.5"
    }
}
