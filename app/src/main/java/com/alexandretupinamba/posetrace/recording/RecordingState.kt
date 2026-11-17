package com.alexandretupinamba.posetrace.recording

enum class RecordingState {
    IDLE,
    PREPARING,
    RECORDING,
    STOPPING,
    COMPLETED,
    ABORTED,
    FAILED,
}

data class RecordingStatus(
    val state: RecordingState = RecordingState.IDLE,
    val sessionId: String? = null,
    val sessionDirectory: String? = null,
    val framesEnqueued: Long = 0L,
    val framesWritten: Long = 0L,
    val error: String? = null,
)

internal class RecordingStateMachine {
    private var currentState: RecordingState = RecordingState.IDLE

    val state: RecordingState
        @Synchronized get() = currentState

    @Synchronized
    fun transitionTo(target: RecordingState) {
        if (target == currentState) return
        require(target in allowedTargets(currentState)) {
            "Invalid recording transition: $currentState -> $target"
        }
        currentState = target
    }

    private fun allowedTargets(from: RecordingState): Set<RecordingState> = when (from) {
        RecordingState.IDLE -> setOf(RecordingState.PREPARING)
        RecordingState.PREPARING -> setOf(
            RecordingState.RECORDING,
            RecordingState.ABORTED,
            RecordingState.FAILED,
        )
        RecordingState.RECORDING -> setOf(
            RecordingState.STOPPING,
            RecordingState.ABORTED,
            RecordingState.FAILED,
        )
        RecordingState.STOPPING -> setOf(
            RecordingState.COMPLETED,
            RecordingState.ABORTED,
            RecordingState.FAILED,
        )
        RecordingState.COMPLETED,
        RecordingState.ABORTED,
        RecordingState.FAILED,
        -> setOf(RecordingState.IDLE)
    }
}
