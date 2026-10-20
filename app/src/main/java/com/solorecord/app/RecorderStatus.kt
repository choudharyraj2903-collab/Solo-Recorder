package com.solorecord.app

/** In-process display status only. The foreground service owns the actual recording. */
data class StatusSnapshot(
    val state: CaptureState = CaptureState.IDLE,
    val message: String = "Ready to record",
    val elapsedMs: Long = 0L
)

object RecorderStatus {
    @Volatile var snapshot: StatusSnapshot = StatusSnapshot()
        private set

    fun update(state: CaptureState, message: String, elapsedMs: Long = 0L) {
        snapshot = StatusSnapshot(state, message, elapsedMs)
    }
}
