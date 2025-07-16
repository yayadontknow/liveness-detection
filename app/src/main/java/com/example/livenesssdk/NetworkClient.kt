package com.example.livenesssdk

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException

object NetworkClient {
    fun sendLivenessPayload(payload: String) {
        val request = Request.Builder()
            .url("https://your-backend.com/api/liveness")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), payload))
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Network", "Failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d("Network", "Response: ${response.body?.string()}")
            }
        })
    }
}
