package com.example.livenesssdk

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object NetworkClient {
    private val client = OkHttpClient()
    private const val TAG = "NetworkClient"
    private const val BASE_URL = "http://192.168.0.5"

    fun detectIdCard(imageBytes: ByteArray, callback: (response: String?, error: String?) -> Unit) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "id_card.jpg", imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url("$BASE_URL:8000/detect/identity-card/") // Updated URL
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(getCallback(callback, "ID Card"))
    }

    // --- New Function ---
    fun detectHologram(imageBytes: ByteArray, callback: (response: String?, error: String?) -> Unit) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "hologram.jpg", imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url("$BASE_URL:8000/detect/hologram/") // New Hologram URL
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(getCallback(callback, "Hologram"))
    }

    private fun getCallback(callback: (String?, String?) -> Unit, type: String): Callback {
        return object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "$type detection failed", e)
                callback(null, e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    Log.d(TAG, "$type detection success: $responseBody")
                    callback(responseBody, null)
                } else {
                    val errorMsg = "Server error: ${response.code} ${response.message}. Body: $responseBody"
                    Log.e(TAG, errorMsg)
                    callback(null, errorMsg)
                }
            }
        }
    }
}