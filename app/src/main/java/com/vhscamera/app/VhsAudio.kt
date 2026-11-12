package com.vhscamera.app

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * VHS linear-track sound, 48 kHz mono.
 *
 * The microphone is band-limited and kept quiet with a soft ceiling, gets a touch of wow
 * and flutter, and is laid over the tape's own noise bed: hiss with the measured spectrum
 * (pink below 2 kHz, flat to 6.5 kHz, rolling off above) that buzzes slightly at the head
 * drum rate, plus mains hum at 60 Hz and its harmonics.
 */
class VhsAudio(seed: Long = 20250309L) {
    private val rnd = Random(seed)

    // microphone chain
    private val highPass = Biquad.highPass(70.0, 0.707)
    private val tone = Biquad.highShelf(2200.0, -7.0)
    private val lowPassA = Biquad.lowPass(7200.0, 0.541)
    private val lowPassB = Biquad.lowPass(7200.0, 1.307)

    // wow & flutter delay line
    private val delay = FloatArray(DELAY_SIZE)
    private var delayPos = 0
    private var wowPhase = rnd.nextDouble() * 2 * PI
    private var flutterPhase = rnd.nextDouble() * 2 * PI

    // noise bed
    private val hiss: FloatArray = makeHiss(rnd)
    private var hissPos = rnd.nextInt(HISS_SIZE)
    private val humEven = FloatArray(HUM_PERIOD)
    private val humOdd = FloatArray(HUM_PERIOD)
    private var humPos = 0
    private var humGainEven = 1f
    private var humGainOdd = 1f
    private var humTargetEven = 1f
    private var humTargetOdd = 1f
    private var humCountdown = 0
    private var fadeIn = 0

    init {
        for (h in HUM_LEVELS_DB.indices) {
            val k = h + 1
            val amp = 10.0.pow(HUM_LEVELS_DB[h] / 20.0) * sqrt(2.0)
            val phase = rnd.nextDouble() * 2 * PI
            val table = if (k % 2 == 0) humEven else humOdd
            for (i in 0 until HUM_PERIOD) {
                table[i] += (amp * sin(2 * PI * k * i / HUM_PERIOD + phase)).toFloat()
            }
        }
    }

    /** Processes [count] microphone samples of [pcm] in place. */
    fun process(pcm: ShortArray, count: Int) {
        for (i in 0 until count) {
            // microphone -> tape
            var x = pcm[i] / 32768f * MIC_GAIN
            x = highPass.run(x)
            x = wowFlutter(x)
            x = tone.run(x)
            x = lowPassA.run(x)
            x = lowPassB.run(x)
            x = CEILING * tanh(x / CEILING)

            // tape noise bed
            val buzz = 1f + 0.10f * humOdd[humPos] * BUZZ_NORM + 0.06f * humEven[humPos] * BUZZ_NORM
            var n = hiss[hissPos] * buzz
            if (++hissPos >= HISS_SIZE) hissPos = 0
            if (--humCountdown <= 0) {
                humCountdown = 4800 + rnd.nextInt(24000)
                humTargetEven = 0.55f + 0.75f * rnd.nextFloat()
                humTargetOdd = 0.55f + 0.75f * rnd.nextFloat()
            }
            humGainEven += (humTargetEven - humGainEven) * 0.00012f
            humGainOdd += (humTargetOdd - humGainOdd) * 0.00012f
            n += humEven[humPos] * humGainEven + humOdd[humPos] * humGainOdd
            if (++humPos >= HUM_PERIOD) humPos = 0

            var y = x + n
            if (fadeIn < FADE_SAMPLES) {
                y *= fadeIn / FADE_SAMPLES.toFloat()
                fadeIn++
            }
            val s = (y * 32767f).toInt()
            pcm[i] = (if (s > 32767) 32767 else if (s < -32768) -32768 else s).toShort()
        }
    }

    private fun wowFlutter(x: Float): Float {
        delay[delayPos] = x
        wowPhase += 2 * PI * 0.55 / SAMPLE_RATE
        flutterPhase += 2 * PI * 6.3 / SAMPLE_RATE
        if (wowPhase > 2 * PI) wowPhase -= 2 * PI
        if (flutterPhase > 2 * PI) flutterPhase -= 2 * PI
        // Delay in samples; its slope is the pitch deviation (about +-0.12 % wow, +-0.05 % flutter).
        val d = 40.0 + 17.0 * sin(wowPhase) + 0.6 * sin(flutterPhase)
        val rp = delayPos - d
        val i0 = Math.floor(rp).toInt()
        val frac = (rp - i0).toFloat()
        val a = delay[i0 and DELAY_MASK]
        val b = delay[(i0 + 1) and DELAY_MASK]
        delayPos = (delayPos + 1) and DELAY_MASK
        return a + (b - a) * frac
    }

    private class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        private var z1 = 0f
        private var z2 = 0f

