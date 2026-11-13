package com.vhscamera.app

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Turns microphone samples into the soundtrack of a look, in place. */
interface TapeSound {
    fun process(pcm: ShortArray, count: Int)
}

/**
 * REAL_VHS: the mono linear audio track of a 90s VHS camcorder, SP speed, 48 kHz.
 *
 * What the built-in microphone hears also includes the camcorder itself: the whir of the
 * head drum and transport and, while the zoom rocker is pressed, the zoom motor. The
 * camera's automatic level control (ALC) pulls loud sound down fast and lets go slowly,
 * so in a quiet room it brings up the room and the mechanism. On tape the sound gets the
 * linear track's 150 Hz - 8 kHz, wow and flutter of about 0.3 %, gentle saturation,
 * hiss about 42 dB below the recording level and the buzz of the video sync pulses that
 * leaks into the audio head (59.94 Hz with its 29.97 Hz half from the two heads).
 */
class CamcorderSound(private val zoomMotor: () -> Boolean, seed: Long = System.nanoTime()) : TapeSound {
    private val rnd = Random(seed)
    private var noiseState = seed xor 0x5DEECE66DL

    // microphone and linear-track response
    private val highPassA = Biquad.highPass(150.0, 0.707)
    private val highPassB = Biquad.highPass(150.0, 0.707)
    private val shelf = Biquad.highShelf(4000.0, -3.0)
    private val lowPassA = Biquad.lowPass(8000.0, 0.541)
    private val lowPassB = Biquad.lowPass(8000.0, 1.307)

    // ALC
    private var rms = 1e-4
    private var alcGain = 1.0

    // mechanism and zoom motor
    private val whirBand = Biquad.bandPass(700.0, 0.9)
    private val gearBand = Biquad.bandPass(1450.0, 6.0)
    private val rattleBand = Biquad.bandPass(3200.0, 1.2)
    private var drumPhase = 0.0
    private var motorPhase = 0.0
    private var motorLevel = 0.0
    private var motorPitch = 1.0
    private var motorPitchTarget = 1.0
    private var motorWasOn = false
    private var clickLeft = 0

    // tape: wow & flutter
    private val delay = FloatArray(DELAY_SIZE)
    private var delayPos = 0
    private var wowPhase = rnd.nextDouble() * 2 * PI
    private var flutterPhase = rnd.nextDouble() * 2 * PI
    private var drift = 0.0
    private var driftTarget = 0.0
    private var driftCountdown = 0

    // tape: hiss and sync buzz
    private val hissShelf = Biquad.highShelf(3000.0, 4.0)
    private val hissLowA = Biquad.lowPass(9000.0, 0.541)
    private val hissLowB = Biquad.lowPass(9000.0, 1.307)
    private var hissWobble = 1.0
    private var hissWobbleTarget = 1.0
    private val buzzShape = FloatArray(BUZZ_LEN)
    private var syncPhase = rnd.nextDouble()
    private var syncField = 0
    private var buzzPos = BUZZ_LEN
    private var buzzAmp = 0.0
    private val buzzLow = Biquad.lowPass(9000.0, 0.707)

    private var fadeIn = 0

    init {
        // one sync pulse as it reaches the audio head: a short, ringing click
        for (i in 0 until BUZZ_LEN) {
            val t = i / SAMPLE_RATE
            buzzShape[i] = (exp(-t / 0.00045) * sin(2 * PI * 2300.0 * t) +
                0.6 * exp(-t / 0.0012) * sin(2 * PI * 620.0 * t)).toFloat()
        }
    }

