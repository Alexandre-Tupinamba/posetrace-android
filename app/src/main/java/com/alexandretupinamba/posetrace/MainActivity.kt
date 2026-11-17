package com.alexandretupinamba.posetrace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.alexandretupinamba.posetrace.pose.PoseLandmarkerEngine
import com.alexandretupinamba.posetrace.recording.PoseRecordingController
import com.alexandretupinamba.posetrace.ui.PoseTraceSpikeScreen
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var poseEngine: PoseLandmarkerEngine
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var recordingController: PoseRecordingController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraExecutor = Executors.newSingleThreadExecutor()
        recordingController = PoseRecordingController(
            recordingsRoot = File(filesDir, "recordings"),
        )
        poseEngine = PoseLandmarkerEngine(applicationContext).apply {
            setRawFrameListener(
                listener = { frame ->
                    recordingController.record(frame)
                    Unit
                },
                onError = recordingController::fail,
            )
        }

        setContent {
            MaterialTheme {
                Surface {
                    PoseTraceSpikeScreen(
                        poseEngine = poseEngine,
                        cameraExecutor = cameraExecutor,
                        recordingController = recordingController,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        poseEngine.setRawFrameListener(null)
        recordingController.close()
        poseEngine.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
