package com.alexandretupinamba.posetrace.recording

data class PoseLandmarkSample(
    val index: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float?,
    val presence: Float?,
)

data class PoseFrame(
    val analyzerFrameIndex: Long,
    val cameraTimestampNs: Long,
    val mediaPipeTimestampMs: Long,
    val inferenceLatencyNs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val poseDetected: Boolean,
    val normalizedLandmarks: List<PoseLandmarkSample>,
    val worldLandmarks: List<PoseLandmarkSample>,
) {
    init {
        require(analyzerFrameIndex >= 0L)
        require(cameraTimestampNs >= 0L)
        require(mediaPipeTimestampMs >= 0L)
        require(inferenceLatencyNs >= 0L)
        require(sourceWidth >= 0)
        require(sourceHeight >= 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(poseDetected == normalizedLandmarks.isNotEmpty()) {
            "poseDetected must match normalized landmark presence"
        }
    }
}
