package com.alexandretupinamba.posetrace.camera

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

fun bindRearCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analyzerExecutor: Executor,
    analyzer: ImageAnalysis.Analyzer,
    onError: (Throwable) -> Unit,
) {
    // PreviewView's ViewPort depends on its measured size. Binding after layout lets
    // Preview and ImageAnalysis share the exact same visible camera region.
    previewView.doOnLayout {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(
            {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val rotation = when (previewView.display?.rotation) {
                        Surface.ROTATION_90 -> Surface.ROTATION_90
                        Surface.ROTATION_180 -> Surface.ROTATION_180
                        Surface.ROTATION_270 -> Surface.ROTATION_270
                        else -> Surface.ROTATION_0
                    }
                    val viewPort = previewView.viewPort
                        ?: error("PreviewView ViewPort is not available after layout")

                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(rotation)
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                    val analysis = ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { it.setAnalyzer(analyzerExecutor, analyzer) }

                    val useCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(analysis)
                        .setViewPort(viewPort)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        useCaseGroup,
                    )
                } catch (error: Throwable) {
                    Log.e("PoseTraceCamera", "Camera binding failed", error)
                    onError(error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }
}
