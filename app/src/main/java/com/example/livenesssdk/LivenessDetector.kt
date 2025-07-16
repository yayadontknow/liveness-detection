package com.example.livenesssdk

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.face.Face

class LivenessDetector(
    private val onResult: (Boolean) -> Unit
) {

    private val colorSequence = listOf(Color.RED, Color.GREEN, Color.BLUE)
    private var sequenceIndex = 0
    private var lastColorChangeTime = 0L
    private var sequenceStartTime = 0L

    private var lastDetectedColor: Int? = null
    private var stableFrameCount = 0

    companion object {
        private const val TAG = "LivenessDetector"
        private const val COLOR_MATCH_TIMEOUT_MS = 2500L
        private const val SEQUENCE_TIMEOUT_MS = 8000L
        private const val STABLE_FRAMES_REQUIRED = 2
    }

    fun processFrame(bitmap: Bitmap, face: Face, expectedColor: Int) {
        if (sequenceIndex >= colorSequence.size) return

        if (sequenceIndex == 0 && sequenceStartTime == 0L) {
            if (expectedColor == colorSequence[0]) {
                sequenceStartTime = System.currentTimeMillis()
                lastColorChangeTime = System.currentTimeMillis()
            }
        }

        if (sequenceStartTime > 0L && System.currentTimeMillis() - sequenceStartTime > SEQUENCE_TIMEOUT_MS) {
            Log.w(TAG, "Liveness check failed: Sequence timed out.")
            onResult(false)
            return
        }

        if (lastColorChangeTime > 0L && System.currentTimeMillis() - lastColorChangeTime > COLOR_MATCH_TIMEOUT_MS) {
            Log.w(TAG, "Liveness check failed: Timed out waiting for ${colorToName(colorSequence[sequenceIndex])}.")
            onResult(false)
            return
        }

        val faceColor = getAverageFaceColor(bitmap, face)
        if (isColorMatch(faceColor, expectedColor) && expectedColor == colorSequence[sequenceIndex]) {
            if (lastDetectedColor == expectedColor) {
                stableFrameCount++
            } else {
                lastDetectedColor = expectedColor
                stableFrameCount = 1
            }

            if (stableFrameCount >= STABLE_FRAMES_REQUIRED) {
                Log.i(TAG, "PASSED: ${colorToName(expectedColor)}")
                sequenceIndex++
                lastColorChangeTime = System.currentTimeMillis()
                stableFrameCount = 0
                lastDetectedColor = null

                if (sequenceIndex >= colorSequence.size) {
                    Log.i(TAG, "Liveness check passed!")
                    onResult(true)
                }
            }
        }
    }

    private fun getAverageFaceColor(bitmap: Bitmap, face: Face): Int {
        val bounds = face.boundingBox
        val insetX = bounds.width() * 0.2f
        val insetY = bounds.height() * 0.2f
        val faceRect = Rect(
            (bounds.left + insetX).toInt(),
            (bounds.top + insetY).toInt(),
            (bounds.right - insetX).toInt(),
            (bounds.bottom - insetY).toInt()
        )
        faceRect.intersect(0, 0, bitmap.width, bitmap.height)
        if (faceRect.isEmpty) return Color.BLACK

        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        val pixels = IntArray(faceRect.width() * faceRect.height())
        bitmap.getPixels(pixels, 0, faceRect.width(), faceRect.left, faceRect.top, faceRect.width(), faceRect.height())

        for (color in pixels) {
            redSum += Color.red(color)
            greenSum += Color.green(color)
            blueSum += Color.blue(color)
        }

        val count = pixels.size
        if (count == 0) return Color.BLACK
        return Color.rgb((redSum / count).toInt(), (greenSum / count).toInt(), (blueSum / count).toInt())
    }

    private fun isColorMatch(detectedColor: Int, screenColor: Int): Boolean {
        val r = Color.red(detectedColor)
        val g = Color.green(detectedColor)
        val b = Color.blue(detectedColor)
        return when (screenColor) {
            Color.RED -> r > g && r > b
            Color.GREEN -> g > r && g > b
            Color.BLUE -> b > r && b > g
            else -> false
        }
    }

    private fun colorToName(color: Int) = when (color) {
        Color.RED -> "RED"
        Color.GREEN -> "GREEN"
        Color.BLUE -> "BLUE"
        else -> "UNKNOWN"
    }

    fun reset() {
        sequenceIndex = 0
        stableFrameCount = 0
        lastDetectedColor = null
        lastColorChangeTime = 0L
        sequenceStartTime = 0L
    }
}