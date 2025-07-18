// file path: E:\Competitions\LivenessSDK\app\src\main\java\com\example\livenesssdk\CameraAnalyzer.kt
package com.example.livenesssdk

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
// Import the correct builder class
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.ByteArrayOutputStream

class CameraAnalyzer(
    private val context: Context,
    // The callback now receives a MediaPipe FaceLandmarkerResult
    private val onFaceDetected: (Bitmap, FaceLandmarkerResult) -> Unit
) : ImageAnalysis.Analyzer {

    // Get the MediaPipe detector instance
    private val faceLandmarker = MediaPipeFaceMeshHelper.getDetector(context)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap() // Convert the frame to a Bitmap
        if (bitmap == null) {
            imageProxy.close()
            return
        }

        try {
            // THE CORRECT WAY: Use BitmapImageBuilder to create an MPImage
            val mpImage = BitmapImageBuilder(bitmap).build()

            val result = faceLandmarker.detect(mpImage)

            // Check if any faces were detected
            if (result != null && result.faceLandmarks().isNotEmpty()) {
                // Pass the bitmap and the result to the callback
                onFaceDetected(bitmap, result)
            }
        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "MediaPipe face landmark detection failed", e)
        } finally {
            // IMPORTANT: Close the ImageProxy to allow the next frame to be processed
            imageProxy.close()
        }
    }
}

@OptIn(ExperimentalGetImage::class)
fun ImageProxy.toBitmap(): Bitmap? {
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
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 90, out)
    val imageBytes = out.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

    val matrix = Matrix()
    matrix.postRotate(this.imageInfo.rotationDegrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}