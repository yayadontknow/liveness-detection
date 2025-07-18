// file path: E:\Competitions\LivenessSDK\app\src\main\java\com\example\livenesssdk\MainActivity.kt
package com.example.livenesssdk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var resultTextView: TextView
    private lateinit var debugIrisView: ImageView // View to display the extracted iris

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
        checkAssets()

        // Initialize UI components
        previewView = findViewById(R.id.preview_view)
        rootLayout = findViewById(R.id.root)
        resultTextView = findViewById(R.id.liveness_result_text)
        debugIrisView = findViewById(R.id.debug_iris_view) // Initialize the debug ImageView
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize LivenessDetector with a callback for results and a new callback for debug frames
        livenessDetector = LivenessDetector(
            onResult = { isLive ->
                runOnUiThread { handleLivenessResult(isLive) }
            },
            onDebugFrame = { irisBitmap ->
                // This callback receives the cropped/annotated iris image.
                // Update the ImageView on the main thread.
                runOnUiThread {
                    debugIrisView.setImageBitmap(irisBitmap)
                }
            }
        )

        colorFlasher = ColorFlasher(rootLayout)
        setFullBrightness()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    private fun checkAssets() {
        val assetManager = assets
        try {
            // Log all files in the root of the assets folder
            val files = assetManager.list("")
            Log.d("AssetCheck", "Files in assets: ${files?.joinToString()}")

            // Try to open the specific model file
            assetManager.open("face_landmarker.task").use {
                // If this line is reached, the file exists and is readable.
                Log.i("AssetCheck", "SUCCESS: 'face_landmarker.task' was found and opened successfully.")
            }
        } catch (e: IOException) {
            // If we get an IOException, the file is NOT in the assets folder or is named incorrectly.
            Log.e("AssetCheck", "ERROR: 'face_landmarker.task' not found in assets! This is the cause of the crash.", e)
            Toast.makeText(this, "ERROR: Model file not found!", Toast.LENGTH_LONG).show()
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
                    it.setAnalyzer(cameraExecutor, CameraAnalyzer(this) { bitmap, result ->
                        if (isLivenessCheckInProgress) {
                            livenessDetector.processFrame(bitmap, result, colorFlasher.currentColor)
                        }
                    })
                }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                Log.d(TAG, "Camera bound, starting first liveness check.")
                startLivenessCheck()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Could not initialize MediaPipe.", e)
                Toast.makeText(this, "Failed to load liveness model. Please restart the app.", Toast.LENGTH_LONG).show()
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
        // Show the debug view at the start of the check and clear any old image
        debugIrisView.visibility = View.VISIBLE
        debugIrisView.setImageBitmap(null)
        colorFlasher.start()
    }

    private fun handleLivenessResult(isLive: Boolean) {
        if (!isLivenessCheckInProgress) return

        isLivenessCheckInProgress = false
        colorFlasher.stop()
        // Hide the debug iris view once the check is complete
        debugIrisView.visibility = View.GONE

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
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
        MediaPipeFaceMeshHelper.close()
    }

    private fun setFullBrightness() {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = 1f
        window.attributes = layoutParams
    }
}