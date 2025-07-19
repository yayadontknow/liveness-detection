package com.example.livenesssdk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.View
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
    private lateinit var progressBar: ProgressBar

    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    @Volatile
    private var isProcessing = false

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1001
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        progressBar = findViewById(R.id.progress_bar)

        cameraExecutor = Executors.newSingleThreadExecutor()

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

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, setupAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                camera?.cameraControl?.enableTorch(true)
                Log.d(TAG, "Camera started with real-time analysis.")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            if (isProcessing) {
                imageProxy.close()
                return@Analyzer
            }

            isProcessing = true

            val croppedBitmap = getCroppedBitmap(imageProxy)
            imageProxy.close()

            if (croppedBitmap != null) {
                runOnUiThread { setUiInProgress(true) }
                processAndSendImage(croppedBitmap)
            } else {
                Log.e(TAG, "Failed to crop image.")
                isProcessing = false
            }
        }
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
                isProcessing = false
            }
        }
    }

    private fun handleNetworkResponse(jsonResponse: String) {
        try {
            val detections = JSONObject(jsonResponse).getJSONArray("detections")

            if (detections.length() == 0) {
                overlayView.setResults(null) // Clear previous boxes if nothing is detected
                return
            }

            // Assume we only care about the first detection from the server
            val firstDetection = detections.getJSONObject(0)
            val confidence = firstDetection.getDouble("confidence")
            val bboxArray = firstDetection.getJSONArray("bbox")
            val x1 = bboxArray.getDouble(0).toFloat()
            val y1 = bboxArray.getDouble(1).toFloat()
            val x2 = bboxArray.getDouble(2).toFloat()
            val y2 = bboxArray.getDouble(3).toFloat()

            val guideBox = overlayView.getGuideBox()
            val resultBox = RectF(
                guideBox.left + (x1 / 640f) * guideBox.width(),
                guideBox.top + (y1 / 640f) * guideBox.height(),
                guideBox.left + (x2 / 640f) * guideBox.width(),
                guideBox.top + (y2 / 640f) * guideBox.height()
            )
            val label = "ID Confidence: ${String.format("%.2f", confidence)}"

            // Create a single result and pass it to the view
            val result = OverlayView.DetectionResult(resultBox, label)
            overlayView.setResults(result)

        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse JSON response", e)
            Toast.makeText(this, "Invalid response from server.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        val image = this.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e("ImageProxyExt", "Unsupported image format: ${image.format}")
            return null
        }

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 95, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun setUiInProgress(inProgress: Boolean) {
        progressBar.visibility = if (inProgress) View.VISIBLE else View.GONE
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
}