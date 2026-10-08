package com.example.cs536

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.max

class OverlayView(
    context: Context,
    attrs: AttributeSet?
) : View(context, attrs) {

    private var result: ObjectDetectorResult? = null
    private var imageWidth = 1
    private var imageHeight = 1

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 36f
        style = Paint.Style.FILL
    }

    fun setResults(
        detectionResult: ObjectDetectorResult,
        width: Int,
        height: Int
    ) {
        result = detectionResult
        imageWidth = width
        imageHeight = height
        invalidate()
    }

    fun clear() {
        result = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scaleX =
            width.toFloat() / imageWidth.toFloat()

        val scaleY =
            height.toFloat() / imageHeight.toFloat()

        result?.detections()?.forEach { detection ->

            val box = detection.boundingBox()

            val rect = RectF(
                box.left * scaleX,
                box.top * scaleY,
                box.right * scaleX,
                box.bottom * scaleY
            )

            canvas.drawRect(
                rect,
                boxPaint
            )

            val category =
                detection.categories().firstOrNull()

            if (category != null) {

                val label =
                    category.categoryName()

                val confidence =
                    (category.score() * 100).toInt()

                val text =
                    "$label $confidence%"

                canvas.drawText(
                    text,
                    rect.left,
                    max(40f, rect.top - 10f),
                    textPaint
                )
            }
        }
    }
}