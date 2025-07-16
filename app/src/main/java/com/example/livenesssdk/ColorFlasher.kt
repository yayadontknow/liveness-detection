package com.example.livenesssdk

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View

class ColorFlasher(private val view: View) {
    val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
    private val handler = Handler(Looper.getMainLooper())
    private var index = 0

    var currentColor: Int = Color.BLACK
        private set

    private val runnable = object : Runnable {
        override fun run() {
            currentColor = colors[index]
            view.setBackgroundColor(currentColor)
            index = (index + 1) % colors.size
            handler.postDelayed(this, 1500) // Flash every 1.5s for better detection
        }
    }

    fun start() {
        // Reset to black before starting
        view.setBackgroundColor(Color.BLACK)
        handler.post(runnable)
    }

    fun stop() {
        handler.removeCallbacks(runnable)
        view.setBackgroundColor(Color.BLACK)
    }
}