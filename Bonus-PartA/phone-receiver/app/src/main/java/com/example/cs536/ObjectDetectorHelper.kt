package com.example.cs536

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

class ObjectDetectorHelper(
    context: Context
) {

    private val objectDetector: ObjectDetector

    init {

        val baseOptions =
            BaseOptions.builder()
                .setModelAssetPath(
                    "efficientdet_lite0.tflite"
                )
                .build()

        val options =
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(
                    RunningMode.IMAGE
                )
                .setScoreThreshold(0.50f)
                .setMaxResults(5)
                .build()

        objectDetector =
            ObjectDetector.createFromOptions(
                context,
                options
            )
    }

    fun detect(
        bitmap: Bitmap
    ): ObjectDetectorResult {

        val mpImage =
            BitmapImageBuilder(bitmap)
                .build()

        return objectDetector.detect(
            mpImage
        )
    }

    fun close() {
        objectDetector.close()
    }
}