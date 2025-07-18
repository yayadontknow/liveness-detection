// file path: E:\Competitions\LivenessSDK\app\src\main\java\com\example\livenesssdk\MediaPipeFaceMeshHelper.kt
package com.example.livenesssdk

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

object MediaPipeFaceMeshHelper {

    private const val TAG = "MediaPipeFaceMeshHelper"
    private const val MODEL_ASSET_PATH = "face_landmarker.task"

    private var faceLandmarker: FaceLandmarker? = null

    // Creates and returns a FaceLandmarker instance
    fun getDetector(context: Context): FaceLandmarker {
        if (faceLandmarker == null) {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .build()

            try {
                // This is the call that is likely crashing
                faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            } catch (e: Exception) {
                // By catching the exception, we prevent the SIGSEGV and provide a clear log.
                Log.e(TAG, "MediaPipe failed to load the model. Check if '$MODEL_ASSET_PATH' exists in assets.", e)
                // Re-throw as a more specific exception that our Activity can handle.
                throw IllegalStateException("Failed to create FaceLandmarker. See logs for details.", e)
            }
        }
        return faceLandmarker!!
    }

    // Closes the landmarker to release resources
    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}