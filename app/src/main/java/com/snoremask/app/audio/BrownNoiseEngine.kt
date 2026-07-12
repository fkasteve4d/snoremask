package com.snoremask.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.exp
import kotlin.random.Random

/**
 * Generates continuous brown noise and plays it through an [AudioTrack], applying
 * a smooth gain envelope so the level glides between the quiescent floor and the
 * masking ceiling without clicks.
 *
 * Brown ("red") noise is integrated white noise — energy falls off ~6 dB/octave,
 * giving the deep, soft rumble that masks snoring well. We use a leaky integrator
 * so the signal stays bounded and DC-free.
 *
 * All audio happens on a dedicated thread. [start]/[stop] are safe to call from
 * the service's main thread.
 */
class BrownNoiseEngine {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "BrownNoiseEngine").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun runLoop() {
        val sampleRate = 48_000
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        // Comfortable buffer: a few periods worth, keeps playback glitch-free.
        val bufferBytes = (minBuf * 4).coerceAtLeast(8 * 1024)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()

        // Frames per write (stereo => 2 shorts per frame).
        val framesPerWrite = 1024
        val out = ShortArray(framesPerWrite * 2)

        // --- envelope coefficients (per-sample one-pole smoothing) ---
        // attack ~ swell up time, release ~ gentle fade down time.
        val attackCoef = onePoleCoef(timeConstantSec = 0.8, sampleRate = sampleRate)
        val releaseCoef = onePoleCoef(timeConstantSec = 3.0, sampleRate = sampleRate)

        var brown = 0f          // leaky-integrator state
        var gain = MaskState.baseLevel.value  // current smoothed amplitude

        track.play()
        try {
            while (running) {
                val target = if (MaskState.masking.value) MaskState.maxLevel.value
                             else MaskState.baseLevel.value

                var i = 0
                while (i < out.size) {
                    // Brown noise: leaky integration of white noise, normalized.
                    val white = Random.nextFloat() * 2f - 1f
                    brown = (brown + 0.02f * white) / 1.02f
                    val sample = brown * 3.5f   // bring RMS up toward unity-ish

                    // Glide gain toward target (asymmetric attack/release).
                    val coef = if (target > gain) attackCoef else releaseCoef
                    gain += (target - gain) * coef

                    val v = (sample * gain).coerceIn(-1f, 1f)
                    val s = (v * Short.MAX_VALUE).toInt().toShort()
                    out[i] = s       // left
                    out[i + 1] = s   // right
                    i += 2
                }
                track.write(out, 0, out.size)
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    /** One-pole smoothing coefficient for a given time constant, per sample. */
    private fun onePoleCoef(timeConstantSec: Double, sampleRate: Int): Float =
        (1.0 - exp(-1.0 / (timeConstantSec * sampleRate))).toFloat()
}
