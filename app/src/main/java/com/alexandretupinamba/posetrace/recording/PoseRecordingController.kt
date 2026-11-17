package com.alexandretupinamba.posetrace.recording

import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class PoseRecordingController(
    private val recordingsRoot: File,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val writerFactory: (File, String, Long) -> PoseSessionWriter =
        { directory, sessionId, startedAt ->
            FilePoseSessionWriter(directory, sessionId, startedAt)
        },
) : AutoCloseable {
    private val stateMachine = RecordingStateMachine()
    private val lifecycleLock = Any()
    private val frameLock = Any()
    private val framesEnqueued = AtomicLong(0L)
    private val framesWritten = AtomicLong(0L)

    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private var queue: ArrayBlockingQueue<PoseFrame>? = null
    private var worker: Thread? = null
    private var lastEnqueuedCameraTimestampNs: Long? = null

    init {
        require(queueCapacity > 0) { "queueCapacity must be positive" }
    }

    fun start(): String {
        synchronized(lifecycleLock) {
            resetTerminalStateIfNeeded()
            check(stateMachine.state == RecordingState.IDLE) {
                "Cannot start recording from ${stateMachine.state}"
            }

            stateMachine.transitionTo(RecordingState.PREPARING)
            publishState(RecordingState.PREPARING)

            val sessionId = newSessionId()
            val sessionDirectory = File(recordingsRoot, sessionId)
            val startedAt = nowEpochMs()

            return try {
                val sessionWriter = writerFactory(sessionDirectory, sessionId, startedAt)
                val frameQueue = ArrayBlockingQueue<PoseFrame>(queueCapacity)

                framesEnqueued.set(0L)
                framesWritten.set(0L)
                lastEnqueuedCameraTimestampNs = null
                queue = frameQueue

                worker = Thread(
                    { writerLoop(sessionWriter, frameQueue) },
                    "PoseTrace-RecordingWriter",
                ).apply {
                    isDaemon = true
                    start()
                }

                stateMachine.transitionTo(RecordingState.RECORDING)
                _status.value = RecordingStatus(
                    state = RecordingState.RECORDING,
                    sessionId = sessionId,
                    sessionDirectory = sessionDirectory.absolutePath,
                )
                sessionId
            } catch (error: Exception) {
                markFailedLocked("Unable to start recording: ${error.message}")
                throw error
            }
        }
    }

    fun record(frame: PoseFrame): Boolean {
        if (stateMachine.state != RecordingState.RECORDING) return false

        synchronized(frameLock) {
            if (stateMachine.state != RecordingState.RECORDING) return false

            val previousTimestamp = lastEnqueuedCameraTimestampNs
            if (previousTimestamp != null && frame.cameraTimestampNs <= previousTimestamp) {
                fail(
                    "Non-monotonic camera timestamp: " +
                        "$previousTimestamp -> ${frame.cameraTimestampNs}",
                )
                return false
            }

            val frameQueue = queue ?: run {
                fail("Recording queue is unavailable")
                return false
            }

            if (!frameQueue.offer(frame)) {
                fail("Recording buffer overflow at capacity $queueCapacity")
                return false
            }

            lastEnqueuedCameraTimestampNs = frame.cameraTimestampNs
            val enqueued = framesEnqueued.incrementAndGet()
            _status.update {
                it.copy(
                    framesEnqueued = enqueued,
                    framesWritten = framesWritten.get(),
                )
            }
            return true
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            if (stateMachine.state != RecordingState.RECORDING) return
            stateMachine.transitionTo(RecordingState.STOPPING)
            publishState(RecordingState.STOPPING)
        }
    }

    fun abort(reason: String = "Recording aborted") {
        synchronized(lifecycleLock) {
            when (stateMachine.state) {
                RecordingState.PREPARING,
                RecordingState.RECORDING,
                RecordingState.STOPPING,
                -> {
                    stateMachine.transitionTo(RecordingState.ABORTED)
                    queue?.clear()
                    _status.update {
                        it.copy(
                            state = RecordingState.ABORTED,
                            framesEnqueued = framesEnqueued.get(),
                            framesWritten = framesWritten.get(),
                            error = reason,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    fun fail(reason: String) {
        synchronized(lifecycleLock) {
            when (stateMachine.state) {
                RecordingState.PREPARING,
                RecordingState.RECORDING,
                RecordingState.STOPPING,
                -> markFailedLocked(reason)
                else -> Unit
            }
        }
    }

    private fun writerLoop(
        sessionWriter: PoseSessionWriter,
        frameQueue: ArrayBlockingQueue<PoseFrame>,
    ) {
        try {
            while (true) {
                val frame = frameQueue.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS)
                if (frame != null && stateMachine.state !in TERMINAL_FAILURE_STATES) {
                    sessionWriter.append(frame)
                    val written = framesWritten.incrementAndGet()
                    _status.update {
                        it.copy(
                            framesEnqueued = framesEnqueued.get(),
                            framesWritten = written,
                        )
                    }
                }

                when (stateMachine.state) {
                    RecordingState.STOPPING -> {
                        if (frameQueue.isEmpty()) {
                            sessionWriter.complete(nowEpochMs())
                            synchronized(lifecycleLock) {
                                stateMachine.transitionTo(RecordingState.COMPLETED)
                                publishState(RecordingState.COMPLETED)
                            }
                            return
                        }
                    }
                    RecordingState.ABORTED -> {
                        sessionWriter.abort(
                            finalState = RecordingState.ABORTED,
                            endedAtEpochMs = nowEpochMs(),
                            reason = _status.value.error,
                        )
                        return
                    }
                    RecordingState.FAILED -> {
                        sessionWriter.abort(
                            finalState = RecordingState.FAILED,
                            endedAtEpochMs = nowEpochMs(),
                            reason = _status.value.error,
                        )
                        return
                    }
                    else -> Unit
                }
            }
        } catch (error: Exception) {
            synchronized(lifecycleLock) {
                if (stateMachine.state != RecordingState.FAILED) {
                    markFailedLocked("Recording writer failed: ${error.message}")
                }
            }
            runCatching {
                sessionWriter.abort(
                    finalState = RecordingState.FAILED,
                    endedAtEpochMs = nowEpochMs(),
                    reason = _status.value.error,
                )
            }
        }
    }

    private fun resetTerminalStateIfNeeded() {
        when (stateMachine.state) {
            RecordingState.COMPLETED,
            RecordingState.ABORTED,
            RecordingState.FAILED,
            -> {
                stateMachine.transitionTo(RecordingState.IDLE)
                queue = null
                worker = null
                lastEnqueuedCameraTimestampNs = null
                _status.value = RecordingStatus()
            }
            else -> Unit
        }
    }

    private fun markFailedLocked(reason: String) {
        if (stateMachine.state != RecordingState.FAILED) {
            stateMachine.transitionTo(RecordingState.FAILED)
        }
        queue?.clear()
        _status.update {
            it.copy(
                state = RecordingState.FAILED,
                framesEnqueued = framesEnqueued.get(),
                framesWritten = framesWritten.get(),
                error = reason,
            )
        }
    }

    private fun publishState(state: RecordingState) {
        _status.update {
            it.copy(
                state = state,
                framesEnqueued = framesEnqueued.get(),
                framesWritten = framesWritten.get(),
            )
        }
    }

    override fun close() {
        abort("Recording controller closed")
        worker?.join(CLOSE_JOIN_MS)
    }

    companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 256
        private const val WRITER_POLL_MS = 25L
        private const val CLOSE_JOIN_MS = 2_000L
        private val TERMINAL_FAILURE_STATES = setOf(
            RecordingState.ABORTED,
            RecordingState.FAILED,
        )
    }
}
