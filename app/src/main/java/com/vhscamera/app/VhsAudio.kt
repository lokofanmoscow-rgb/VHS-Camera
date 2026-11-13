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
 * The microphone is band-limited, levelled by a camcorder-style automatic level control
 * (a soft compressor), gets a touch of wow and flutter and is laid over the tape's own
 * noise bed. The bed is synthesised from measurements of real tape noise:
 * a hiss with the measured spectrum (gently "breathing", and buzzing slightly at the head
 * drum rate) plus the buzz itself, a comb of tones from 30 Hz up to 1.6 kHz.
 */
class VhsAudio(seed: Long = System.nanoTime()) {
    private val rnd = Random(seed)

    // microphone chain
    private val highPass = Biquad.highPass(70.0, 0.707)
    private val tone = Biquad.highShelf(2200.0, -7.0)
    private val lowPassA = Biquad.lowPass(7200.0, 0.541)
    private val lowPassB = Biquad.lowPass(7200.0, 1.307)

    // automatic level control
    private var env = 0f
    private var alcGain = 1f

    // wow & flutter delay line
    private val delay = FloatArray(DELAY_SIZE)
    private var delayPos = 0
    private var wowPhase = rnd.nextDouble() * 2 * PI
    private var flutterPhase = rnd.nextDouble() * 2 * PI

    // hiss
    private val hiss: FloatArray = hissLoop
    private var hissPos = rnd.nextInt(hiss.size)
    private var hissGain = 1f
    private var hissTarget = 1f

    // buzz: one rotating phasor per tone
    private val n = TONES.size
    private val oscC = DoubleArray(n)
    private val oscS = DoubleArray(n)
    private val rotC = DoubleArray(n)
    private val rotS = DoubleArray(n)
    private val amp = FloatArray(n)
    private val gain = FloatArray(n) { 1f }
    private val gainTarget = FloatArray(n) { 1f }
    private var driftCountdown = 0
    private var fadeIn = 0

    init {
        for (i in 0 until n) {
            val w = 2 * PI * TONES[i][0] / SAMPLE_RATE
            rotC[i] = cos(w)
            rotS[i] = sin(w)
            val p = rnd.nextDouble() * 2 * PI
            oscC[i] = cos(p)
            oscS[i] = sin(p)
            amp[i] = (10.0.pow(TONES[i][1] / 20.0) * sqrt(2.0)).toFloat()
        }
    }

    /** Processes [count] microphone samples of [pcm] in place. */
    fun process(pcm: ShortArray, count: Int) {
        // Slow drift of the noise, re-aimed a few times per second.
        driftCountdown -= count
        if (driftCountdown <= 0) {
            driftCountdown = 12000 + rnd.nextInt(36000)
            hissTarget = 1f + 0.07f * (rnd.nextFloat() * 2f - 1f)
            for (i in 0 until n) gainTarget[i] = 10.0.pow((rnd.nextDouble() * 2 - 1) * TONE_WANDER_DB / 20.0).toFloat()
        }
        // Keep the phasors on the unit circle.
        for (i in 0 until n) {
            val r = 1.0 / sqrt(oscC[i] * oscC[i] + oscS[i] * oscS[i])
            oscC[i] *= r
            oscS[i] *= r
        }

        for (k in 0 until count) {
            // microphone -> tape
            var x = pcm[k] / 32768f * MIC_GAIN
            x = highPass.run(x)
            x = wowFlutter(x)
            x = tone.run(x)
            x = lowPassA.run(x)
            x = lowPassB.run(x)
            x = alc(x)
            x = CEILING * tanh(x / CEILING)

            // buzz comb
            var buzz = 0f
            for (i in 0 until n) {
                val c = oscC[i] * rotC[i] - oscS[i] * rotS[i]
                val s = oscS[i] * rotC[i] + oscC[i] * rotS[i]
                oscC[i] = c
                oscS[i] = s
                gain[i] += (gainTarget[i] - gain[i]) * 0.00004f
                buzz += amp[i] * gain[i] * s.toFloat()
            }

            // hiss, breathing slowly and buzzing a little with the head drum
            hissGain += (hissTarget - hissGain) * 0.00004f
            val am = 1f + 0.05f * oscS[IDX_60].toFloat() + 0.03f * oscS[IDX_120].toFloat()
            val h = hiss[hissPos] * hissGain * am
            if (++hissPos >= hiss.size) hissPos = 0

            var y = x + h + buzz
            if (fadeIn < FADE_SAMPLES) {
                y *= fadeIn / FADE_SAMPLES.toFloat()
                fadeIn++
            }
            val v = (y * 32767f).toInt()
            pcm[k] = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
        }
    }

