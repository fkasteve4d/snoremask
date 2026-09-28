package com.snoremask.app.audio

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-process shared state bridging the UI and the audio service (they run in the
 * same process). The UI writes the user's settings here; [BrownNoiseEngine] reads
 * them on the audio thread.
 *
 *  - [baseLevel]  quiescent floor (0..1): brown noise that plays all night.
 *  - [maxLevel]   ceiling (0..1): level the masker swells up to during snoring.
 *  - [masking]     true while actively masking — driven by snore detection.
 *  - [running]     true while audio is active (engine playing).
 *  - [waiting]     true while the service is resident but idle, waiting for the
 *                  chosen Bluetooth device to connect (auto-start armed).
 *  - [sensitivity] detection sensitivity (0..1); higher = triggers more easily.
 *  - [micLevel]    smoothed mic level (0..1) for the live meter.
 */
object MaskState {
    val baseLevel = MutableStateFlow(0.08f)
    val maxLevel = MutableStateFlow(0.50f)
    val masking = MutableStateFlow(false)
    val running = MutableStateFlow(false)
    val waiting = MutableStateFlow(false)
    val sensitivity = MutableStateFlow(0.5f)
    val micLevel = MutableStateFlow(0f)
}
