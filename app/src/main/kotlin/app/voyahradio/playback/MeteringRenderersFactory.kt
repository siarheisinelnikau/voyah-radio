package app.voyahradio.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

@OptIn(markerClass = [UnstableApi::class])
class MeteringRenderersFactory(context: Context, private val meter: AudioMeter) :
    DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context, enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? {
        val delegate =
            super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return object : ForwardingAudioSink(delegate) {
            private var format: Format? = null

            @kotlin.Throws(AudioSink.ConfigurationException::class)
            override fun configure(format: Format, bufferSize: Int, outputChannels: IntArray?) {
                super.configure(format, bufferSize, outputChannels)
                this.format = format
            }

            @kotlin.Throws(
                AudioSink.InitializationException::class,
                AudioSink.WriteException::class
            )
            override fun handleBuffer(buffer: ByteBuffer, timeUs: Long, units: Int): Boolean {
                if (format != null) meter.sample(
                    buffer,
                    format!!.sampleRate,
                    format!!.channelCount,
                    format!!.pcmEncoding
                )
                return super.handleBuffer(buffer, timeUs, units)
            }
        }
    }
}
