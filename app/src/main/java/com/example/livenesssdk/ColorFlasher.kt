// file path: E:\Competitions\LivenessSDK\app\src\main\java\com\example\livenesssdk\ColorFlasher.kt
package com.example.livenesssdk

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View

class ColorFlasher(private val view: View) {
    // Add WHITE to the beginning of the list
    val colors = listOf(Color.WHITE, Color.RED, Color.GREEN, Color.BLUE)
    private val handler = Handler(Looper.getMainLooper())
    private var index = 0

    var currentColor: Int = Color.BLACK
        private set

    private val runnable = object : Runnable {
        override fun run() {
            currentColor = colors[index]
            view.setBackgroundColor(currentColor)
            index = (index + 1) % colors.size
            // Use a slightly longer delay for calibration, then shorter for colors
            val delay = if (index == 1) 1000L else 1500L
            handler.postDelayed(this, delay)
        }
    }

    fun start() {
        // Reset to black before starting
        view.setBackgroundColor(Color.BLACK)
        // Reset index
        index = 0
        handler.post(runnable)
    }

    fun stop() {
        handler.removeCallbacks(runnable)
        view.setBackgroundColor(Color.BLACK)
    }
}