    override fun process(pcm: ShortArray, count: Int) {
        val motorOn = zoomMotor()
        if (motorOn && !motorWasOn) clickLeft = 240          // the motor engages with a tick
        motorWasOn = motorOn
        if (--driftCountdown <= 0) {
            driftCountdown = 4 + rnd.nextInt(10)
            driftTarget = (rnd.nextDouble() * 2 - 1) * 6.0
            hissWobbleTarget = 10.0.pow((rnd.nextDouble() * 2 - 1) * 0.06)
            motorPitchTarget = 1.0 + (rnd.nextDouble() * 2 - 1) * 0.04
        }

        for (k in 0 until count) {
            // ------------------------------------------------ what the microphone hears
            var x = pcm[k] / 32768.0 * MIC_GAIN

            // the transport: a faint whir, beating with the head drum (29.97 rev/s)
            drumPhase += DRUM_HZ / SAMPLE_RATE
            if (drumPhase >= 1.0) drumPhase -= 1.0
            val whir = whirBand.run(noise()) * (1.0 + 0.5 * sin(2 * PI * drumPhase))
            x += whir * MECHANISM

            // the zoom motor
            motorLevel += ((if (motorOn) 1.0 else 0.0) - motorLevel) * (if (motorOn) 0.004 else 0.002)
            motorPitch += (motorPitchTarget - motorPitch) * 0.0005
            if (motorLevel > 1e-4) {
                motorPhase += MOTOR_HZ * motorPitch / SAMPLE_RATE
                if (motorPhase >= 1.0) motorPhase -= 1.0
                // a brushed motor: buzzy, with commutator harmonics, a gear whine and some rattle
                val saw = 2.0 * motorPhase - 1.0
                val buzz = saw * 0.6 + 0.4 * sin(2 * PI * 3.0 * motorPhase)
                val whine = gearBand.run(noise()) * 2.2
                val rattle = rattleBand.run(noise()) * 0.8
                x += (buzz * 0.5 + whine + rattle) * MOTOR * motorLevel
            }
            if (clickLeft > 0) {
                x += noise() * MOTOR * 3.0 * (clickLeft / 240.0)
                clickLeft--
            }

            // ------------------------------------------------ microphone and ALC
            x = highPassA.run(x)
            x = highPassB.run(x)
            x = shelf.run(x)
            x = lowPassA.run(x)
            x = lowPassB.run(x)

            val e = x * x
            rms += (e - rms) * RMS_COEF
            val level = sqrt(rms)
            var want = ALC_TARGET / (level + 1e-9)
            if (want > ALC_MAX) want = ALC_MAX
            if (want < ALC_MIN) want = ALC_MIN
            alcGain += (want - alcGain) * (if (want < alcGain) ALC_ATTACK else ALC_RELEASE)
            x *= alcGain
            x = LIMIT * tanh(x / LIMIT)

            // ------------------------------------------------ the tape
            x = wowFlutter(x)
            x = SATURATION * tanh(x / SATURATION)

            // hiss, a little uneven along the tape
            hissWobble += (hissWobbleTarget - hissWobble) * 0.00003
            var h = hissShelf.run(noise())
            h = hissLowA.run(h)
            h = hissLowB.run(h)
            x += h * HISS * hissWobble

            // sync buzz: a click per video field, the two heads not quite alike
            syncPhase += FIELD_HZ / SAMPLE_RATE
            if (syncPhase >= 1.0) {
                syncPhase -= 1.0
                syncField = 1 - syncField
                buzzPos = 0
                buzzAmp = if (syncField == 0) 1.0 else 0.78
            }
            if (buzzPos < BUZZ_LEN) {
                x += buzzLow.run(buzzShape[buzzPos] * buzzAmp * BUZZ)
                buzzPos++
            } else {
                x += buzzLow.run(0.0)
            }

            if (fadeIn < FADE_SAMPLES) {
                x *= fadeIn / FADE_SAMPLES.toDouble()
                fadeIn++
            }
            val v = (x * 32767.0).toInt()
            pcm[k] = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
        }
    }

    /** Fast Gaussian-ish noise, unit variance. */
    private fun noise(): Double {
        var s = 0.0
        for (i in 0 until 3) {
            noiseState = noiseState * 6364136223846793005L + 1442695040888963407L
            s += ((noiseState ushr 11) * (1.0 / (1L shl 53))) - 0.5
        }
        return s * 2.0       // sum of three uniforms: variance 3/12, scaled to 1
    }

