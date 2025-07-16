package com.example.livenesssdk

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HMACUtil {
    fun generate(data: String, key: String): String {
        val hmacKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        val hash = mac.doFinal(data.toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
