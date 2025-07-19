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

    private var results: List<DetectionResult> = emptyList()
    private var guideBox: RectF? = null

    data class DetectionResult(val box: RectF, val label: String)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw the guide box
        val box = getGuideBox()
        canvas.drawRect(box, guideBoxPaint)

        // Draw the detection results
        for (result in results) {
            canvas.drawRect(result.box, resultBoxPaint)
            canvas.drawText(result.label, result.box.left, result.box.top - 10, resultTextPaint)
        }
    }

    fun setResults(detections: List<DetectionResult>) {
        this.results = detections
        postInvalidate()
    }

    fun clearResults() {
        this.results = emptyList()
        postInvalidate()
    }

    fun getGuideBox(): RectF {
        if (guideBox == null) {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            // Make the box a square based on the smaller dimension of the view
            val boxSize = min(viewWidth, viewHeight) * 0.85f // 85% of the smaller side
            val left = (viewWidth - boxSize) / 2
            val top = (viewHeight - boxSize) / 2
            guideBox = RectF(left, top, left + boxSize, top + boxSize)
        }
        return guideBox!!
    }
}