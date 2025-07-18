// file path: E:\Competitions\LivenessSDK\app\src\main\java\com\example\livenesssdk\LivenessDetector.kt
package com.example.livenesssdk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FACE_LANDMARKS_LEFT_IRIS
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FACE_LANDMARKS_RIGHT_IRIS
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max

class LivenessDetector(
    private val onResult: (Boolean) -> Unit,
    private val onDebugFrame: ((Bitmap) -> Unit)? = null
) {
    // --- NEW: Add WHITE to the start for calibration ---
    private val colorSequence = listOf(Color.WHITE, Color.RED, Color.GREEN, Color.BLUE)
    private var sequenceIndex = 0
    private var lastColorChangeTime = 0L
    private var sequenceStartTime = 0L
    private var stableFrameCount = 0

    // --- NEW: Calibration Data Storage ---
    // Store the location of the natural white glare to ignore it later.
    private var leftGlareArea: RectF? = null
    private var rightGlareArea: RectF? = null
    private var isCalibrated = false

    companion object {
        private const val TAG = "LivenessIrisDetector"
        private const val SEQUENCE_TIMEOUT_MS = 10000L // Increased for calibration step
        private const val COLOR_MATCH_TIMEOUT_MS = 3000L
        private const val STABLE_FRAMES_REQUIRED = 3 // Increase for more stability

        // We can be more aggressive with thresholds now that we have calibration
        private const val REFLECTION_THRESHOLD = 140
        private const val DARK_RATIO_REQUIRED = 0.75f
        private const val ADAPTIVE_DARKNESS_FACTOR = 0.4f
        private const val GLARE_IGNORE_RADIUS = 1.5f // Factor to expand the glare zone

        private val LEFT_IRIS_INDEXES = FACE_LANDMARKS_LEFT_IRIS.map { it.start() }
        private val RIGHT_IRIS_INDEXES = FACE_LANDMARKS_RIGHT_IRIS.map { it.start() }
    }

    fun processFrame(bitmap: Bitmap, result: FaceLandmarkerResult, expectedColor: Int) {
        if (sequenceIndex >= colorSequence.size) return
        val targetColor = colorSequence[sequenceIndex]

        if (sequenceIndex == 0 && sequenceStartTime == 0L) {
            sequenceStartTime = System.currentTimeMillis()
            lastColorChangeTime = sequenceStartTime
        }

        if (sequenceStartTime > 0 && System.currentTimeMillis() - sequenceStartTime > SEQUENCE_TIMEOUT_MS) {
            Log.w(TAG, "Liveness check failed: Sequence timed out.")
            onResult(false)
            return
        }

        if (lastColorChangeTime > 0 && System.currentTimeMillis() - lastColorChangeTime > COLOR_MATCH_TIMEOUT_MS) {
            Log.w(TAG, "Liveness check failed: Timed out waiting for ${colorToName(targetColor)}.")
            onResult(false)
            return
        }

        if (expectedColor != targetColor) return

        val landmarks = result.faceLandmarks().getOrNull(0) ?: return
        val hasValid: Boolean

        // --- NEW: Handle Calibration Step ---
        if (targetColor == Color.WHITE && !isCalibrated) {
            hasValid = calibrateGlare(bitmap, landmarks)
            if (hasValid) {
                // We only need one successful calibration frame
                Log.i(TAG, "PASSED calibration. Glare locations recorded.")
                isCalibrated = true
                stableFrameCount = STABLE_FRAMES_REQUIRED // Force moving to the next step
            }
        } else {
            // Proceed with normal color detection, now using calibration data
            hasValid = detectIrisReflection(bitmap, landmarks, targetColor)
        }

        if (hasValid) stableFrameCount++ else stableFrameCount = 0

        if (stableFrameCount >= STABLE_FRAMES_REQUIRED) {
            Log.i(TAG, "PASSED step: ${colorToName(targetColor)}")
            sequenceIndex++
            lastColorChangeTime = System.currentTimeMillis()
            stableFrameCount = 0
            if (sequenceIndex >= colorSequence.size) {
                Log.i(TAG, "Liveness check passed!")
                onResult(true)
            }
        }
    }

    // --- NEW: Calibration Function ---
    private fun calibrateGlare(bitmap: Bitmap, landmarks: List<NormalizedLandmark>): Boolean {
        // We need to find the glare in both eyes to be considered calibrated
        val leftCalibrated = findAndStoreGlare(bitmap, landmarks, LEFT_IRIS_INDEXES, true) { leftGlareArea = it }
        val rightCalibrated = findAndStoreGlare(bitmap, landmarks, RIGHT_IRIS_INDEXES, true) { rightGlareArea = it }
        return leftCalibrated && rightCalibrated
    }

    private fun detectIrisReflection(bitmap: Bitmap, landmarks: List<NormalizedLandmark>, screenColor: Int): Boolean {
        val leftSuccess = findAndStoreGlare(bitmap, landmarks, LEFT_IRIS_INDEXES, false, screenColor, leftGlareArea)
        val rightSuccess = findAndStoreGlare(bitmap, landmarks, RIGHT_IRIS_INDEXES, false, screenColor, rightGlareArea)
        return leftSuccess || rightSuccess
    }

    private fun findAndStoreGlare(
        bitmap: Bitmap,
        landmarks: List<NormalizedLandmark>,
        irisIndexes: List<Int>,
        isCalibration: Boolean,
        screenColor: Int = Color.WHITE, // Default to white for calibration
        ignoreArea: RectF? = null,
        onGlareFound: ((RectF) -> Unit)? = null
    ): Boolean {
        val irisPoints = irisIndexes.map { PointF(landmarks[it].x() * bitmap.width, landmarks[it].y() * bitmap.height) }
        if (irisPoints.isEmpty()) return false

        val centerX = irisPoints.map { it.x }.average().toFloat()
        val centerY = irisPoints.map { it.y }.average().toFloat()
        val radius = irisPoints.map { pt -> Math.hypot((pt.x - centerX).toDouble(), (pt.y - centerY).toDouble()) }.average().toFloat().coerceAtLeast(5f)

        val pixels = getIrisPixels(bitmap, centerX, centerY, radius) ?: return false

        var specularPoint: PointF? = null
        var maxBrightness = 0

        // Pass 1: Find the best specular reflection candidate
        pixels.forEach { (point, color) ->
            // --- NEW: Ignore the calibrated glare area during color checks ---
            if (!isCalibration && ignoreArea != null && ignoreArea.contains(point.x, point.y)) {
                return@forEach // Skip this pixel, it's in the known glare zone
            }

            val isMatch = if (isCalibration) isWhite(color) else isSpecular(color, screenColor)
            if (isMatch) {
                val brightness = max(Color.red(color), max(Color.green(color), Color.blue(color)))
                if (brightness > maxBrightness) {
                    maxBrightness = brightness
                    specularPoint = point
                }
            }
        }

        if (specularPoint == null) {
            generateAndInvokeDebugFrame(bitmap, centerX, centerY, radius, isSuccess = false, ignoreArea)
            return false
        }

        if (isCalibration) {
            val glareRadius = radius / 4f * GLARE_IGNORE_RADIUS
            onGlareFound?.invoke(RectF(specularPoint!!.x - glareRadius, specularPoint!!.y - glareRadius, specularPoint!!.x + glareRadius, specularPoint!!.y + glareRadius))
            generateAndInvokeDebugFrame(bitmap, centerX, centerY, radius, isSuccess = true, onGlareFound = { canvas, f ->
                canvas.drawRect(f, Paint().apply { this.color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 1f })
            })
            return true
        }

        // Pass 2: Check for relative darkness
        val dynamicDarkThreshold = maxBrightness * ADAPTIVE_DARKNESS_FACTOR
        var darkCount = 0
        var totalCount = 0
        pixels.forEach { (point, color) ->
            if (point != specularPoint && (ignoreArea == null || !ignoreArea.contains(point.x, point.y))) {
                totalCount++
                val brightness = max(Color.red(color), max(Color.green(color), Color.blue(color)))
                if (brightness < dynamicDarkThreshold) darkCount++
            }
        }
        val darkRatio = if (totalCount > 0) darkCount.toFloat() / totalCount else 1f
        val isSuccess = darkRatio >= DARK_RATIO_REQUIRED
        generateAndInvokeDebugFrame(bitmap, centerX, centerY, radius, isSuccess, ignoreArea)
        return isSuccess
    }

    private fun isWhite(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return r > 200 && g > 200 && b > 200 // High threshold for white glare
    }

    private fun isSpecular(color: Int, screen: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val colorDominanceFactor = 1.2f
        return when (screen) {
            Color.RED -> r > REFLECTION_THRESHOLD && r > g * colorDominanceFactor && r > b * colorDominanceFactor
            Color.GREEN -> g > REFLECTION_THRESHOLD && g > r * colorDominanceFactor && g > b * colorDominanceFactor
            Color.BLUE -> b > REFLECTION_THRESHOLD && b > r * colorDominanceFactor && b > g * colorDominanceFactor
            else -> false
        }
    }

    private fun getIrisPixels(bitmap: Bitmap, centerX: Float, centerY: Float, radius: Float): List<Pair<PointF, Int>>? {
        val left = (centerX - radius).toInt().coerceIn(0, bitmap.width - 1)
        val top = (centerY - radius).toInt().coerceIn(0, bitmap.height - 1)
        val right = (centerX + radius).toInt().coerceIn(0, bitmap.width - 1)
        val bottom = (centerY + radius).toInt().coerceIn(0, bitmap.height - 1)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        val pixelArray = IntArray(w * h)
        bitmap.getPixels(pixelArray, 0, w, left, top, w, h)
        val resultList = mutableListOf<Pair<PointF, Int>>()
        for (i in pixelArray.indices) {
            val px = left + (i % w).toFloat()
            val py = top + (i / w).toFloat()
            if ((px - centerX).let { it * it } + (py - centerY).let { it * it } <= radius * radius) {
                resultList.add(PointF(px, py) to pixelArray[i])
            }
        }
        return resultList
    }

    private fun generateAndInvokeDebugFrame(bitmap: Bitmap, centerX: Float, centerY: Float, radius: Float, isSuccess: Boolean, ignoreArea: RectF? = null, onGlareFound: ((Canvas, RectF) -> Unit)? = null) {
        onDebugFrame?.let {
            // Drawing logic remains similar, but now we can also draw the 'ignoreArea'
            val cropPadding = 2.0f
            val cropSize = (radius * 2 * cropPadding).toInt().coerceAtLeast(80)
            val cropLeft = (centerX - cropSize / 2).toInt().coerceIn(0, bitmap.width - cropSize)
            val cropTop = (centerY - cropSize / 2).toInt().coerceIn(0, bitmap.height - cropSize)
            if (cropLeft < 0 || cropTop < 0 || cropLeft + cropSize > bitmap.width || cropTop + cropSize > bitmap.height) return
            val eyeBitmap = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropSize, cropSize)
            val mutableEyeBitmap = eyeBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableEyeBitmap)
            val circlePaint = Paint().apply { color = if (isSuccess) Color.GREEN else Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f }
            val ignorePaint = Paint().apply { color = Color.MAGENTA; style = Paint.Style.STROKE; strokeWidth = 1f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f) }

            // Draw ignore area
            ignoreArea?.let {
                val relativeIgnoreRect = RectF(it.left - cropLeft, it.top - cropTop, it.right - cropLeft, it.bottom - cropTop)
                canvas.drawRect(relativeIgnoreRect, ignorePaint)
                onGlareFound?.invoke(canvas, relativeIgnoreRect)
            }
            canvas.drawCircle(centerX - cropLeft, centerY - cropTop, radius, circlePaint)
            it.invoke(mutableEyeBitmap)
        }
    }

    fun reset() {
        sequenceIndex = 0
        stableFrameCount = 0
        lastColorChangeTime = 0L
        sequenceStartTime = 0L
        // --- NEW: Reset calibration data ---
        isCalibrated = false
        leftGlareArea = null
        rightGlareArea = null
    }

    private fun colorToName(color: Int) = when (color) {
        Color.WHITE -> "WHITE (Calibration)"
        Color.RED -> "RED"
        Color.GREEN -> "GREEN"
        Color.BLUE -> "BLUE"
        else -> "UNKNOWN"
    }
}