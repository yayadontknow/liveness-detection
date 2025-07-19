package com.example.livenesssdk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val guideBoxPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val resultBoxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val resultTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }

    // --- Start of Changes ---

    // Updated Paint for the larger, outlined green circles
    private val circlePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f // Make the outline thicker for better visibility
        isAntiAlias = true
    }

    private var result: DetectionResult? = null
    private var guideBox: RectF? = null
    // The fixed circleRadius property is removed, as it's now dynamic.

    // --- End of Changes ---

    data class DetectionResult(val box: RectF, val label: String)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box = getGuideBox()
        canvas.drawRect(box, guideBoxPaint)

        result?.let {
            canvas.drawRect(it.box, resultBoxPaint)
            canvas.drawText(it.label, it.box.left, it.box.top - 10, resultTextPaint)
            drawValidationCircles(canvas, it.box)
        }
    }

    // --- Start of Changes ---

    private fun drawValidationCircles(canvas: Canvas, box: RectF) {
        // If the box has no height, we can't draw, so we exit early.
        if (box.height() <= 0) return

        // 1. Calculate the dynamic radius.
        // "Circle size (diameter) is 1/3 of the bounding box height".
        // So, the radius is half of that.
        val dynamicRadius = (box.height() / 3f) / 2f

        // 2. Define a bigger, dynamic margin. We'll use the radius itself as the margin
        // from the center of the circle to the edge of the box. This creates a nice, clean inset.
        val insetMargin = dynamicRadius

        // 3. Draw the circles with the new dynamic values.
        // Top-left corner
        canvas.drawCircle(box.left + insetMargin, box.top + insetMargin, dynamicRadius, circlePaint)
        // Top-right corner
        canvas.drawCircle(box.right - insetMargin, box.top + insetMargin, dynamicRadius, circlePaint)
        // Bottom-left corner
        canvas.drawCircle(box.left + insetMargin, box.bottom - insetMargin, dynamicRadius, circlePaint)
        // Bottom-right corner
        canvas.drawCircle(box.right - insetMargin, box.bottom - insetMargin, dynamicRadius, circlePaint)
        // Top-center
        canvas.drawCircle(box.centerX(), box.top + insetMargin, dynamicRadius, circlePaint)
        // Bottom-center
        canvas.drawCircle(box.centerX(), box.bottom - insetMargin, dynamicRadius, circlePaint)
    }

    // --- End of Changes ---

    fun setResults(detection: DetectionResult?) {
        this.result = detection
        postInvalidate()
    }

    fun clearResults() {
        this.result = null
        postInvalidate()
    }

    fun getGuideBox(): RectF {
        if (guideBox == null) {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val boxSize = min(viewWidth, viewHeight) * 0.85f
            val left = (viewWidth - boxSize) / 2
            val top = (viewHeight - boxSize) / 2
            guideBox = RectF(left, top, left + boxSize, top + boxSize)
        }
        return guideBox!!
    }
}