package com.example.livenesssdk


import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import java.util.concurrent.Executors

class CameraAnalyzer(
    private val context: Context,
    private val onFaceDetected: (Bitmap, Face) -> Unit
) : ImageAnalysis.Analyzer {

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        FaceDetectorHelper.detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val bitmap = imageProxy.toBitmap() // Extension needed
                    onFaceDetected(bitmap, faces[0])
                }
                imageProxy.close()
            }
            .addOnFailureListener {
                imageProxy.close()
            }
    }
}