    /**
     * Compressor of the kind a camcorder uses on its microphone: quick to pull loud sounds
     * down, slow to let go. Below the threshold sound only gets the make-up gain.
     */
    private fun alc(x: Float): Float {
        val a = if (x >= 0f) x else -x
        env += (a - env) * (if (a > env) ALC_ATTACK else ALC_RELEASE)
        if (++alcCount >= 16) {
            alcCount = 0
            val levelDb = 20.0 * Math.log10(env.toDouble() + 1e-9)
            val over = levelDb - ALC_THRESHOLD_DB
            // soft knee
            val reduce = when {
                over <= -ALC_KNEE_DB / 2 -> 0.0
                over >= ALC_KNEE_DB / 2 -> over * (1.0 - 1.0 / ALC_RATIO)
                else -> {
                    val k = over + ALC_KNEE_DB / 2
                    (1.0 - 1.0 / ALC_RATIO) * k * k / (2 * ALC_KNEE_DB)
                }
            }
            alcTarget = 10.0.pow((ALC_MAKEUP_DB - reduce) / 20.0).toFloat()
        }
        alcGain += (alcTarget - alcGain) * 0.02f
        return x * alcGain
    }

    private var alcCount = 0
    private var alcTarget = 1f

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
        private const val MIC_GAIN = 1.4f
        private const val CEILING = 0.5f            // only keeps very loud sound from clipping
        private const val ALC_THRESHOLD_DB = -15.0  // peak level where the level control starts to act
        private const val ALC_RATIO = 3.0
        private const val ALC_KNEE_DB = 8.0
        private const val ALC_MAKEUP_DB = 3.0
        private const val ALC_ATTACK = 0.004f       // per sample: ~5 ms
        private const val ALC_RELEASE = 0.00008f    // per sample: ~0.25 s
        private const val FADE_SAMPLES = 720
        private const val DELAY_SIZE = 256
        private const val DELAY_MASK = DELAY_SIZE - 1
        private const val HISS_SIZE = 1 shl 19      // 10.9 s, long enough not to be heard repeating
        private const val HISS_RMS_DB = -64.9
        private const val TONE_WANDER_DB = 1.5
        private const val IDX_60 = 1
        private const val IDX_120 = 3

        /** The buzz: frequency (Hz) and RMS level (dBFS) of each tone of the comb. */
        private val TONES = arrayOf(
            doubleArrayOf(30.0, -81.4),
            doubleArrayOf(60.1, -72.0),
            doubleArrayOf(90.1, -72.8),
            doubleArrayOf(120.1, -64.0),
            doubleArrayOf(180.2, -77.2),
            doubleArrayOf(240.2, -69.5),
            doubleArrayOf(299.6, -83.0),
            doubleArrayOf(359.6, -78.6),
            doubleArrayOf(419.7, -83.5),
            doubleArrayOf(480.5, -82.2),
            doubleArrayOf(539.8, -79.1),
            doubleArrayOf(599.9, -83.8),
            doubleArrayOf(659.2, -88.7),
            doubleArrayOf(719.2, -84.1),
            doubleArrayOf(779.3, -91.3),
            doubleArrayOf(839.4, -86.1),
            doubleArrayOf(898.7, -87.0),
            doubleArrayOf(958.7, -86.5),
            doubleArrayOf(1018.8, -90.7),
            doubleArrayOf(1078.9, -90.1),
            doubleArrayOf(1138.9, -89.5),
            doubleArrayOf(1198.2, -90.6),
            doubleArrayOf(1258.3, -92.2),
            doubleArrayOf(1318.4, -89.8),
            doubleArrayOf(1378.4, -90.9),
            doubleArrayOf(1438.5, -91.2),
            doubleArrayOf(1497.8, -92.4),
            doubleArrayOf(1617.9, -93.2),
        )

