package com.alexandretupinamba.posetrace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.alexandretupinamba.posetrace.pose.PosePoint

private val poseConnections = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 7,
    0 to 4, 4 to 5, 5 to 6, 6 to 8,
    9 to 10,
    11 to 12,
    11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21,
    17 to 19,
    12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22,
    18 to 20,
    11 to 23, 12 to 24, 23 to 24,
    23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31,
    24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32,
)

@Composable
fun PoseOverlay(
    landmarks: List<PosePoint>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (landmarks.size < 33) return@Canvas

        val points = landmarks.associateBy { it.index }

        poseConnections.forEach { (from, to) ->
            val a = points[from] ?: return@forEach
            val b = points[to] ?: return@forEach
            if (!a.isVisibleEnough() || !b.isVisibleEnough()) return@forEach

            drawLine(
                color = Color(0xFF00E5FF),
                start = previewOffset(a),
                end = previewOffset(b),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        landmarks.forEach { landmark ->
            if (!landmark.isVisibleEnough()) return@forEach
            drawCircle(
                color = Color(0xFFFFC400),
                radius = 4.dp.toPx(),
                center = previewOffset(landmark),
            )
        }
    }
}

private fun PosePoint.isVisibleEnough(): Boolean = (visibility ?: 1f) >= 0.35f

private fun DrawScope.previewOffset(landmark: PosePoint): Offset {
    // displayX/displayY are generated through CameraX CoordinateTransform.
    // The fallback keeps the spike usable while PreviewView transform metadata is warming up.
    val normalizedX = landmark.displayX ?: landmark.x
    val normalizedY = landmark.displayY ?: landmark.y
    return Offset(
        x = normalizedX * size.width,
        y = normalizedY * size.height,
    )
}
