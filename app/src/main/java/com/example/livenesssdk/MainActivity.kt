package com.example.livenesssdk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var resultTextView: TextView

    private lateinit var colorFlasher: ColorFlasher
    private lateinit var livenessDetector: LivenessDetector
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isLivenessCheckInProgress = false

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1001
        private const val TAG = "MainActivity"
        private const val RESTART_DELAY_MS = 3000L // 3 seconds delay before restarting
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        rootLayout = findViewById(R.id.root)
        resultTextView = findViewById(R.id.liveness_result_text)
        cameraExecutor = Executors.newSingleThreadExecutor()

        livenessDetector = LivenessDetector { isLive ->
            runOnUiThread { handleLivenessResult(isLive) }
        }
        colorFlasher = ColorFlasher(rootLayout)
        setFullBrightness()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, CameraAnalyzer(this) { bitmap, face ->
                        if (isLivenessCheckInProgress) {
                            livenessDetector.processFrame(bitmap, face, colorFlasher.currentColor)
                        }
                    })
                }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                Log.d(TAG, "Camera bound, starting first liveness check.")
                // Start the first check after the camera is successfully bound.
                startLivenessCheck()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed. A front camera is required.", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLivenessCheck() {
        if (isLivenessCheckInProgress) return
        Log.i(TAG, "Starting new liveness cycle.")
        isLivenessCheckInProgress = true
        livenessDetector.reset()
        resultTextView.visibility = View.GONE
        colorFlasher.start()
    }

    private fun handleLivenessResult(isLive: Boolean) {
        // Ensure we only handle one result per cycle
        if (!isLivenessCheckInProgress) return

        isLivenessCheckInProgress = false
        colorFlasher.stop()

        resultTextView.visibility = View.VISIBLE
        if (isLive) {
            resultTextView.text = "Yes"
            resultTextView.setTextColor(Color.GREEN)
            Log.i(TAG, "Liveness Result: YES")
        } else {
            resultTextView.text = "No"
            resultTextView.setTextColor(Color.RED)
            Log.w(TAG, "Liveness Result: NO")
        }

        // Schedule the next check to run after a delay
        Log.i(TAG, "Scheduling next check in ${RESTART_DELAY_MS}ms")
        handler.postDelayed({
            startLivenessCheck()
        }, RESTART_DELAY_MS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Log.e(TAG, "Camera permission denied.")
                resultTextView.text = "Camera Permission Needed"
                resultTextView.setTextColor(Color.YELLOW)
                resultTextView.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    private fun setFullBrightness() {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = 1f
        window.attributes = layoutParams
    }
}