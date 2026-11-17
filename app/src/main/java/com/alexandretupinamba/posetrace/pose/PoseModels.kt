package com.alexandretupinamba.posetrace.pose

import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.OutputTransform

data class PosePoint(
    val index: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float?,
    val presence: Float?,
    val worldX: Float?,
    val worldY: Float?,
    val worldZ: Float?,
    val worldVisibility: Float?,
    val worldPresence: Float?,
    /**
     * Normalized coordinates in PreviewView space. These are for rendering only.
     * Raw MediaPipe x/y above remain untouched for future persistence/export.
     */
    val displayX: Float? = null,
    val displayY: Float? = null,
)

data class PoseTelemetryState(
    val initialized: Boolean = false,
    val error: String? = null,
    val poseDetected: Boolean = false,
    val landmarks: List<PosePoint> = emptyList(),
    val normalizedLandmarkCount: Int = 0,
    val worldLandmarkCount: Int = 0,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val coordinateTransformReady: Boolean = false,
    val analyzerFrameIndex: Long = -1,
    val cameraTimestampNs: Long = 0,
    val mediaPipeTimestampMs: Long = 0,
    val endToEndInferenceMs: Double = 0.0,
    val analyzerFps: Double = 0.0,
    val resultFps: Double = 0.0,
    val analyzerFrameCount: Long = 0,
    val submittedCount: Long = 0,
    val resultCount: Long = 0,
    val poseDetectedCount: Long = 0,
    val poseMissingCount: Long = 0,
    val expiredSubmissionEstimate: Long = 0,
    val displayStabilizationEnabled: Boolean = true,
)

@OptIn(TransformExperimental::class)
data class PreviewTransformTarget(
    val outputTransform: OutputTransform,
    val width: Int,
    val height: Int,
)

internal data class SubmittedFrame(
    val analyzerFrameIndex: Long,
    val cameraTimestampNs: Long,
    val mediaPipeTimestampMs: Long,
    val submitElapsedNs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val previewTransform: Matrix?,
    val previewWidth: Int,
    val previewHeight: Int,
)
