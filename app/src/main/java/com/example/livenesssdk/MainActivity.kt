package com.example.livenesssdk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var detectButton: Button
    private lateinit var progressBar: ProgressBar

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private lateinit var cameraExecutor: ExecutorService

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1001
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        detectButton = findViewById(R.id.detect_button)
        progressBar = findViewById(R.id.progress_bar)

        cameraExecutor = Executors.newSingleThreadExecutor()

        detectButton.setOnClickListener { takePhoto() }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                camera?.cameraControl?.enableTorch(true)
                Log.d(TAG, "Camera started and flashlight enabled.")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        setUiInProgress(true)
        overlayView.clearResults()

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.d(TAG, "Photo capture success. Image Resolution: ${image.width}x${image.height}, Rotation: ${image.imageInfo.rotationDegrees}")

                    val croppedBitmap = getCroppedBitmap(image)
                    image.close()

                    if (croppedBitmap != null) {
                        processAndSendImage(croppedBitmap)
                    } else {
                        Log.e(TAG, "Failed to crop image.")
                        setUiInProgress(false)
                        Toast.makeText(baseContext, "Failed to process image.", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    setUiInProgress(false)
                    Toast.makeText(baseContext, "Photo capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun getCroppedBitmap(image: ImageProxy): Bitmap? {
        val fullBitmap = image.toBitmap() ?: return null
        val rotationDegrees = image.imageInfo.rotationDegrees
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, fullBitmap.height, matrix, true)

        val guideBox = overlayView.getGuideBox()
        val cropRect = mapPreviewBoxToBitmapBox(guideBox, rotatedBitmap.width, rotatedBitmap.height)

        return try {
            Bitmap.createBitmap(
                rotatedBitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop bitmap with rect: $cropRect and bitmap size: ${rotatedBitmap.width}x${rotatedBitmap.height}", e)
            null
        }
    }

    private fun mapPreviewBoxToBitmapBox(previewBox: RectF, bitmapWidth: Int, bitmapHeight: Int): Rect {
        val previewRatio = previewView.width.toFloat() / previewView.height.toFloat()
        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()

        val newLeft: Float
        val newTop: Float
        val newWidth: Float
        val newHeight: Float

        if (bitmapRatio > previewRatio) {
            newWidth = bitmapWidth.toFloat()
            newHeight = bitmapWidth / previewRatio
            newLeft = 0f
            newTop = (newHeight - bitmapHeight) / 2f
        } else {
            newHeight = bitmapHeight.toFloat()
            newWidth = bitmapHeight * previewRatio
            newTop = 0f
            newLeft = (newWidth - bitmapWidth) / 2f
        }

        val scale = newWidth / previewView.width.toFloat()
        val left = (previewBox.left * scale) - newLeft
        val top = (previewBox.top * scale) - newTop
        val right = (previewBox.right * scale) - newLeft
        val bottom = (previewBox.bottom * scale) - newLeft

        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    private fun processAndSendImage(croppedBitmap: Bitmap) {
        val finalBitmap = Bitmap.createScaledBitmap(croppedBitmap, 640, 640, true)
        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        val imageBytes = outputStream.toByteArray()

        NetworkClient.detectIdCard(imageBytes) { response, error ->
            runOnUiThread {
                setUiInProgress(false)
                if (response != null) {
                    handleNetworkResponse(response)
                } else {
                    Toast.makeText(this, "Network Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleNetworkResponse(jsonResponse: String) {
        try {
            val detections = JSONObject(jsonResponse).getJSONArray("detections")
            if (detections.length() == 0) {
                Toast.makeText(this, "No ID card detected.", Toast.LENGTH_SHORT).show()
                return
            }
            val results = mutableListOf<OverlayView.DetectionResult>()
            val guideBox = overlayView.getGuideBox()

            for (i in 0 until detections.length()) {
                val detection = detections.getJSONObject(i)
                val confidence = detection.getDouble("confidence")
                val bboxArray = detection.getJSONArray("bbox")
                val x1 = bboxArray.getDouble(0).toFloat()
                val y1 = bboxArray.getDouble(1).toFloat()
                val x2 = bboxArray.getDouble(2).toFloat()
                val y2 = bboxArray.getDouble(3).toFloat()

                val resultBox = RectF(
                    guideBox.left + (x1 / 640f) * guideBox.width(),
                    guideBox.top + (y1 / 640f) * guideBox.height(),
                    guideBox.left + (x2 / 640f) * guideBox.width(),
                    guideBox.top + (y2 / 640f) * guideBox.height()
                )
                val label = "ID Confidence: ${String.format("%.2f", confidence)}"
                results.add(OverlayView.DetectionResult(resultBox, label))
            }
            overlayView.setResults(results)
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse JSON response", e)
            Toast.makeText(this, "Invalid response from server.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUiInProgress(inProgress: Boolean) {
        progressBar.visibility = if (inProgress) View.VISIBLE else View.GONE
        detectButton.isEnabled = !inProgress
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permissions are required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        camera?.cameraControl?.enableTorch(false)
    }

    // Helper function to convert ImageProxy to Bitmap
    private fun ImageProxy.toBitmap(): Bitmap? {
        val planeProxy = this.planes[0]
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}