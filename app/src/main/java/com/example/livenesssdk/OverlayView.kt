package com.example.livenesssdk

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

// Enum to represent the possible states of a validation circle
enum class CircleState {
    INCOMPLETE, // Not yet validated (White)
    SUCCESS,    // Validation passed (Green)
    FAILURE,    // Validation failed (Red, temporary)
    ACTIVE      // The circle the user should currently focus on (Blue)
}

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

    private val incompleteCirclePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val successCirclePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val failureCirclePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    // *** KEY CHANGE: New Paint for the active state ***
    private val activeCirclePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL_AND_STROKE // Make it stand out
        strokeWidth = 10f
        isAntiAlias = true
        setShadowLayer(15f, 0f, 0f, Color.BLUE) // Add a glow effect
    }


    data class DetectionResult(
        val box: RectF,
        val label: String,
        val circleStates: List<CircleState>
    )

    private var result: DetectionResult? = null
    private var guideBox: RectF? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box = getGuideBox()
        canvas.drawRect(box, guideBoxPaint)

        result?.let {
            canvas.drawRect(it.box, resultBoxPaint)
            // canvas.drawText(it.label, it.box.left, it.box.top - 10, resultTextPaint) // Text is now in MainActivity
            drawValidationCircles(canvas, it.box, it.circleStates)
        }
    }

    private fun drawValidationCircles(canvas: Canvas, box: RectF, states: List<CircleState>) {
        if (box.height() <= 0 || states.size < 6) return

        val dynamicRadius = (box.height() / 3f) / 2f
        val insetMargin = dynamicRadius

        val points = listOf(
            PointF(box.left + insetMargin, box.top + insetMargin),
            PointF(box.centerX(), box.top + insetMargin),
            PointF(box.right - insetMargin, box.top + insetMargin),
            PointF(box.left + insetMargin, box.bottom - insetMargin),
            PointF(box.centerX(), box.bottom - insetMargin),
            PointF(box.right - insetMargin, box.bottom - insetMargin)
        )

        points.forEachIndexed { index, point ->
            // *** KEY CHANGE: Choose paint based on the circle's state, including ACTIVE ***
            val paint = when(states[index]) {
                CircleState.INCOMPLETE -> incompleteCirclePaint
                CircleState.SUCCESS -> successCirclePaint
                CircleState.FAILURE -> failureCirclePaint
                CircleState.ACTIVE -> activeCirclePaint
            }
            canvas.drawCircle(point.x, point.y, dynamicRadius, paint)
        }
    }

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