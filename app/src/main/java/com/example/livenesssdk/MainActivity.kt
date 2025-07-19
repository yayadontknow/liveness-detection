package com.example.livenesssdk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService

    // --- State Management ---
    @Volatile
    private var isProcessingFrame = false
    private var currentFrameBitmap: Bitmap? = null
    private var lastDetectedIdBox: RectF? = null
    private val circleStates = MutableList(6) { CircleState.INCOMPLETE }
    private val isCheckingHologram = BooleanArray(6) { false }
    private var lastIdCardCrop: Bitmap? = null // *** KEY CHANGE: Store the ID card crop ***
    // --- End State Management ---

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

        if (allPermissionsGranted()) { startCamera() }
        else { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE) }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, setupAnalyzer()) }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
                camera?.cameraControl?.enableTorch(true)
            } catch (exc: Exception) { Log.e(TAG, "Use case binding failed", exc) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            if (isProcessingFrame) {
                imageProxy.close()
                return@Analyzer
            }
            isProcessingFrame = true

            currentFrameBitmap = getRotatedBitmap(imageProxy)
            val idCardCrop = cropBitmapByGuideBox(currentFrameBitmap)
            lastIdCardCrop = idCardCrop // *** KEY CHANGE: Store the crop ***
            imageProxy.close()

            if (idCardCrop != null) {
                processAndSendIdCard(idCardCrop)
            } else {
                isProcessingFrame = false
            }
        }
    }

    private fun processAndSendIdCard(idCardCrop: Bitmap) {
        val finalBitmap = Bitmap.createScaledBitmap(idCardCrop, 640, 640, true)
        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)

        NetworkClient.detectIdCard(imageBytes = outputStream.toByteArray()) { response, _ ->
            handleIdCardResponse(response)
        }
    }

    private fun handleIdCardResponse(jsonResponse: String?) {
        val bitmapToAnalyze = currentFrameBitmap
        if (jsonResponse == null || bitmapToAnalyze == null) {
            runOnUiThread { overlayView.clearResults() }
            isProcessingFrame = false
            return
        }

        try {
            val detections = JSONObject(jsonResponse).getJSONArray("detections")
            if (detections.length() == 0) {
                runOnUiThread { overlayView.clearResults() }
                isProcessingFrame = false
                return
            }

            val firstDetection = detections.getJSONObject(0)
            val bboxArray = firstDetection.getJSONArray("bbox")
            val guideBox = overlayView.getGuideBox()
            lastDetectedIdBox = RectF(
                guideBox.left + (bboxArray.getDouble(0).toFloat() / 640f) * guideBox.width(),
                guideBox.top + (bboxArray.getDouble(1).toFloat() / 640f) * guideBox.height(),
                guideBox.left + (bboxArray.getDouble(2).toFloat() / 640f) * guideBox.width(),
                guideBox.top + (bboxArray.getDouble(3).toFloat() / 640f) * guideBox.height()
            )

            triggerHologramChecks(bitmapToAnalyze, lastDetectedIdBox!!)

        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse ID card JSON", e)
            isProcessingFrame = false
        }
    }

    private fun triggerHologramChecks(bitmap: Bitmap, boxOnScreen: RectF) {
        if (boxOnScreen.height() <= 0) {
            isProcessingFrame = false
            return
        }

        val dynamicRadius = (boxOnScreen.height() / 3f) / 2f
        val insetMargin = dynamicRadius

        val pointsOnScreen = listOf(
            PointF(boxOnScreen.left + insetMargin, boxOnScreen.top + insetMargin), PointF(boxOnScreen.centerX(), boxOnScreen.top + insetMargin), PointF(boxOnScreen.right - insetMargin, boxOnScreen.top + insetMargin),
            PointF(boxOnScreen.left + insetMargin, boxOnScreen.bottom - insetMargin), PointF(boxOnScreen.centerX(), boxOnScreen.bottom - insetMargin), PointF(boxOnScreen.right - insetMargin, boxOnScreen.bottom - insetMargin)
        )

        var checksTriggered = false
        pointsOnScreen.forEachIndexed { index, point ->
            if (circleStates[index] == CircleState.SUCCESS || isCheckingHologram[index]) return@forEachIndexed

            val regionOnScreen = RectF(point.x - dynamicRadius, point.y - dynamicRadius, point.x + dynamicRadius, point.y + dynamicRadius)
            val regionOnBitmap = mapPreviewBoxToBitmapBox(regionOnScreen, bitmap.width, bitmap.height)

            if (hasReflection(bitmap, regionOnBitmap)) {
                checksTriggered = true
                isCheckingHologram[index] = true
                performHologramCheck(index) // No longer need to pass bitmap/region
            }
        }

        if (!checksTriggered) {
            isProcessingFrame = false
        }

        updateOverlay()
    }

    // *** KEY CHANGE: This function now uses the stored ID card crop ***
    private fun performHologramCheck(index: Int) {
        val idCardImage = lastIdCardCrop
        if (idCardImage == null) {
            Log.e(TAG, "Cannot perform hologram check, ID card image is null")
            isCheckingHologram[index] = false
            isProcessingFrame = false
            return
        }

        // We send the entire ID card image, not a new crop
        val finalBitmap = Bitmap.createScaledBitmap(idCardImage, 640, 640, true)
        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)

        NetworkClient.detectHologram(outputStream.toByteArray()) { response, _ ->
            val numDetections = try {
                if (response == null) 0 else JSONObject(response).getJSONArray("detections").length()
            } catch (e: JSONException) { 0 }

            val isCorner = index in listOf(0, 2, 3, 5)
            val checkPassed = if (isCorner) numDetections == 0 else numDetections == 1

            if (checkPassed) {
                circleStates[index] = CircleState.SUCCESS
                checkForCompletion()
            } else {
                circleStates[index] = CircleState.FAILURE
                Handler(Looper.getMainLooper()).postDelayed({
                    if(circleStates[index] == CircleState.FAILURE) {
                        circleStates[index] = CircleState.INCOMPLETE
                        updateOverlay()
                    }
                }, 1000)
            }

            isCheckingHologram[index] = false
            if (isCheckingHologram.none { it }) {
                isProcessingFrame = false
            }
            updateOverlay()
        }
    }

    private fun checkForCompletion() {
        if (circleStates.all { it == CircleState.SUCCESS }) {
            runOnUiThread {
                stopAnalysis()
                AlertDialog.Builder(this)
                    .setTitle("Verification Complete")
                    .setMessage("All checks passed successfully.")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss(); finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun stopAnalysis() {
        imageAnalyzer?.clearAnalyzer()
        camera?.cameraControl?.enableTorch(false)
        isProcessingFrame = true
    }

    private fun updateOverlay() {
        lastDetectedIdBox?.let { box ->
            val result = OverlayView.DetectionResult(box, "ID Found", circleStates.toList())
            runOnUiThread { overlayView.setResults(result) }
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
            val brightness = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
            if (brightness > BRIGHTNESS_THRESHOLD) {
                brightPixelCount++
            }
        }
        val percentage = brightPixelCount.toFloat() / totalPixels.toFloat()
        return percentage > BRIGHT_PIXEL_PERCENTAGE_THRESHOLD
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
            Bitmap.createBitmap(sourceBitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop bitmap", e)
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

    private fun ImageProxy.toBitmap(): Bitmap? {
        val image = this.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) { return null }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (allPermissionsGranted()) { startCamera() } else {
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