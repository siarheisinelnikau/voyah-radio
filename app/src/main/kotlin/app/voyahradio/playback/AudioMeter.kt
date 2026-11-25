package app.voyahradio.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.sqrt

@OptIn(markerClass = [UnstableApi::class])
class AudioMeter {
    class Frame(val spectrum: FloatArray, val wave: FloatArray)

    @kotlin.concurrent.Volatile
    private var frame: Frame? = null

    @kotlin.concurrent.Volatile
    private var enabled = false
    private val input = FloatArray(AudioMeter.WINDOW)
    private val window = FloatArray(AudioMeter.WINDOW)
    private val coefficients = FloatArray(AudioMeter.FREQUENCIES.size)
    private var sampleRate = 0
    private var nextSample: Long = 0

    init {
        for (i in 0..<AudioMeter.WINDOW) {
            window[i] =
                (0.5 - 0.5 * cos(2 * Math.PI * i / (AudioMeter.WINDOW - 1))).toFloat()
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) frame = null
    }

    fun frame(): Frame? {
        return frame
    }

    fun sample(pcm: ByteBuffer, rate: Int, channels: Int, encoding: Int) {
        if (!enabled || channels <= 0 || rate <= 0) return
        val bytesPerChannel = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        val stride = channels * bytesPerChannel
        if (stride == 0 || pcm.remaining() < AudioMeter.WINDOW * stride) return
        val now = System.nanoTime()
        if (now < nextSample) return
        nextSample = now + AudioMeter.SAMPLE_INTERVAL_NS

        if (sampleRate != rate) initializeCoefficients(rate)
        val samples = pcm.duplicate().order(ByteOrder.nativeOrder())
        val start = samples.position()
        val wave = FloatArray(AudioMeter.WAVE_POINTS)
        for (i in 0..<AudioMeter.WINDOW) {
            val offset = start + i * stride
            var value = read(samples, offset, bytesPerChannel)
            if (channels > 1) value =
                (value + read(samples, offset + bytesPerChannel, bytesPerChannel)) * 0.5f
            input[i] = value * window[i]
            if (i % (AudioMeter.WINDOW / AudioMeter.WAVE_POINTS) == 0) wave[i / (AudioMeter.WINDOW / AudioMeter.WAVE_POINTS)] =
                value
        }

        val spectrum = FloatArray(AudioMeter.FREQUENCIES.size)
        for (band in spectrum.indices) {
            var previous = 0f
            var before = 0f
            val coefficient = coefficients[band]
            for (sample in input) {
                val current = sample + coefficient * previous - before
                before = previous
                previous = current
            }
            val power = previous * previous + before * before - coefficient * previous * before
            val amplitude: Double = sqrt(power.toDouble().coerceAtLeast(0.0)) / AudioMeter.WINDOW
            spectrum[band] = (ln1p(amplitude * 60) / 2.5).coerceAtMost(1.0).toFloat()
        }
        if (enabled) frame = Frame(spectrum, wave)
    }

    private fun initializeCoefficients(rate: Int) {
        sampleRate = rate
        for (band in AudioMeter.FREQUENCIES.indices) {
            val frequency: Double = minOf(FREQUENCIES[band].toDouble(), rate * 0.45)
            coefficients[band] = (2 * cos(2 * Math.PI * frequency / rate)).toFloat()
        }
    }

    private fun read(buffer: ByteBuffer, offset: Int, bytes: Int): Float {
        return if (bytes == 2) buffer.getShort(offset) / 32768f else buffer.getFloat(offset)
    }

    companion object {
        private const val WINDOW = 256
        private const val WAVE_POINTS = 64
        private const val SAMPLE_INTERVAL_NS: Long = 32000000
        private val FREQUENCIES =
            intArrayOf(90, 140, 220, 350, 550, 850, 1300, 2000, 3000, 4500, 6500, 9500)
    }
}
