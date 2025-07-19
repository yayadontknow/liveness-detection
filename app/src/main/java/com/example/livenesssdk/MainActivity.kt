package com.example.livenesssdk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.face.Face
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var instructionsText: TextView
    private lateinit var rootLayout: View

    // --- Camera & Analysis ---
    private var camera: Camera? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // --- Sound ---
    private var mediaPlayer: MediaPlayer? = null

    // --- State Management ---
    private enum class AppState { ID_VERIFICATION, LIVENESS_CHALLENGE, FINISHED }
    private var currentState = AppState.ID_VERIFICATION

    @Volatile
    private var isProcessingFrame = false

    // --- ID Verification State ---
    private var lastDetectedIdBox: RectF? = null
    private val circleStates = MutableList(6) { CircleState.INCOMPLETE }
    private val isCheckingHologram = BooleanArray(6) { false }
    private var lastIdCardCrop: Bitmap? = null
    private var activeCircleIndex: Int? = null

    // --- Liveness Challenge State ---
    private lateinit var colorFlasher: ColorFlasher
    private lateinit var livenessDetector: LivenessDetector

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1001
        private const val TAG = "MainActivity"
        private const val BRIGHTNESS_THRESHOLD = 230
        private const val BRIGHT_PIXEL_PERCENTAGE_THRESHOLD = 0.25
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- Initialize Views ---
        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        instructionsText = findViewById(R.id.instructions_text)
        rootLayout = findViewById(R.id.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // --- Initialize Sound Player ---
        mediaPlayer = MediaPlayer.create(this, R.raw.liveness_start_sound)

        // --- Initialize Liveness Components ---
        colorFlasher = ColorFlasher(rootLayout)
        livenessDetector = LivenessDetector { isLive ->
            runOnUiThread {
                colorFlasher.stop()
                currentState = AppState.FINISHED
                showFinalResult(isLive, if (isLive) "Liveness Verified" else "Liveness Check Failed")
            }
        }

        // --- Start Camera ---
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also {
                when (currentState) {
                    AppState.ID_VERIFICATION -> it.setAnalyzer(cameraExecutor, setupIdCardAnalyzer())
                    AppState.LIVENESS_CHALLENGE -> it.setAnalyzer(cameraExecutor, setupLivenessAnalyzer())
                    else -> {}
                }
            }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            camera?.cameraControl?.enableTorch(currentState == AppState.ID_VERIFICATION)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // =================================================================================
    // --- State 1: ID CARD VERIFICATION ---
    // =================================================================================

    private fun setupIdCardAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            if (isProcessingFrame || currentState != AppState.ID_VERIFICATION) {
                imageProxy.close()
                return@Analyzer
            }
            isProcessingFrame = true

            val currentFrameBitmap = imageProxy.toBitmap()?.rotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            imageProxy.close()

            if (currentFrameBitmap == null) {
                isProcessingFrame = false
                return@Analyzer
            }

            val idCardCrop = cropBitmapByGuideBox(currentFrameBitmap)
            lastIdCardCrop = idCardCrop

            if (idCardCrop != null) {
                processAndSendIdCard(idCardCrop)
            } else {
                runOnUiThread {
                    instructionsText.text = "Position ID card in the box"
                    overlayView.clearResults()
                    if (activeCircleIndex != null || circleStates.any { it != CircleState.INCOMPLETE }) {
                        resetHologramState()
                    }
                }
                isProcessingFrame = false
            }
        }
    }

    private fun processAndSendIdCard(idCardCrop: Bitmap) {
        val finalBitmap = Bitmap.createScaledBitmap(idCardCrop, 640, 640, true)
        val outputStream = ByteArrayOutputStream().apply {
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, this)
        }

        NetworkClient.detectIdCard(outputStream.toByteArray()) { response, _ ->
            handleIdCardResponse(response, lastIdCardCrop)
        }
    }

    private fun handleIdCardResponse(jsonResponse: String?, bitmapToAnalyze: Bitmap?) {
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

            manageHologramState(bitmapToAnalyze, lastDetectedIdBox!!)
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse ID card JSON", e)
            isProcessingFrame = false
        }
    }

    private fun manageHologramState(bitmap: Bitmap, boxOnScreen: RectF) {
        if (activeCircleIndex == null) {
            val incompleteIndices = circleStates.mapIndexedNotNull { index, state -> if (state == CircleState.INCOMPLETE) index else null }
            if (incompleteIndices.isNotEmpty()) {
                activeCircleIndex = incompleteIndices.random(Random(System.currentTimeMillis()))
                circleStates[activeCircleIndex!!] = CircleState.ACTIVE
                runOnUiThread { instructionsText.text = "Create a reflection on the active circle" }
            } else {
                isProcessingFrame = false
                return
            }
        }

        updateOverlay()

        val activeIndex = activeCircleIndex
        if (activeIndex != null && !isCheckingHologram[activeIndex] && boxOnScreen.height() > 0) {
            val dynamicRadius = (boxOnScreen.height() / 3f) / 2f
            val insetMargin = dynamicRadius
            val pointsOnScreen = listOf(
                PointF(boxOnScreen.left + insetMargin, boxOnScreen.top + insetMargin), PointF(boxOnScreen.centerX(), boxOnScreen.top + insetMargin), PointF(boxOnScreen.right - insetMargin, boxOnScreen.top + insetMargin),
                PointF(boxOnScreen.left + insetMargin, boxOnScreen.bottom - insetMargin), PointF(boxOnScreen.centerX(), boxOnScreen.bottom - insetMargin), PointF(boxOnScreen.right - insetMargin, boxOnScreen.bottom - insetMargin)
            )

            val point = pointsOnScreen[activeIndex]
            val regionOnScreen = RectF(point.x - dynamicRadius, point.y - dynamicRadius, point.x + dynamicRadius, point.y + dynamicRadius)
            val regionOnBitmap = mapPreviewBoxToBitmapBox(regionOnScreen, bitmap.width, bitmap.height)

            if (hasReflection(bitmap, regionOnBitmap)) {
                isCheckingHologram[activeIndex] = true
                performHologramCheck(activeIndex)
            } else {
                isProcessingFrame = false
            }
        } else {
            isProcessingFrame = false
        }
    }

    private fun performHologramCheck(index: Int) {
        val idCardImage = lastIdCardCrop ?: run {
            Log.e(TAG, "ID card image is null")
            isCheckingHologram[index] = false
            isProcessingFrame = false
            return
        }

        val finalBitmap = Bitmap.createScaledBitmap(idCardImage, 640, 640, true)
        val outputStream = ByteArrayOutputStream().apply {
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, this)
        }

        NetworkClient.detectHologram(outputStream.toByteArray()) { response, _ ->
            val numDetections = try {
                if (response == null) 0 else JSONObject(response).getJSONArray("detections").length()
            } catch (e: JSONException) { 0 }

            val isCorner = index in listOf(0, 2, 3, 5)
            val checkPassed = if (isCorner) numDetections == 0 else numDetections == 1

            if (checkPassed) {
                circleStates[index] = CircleState.SUCCESS
                activeCircleIndex = null
                checkForCompletion()
            } else {
                circleStates[index] = CircleState.FAILURE
                Handler(Looper.getMainLooper()).postDelayed({
                    if (circleStates[index] == CircleState.FAILURE) {
                        circleStates[index] = CircleState.ACTIVE
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
                transitionToLivenessState()
            }
        }
    }

    private fun resetHologramState() {
        activeCircleIndex = null
        circleStates.fill(CircleState.INCOMPLETE)
        isCheckingHologram.fill(false)
        updateOverlay()
    }

    private fun updateOverlay() {
        lastDetectedIdBox?.let { box ->
            val result = OverlayView.DetectionResult(box, "ID Found", circleStates.toList())
            runOnUiThread { overlayView.setResults(result) }
        }
    }

    // =================================================================================
    // --- State 2: LIVENESS CHALLENGE ---
    // =================================================================================

    private fun transitionToLivenessState() {
        currentState = AppState.LIVENESS_CHALLENGE
        isProcessingFrame = true // Prevent ID analysis

        // 1. Update UI and play sound
        instructionsText.text = "Get ready for liveness check..."
        mediaPlayer?.start() // Play sound cue
        overlayView.clearResults()
        camera?.cameraControl?.enableTorch(false)

        // 2. Switch camera after a delay
        Handler(Looper.getMainLooper()).postDelayed({
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            livenessDetector.reset()
            bindCameraUseCases()
            colorFlasher.start()
            instructionsText.text = "Look at the camera"
            isProcessingFrame = false
        }, 1500)
    }

    private fun setupLivenessAnalyzer(): ImageAnalysis.Analyzer {
        val cameraAnalyzer = CameraAnalyzer(this) { bitmap, face ->
            if (currentState == AppState.LIVENESS_CHALLENGE) {
                livenessDetector.processFrame(bitmap, face, colorFlasher.currentColor)
            }
        }
        return ImageAnalysis.Analyzer { imageProxy ->
            if (currentState != AppState.LIVENESS_CHALLENGE) {
                imageProxy.close()
                return@Analyzer
            }
            // Liveness analyzer manages its own isProcessingFrame and closes its own proxy
            cameraAnalyzer.analyze(imageProxy)
        }
    }

    // =================================================================================
    // --- HELPER FUNCTIONS ---
    // =================================================================================

    private fun showFinalResult(isSuccess: Boolean, message: String) {
        val title = if (isSuccess) "Verification Complete" else "Verification Failed"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun hasReflection(bitmap: Bitmap, region: Rect): Boolean {
        if (region.width() <= 0 || region.height() <= 0) return false
        region.intersect(0, 0, bitmap.width, bitmap.height)
        val totalPixels = region.width() * region.height()
        if (totalPixels == 0) return false

        val pixels = IntArray(totalPixels)
        try {
            bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pixels from region $region", e)
            return false
        }

        var brightPixelCount = 0
        for (color in pixels) {
            val brightness = (android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color)) / 3
            if (brightness > BRIGHTNESS_THRESHOLD) {
                brightPixelCount++
            }
        }
        val percentage = brightPixelCount.toFloat() / totalPixels.toFloat()
        return percentage > BRIGHT_PIXEL_PERCENTAGE_THRESHOLD
    }

    private fun cropBitmapByGuideBox(sourceBitmap: Bitmap): Bitmap? {
        val guideBox = overlayView.getGuideBox()
        val cropRect = mapPreviewBoxToBitmapBox(guideBox, sourceBitmap.width, sourceBitmap.height)
        return try {
            Bitmap.createBitmap(sourceBitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop bitmap with rect: $cropRect", e)
            null
        }
    }

    private fun mapPreviewBoxToBitmapBox(previewBox: RectF, bitmapWidth: Int, bitmapHeight: Int): Rect {
        val previewRatio = previewView.width.toFloat() / previewView.height.toFloat()
        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()

        val scale: Float
        var offsetX = 0f
        var offsetY = 0f

        if (previewRatio > bitmapRatio) {
            scale = previewView.width.toFloat() / bitmapWidth.toFloat()
            val newHeight = bitmapHeight.toFloat() * scale
            offsetY = (previewView.height.toFloat() - newHeight) / 2f
        } else {
            scale = previewView.height.toFloat() / bitmapHeight.toFloat()
            val newWidth = bitmapWidth.toFloat() * scale
            offsetX = (previewView.width.toFloat() - newWidth) / 2f
        }

        val left = ((previewBox.left - offsetX) / scale).toInt()
        val top = ((previewBox.top - offsetY) / scale).toInt()
        val right = ((previewBox.right - offsetX) / scale).toInt()
        val bottom = ((previewBox.bottom - offsetY) / scale).toInt()

        return Rect(left, top, right, bottom)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun ImageProxy.toBitmap(): Bitmap? {
        val image = this.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unsupported image format: ${image.format}")
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

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
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
        if (::colorFlasher.isInitialized) {
            colorFlasher.stop()
        }
        // Release media player resources
        mediaPlayer?.release()
        mediaPlayer = null
    }
}