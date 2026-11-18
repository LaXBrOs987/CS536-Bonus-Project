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
import kotlin.math.min

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var result: ObjectDetectorResult? = null
    private var imageWidth = 1
    private var imageHeight = 1
    private var fillScreen = false
    private val density = resources.displayMetrics.density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 230, 182)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 14, 31, 42)
    }

    fun setFillScreen(fill: Boolean) {
        fillScreen = fill
        invalidate()
    }

    fun setResults(detectionResult: ObjectDetectorResult, width: Int, height: Int) {
        result = detectionResult
        imageWidth = width.coerceAtLeast(1)
        imageHeight = height.coerceAtLeast(1)
        invalidate()
    }

    fun clear() {
        result = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val current = result ?: return
        val sx = width.toFloat() / imageWidth
        val sy = height.toFloat() / imageHeight
        val scale = if (fillScreen) max(sx, sy) else min(sx, sy)
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f
        val visible = RectF(max(0f, dx), max(0f, dy),
            min(width.toFloat(), dx + imageWidth * scale),
            min(height.toFloat(), dy + imageHeight * scale))
        val saved = canvas.save()
        canvas.clipRect(visible)
        for (detection in current.detections()) {
            val box = detection.boundingBox()
            val rect = RectF(box.left * scale + dx, box.top * scale + dy,
                box.right * scale + dx, box.bottom * scale + dy)
            if (!RectF.intersects(rect, visible)) continue
            canvas.drawRoundRect(rect, 5f * density, 5f * density, boxPaint)
            val category = detection.categories().firstOrNull() ?: continue
            val text = "${category.categoryName()} ${(category.score() * 100).toInt()}%"
            val padding = 6f * density
            val textCount = textPaint.breakText(text, true,
                (visible.width() - padding * 2).coerceAtLeast(1f), null)
            val fitted = text.take(textCount)
            val labelWidth = min(visible.width(), textPaint.measureText(fitted) + padding * 2)
            val metrics = textPaint.fontMetrics
            val labelHeight = metrics.descent - metrics.ascent + padding * 2
            val left = rect.left.coerceIn(visible.left, max(visible.left, visible.right - labelWidth))
            val preferredTop = if (rect.top - labelHeight >= visible.top)
                rect.top - labelHeight else rect.top
            val top = preferredTop.coerceIn(visible.top,
                max(visible.top, visible.bottom - labelHeight))
            canvas.drawRoundRect(RectF(left, top, left + labelWidth, top + labelHeight),
                5f * density, 5f * density, labelPaint)
            canvas.drawText(fitted, left + padding, top + padding - metrics.ascent, textPaint)
        }
        canvas.restoreToCount(saved)
    }
}
