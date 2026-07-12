package com.snoremask.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens to the phone's built-in microphone and decides when snoring is present,
 * driving [MaskState.masking] (which the [BrownNoiseEngine] swells in response to).
 *
 * Detection strategy (deliberately simple and robust for v0.3):
 *  1. Per-frame full-band RMS and low-band RMS (one-pole low-pass ~600 Hz).
 *  2. An adaptive noise floor that tracks the ambient quiet level (rises slowly so
 *     loud snores don't pull it up; falls moderately so it settles in quiet).
 *  3. A frame counts as a snore if it is (a) sufficiently above the floor — the
 *     margin is set by [MaskState.sensitivity] — (b) low-frequency dominant, and
 *     (c) above an absolute silence threshold.
 *  4. A hold timer keeps masking engaged through the quiet gap between snores and
 *     for a few seconds after snoring stops.
 *
 * Input is pinned to the built-in mic so we hear the snorer (phone on the
 * nightstand), not the earbud mic, and so opening the mic doesn't flip Bluetooth
 * into low-quality SCO call mode.
 */
class SnoreDetector {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(context: Context) {
        if (running) return
        running = true
        val appContext = context.applicationContext
        thread = Thread({ runLoop(appContext) }, "SnoreDetector").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        MaskState.masking.value = false
        MaskState.micLevel.value = 0f
    }

    private fun runLoop(context: Context) {
        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, 8 * 1024)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (_: Exception) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return
        }

        // Pin input to the built-in mic (hear the snorer, keep playback on A2DP).
        runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { record.preferredDevice = it }
        }

        val frame = 1024
        val buf = ShortArray(frame)
        val lpCoef = onePoleLowPassCoef(cutoffHz = 600.0, sampleRate = sampleRate)

        var floor = 0.0005f       // adaptive ambient floor (RMS)
        var lpState = 0f          // low-pass filter memory
        var meter = 0f            // smoothed UI meter
        var holdUntil = 0L

        record.startRecording()
        try {
            while (running) {
                val n = record.read(buf, 0, frame)
                if (n <= 0) continue

                var sumSq = 0.0
                var lowSumSq = 0.0
                for (i in 0 until n) {
                    val x = buf[i] / 32768f
                    sumSq += (x * x).toDouble()
                    lpState += lpCoef * (x - lpState)
                    lowSumSq += (lpState * lpState).toDouble()
                }
                val rms = sqrt(sumSq / n).toFloat()
                val lowRms = sqrt(lowSumSq / n).toFloat()
                val lowRatio = lowRms / (rms + 1e-6f)

                // Adaptive floor: rise slowly, fall moderately.
                floor += if (rms < floor) (rms - floor) * 0.05f else (rms - floor) * 0.001f
                if (floor < 1e-5f) floor = 1e-5f

                val rmsDb = 20f * log10(rms + 1e-7f)
                val floorDb = 20f * log10(floor + 1e-7f)

                val sens = MaskState.sensitivity.value
                val margin = 6f + (1f - sens) * 18f   // 6 dB (sensitive) .. 24 dB (strict)

                val isSnore = rmsDb > floorDb + margin &&
                    rmsDb > ABS_SILENCE_DB &&
                    lowRatio > 0.5f

                val now = System.currentTimeMillis()
                if (isSnore) holdUntil = now + HOLD_MS
                MaskState.masking.value = now < holdUntil

                // Live meter: map [-60, 0] dB to [0, 1], lightly smoothed.
                val norm = ((rmsDb + 60f) / 60f).coerceIn(0f, 1f)
                meter += 0.3f * (norm - meter)
                MaskState.micLevel.value = meter
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            MaskState.masking.value = false
            MaskState.micLevel.value = 0f
        }
    }

    private fun onePoleLowPassCoef(cutoffHz: Double, sampleRate: Int): Float {
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        return (dt / (rc + dt)).toFloat()
    }

    companion object {
        private const val HOLD_MS = 6_000L
        private const val ABS_SILENCE_DB = -55f
    }
}
