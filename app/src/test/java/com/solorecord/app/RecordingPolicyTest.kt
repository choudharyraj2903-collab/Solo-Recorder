package com.solorecord.app

import org.junit.Assert.*
import org.junit.Test

class RecordingPolicyTest {
    @Test fun fullSessionWithPauseResume() {
        val m = RecordingStateMachine()
        assertTrue(m.send(CaptureEvent.START))
        assertTrue(m.send(CaptureEvent.READY))
        assertTrue(m.send(CaptureEvent.PAUSE))
        assertTrue(m.send(CaptureEvent.RESUME))
        assertTrue(m.send(CaptureEvent.STOP))
        assertTrue(m.send(CaptureEvent.FINISH))
        assertEquals(CaptureState.IDLE, m.state)
    }

    @Test fun rejectsInvalidRecordingTransitions() {
        val m = RecordingStateMachine()
        assertFalse(m.send(CaptureEvent.PAUSE))
        assertFalse(m.send(CaptureEvent.STOP))
        assertTrue(m.send(CaptureEvent.START))
        assertFalse(m.send(CaptureEvent.START))
        assertFalse(m.send(CaptureEvent.PAUSE))
        assertTrue(m.send(CaptureEvent.FAIL))
        assertEquals(CaptureState.IDLE, m.state)
    }

    @Test fun cleanupFailureFromActiveStates() {
        val m = RecordingStateMachine()
        m.send(CaptureEvent.START)
        m.send(CaptureEvent.READY)
        assertTrue(m.send(CaptureEvent.FAIL))
        assertEquals(CaptureState.IDLE, m.state)
        val paused = RecordingStateMachine()
        paused.send(CaptureEvent.START)
        paused.send(CaptureEvent.READY)
        paused.send(CaptureEvent.PAUSE)
        assertTrue(paused.send(CaptureEvent.FAIL))
        assertEquals(CaptureState.IDLE, paused.state)
    }

    @Test fun acceptsStopDuringPreparation() {
        val m = RecordingStateMachine()
        assertTrue(m.send(CaptureEvent.START))
        assertTrue(m.send(CaptureEvent.STOP))
        assertFalse(m.send(CaptureEvent.READY))
        assertTrue(m.send(CaptureEvent.FINISH))
    }

    @Test fun stopIsIdempotentAfterFinishing() {
        val m = RecordingStateMachine()
        m.send(CaptureEvent.START)
        m.send(CaptureEvent.READY)
        assertTrue(m.send(CaptureEvent.STOP))
        assertFalse(m.send(CaptureEvent.STOP))
        assertTrue(m.send(CaptureEvent.FINISH))
        assertFalse(m.send(CaptureEvent.FINISH))
    }

    @Test fun portraitDownscalePreservesAlignment() {
        val result = RecordingPolicy.videoSize(1080, 2400, 1920)
        assertEquals(VideoSize(864, 1920), result)
        assertEquals(0, result.width % 16)
        assertEquals(0, result.height % 16)
    }

    @Test fun landscapeDownscale() {
        assertEquals(VideoSize(1920, 864), RecordingPolicy.videoSize(2400, 1080, 1920))
    }

    @Test fun noUpscalingAndEvenAlignment() {
        assertEquals(VideoSize(720, 1280), RecordingPolicy.videoSize(720, 1280, 1920))
        assertEquals(VideoSize(704, 1264), RecordingPolicy.videoSize(719, 1279, 1280))
    }

    @Test fun bitrateInReasonableBounds() {
        assertEquals(2_000_000, RecordingPolicy.bitrate(VideoSize(320, 320), 30))
        assertTrue(RecordingPolicy.bitrate(VideoSize(1088, 1920), 30) in 2_000_000..14_000_000)
        assertEquals(14_000_000, RecordingPolicy.bitrate(VideoSize(3840, 2160), 60))
    }

    @Test fun filenamePrefixIsSafe() {
        assertEquals("SoloRecord", RecordingPolicy.safePrefix("..."))
        assertEquals("My_Record", RecordingPolicy.safePrefix("My Record"))
        assertEquals("hack____evil", RecordingPolicy.safePrefix("hack/../evil"))
        assertEquals(24, RecordingPolicy.safePrefix("a".repeat(100)).length)
        assertEquals("SoloRecord", RecordingPolicy.safePrefix("💬💬💬"))
    }
}