    private fun wowFlutter(x: Double): Double {
        delay[delayPos] = x.toFloat()
        wowPhase += 2 * PI * WOW_HZ / SAMPLE_RATE
        flutterPhase += 2 * PI * FLUTTER_HZ / SAMPLE_RATE
        if (wowPhase > 2 * PI) wowPhase -= 2 * PI
        if (flutterPhase > 2 * PI) flutterPhase -= 2 * PI
        drift += (driftTarget - drift) * 0.000004
        // delay in samples; its slope is the speed error: wow ~0.25 %, flutter ~0.08 %
        val d = 64.0 + 32.0 * sin(wowPhase) + 0.8 * sin(flutterPhase) + drift
        val rp = delayPos - d
        val i0 = Math.floor(rp).toInt()
        val frac = rp - i0
        val a = delay[i0 and DELAY_MASK]
        val b = delay[(i0 + 1) and DELAY_MASK]
        delayPos = (delayPos + 1) and DELAY_MASK
        return a + (b - a) * frac
    }

    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun run(x: Double): Double {
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
                return Biquad(b0, 2 * b0, b0, 2 * (k * k - 1) * norm, (1 - k / q + k * k) * norm)
            }

            fun highPass(freq: Double, q: Double): Biquad {
                val k = tan(PI * freq / SAMPLE_RATE)
                val norm = 1 / (1 + k / q + k * k)
                return Biquad(norm, -2 * norm, norm, 2 * (k * k - 1) * norm, (1 - k / q + k * k) * norm)
            }

            fun bandPass(freq: Double, q: Double): Biquad {
                val k = tan(PI * freq / SAMPLE_RATE)
                val norm = 1 / (1 + k / q + k * k)
                return Biquad(k / q * norm, 0.0, -k / q * norm, 2 * (k * k - 1) * norm, (1 - k / q + k * k) * norm)
            }

            fun highShelf(freq: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2 * PI * freq / SAMPLE_RATE
                val cs = cos(w0)
                val alpha = sin(w0) / 2 * sqrt(2.0)
                val sq = 2 * sqrt(a) * alpha
                val a0 = (a + 1) - (a - 1) * cs + sq
                return Biquad(
                    a * ((a + 1) + (a - 1) * cs + sq) / a0,
                    -2 * a * ((a - 1) + (a + 1) * cs) / a0,
                    a * ((a + 1) + (a - 1) * cs - sq) / a0,
                    2 * ((a - 1) - (a + 1) * cs) / a0,
                    ((a + 1) - (a - 1) * cs - sq) / a0,
                )
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48000.0
        private const val MIC_GAIN = 1.4
        private const val MECHANISM = 0.0006          // transport whir at the microphone, ~ -68 dBFS
        private const val MOTOR = 0.0038              // zoom motor at the microphone, ~ -50 dBFS
        private const val MOTOR_HZ = 165.0
        private const val DRUM_HZ = 29.97
        private const val ALC_TARGET = 0.1            // -20 dBFS rms
        private const val ALC_MAX = 10.0              // +20 dB in a quiet room
        private const val ALC_MIN = 0.18              // -15 dB for very loud sound
        private const val ALC_ATTACK = 0.003          // per sample: ~7 ms
        private const val ALC_RELEASE = 0.000018      // per sample: ~1.2 s, the "breathing"
        private const val RMS_COEF = 0.0007           // ~30 ms level detector
        private const val LIMIT = 0.7
        private const val SATURATION = 0.85
        private const val HISS = 0.0020               // ~ -54 dBFS
        private const val BUZZ = 0.0075               // sync buzz, ~ -50 dBFS
        private const val FIELD_HZ = 59.94
        private const val BUZZ_LEN = 192
        private const val WOW_HZ = 0.6
        private const val FLUTTER_HZ = 8.0
        private const val DELAY_SIZE = 256
        private const val DELAY_MASK = DELAY_SIZE - 1
        private const val FADE_SAMPLES = 480
    }
}
