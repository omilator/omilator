package com.omilator.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pass C finding 3: the mobile SET_SYSTEM_AV_INFO listener reconfigures
 * the audio output (a full AVAudioEngine/AudioTrack teardown+rebuild) —
 * it must be epsilon-guarded like desktop's PlayerEngine, or every
 * float-noise repeat (59.940 vs 59.9401) and every re-asserted report
 * churns the whole audio stack with audible gaps.
 */
class AvTimingGuardTest {

    @Test
    fun floatNoiseFpsRepeatsDoNotCountAsChanges() {
        assertFalse(AvTimingGuard.fpsChanged(59.94f, 59.9401f))
        assertFalse(AvTimingGuard.fpsChanged(59.94f, 59.94f))
        assertFalse(AvTimingGuard.fpsChanged(60f, 60.000005f))
    }

    @Test
    fun oneHertzLevelSampleRateNoiseDoesNotCountAsAChange() {
        assertFalse(AvTimingGuard.sampleRateChanged(48000.0, 48001.0))
        assertFalse(AvTimingGuard.sampleRateChanged(44100.0, 44100.5))
    }

    @Test
    fun realModeSwitchesDoCount() {
        assertTrue(AvTimingGuard.fpsChanged(60f, 50f)) // NTSC ↔ PAL
        assertTrue(AvTimingGuard.fpsChanged(50f, 60f))
        assertTrue(AvTimingGuard.sampleRateChanged(44100.0, 48000.0))
        assertTrue(AvTimingGuard.sampleRateChanged(48000.0, 44100.0))
    }

    @Test
    fun theFirstReportAlwaysApplies() {
        // The listener seeds its applied values at zero so the load-time
        // av info configures audio/pacing unconditionally.
        assertTrue(AvTimingGuard.fpsChanged(0f, 60f))
        assertTrue(AvTimingGuard.sampleRateChanged(0.0, 48000.0))
    }
}
