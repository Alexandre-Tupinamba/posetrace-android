package com.alexandretupinamba.posetrace.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.annotation.OptIn
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import com.alexandretupinamba.posetrace.recording.PoseFrame
import com.alexandretupinamba.posetrace.recording.PoseLandmarkSample
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@OptIn(TransformExperimental::class)
class PoseLandmarkerEngine(
    context: Context,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val appContext = context.applicationContext
    private val timestampMapper = MonotonicTimestampMapper()
    private val frameIndex = AtomicLong(0)
    private val analyzerCount = AtomicLong(0)
    private val submittedCount = AtomicLong(0)
    private val resultCount = AtomicLong(0)
    private val detectedCount = AtomicLong(0)
    private val missingCount = AtomicLong(0)
    private val expiredSubmissionEstimate = AtomicLong(0)

    private val submittedFrames = ConcurrentHashMap<Long, SubmittedFrame>()

    private val analyzerTimestampWindow = ArrayDeque<Long>()
    private val resultElapsedWindow = ArrayDeque<Long>()
    private val windowLock = Any()

    private val displayStabilizer = DisplayPoseStabilizer()

    private val imageProxyTransformFactory = ImageProxyTransformFactory().apply {
        // MediaPipe receives the ImageProxy crop after its rotation has been physically applied.
        setUsingCropRect(true)
        setUsingRotationDegrees(true)
    }

    private val _state = MutableStateFlow(PoseTelemetryState())
    val state: StateFlow<PoseTelemetryState> = _state.asStateFlow()

    @Volatile
    private var poseLandmarker: PoseLandmarker? = null

    @Volatile
    private var previewTransformTarget: PreviewTransformTarget? = null

    @Volatile
    private var rawFrameListener: ((PoseFrame) -> Unit)? = null

    @Volatile
    private var rawFrameErrorListener: ((String) -> Unit)? = null

    init {
        initialize()
    }

    fun setPreviewTransformTarget(target: PreviewTransformTarget?) {
        previewTransformTarget = target
    }

    fun setRawFrameListener(
        listener: ((PoseFrame) -> Unit)?,
        onError: ((String) -> Unit)? = null,
    ) {
        rawFrameListener = listener
        rawFrameErrorListener = onError
    }

    fun setDisplayStabilizationEnabled(enabled: Boolean) {
        displayStabilizer.setEnabled(enabled)
        _state.update { it.copy(displayStabilizationEnabled = enabled) }
    }

    private fun initialize() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputSegmentationMasks(false)
                .setResultListener(::onResult)
                .setErrorListener(::onMediaPipeError)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(appContext, options)
            _state.update { it.copy(initialized = true, error = null) }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to initialize Pose Landmarker", error)
            _state.update {
                it.copy(
                    initialized = false,
                    error = "MediaPipe initialization failed: ${error.message}",
                )
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        val index = frameIndex.getAndIncrement()
        analyzerCount.incrementAndGet()
        val cameraTimestampNs = image.imageInfo.timestamp

        try {
            recordAnalyzerTimestamp(cameraTimestampNs)

            val landmarker = poseLandmarker
            if (landmarker == null) {
                publishCountersOnly(
                    analyzerFrameIndex = index,
                    cameraTimestampNs = cameraTimestampNs,
                    mediaPipeTimestampMs = _state.value.mediaPipeTimestampMs,
                )
                return
            }

            val rotationDegrees = normalizeRotation(image.imageInfo.rotationDegrees)
            val mediaPipeBitmap = createCroppedRotatedBitmap(image, rotationDegrees)

            val mediaPipeTimestampMs = timestampMapper.map(cameraTimestampNs)
            val submitElapsedNs = SystemClock.elapsedRealtimeNanos()
            // Snapshot produced on the Android main thread. The analyzer never touches PreviewView.
            val target = previewTransformTarget
            val transform = buildPreviewTransform(image, target)

            val metadata = SubmittedFrame(
                analyzerFrameIndex = index,
                cameraTimestampNs = cameraTimestampNs,
                mediaPipeTimestampMs = mediaPipeTimestampMs,
                submitElapsedNs = submitElapsedNs,
                sourceWidth = mediaPipeBitmap.width,
                sourceHeight = mediaPipeBitmap.height,
                rotationDegrees = rotationDegrees,
                previewTransform = transform,
                previewWidth = target?.width ?: 0,
                previewHeight = target?.height ?: 0,
            )

            pruneExpiredSubmissions(mediaPipeTimestampMs)
            submittedFrames[mediaPipeTimestampMs] = metadata
            submittedCount.incrementAndGet()

            val mpImage = BitmapImageBuilder(mediaPipeBitmap).build()

            try {
                // The bitmap has already been cropped to CameraX's shared ViewPort and
                // physically rotated into display orientation, matching Google's sample path.
                landmarker.detectAsync(mpImage, mediaPipeTimestampMs)
            } catch (error: RuntimeException) {
                submittedFrames.remove(mediaPipeTimestampMs)
                submittedCount.decrementAndGet()
                mpImage.close()
                throw error
            }

            publishCountersOnly(
                analyzerFrameIndex = index,
                cameraTimestampNs = cameraTimestampNs,
                mediaPipeTimestampMs = mediaPipeTimestampMs,
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Frame analysis failed", error)
            _state.update { it.copy(error = "Frame analysis failed: ${error.message}") }
        } finally {
            image.close()
        }
    }

    private fun buildPreviewTransform(
        image: ImageProxy,
        target: PreviewTransformTarget?,
    ): Matrix? {
        if (target == null || target.width <= 0 || target.height <= 0) return null

        return try {
            val source = imageProxyTransformFactory.getOutputTransform(image)
            Matrix().also { matrix ->
                CoordinateTransform(source, target.outputTransform).transform(matrix)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Preview coordinate transform unavailable for frame", error)
            null
        }
    }

    private fun createCroppedRotatedBitmap(
        image: ImageProxy,
        rotationDegrees: Int,
    ): Bitmap {
        val fullBitmap = image.toBitmap()
        val crop = image.cropRect

        val croppedBitmap = if (
            crop.left == 0 && crop.top == 0 &&
            crop.width() == fullBitmap.width && crop.height() == fullBitmap.height
        ) {
            fullBitmap
        } else {
            Bitmap.createBitmap(
                fullBitmap,
                crop.left,
                crop.top,
                crop.width(),
                crop.height(),
            )
        }

        if (rotationDegrees == 0) return croppedBitmap

        val rotationMatrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(
            croppedBitmap,
            0,
            0,
            croppedBitmap.width,
            croppedBitmap.height,
            rotationMatrix,
            true,
        )
    }

    private fun onResult(result: PoseLandmarkerResult, input: MPImage) {
        try {
            val receivedElapsedNs = SystemClock.elapsedRealtimeNanos()
            val metadata = submittedFrames.remove(result.timestampMs())
            val results = resultCount.incrementAndGet()
            recordResultTimestamp(receivedElapsedNs)

            val normalizedPose = result.landmarks().firstOrNull()
            val worldPose = result.worldLandmarks().firstOrNull()
            val detected = normalizedPose != null

            if (detected) {
                detectedCount.incrementAndGet()
            } else {
                missingCount.incrementAndGet()
            }

            val rawDisplayPoints = normalizedPose.orEmpty().mapIndexed { landmarkIndex, landmark ->
                val world = worldPose?.getOrNull(landmarkIndex)
                val display = mapToPreview(
                    normalizedX = landmark.x(),
                    normalizedY = landmark.y(),
                    metadata = metadata,
                )

                PosePoint(
                    index = landmarkIndex,
                    x = landmark.x(),
                    y = landmark.y(),
                    z = landmark.z(),
                    visibility = landmark.visibility().orElse(null),
                    presence = landmark.presence().orElse(null),
                    worldX = world?.x(),
                    worldY = world?.y(),
                    worldZ = world?.z(),
                    worldVisibility = world?.visibility()?.orElse(null),
                    worldPresence = world?.presence()?.orElse(null),
                    displayX = display?.first,
                    displayY = display?.second,
                )
            }

            val points = if (detected) {
                displayStabilizer.stabilize(
                    points = rawDisplayPoints,
                    timestampNs = metadata?.cameraTimestampNs ?: receivedElapsedNs,
                )
            } else {
                displayStabilizer.reset()
                emptyList()
            }

            val latencyNs = metadata?.let { receivedElapsedNs - it.submitElapsedNs } ?: 0L

            publishRawFrame(
                metadata = metadata,
                resultTimestampMs = result.timestampMs(),
                latencyNs = latencyNs,
                normalizedPose = normalizedPose,
                worldPose = worldPose,
            )

            _state.update { current ->
                current.copy(
                    initialized = true,
                    error = null,
                    poseDetected = detected,
                    landmarks = points,
                    normalizedLandmarkCount = normalizedPose?.size ?: 0,
                    worldLandmarkCount = worldPose?.size ?: 0,
                    sourceWidth = metadata?.sourceWidth ?: current.sourceWidth,
                    sourceHeight = metadata?.sourceHeight ?: current.sourceHeight,
                    rotationDegrees = metadata?.rotationDegrees ?: current.rotationDegrees,
                    coordinateTransformReady = metadata?.previewTransform != null,
                    analyzerFrameIndex = metadata?.analyzerFrameIndex ?: current.analyzerFrameIndex,
                    cameraTimestampNs = metadata?.cameraTimestampNs ?: current.cameraTimestampNs,
                    mediaPipeTimestampMs = result.timestampMs(),
                    endToEndInferenceMs = latencyNs / NS_PER_MS_DOUBLE,
                    analyzerFps = currentAnalyzerFps(),
                    resultFps = currentResultFps(),
                    analyzerFrameCount = analyzerCount.get(),
                    submittedCount = submittedCount.get(),
                    resultCount = results,
                    poseDetectedCount = detectedCount.get(),
                    poseMissingCount = missingCount.get(),
                    expiredSubmissionEstimate = expiredSubmissionEstimate.get(),
                )
            }
        } finally {
            input.close()
        }
    }

    private fun publishRawFrame(
        metadata: SubmittedFrame?,
        resultTimestampMs: Long,
        latencyNs: Long,
        normalizedPose: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?,
        worldPose: List<com.google.mediapipe.tasks.components.containers.Landmark>?,
    ) {
        val listener = rawFrameListener ?: return
        if (metadata == null) {
            rawFrameErrorListener?.invoke(
                "MediaPipe result $resultTimestampMs ms has no matching CameraX metadata",
            )
            return
        }

        val normalized = normalizedPose.orEmpty().mapIndexed { index, landmark ->
            PoseLandmarkSample(
                index = index,
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                visibility = landmark.visibility().orElse(null),
                presence = landmark.presence().orElse(null),
            )
        }
        val world = worldPose.orEmpty().mapIndexed { index, landmark ->
            PoseLandmarkSample(
                index = index,
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                visibility = landmark.visibility().orElse(null),
                presence = landmark.presence().orElse(null),
            )
        }

        listener(
            PoseFrame(
                analyzerFrameIndex = metadata.analyzerFrameIndex,
                cameraTimestampNs = metadata.cameraTimestampNs,
                mediaPipeTimestampMs = resultTimestampMs,
                inferenceLatencyNs = latencyNs.coerceAtLeast(0L),
                sourceWidth = metadata.sourceWidth,
                sourceHeight = metadata.sourceHeight,
                rotationDegrees = metadata.rotationDegrees,
                poseDetected = normalized.isNotEmpty(),
                normalizedLandmarks = normalized,
                worldLandmarks = world,
            ),
        )
    }

    private fun mapToPreview(
        normalizedX: Float,
        normalizedY: Float,
        metadata: SubmittedFrame?,
    ): Pair<Float, Float>? {
        metadata ?: return null
        val matrix = metadata.previewTransform ?: return null
        if (metadata.previewWidth <= 0 || metadata.previewHeight <= 0) return null

        val point = floatArrayOf(
            normalizedX * metadata.sourceWidth,
            normalizedY * metadata.sourceHeight,
        )
        matrix.mapPoints(point)

        return (point[0] / metadata.previewWidth) to (point[1] / metadata.previewHeight)
    }

    private fun onMediaPipeError(error: RuntimeException) {
        Log.e(TAG, "MediaPipe live-stream error", error)
        _state.update { it.copy(error = "MediaPipe error: ${error.message}") }
    }

    private fun publishCountersOnly(
        analyzerFrameIndex: Long,
        cameraTimestampNs: Long,
        mediaPipeTimestampMs: Long,
    ) {
        _state.update { current ->
            current.copy(
                analyzerFrameIndex = analyzerFrameIndex,
                cameraTimestampNs = cameraTimestampNs,
                mediaPipeTimestampMs = mediaPipeTimestampMs,
                analyzerFps = currentAnalyzerFps(),
                resultFps = currentResultFps(),
                analyzerFrameCount = analyzerCount.get(),
                submittedCount = submittedCount.get(),
                resultCount = resultCount.get(),
                poseDetectedCount = detectedCount.get(),
                poseMissingCount = missingCount.get(),
                expiredSubmissionEstimate = expiredSubmissionEstimate.get(),
            )
        }
    }

    private fun recordAnalyzerTimestamp(timestampNs: Long) {
        synchronized(windowLock) {
            analyzerTimestampWindow.addLast(timestampNs)
            while (
                analyzerTimestampWindow.size > 2 &&
                timestampNs - analyzerTimestampWindow.first() > FPS_WINDOW_NS
            ) {
                analyzerTimestampWindow.removeFirst()
            }
        }
    }

    private fun recordResultTimestamp(elapsedNs: Long) {
        synchronized(windowLock) {
            resultElapsedWindow.addLast(elapsedNs)
            while (
                resultElapsedWindow.size > 2 &&
                elapsedNs - resultElapsedWindow.first() > FPS_WINDOW_NS
            ) {
                resultElapsedWindow.removeFirst()
            }
        }
    }

    private fun currentAnalyzerFps(): Double = synchronized(windowLock) {
        fpsFromWindow(analyzerTimestampWindow)
    }

    private fun currentResultFps(): Double = synchronized(windowLock) {
        fpsFromWindow(resultElapsedWindow)
    }

    private fun fpsFromWindow(window: ArrayDeque<Long>): Double {
        if (window.size < 2) return 0.0
        val elapsed = window.last() - window.first()
        if (elapsed <= 0L) return 0.0
        return (window.size - 1) * NS_PER_SECOND_DOUBLE / elapsed
    }

    private fun pruneExpiredSubmissions(currentTimestampMs: Long) {
        if (submittedFrames.size < MAX_PENDING_BEFORE_PRUNE) return

        var removed = 0L
        submittedFrames.entries.removeIf { entry ->
            val expired = currentTimestampMs - entry.key > SUBMISSION_EXPIRY_MS
            if (expired) removed++
            expired
        }
        if (removed > 0) expiredSubmissionEstimate.addAndGet(removed)
    }

    private fun normalizeRotation(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "Rotation must be a multiple of 90: $rotationDegrees" }
        return normalized
    }

    override fun close() {
        displayStabilizer.reset()
        poseLandmarker?.close()
        poseLandmarker = null
        submittedFrames.clear()
        previewTransformTarget = null
        rawFrameListener = null
        rawFrameErrorListener = null
    }

    companion object {
        private const val TAG = "PoseTraceEngine"
        private const val MODEL_ASSET = "pose_landmarker_lite.task"
        private const val FPS_WINDOW_NS = 1_000_000_000L
        private const val MAX_PENDING_BEFORE_PRUNE = 128
        private const val SUBMISSION_EXPIRY_MS = 3_000L
        private const val NS_PER_SECOND_DOUBLE = 1_000_000_000.0
        private const val NS_PER_MS_DOUBLE = 1_000_000.0
    }
}
