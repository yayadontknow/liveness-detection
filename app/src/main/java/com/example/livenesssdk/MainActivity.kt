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
    private var currentFrameBitmap: Bitmap? = null

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1001
        private const val TAG = "MainActivity"
        private const val BRIGHTNESS_THRESHOLD = 230
        private const val BRIGHT_PIXEL_PERCENTAGE_THRESHOLD = 0.25
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        progressBar = findViewById(R.id.progress_bar)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) { // <-- This function needs to exist
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        }
    }

    // --- Start of Fix ---
    // Add this function back into the class
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    // --- End of Fix ---

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
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
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

            currentFrameBitmap = getRotatedBitmap(imageProxy)
            val croppedBitmapForServer = cropBitmapByGuideBox(currentFrameBitmap)

            imageProxy.close()

            if (croppedBitmapForServer != null) {
                runOnUiThread { setUiInProgress(true) }
                processAndSendImage(croppedBitmapForServer)
            } else {
                Log.e(TAG, "Failed to crop image for server.")
                isProcessing = false
            }
        }
    }

    private fun getRotatedBitmap(image: ImageProxy): Bitmap? {
        val fullBitmap = image.toBitmap() ?: return null
        val rotationDegrees = image.imageInfo.rotationDegrees
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, fullBitmap.height, matrix, true)
    }

    private fun cropBitmapByGuideBox(sourceBitmap: Bitmap?): Bitmap? {
        if (sourceBitmap == null) return null
        val guideBox = overlayView.getGuideBox()
        val cropRect = mapPreviewBoxToBitmapBox(guideBox, sourceBitmap.width, sourceBitmap.height)

        return try {
            Bitmap.createBitmap(
                sourceBitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop bitmap with rect: $cropRect and bitmap size: ${sourceBitmap.width}x${sourceBitmap.height}", e)
            null
        }
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
                    overlayView.clearResults()
                }
                isProcessing = false
            }
        }
    }

    private fun handleNetworkResponse(jsonResponse: String) {
        val bitmapToAnalyze = currentFrameBitmap
        if (bitmapToAnalyze == null) {
            overlayView.clearResults()
            return
        }

        try {
            val detections = JSONObject(jsonResponse).getJSONArray("detections")
            if (detections.length() == 0) {
                overlayView.clearResults()
                return
            }

            val firstDetection = detections.getJSONObject(0)
            val confidence = firstDetection.getDouble("confidence")
            val bboxArray = firstDetection.getJSONArray("bbox")
            val x1 = bboxArray.getDouble(0).toFloat()
            val y1 = bboxArray.getDouble(1).toFloat()
            val x2 = bboxArray.getDouble(2).toFloat()
            val y2 = bboxArray.getDouble(3).toFloat()

            val guideBox = overlayView.getGuideBox()
            val resultBoxOnScreen = RectF(
                guideBox.left + (x1 / 640f) * guideBox.width(),
                guideBox.top + (y1 / 640f) * guideBox.height(),
                guideBox.left + (x2 / 640f) * guideBox.width(),
                guideBox.top + (y2 / 640f) * guideBox.height()
            )

            val circleStates = checkReflections(bitmapToAnalyze, resultBoxOnScreen)

            val label = "ID Confidence: ${String.format("%.2f", confidence)}"
            val result = OverlayView.DetectionResult(resultBoxOnScreen, label, circleStates)
            overlayView.setResults(result)

        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse JSON response", e)
            overlayView.clearResults()
        }
    }

    private fun checkReflections(bitmap: Bitmap, boxOnScreen: RectF): List<Boolean> {
        if (boxOnScreen.height() <= 0) return List(6) { false }

        val dynamicRadius = (boxOnScreen.height() / 3f) / 2f
        val insetMargin = dynamicRadius

        val pointsOnScreen = listOf(
            PointF(boxOnScreen.left + insetMargin, boxOnScreen.top + insetMargin),
            PointF(boxOnScreen.centerX(), boxOnScreen.top + insetMargin),
            PointF(boxOnScreen.right - insetMargin, boxOnScreen.top + insetMargin),
            PointF(boxOnScreen.left + insetMargin, boxOnScreen.bottom - insetMargin),
            PointF(boxOnScreen.centerX(), boxOnScreen.bottom - insetMargin),
            PointF(boxOnScreen.right - insetMargin, boxOnScreen.bottom - insetMargin)
        )

        return pointsOnScreen.map { pointOnScreen ->
            val regionOnBitmap = RectF(
                pointOnScreen.x - dynamicRadius,
                pointOnScreen.y - dynamicRadius,
                pointOnScreen.x + dynamicRadius,
                pointOnScreen.y + dynamicRadius
            )
            val mappedRegion = mapPreviewBoxToBitmapBox(regionOnBitmap, bitmap.width, bitmap.height)
            hasReflection(bitmap, mappedRegion)
        }
    }

    private fun hasReflection(bitmap: Bitmap, region: Rect): Boolean {
        if (region.width() <= 0 || region.height() <= 0) return false
        region.intersect(0, 0, bitmap.width, bitmap.height)

        val totalPixels = region.width() * region.height()
        if (totalPixels == 0) return false

        var brightPixelCount = 0
        val pixels = IntArray(totalPixels)
        try {
            bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pixels from region $region", e)
            return false
        }

        for (color in pixels) {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val brightness = (r + g + b) / 3
            if (brightness > BRIGHTNESS_THRESHOLD) {
                brightPixelCount++
            }
        }

        val percentage = brightPixelCount.toFloat() / totalPixels.toFloat()
        return percentage > BRIGHT_PIXEL_PERCENTAGE_THRESHOLD
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

    private fun ImageProxy.toBitmap(): Bitmap? {
        val image = this.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) {
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
            if (allPermissionsGranted()) { startCamera() } else { finish() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        camera?.cameraControl?.enableTorch(false)
    }
}