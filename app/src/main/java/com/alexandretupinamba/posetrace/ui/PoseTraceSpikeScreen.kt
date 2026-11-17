package com.alexandretupinamba.posetrace.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexandretupinamba.posetrace.camera.bindRearCamera
import com.alexandretupinamba.posetrace.pose.PoseLandmarkerEngine
import com.alexandretupinamba.posetrace.pose.PreviewTransformTarget
import com.alexandretupinamba.posetrace.recording.PoseRecordingController
import com.alexandretupinamba.posetrace.recording.RecordingState
import com.alexandretupinamba.posetrace.recording.RecordingStatus
import java.util.concurrent.Executor

private val RecordRed = Color(0xFFE23B3B)

@OptIn(TransformExperimental::class)
@Composable
internal fun PoseTraceSpikeScreen(
    poseEngine: PoseLandmarkerEngine,
    cameraExecutor: Executor,
    recordingController: PoseRecordingController,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val telemetry by poseEngine.state.collectAsStateWithLifecycle()
    val recording by recordingController.status.collectAsStateWithLifecycle()

    var cameraError by remember { mutableStateOf<String?>(null) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        PermissionRequiredScreen(
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        )
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(previewView, lifecycleOwner, poseEngine) {
        fun publishPreviewTransform() {
            val outputTransform = previewView.outputTransform ?: return
            val width = previewView.width
            val height = previewView.height
            if (width <= 0 || height <= 0) return

            poseEngine.setPreviewTransformTarget(
                PreviewTransformTarget(
                    outputTransform = outputTransform,
                    width = width,
                    height = height,
                ),
            )
        }

        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            publishPreviewTransform()
        }
        previewView.addOnLayoutChangeListener(layoutListener)

        val streamObserver = androidx.lifecycle.Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING) {
                publishPreviewTransform()
            }
        }
        previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
        previewView.post { publishPreviewTransform() }

        onDispose {
            previewView.removeOnLayoutChangeListener(layoutListener)
            previewView.previewStreamState.removeObserver(streamObserver)
            poseEngine.setPreviewTransformTarget(null)
        }
    }

    LaunchedEffect(previewView, lifecycleOwner) {
        bindRearCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            analyzerExecutor = cameraExecutor,
            analyzer = poseEngine,
            onError = { cameraError = it.message ?: it.javaClass.simpleName },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { previewView },
            update = { view ->
                val outputTransform = view.outputTransform
                val width = view.width
                val height = view.height
                if (outputTransform != null && width > 0 && height > 0) {
                    poseEngine.setPreviewTransformTarget(
                        PreviewTransformTarget(
                            outputTransform = outputTransform,
                            width = width,
                            height = height,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        PoseOverlay(landmarks = telemetry.landmarks)

        CaptureHeader(
            initialized = telemetry.initialized,
            poseDetected = telemetry.poseDetected,
            landmarkCount = telemetry.normalizedLandmarkCount,
            recordingState = recording.state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (diagnosticsExpanded) {
                DiagnosticsPanel(
                    initialized = telemetry.initialized,
                    poseDetected = telemetry.poseDetected,
                    analyzerFps = telemetry.analyzerFps,
                    resultFps = telemetry.resultFps,
                    inferenceMs = telemetry.endToEndInferenceMs,
                    analyzerFrames = telemetry.analyzerFrameCount,
                    submitted = telemetry.submittedCount,
                    results = telemetry.resultCount,
                    normalizedLandmarks = telemetry.normalizedLandmarkCount,
                    worldLandmarks = telemetry.worldLandmarkCount,
                    expiredEstimate = telemetry.expiredSubmissionEstimate,
                    cameraTimestampNs = telemetry.cameraTimestampNs,
                    sourceWidth = telemetry.sourceWidth,
                    sourceHeight = telemetry.sourceHeight,
                    rotationDegrees = telemetry.rotationDegrees,
                    coordinateTransformReady = telemetry.coordinateTransformReady,
                )
            }

            RecordingPanel(
                status = recording,
                poseDetected = telemetry.poseDetected,
                error = cameraError ?: telemetry.error ?: recording.error,
                diagnosticsExpanded = diagnosticsExpanded,
                onToggleDiagnostics = { diagnosticsExpanded = !diagnosticsExpanded },
                onStart = { runCatching { recordingController.start() } },
                onStop = recordingController::stop,
            )
        }
    }
}

@Composable
private fun CaptureHeader(
    initialized: Boolean,
    poseDetected: Boolean,
    landmarkCount: Int,
    recordingState: RecordingState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.58f),
        contentColor = Color.White,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "PoseTrace",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Private on-device pose capture",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }

                Surface(
                    color = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White,
                    shape = CircleShape,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "On-device",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text = when {
                        !initialized -> "Loading model"
                        poseDetected -> "$landmarkCount landmarks"
                        else -> "Step into frame"
                    },
                    emphasized = poseDetected,
                )

                if (recordingState == RecordingState.RECORDING) {
                    StatusPill(
                        text = "Recording",
                        emphasized = true,
                        recording = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    emphasized: Boolean,
    recording: Boolean = false,
) {
    val background = when {
        recording -> RecordRed.copy(alpha = 0.92f)
        emphasized -> MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
        else -> Color.White.copy(alpha = 0.12f)
    }

    Surface(
        color = background,
        contentColor = Color.White,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                Icon(
                    imageVector = Icons.Rounded.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RecordingPanel(
    status: RecordingStatus,
    poseDetected: Boolean,
    error: String?,
    diagnosticsExpanded: Boolean,
    onToggleDiagnostics: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecordingStateIcon(state = status.state)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recordingTitle(status.state),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = recordingSubtitle(status, poseDetected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val busy = status.state == RecordingState.PREPARING ||
                    status.state == RecordingState.STOPPING
                val isRecording = status.state == RecordingState.RECORDING

                Button(
                    onClick = if (isRecording) onStop else onStart,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) {
                            RecordRed
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = if (isRecording) {
                            Icons.Rounded.Stop
                        } else {
                            Icons.Rounded.FiberManualRecord
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = when (status.state) {
                            RecordingState.RECORDING -> "Stop"
                            RecordingState.COMPLETED -> "Record again"
                            RecordingState.FAILED,
                            RecordingState.ABORTED,
                            -> "Retry"
                            else -> "Record"
                        },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (error != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            TextButton(
                onClick = onToggleDiagnostics,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = "Diagnostics",
                    modifier = Modifier.padding(start = 6.dp),
                )
                Icon(
                    imageVector = if (diagnosticsExpanded) {
                        Icons.Rounded.ExpandMore
                    } else {
                        Icons.Rounded.ExpandLess
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordingStateIcon(state: RecordingState) {
    val icon = when (state) {
        RecordingState.COMPLETED -> Icons.Rounded.CheckCircle
        RecordingState.FAILED,
        RecordingState.ABORTED,
        -> Icons.Rounded.ErrorOutline
        RecordingState.RECORDING -> Icons.Rounded.FiberManualRecord
        else -> Icons.Rounded.CameraAlt
    }

    val tint = when (state) {
        RecordingState.RECORDING -> RecordRed
        RecordingState.COMPLETED -> MaterialTheme.colorScheme.primary
        RecordingState.FAILED,
        RecordingState.ABORTED,
        -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = tint.copy(alpha = 0.12f),
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun recordingTitle(state: RecordingState): String = when (state) {
    RecordingState.IDLE -> "Ready to capture"
    RecordingState.PREPARING -> "Preparing session"
    RecordingState.RECORDING -> "Recording pose data"
    RecordingState.STOPPING -> "Saving session"
    RecordingState.COMPLETED -> "Session saved"
    RecordingState.ABORTED -> "Recording aborted"
    RecordingState.FAILED -> "Recording failed"
}

private fun recordingSubtitle(
    status: RecordingStatus,
    poseDetected: Boolean,
): String = when (status.state) {
    RecordingState.IDLE -> if (poseDetected) {
        "Pose detected · raw landmarks stay on-device"
    } else {
        "Step into frame, then start recording"
    }
    RecordingState.PREPARING -> "Creating a private local session"
    RecordingState.RECORDING -> "${status.framesWritten} frames saved"
    RecordingState.STOPPING -> "${status.framesWritten} of ${status.framesEnqueued} frames written"
    RecordingState.COMPLETED -> {
        val session = status.sessionId?.take(8)?.let { " · $it…" }.orEmpty()
        "${status.framesWritten} frames saved$session"
    }
    RecordingState.ABORTED -> status.error ?: "The session was kept incomplete"
    RecordingState.FAILED -> status.error ?: "Open diagnostics for technical details"
}

@Composable
private fun DiagnosticsPanel(
    initialized: Boolean,
    poseDetected: Boolean,
    analyzerFps: Double,
    resultFps: Double,
    inferenceMs: Double,
    analyzerFrames: Long,
    submitted: Long,
    results: Long,
    normalizedLandmarks: Int,
    worldLandmarks: Int,
    expiredEstimate: Long,
    cameraTimestampNs: Long,
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
    coordinateTransformReady: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Technical diagnostics",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(10.dp))

            DiagnosticRow(
                label = "Model",
                value = if (initialized) "MediaPipe ready" else "Loading",
            )
            DiagnosticRow(
                label = "Pose",
                value = if (poseDetected) "Detected" else "Not detected",
            )
            DiagnosticRow(
                label = "Frame rate",
                value = "%.1f analyzer / %.1f result FPS".format(analyzerFps, resultFps),
            )
            DiagnosticRow(
                label = "Latency",
                value = "%.2f ms end-to-end".format(inferenceMs),
            )
            DiagnosticRow(
                label = "Pipeline",
                value = "$analyzerFrames / $submitted / $results",
            )
            DiagnosticRow(
                label = "Landmarks",
                value = "$normalizedLandmarks normalized / $worldLandmarks world",
            )
            DiagnosticRow(
                label = "Input",
                value = "${sourceWidth}×${sourceHeight} · ${rotationDegrees}°",
            )
            DiagnosticRow(
                label = "Overlay",
                value = if (coordinateTransformReady) "CameraX aligned" else "Warming up",
            )
            DiagnosticRow(
                label = "Expired estimate",
                value = expiredEstimate.toString(),
            )
            DiagnosticRow(
                label = "Camera timestamp",
                value = "$cameraTimestampNs ns",
            )
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PermissionRequiredScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(68.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        Text(
            text = "Camera access",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = "PoseTrace processes camera frames transiently on-device. " +
                "Camera images are not saved by the recording pipeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onRequest,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Allow camera",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}