        /** Hiss power spectral density between the tones: frequency (Hz) -> level (dB/Hz). */
        private val HISS_SHAPE = arrayOf(
            doubleArrayOf(20.0, -98.1),
            doubleArrayOf(22.4, -97.9),
            doubleArrayOf(25.2, -97.6),
            doubleArrayOf(28.3, -97.3),
            doubleArrayOf(31.7, -97.2),
            doubleArrayOf(35.6, -97.0),
            doubleArrayOf(40.0, -96.8),
            doubleArrayOf(44.9, -96.7),
            doubleArrayOf(50.4, -96.5),
            doubleArrayOf(56.6, -96.3),
            doubleArrayOf(63.5, -96.1),
            doubleArrayOf(71.3, -95.8),
            doubleArrayOf(80.0, -95.6),
            doubleArrayOf(89.8, -95.6),
            doubleArrayOf(100.8, -95.5),
            doubleArrayOf(113.1, -95.5),
            doubleArrayOf(127.0, -95.8),
            doubleArrayOf(142.5, -96.0),
            doubleArrayOf(160.0, -96.2),
            doubleArrayOf(179.6, -96.5),
            doubleArrayOf(201.6, -96.7),
            doubleArrayOf(226.3, -97.3),
            doubleArrayOf(254.0, -97.8),
            doubleArrayOf(285.1, -99.0),
            doubleArrayOf(320.0, -99.9),
            doubleArrayOf(359.2, -100.8),
            doubleArrayOf(403.2, -101.2),
            doubleArrayOf(452.5, -101.8),
            doubleArrayOf(508.0, -102.4),
            doubleArrayOf(570.2, -102.8),
            doubleArrayOf(640.0, -103.4),
            doubleArrayOf(718.4, -103.7),
            doubleArrayOf(806.3, -103.9),
            doubleArrayOf(905.1, -104.5),
            doubleArrayOf(1015.9, -104.8),
            doubleArrayOf(1140.4, -105.4),
            doubleArrayOf(1280.0, -104.6),
            doubleArrayOf(1436.8, -104.4),
            doubleArrayOf(1612.7, -105.0),
            doubleArrayOf(1810.2, -105.4),
            doubleArrayOf(2031.9, -105.3),
            doubleArrayOf(2280.7, -105.7),
            doubleArrayOf(2560.0, -106.0),
            doubleArrayOf(2873.5, -105.9),
            doubleArrayOf(3225.4, -106.2),
            doubleArrayOf(3620.4, -106.2),
            doubleArrayOf(4063.7, -106.1),
            doubleArrayOf(4561.4, -105.2),
            doubleArrayOf(5120.0, -104.4),
            doubleArrayOf(5747.0, -104.0),
            doubleArrayOf(6450.8, -104.7),
            doubleArrayOf(7240.8, -107.5),
            doubleArrayOf(8127.5, -110.4),
            doubleArrayOf(9122.8, -112.5),
            doubleArrayOf(10240.0, -114.2),
            doubleArrayOf(11494.0, -116.4),
            doubleArrayOf(12901.6, -118.1),
            doubleArrayOf(14481.5, -119.3),
            doubleArrayOf(15500.0, -120.6),
            doubleArrayOf(16000.0, -124.0),
            doubleArrayOf(16600.0, -140.0),
        )

        private val hissLoop: FloatArray by lazy { makeHiss(Random(20250309L)) }

        /** Builds the hiss ahead of time; it takes a fraction of a second. */
        fun warmUp() {
            hissLoop.size
        }

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
