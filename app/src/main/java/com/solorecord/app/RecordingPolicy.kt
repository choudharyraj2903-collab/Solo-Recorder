package com.solorecord.app

/** Pure policy functions: no Android runtime dependency, suitable for unit testing. */
enum class CaptureState { IDLE, STARTING, RECORDING, PAUSED, STOPPING }
enum class CaptureEvent { START, READY, PAUSE, RESUME, STOP, FINISH, FAIL }

class RecordingStateMachine {
    var state: CaptureState = CaptureState.IDLE
        private set

    @Synchronized
    fun send(event: CaptureEvent): Boolean {
        val next = when (state to event) {
            CaptureState.IDLE to CaptureEvent.START -> CaptureState.STARTING
            CaptureState.STARTING to CaptureEvent.READY -> CaptureState.RECORDING
            CaptureState.STARTING to CaptureEvent.STOP -> CaptureState.STOPPING
            CaptureState.STARTING to CaptureEvent.FAIL -> CaptureState.IDLE
            CaptureState.RECORDING to CaptureEvent.FAIL -> CaptureState.IDLE
            CaptureState.PAUSED to CaptureEvent.FAIL -> CaptureState.IDLE
            CaptureState.STOPPING to CaptureEvent.FAIL -> CaptureState.IDLE
            CaptureState.RECORDING to CaptureEvent.PAUSE -> CaptureState.PAUSED
            CaptureState.PAUSED to CaptureEvent.RESUME -> CaptureState.RECORDING
            CaptureState.RECORDING to CaptureEvent.STOP -> CaptureState.STOPPING
            CaptureState.PAUSED to CaptureEvent.STOP -> CaptureState.STOPPING
            CaptureState.STOPPING to CaptureEvent.FINISH -> CaptureState.IDLE
            else -> return false
        }
        state = next
        return true
    }
}

data class VideoSize(val width: Int, val height: Int)

object RecordingPolicy {
    /** Encoder-friendly, 16-pixel aligned dimensions. Never upscales. */
    fun videoSize(displayWidth: Int, displayHeight: Int, maxLongEdge: Int): VideoSize {
        require(displayWidth >= 16 && displayHeight >= 16)
        require(maxLongEdge >= 320)
        val sourceLongEdge = maxOf(displayWidth, displayHeight)
        val scale = minOf(1.0, maxLongEdge.toDouble() / sourceLongEdge)
        fun align(value: Int): Int = maxOf(16, ((value * scale).toInt() / 16) * 16)
        return VideoSize(align(displayWidth), align(displayHeight))
    }

    /** H.264 bitrate; bounded to keep storage use reasonable. */
    fun bitrate(size: VideoSize, fps: Int): Int {
        require(fps == 30 || fps == 60)
        return (size.width.toLong() * size.height * fps * 9 / 100)
            .coerceIn(2_000_000L, 14_000_000L).toInt()
    }

    /** Prevent filename/path injection; preserves only basic safe characters. */
    fun safePrefix(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_', '-').take(24)
        return cleaned.ifEmpty { "SoloRecord" }
    }
}
