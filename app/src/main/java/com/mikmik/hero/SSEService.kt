package com.mikmik.hero

import android.app.NotificationManager
import android.content.Context
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray

class SSEService(private val context: Context? = null) {

    private val client = OkHttpClient()
    private var eventSource: EventSource? = null

    fun connectSSE(driverId: String) {
        // First try to get driver_id from cookies
        val cookieManager = CookieManager.getInstance()
        val cookieDriverId = getCookieValue(cookieManager.getCookie("https://mikmik.site"), "driver_id")

        // Use cookie driver_id if exists, otherwise use the parameter
        val finalDriverId = cookieDriverId ?: driverId

        // If no driver ID was found (neither in cookie nor parameter), don't connect
        if (finalDriverId.isEmpty()) {
            println("No driver_id available, not connecting to SSE")
            return
        }

        val request = Request.Builder()
            .url("https://mikmik.site/heroes/sse_endpoint.php?driver_id=$finalDriverId")
            .build()

        val eventSourceListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                super.onOpen(eventSource, response)
                println("SSE Connection opened")
            }

            override fun onEvent(
                eventSource: EventSource, id: String?, type: String?,
                data: String
            ) {
                // Handle the event here
                // This will be called every time data is sent from the server
                println("New order data: $data")

                // Parse the data and show a notification if there are new orders
                handleNewOrders(data)
            }

            override fun onClosed(eventSource: EventSource) {
                super.onClosed(eventSource)
                println("SSE Connection closed")
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                super.onFailure(eventSource, t, response)
                println("SSE Connection failed: ${t?.message}")
            }
        }

        // Start the SSE connection
        eventSource = EventSources.createFactory(client).newEventSource(request, eventSourceListener)
    }

    // Helper function to extract specific cookie value
    private fun getCookieValue(cookieString: String?, cookieName: String): String? {
        if (cookieString == null) return null

        val cookies = cookieString.split(";")
        for (cookie in cookies) {
            val parts = cookie.trim().split("=")
            if (parts.size == 2 && parts[0] == cookieName) {
                return parts[1]
            }
        }
        return null
    }

    private fun handleNewOrders(data: String) {
        // Parse the JSON data and show a notification if there are pending orders
        // Example: [{"order_id": "1", "user_id": "100", "assignment_time": "2025-04-10"}]
        val orders = parseOrdersFromJson(data)
        if (orders.isNotEmpty() && context != null) {
            sendNotification("You have new pending orders")
        }
    }

    private fun sendNotification(message: String) {
        context?.let { ctx ->
            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = NotificationCompat.Builder(ctx, "order_notifications")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("New Pending Orders")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            notificationManager.notify(1, builder.build())
        }
    }

    private fun parseOrdersFromJson(data: String): List<Order> {
        // Parse the JSON data into a list of Order objects
        val orders = mutableListOf<Order>()
        try {
            val jsonArray = JSONArray(data)
            for (i in 0 until jsonArray.length()) {
                val orderJson = jsonArray.getJSONObject(i)
                val order = Order(
                    orderJson.getString("order_id"),
                    orderJson.getString("user_id"),
                    orderJson.getString("assignment_time")
                )
                orders.add(order)
            }
        } catch (e: Exception) {
            println("Error parsing orders: ${e.message}")
        }
        return orders
    }

    fun close() {
        eventSource?.cancel()
    }
}

data class Order(
    val orderId: String,
    val userId: String,
    val assignmentTime: String
)