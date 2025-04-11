package com.mikmik.hero

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

class CookieSenderService(private val context: Context, private val webView: WebView) {
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private val serverBaseUrl = "https://mikmik.site/heroes/check_pending_orders.php"
    private var isRunning = false
    private val sendInterval = 15000L // 15 seconds

    // Keep track of the last notification time to avoid spamming toasts
    private var lastNotificationTime = 0L

    // Runnable that sends the cookie and reschedules itself
    private val cookieSenderRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            val driverId = getDriverIdCookie()
            if (driverId != null) {
                checkPendingOrders(driverId)
            }

            // Schedule next run
            handler.postDelayed(this, sendInterval)
        }
    }

    fun start() {
        if (isRunning) return

        isRunning = true
        handler.post(cookieSenderRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(cookieSenderRunnable)
    }

    private fun getDriverIdCookie(): String? {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("https://mikmik.site/heroes") ?: return null

        for (cookie in cookies.split(";")) {
            val trimmedCookie = cookie.trim()
            if (trimmedCookie.startsWith("driver_id=")) {
                return trimmedCookie.substring("driver_id=".length)
            }
        }
        return null
    }

    private fun checkPendingOrders(driverId: String) {
        // Create the URL with driver_id as a GET parameter
        val urlBuilder = serverBaseUrl.toHttpUrlOrNull()?.newBuilder() ?: return
        urlBuilder.addQueryParameter("driver_id", driverId)
        val url = urlBuilder.build()

        // Build the request
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        // Execute the request asynchronously
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Handle network error silently to avoid spamming the user
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val json = JSONObject(responseBody)
                        val success = json.optBoolean("success", false)
                        val message = json.optString("message", "Operation completed")

                        if (success) {
                            // Show toast on the UI thread, but only if we haven't shown one recently
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastNotificationTime > 60000) { // Only notify once per minute at most
                                lastNotificationTime = currentTime
                                handler.post {
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()

                                    // Also add haptic feedback if device supports it
                                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                    } else {
                                        @Suppress("DEPRECATION")
                                        vibrator.vibrate(200)
                                    }

                                    // Play notification sound
                                    val notification = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                                    val ringtone = android.media.RingtoneManager.getRingtone(context, notification)
                                    ringtone.play()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }
}