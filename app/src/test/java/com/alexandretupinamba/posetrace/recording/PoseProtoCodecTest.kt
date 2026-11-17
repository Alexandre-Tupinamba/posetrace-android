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
            normalizedLandmarks = List(33) { index ->
                PoseLandmarkSample(
                    index = index,
                    x = index / 100f,
                    y = (index + 1) / 100f,
                    z = -index / 200f,
                    visibility = if (index % 2 == 0) 0.95f else null,
                    presence = if (index % 3 == 0) 0.88f else null,
                )
            },
            worldLandmarks = List(33) { index ->
                PoseLandmarkSample(
                    index = index,
                    x = index / 10f,
                    y = -index / 20f,
                    z = 1f + index / 30f,
                    visibility = if (index % 2 == 1) 0.9f else null,
                    presence = if (index % 4 == 0) 0.8f else null,
                )
            },
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
