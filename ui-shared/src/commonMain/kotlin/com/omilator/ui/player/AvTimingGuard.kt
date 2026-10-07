package com.omilator.ui.player

import kotlin.math.abs

/**
 * Epsilon guard for mid-run SET_SYSTEM_AV_INFO timing changes on mobile,
 * mirroring desktop PlayerEngine's FPS_CHANGE_EPSILON /
 * SAMPLE_RATE_EPSILON. Cores re-assert the command with float noise
 * (59.940 vs 59.9401) or repeat reports; acting on every report would
 * re-pace the loop and — far worse — reconfigure the audio output, which
 * on mobile is a full platform audio-stack teardown+rebuild
 * (AVAudioEngine + audio session re-activation on iOS, AudioTrack
 * release on Android), producing audible gaps on noise-level changes.
 */
internal object AvTimingGuard {
    private const val FPS_CHANGE_EPSILON = 0.01f
    private const val SAMPLE_RATE_EPSILON = 1.0

    fun fpsChanged(oldFps: Float, newFps: Float): Boolean =
        abs(newFps - oldFps) > FPS_CHANGE_EPSILON

    fun sampleRateChanged(oldRate: Double, newRate: Double): Boolean =
        abs(newRate - oldRate) > SAMPLE_RATE_EPSILON
}
