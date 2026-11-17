package com.alexandretupinamba.posetrace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.alexandretupinamba.posetrace.pose.PoseLandmarkerEngine
import com.alexandretupinamba.posetrace.ui.PoseTraceSpikeScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var poseEngine: PoseLandmarkerEngine
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraExecutor = Executors.newSingleThreadExecutor()
        poseEngine = PoseLandmarkerEngine(applicationContext)

        setContent {
            MaterialTheme {
                Surface {
                    PoseTraceSpikeScreen(
                        poseEngine = poseEngine,
                        cameraExecutor = cameraExecutor,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        poseEngine.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
