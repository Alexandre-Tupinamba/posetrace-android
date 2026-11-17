package com.alexandretupinamba.posetrace.recording

import java.io.FileInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePoseSessionWriterTest {
    @Test
    fun completedSessionPromotesPartialFileAndKeepsFrameOrder() {
        val directory = Files.createTempDirectory("posetrace-writer").resolve("session").toFile()
        val writer = FilePoseSessionWriter(
            sessionDirectory = directory,
            sessionId = "session-1",
            startedAtEpochMs = 1_000L,
        )

        writer.append(frame(index = 0L, timestampNs = 100L))
        writer.append(frame(index = 1L, timestampNs = 200L))
        writer.complete(endedAtEpochMs = 2_000L)

        val completedFile = directory.resolve(FilePoseSessionWriter.COMPLETED_DATA_FILE)
        val partialFile = directory.resolve(FilePoseSessionWriter.PARTIAL_DATA_FILE)
        val manifest = directory.resolve(FilePoseSessionWriter.MANIFEST_FILE)

        assertTrue(completedFile.isFile)
        assertFalse(partialFile.exists())
        assertTrue(manifest.readText().contains("\"state\": \"COMPLETED\""))
        assertTrue(manifest.readText().contains("\"frameCount\": 2"))

        val timestamps = FileInputStream(completedFile).use { input ->
            buildList {
                while (true) {
                    val decoded = PoseProtoCodec.readDelimited(input) ?: break
                    add(decoded.cameraTimestampNs)
                }
            }
        }
        assertEquals(listOf(100L, 200L), timestamps)
    }

    @Test
    fun abortedSessionNeverLooksCompleted() {
        val directory = Files.createTempDirectory("posetrace-writer").resolve("session").toFile()
        val writer = FilePoseSessionWriter(
            sessionDirectory = directory,
            sessionId = "session-aborted",
            startedAtEpochMs = 1_000L,
        )

        writer.append(frame(index = 0L, timestampNs = 100L))
        writer.abort(
            finalState = RecordingState.ABORTED,
            endedAtEpochMs = 1_500L,
            reason = "test abort",
        )

        assertFalse(directory.resolve(FilePoseSessionWriter.COMPLETED_DATA_FILE).exists())
        assertTrue(directory.resolve(FilePoseSessionWriter.PARTIAL_DATA_FILE).isFile)
        val manifest = directory.resolve(FilePoseSessionWriter.MANIFEST_FILE).readText()
        assertTrue(manifest.contains("\"state\": \"ABORTED\""))
        assertFalse(manifest.contains("\"state\": \"COMPLETED\""))
    }

    private fun frame(index: Long, timestampNs: Long): PoseFrame =
        PoseFrame(
            analyzerFrameIndex = index,
            cameraTimestampNs = timestampNs,
            mediaPipeTimestampMs = timestampNs / 1_000_000L,
            inferenceLatencyNs = 1_000_000L,
            sourceWidth = 640,
            sourceHeight = 480,
            rotationDegrees = 0,
            poseDetected = false,
            normalizedLandmarks = emptyList(),
            worldLandmarks = emptyList(),
        )
}
