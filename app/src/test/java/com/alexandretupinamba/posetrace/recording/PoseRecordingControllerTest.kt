package com.alexandretupinamba.posetrace.recording

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseRecordingControllerTest {
    @Test
    fun stopFlushesFramesInTimestampOrderBeforeCompleting() {
        val writer = TestWriter()
        val controller = controller(writer)

        controller.start()
        assertTrue(controller.record(frame(index = 0L, cameraTimestampNs = 100L)))
        assertTrue(controller.record(frame(index = 1L, cameraTimestampNs = 200L)))
        controller.stop()

        awaitState(controller, RecordingState.COMPLETED)

        assertEquals(listOf(100L, 200L), writer.frames.map { it.cameraTimestampNs })
        assertTrue(writer.completed)
        assertEquals(2L, controller.status.value.framesWritten)
        controller.close()
    }

    @Test
    fun frameSubmittedAfterStopIsRejectedAndNeverWritten() {
        val writer = TestWriter()
        val controller = controller(writer)

        controller.start()
        assertTrue(controller.record(frame(index = 0L, cameraTimestampNs = 100L)))
        controller.stop()
        assertFalse(controller.record(frame(index = 1L, cameraTimestampNs = 200L)))

        awaitState(controller, RecordingState.COMPLETED)

        assertEquals(listOf(100L), writer.frames.map { it.cameraTimestampNs })
        assertEquals(1L, controller.status.value.framesEnqueued)
        assertEquals(1L, controller.status.value.framesWritten)
        controller.close()
    }

    @Test
    fun abortCannotOverwriteCompletionOnceFinalizationStarted() {
        val writer = TestWriter(blockOnComplete = true)
        val controller = controller(writer)

        controller.start()
        assertTrue(controller.record(frame(index = 0L, cameraTimestampNs = 100L)))
        controller.stop()

        assertTrue(writer.completeEntered.await(2L, TimeUnit.SECONDS))

        val abortThread = Thread {
            controller.abort("activity destroyed during finalization")
        }.apply { start() }

        writer.releaseComplete.countDown()
        abortThread.join(2_000L)
        awaitState(controller, RecordingState.COMPLETED)

        assertTrue(writer.completed)
        assertEquals(null, writer.abortedState)
        assertEquals(RecordingState.COMPLETED, controller.status.value.state)
        controller.close()
    }

    @Test
    fun nonMonotonicTimestampFailsSessionExplicitly() {
        val writer = TestWriter()
        val controller = controller(writer)

        controller.start()
        assertTrue(controller.record(frame(index = 0L, cameraTimestampNs = 200L)))
        assertFalse(controller.record(frame(index = 1L, cameraTimestampNs = 150L)))

        awaitState(controller, RecordingState.FAILED)
        awaitWriterAbort(writer, RecordingState.FAILED)

        assertTrue(controller.status.value.error!!.contains("Non-monotonic"))
        controller.close()
    }

    @Test
    fun writerExceptionMarksSessionFailed() {
        val writer = TestWriter(failOnAppend = true)
        val controller = controller(writer)

        controller.start()
        assertTrue(controller.record(frame(index = 0L, cameraTimestampNs = 100L)))

        awaitState(controller, RecordingState.FAILED)
        awaitWriterAbort(writer, RecordingState.FAILED)

        assertTrue(controller.status.value.error!!.contains("writer failed", ignoreCase = true))
        controller.close()
    }

    private fun controller(writer: TestWriter): PoseRecordingController {
        val root = Files.createTempDirectory("posetrace-test").toFile()
        return PoseRecordingController(
            recordingsRoot = root,
            queueCapacity = 8,
            nowEpochMs = { 1234L },
            newSessionId = { "session-test" },
            writerFactory = { _: File, _: String, _: Long -> writer },
        )
    }

    private fun awaitState(
        controller: PoseRecordingController,
        expected: RecordingState,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (controller.status.value.state == expected) return
            Thread.sleep(10L)
        }
        assertEquals(expected, controller.status.value.state)
    }

    private fun awaitWriterAbort(
        writer: TestWriter,
        expected: RecordingState,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (writer.abortedState == expected) return
            Thread.sleep(10L)
        }
        assertEquals(expected, writer.abortedState)
    }

    private fun frame(index: Long, cameraTimestampNs: Long): PoseFrame =
        PoseFrame(
            analyzerFrameIndex = index,
            cameraTimestampNs = cameraTimestampNs,
            mediaPipeTimestampMs = cameraTimestampNs / 1_000_000L,
            inferenceLatencyNs = 1_000_000L,
            sourceWidth = 640,
            sourceHeight = 480,
            rotationDegrees = 0,
            poseDetected = false,
            normalizedLandmarks = emptyList(),
            worldLandmarks = emptyList(),
        )

    private class TestWriter(
        private val failOnAppend: Boolean = false,
        private val blockOnComplete: Boolean = false,
    ) : PoseSessionWriter {
        val frames: MutableList<PoseFrame> = Collections.synchronizedList(mutableListOf())
        val completeEntered = CountDownLatch(1)
        val releaseComplete = CountDownLatch(1)
        @Volatile var completed = false
        @Volatile var abortedState: RecordingState? = null

        override fun append(frame: PoseFrame) {
            if (failOnAppend) error("synthetic disk failure")
            frames += frame
        }

        override fun complete(endedAtEpochMs: Long) {
            completeEntered.countDown()
            if (blockOnComplete) {
                check(releaseComplete.await(2L, TimeUnit.SECONDS)) {
                    "Timed out waiting to release synthetic finalization"
                }
            }
            completed = true
        }

        override fun abort(
            finalState: RecordingState,
            endedAtEpochMs: Long,
            reason: String?,
        ) {
            abortedState = finalState
        }
    }
}
