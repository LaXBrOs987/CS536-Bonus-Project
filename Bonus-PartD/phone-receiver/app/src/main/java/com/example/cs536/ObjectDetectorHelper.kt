package com.example.cs536

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

// Set false and reinstall to run a baseline using the same model on CPU.
private const val PREFER_GPU = true
private const val DETECTOR_TAG = "PartDDetector"

// Keep Lite0 in assets for a quick model-only rollback.
private const val MODEL_ASSET = "efficientdet_lite0.tflite"
private const val SCORE_THRESHOLD = 0.50f
private const val MAX_RESULTS = 5

// Create, use, and close this helper on the same inference worker.
class ObjectDetectorHelper(private val context: Context) {
    private var activeDelegate = if (PREFER_GPU) Delegate.GPU else Delegate.CPU
    private var objectDetector: ObjectDetector = try {
        createDetector(activeDelegate)
    } catch (e: RuntimeException) {
        if (activeDelegate != Delegate.GPU) throw e
        createCpuFallback(e)
    }

    val backendLabel: String
        get() = when {
            activeDelegate == Delegate.GPU -> "GPU"
            PREFER_GPU -> "CPU fallback"
            else -> "CPU"
        }

    private fun createDetector(delegate: Delegate): ObjectDetector {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .build()
        return ObjectDetector.createFromOptions(context, options).also {
            Log.i(DETECTOR_TAG, "Loaded $MODEL_ASSET with $delegate")
        }
    }

    private fun createCpuFallback(gpuError: RuntimeException): ObjectDetector {
        Log.w(DETECTOR_TAG, "GPU detector failed; trying CPU", gpuError)
        return try {
            createDetector(Delegate.CPU).also { activeDelegate = Delegate.CPU }
        } catch (cpuError: RuntimeException) {
            if (cpuError !== gpuError) cpuError.addSuppressed(gpuError)
            throw cpuError
        }
    }

    fun detect(bitmap: Bitmap): ObjectDetectorResult {
        val mpImage = BitmapImageBuilder(bitmap).build()
        try {
            return objectDetector.detect(mpImage)
        } catch (e: RuntimeException) {
            if (activeDelegate != Delegate.GPU) throw e

            // Some devices surface delegate errors only when inference starts.
            val failedGpuDetector = objectDetector
            objectDetector = createCpuFallback(e)
            try {
                failedGpuDetector.close()
            } catch (closeError: RuntimeException) {
                Log.w(DETECTOR_TAG, "Could not close failed GPU detector", closeError)
            }
            return objectDetector.detect(BitmapImageBuilder(bitmap).build())
        }
    }

    fun close() {
        objectDetector.close()
    }
}
