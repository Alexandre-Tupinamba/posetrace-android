package com.alexandretupinamba.posetrace.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.annotation.OptIn
import androidx.camera.view.TransformExperimental
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexandretupinamba.posetrace.camera.bindRearCamera
import com.alexandretupinamba.posetrace.pose.PoseLandmarkerEngine
import com.alexandretupinamba.posetrace.pose.PreviewTransformTarget
import java.util.concurrent.Executor

@OptIn(TransformExperimental::class)
@Composable
fun PoseTraceSpikeScreen(
    poseEngine: PoseLandmarkerEngine,
    cameraExecutor: Executor,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val telemetry by poseEngine.state.collectAsStateWithLifecycle()

    var cameraError by remember { mutableStateOf<String?>(null) }
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
        PermissionRequiredScreen(onRequest = {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        })
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
            // This function is invoked only from PreviewView/main-thread callbacks.
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

        // Covers the case where the view was already laid out/streaming before the observers attached.
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

        PoseOverlay(
            landmarks = telemetry.landmarks,
        )

        TelemetryCard(
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
            error = cameraError ?: telemetry.error,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(12.dp),
        )
    }
}

@Composable
private fun PermissionRequiredScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "PoseTrace precisa da câmera para executar o spike de pose. " +
                "Nenhuma imagem é salva por esta versão.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = onRequest,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Permitir câmera")
        }
    }
}

@Composable
private fun TelemetryCard(
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
    error: String?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (initialized) "MediaPipe: ready" else "MediaPipe: loading",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = if (poseDetected) "Pose: detected" else "Pose: —",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Text("Analyzer FPS: %.1f".format(analyzerFps))
            Text("Result FPS: %.1f".format(resultFps))
            Text("End-to-end latency: %.2f ms".format(inferenceMs))
            Text("Analyzer / submitted / results: $analyzerFrames / $submitted / $results")
            Text("Landmarks normalized / world: $normalizedLandmarks / $worldLandmarks")
            Text("Overlay transform: ${if (coordinateTransformReady) "CameraX ✓" else "warming up"}")
            Text("MediaPipe input: ${sourceWidth}×${sourceHeight}, rotation: ${rotationDegrees}°")
            Text("Expired submissions (estimate): $expiredEstimate")
            Text("Camera timestamp: $cameraTimestampNs ns")

            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
