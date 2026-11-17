package com.alexandretupinamba.posetrace.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseProtoCodecTest {
    @Test
    fun roundTripPreservesRawPoseFrame() {
        val frame = PoseFrame(
            analyzerFrameIndex = 42L,
            cameraTimestampNs = 9_876_543_210L,
            mediaPipeTimestampMs = 9_876L,
            inferenceLatencyNs = 21_000_000L,
            sourceWidth = 640,
            sourceHeight = 480,
            rotationDegrees = 90,
            poseDetected = true,
            normalizedLandmarks = listOf(
                PoseLandmarkSample(
                    index = 0,
                    x = 0.25f,
                    y = 0.5f,
                    z = -0.1f,
                    visibility = 0.95f,
                    presence = null,
                ),
            ),
            worldLandmarks = listOf(
                PoseLandmarkSample(
                    index = 0,
                    x = 0.12f,
                    y = -0.22f,
                    z = 1.4f,
                    visibility = null,
                    presence = 0.88f,
                ),
            ),
        )

        assertEquals(frame, PoseProtoCodec.decode(PoseProtoCodec.encode(frame)))
    }

    @Test
    fun roundTripPreservesNoPoseFrame() {
        val frame = PoseFrame(
            analyzerFrameIndex = 7L,
            cameraTimestampNs = 1_000_000L,
            mediaPipeTimestampMs = 1L,
            inferenceLatencyNs = 3_000_000L,
            sourceWidth = 640,
            sourceHeight = 480,
            rotationDegrees = 0,
            poseDetected = false,
            normalizedLandmarks = emptyList(),
            worldLandmarks = emptyList(),
        )

        assertEquals(frame, PoseProtoCodec.decode(PoseProtoCodec.encode(frame)))
    }
}