        fun run(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            fun lowPass(freq: Double, q: Double): Biquad {
                val k = tan(PI * freq / SAMPLE_RATE)
                val norm = 1 / (1 + k / q + k * k)
                val b0 = k * k * norm
                return Biquad(b0.toFloat(), (2 * b0).toFloat(), b0.toFloat(),
                    (2 * (k * k - 1) * norm).toFloat(), ((1 - k / q + k * k) * norm).toFloat())
            }

            fun highPass(freq: Double, q: Double): Biquad {
                val k = tan(PI * freq / SAMPLE_RATE)
                val norm = 1 / (1 + k / q + k * k)
                return Biquad(norm.toFloat(), (-2 * norm).toFloat(), norm.toFloat(),
                    (2 * (k * k - 1) * norm).toFloat(), ((1 - k / q + k * k) * norm).toFloat())
            }

            fun highShelf(freq: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2 * PI * freq / SAMPLE_RATE
                val cs = cos(w0)
                val alpha = sin(w0) / 2 * sqrt(2.0)
                val sq = 2 * sqrt(a) * alpha
                val a0 = (a + 1) - (a - 1) * cs + sq
                return Biquad(
                    (a * ((a + 1) + (a - 1) * cs + sq) / a0).toFloat(),
                    (-2 * a * ((a - 1) + (a + 1) * cs) / a0).toFloat(),
                    (a * ((a + 1) + (a - 1) * cs - sq) / a0).toFloat(),
                    (2 * ((a - 1) - (a + 1) * cs) / a0).toFloat(),
                    (((a + 1) - (a - 1) * cs - sq) / a0).toFloat(),
                )
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val MIC_GAIN = 0.35f
        private const val CEILING = 0.18f
        private const val FADE_SAMPLES = 720
        private const val DELAY_SIZE = 256
        private const val DELAY_MASK = DELAY_SIZE - 1
        private const val HISS_SIZE = 1 shl 17
        private const val HUM_PERIOD = 800          // one 60 Hz cycle at 48 kHz
        private const val BUZZ_NORM = 900f          // brings the hum tables to roughly +-1 for the buzz
        private const val HISS_RMS_DB = -62.6

        /** RMS level of each 60 Hz harmonic, dBFS, as measured on the reference tapes. */
        private val HUM_LEVELS_DB = doubleArrayOf(
            -72.2, -63.9, -76.8, -69.1, -82.5, -78.7, -82.6, -81.6, -79.2, -83.1, -87.4, -84.0,
        )

        /** Hiss power spectral density: frequency (Hz) -> level (dB/Hz), from the reference tapes. */
        private val HISS_SHAPE = arrayOf(
            doubleArrayOf(20.0, -91.0), doubleArrayOf(30.0, -89.5), doubleArrayOf(50.0, -92.5),
            doubleArrayOf(100.0, -92.0), doubleArrayOf(200.0, -93.6), doubleArrayOf(300.0, -95.0),
            doubleArrayOf(500.0, -98.6), doubleArrayOf(700.0, -99.8), doubleArrayOf(1000.0, -100.7),
            doubleArrayOf(1400.0, -101.5), doubleArrayOf(2000.0, -103.6), doubleArrayOf(2800.0, -103.7),
            doubleArrayOf(4000.0, -105.8), doubleArrayOf(5000.0, -104.3), doubleArrayOf(6000.0, -103.8),
            doubleArrayOf(6500.0, -104.5), doubleArrayOf(7000.0, -106.1), doubleArrayOf(8000.0, -109.4),
            doubleArrayOf(9000.0, -111.8), doubleArrayOf(10000.0, -113.5), doubleArrayOf(12000.0, -116.7),
            doubleArrayOf(14000.0, -118.6), doubleArrayOf(15500.0, -120.2), doubleArrayOf(16500.0, -135.0),
        )

        private fun shapeDb(freq: Double): Double {
            if (freq < HISS_SHAPE[0][0]) return -140.0
            val last = HISS_SHAPE[HISS_SHAPE.size - 1]
            if (freq >= last[0]) return -140.0
            for (i in 1 until HISS_SHAPE.size) {
                val hi = HISS_SHAPE[i]
                if (freq < hi[0]) {
                    val lo = HISS_SHAPE[i - 1]
                    val t = ln(freq / lo[0]) / ln(hi[0] / lo[0])
                    return lo[1] + (hi[1] - lo[1]) * t
                }
            }
            return -140.0
        }

        /** A seamless loop of hiss with exactly the measured spectrum: shaped random-phase bins, inverse FFT. */
        private fun makeHiss(rnd: Random): FloatArray {
            val n = HISS_SIZE
            val re = DoubleArray(n)
            val im = DoubleArray(n)
            for (k in 1 until n / 2) {
                val f = k.toDouble() * SAMPLE_RATE / n
                val mag = 10.0.pow(shapeDb(f) / 20.0)
                val ph = rnd.nextDouble() * 2 * PI
                // Rayleigh-distributed bin amplitude keeps the result Gaussian.
                val r = mag * sqrt(-2.0 * ln(1.0 - rnd.nextDouble() * 0.999999))
                re[k] = r * cos(ph)
                im[k] = r * sin(ph)
                re[n - k] = re[k]
                im[n - k] = -im[k]
            }
            fft(re, im)
            var sum = 0.0
            for (v in re) sum += v * v
            val rms = sqrt(sum / n)
            val gain = 10.0.pow(HISS_RMS_DB / 20.0) / rms
            return FloatArray(n) { (re[it] * gain).toFloat() }
        }

        /** In-place radix-2 FFT (the sign convention is irrelevant for random-phase input). */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = 2 * PI / len
                val wr = cos(ang)
                val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr
                        im[b] = im[a] - xi
                        re[a] += xr
                        im[a] += xi
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
