package com.mikmik.hero

import android.R
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

class SSEService {

    private val client = OkHttpClient()

    fun connectSSE(driverId: String) {
        val request = Request.Builder()
            .url("https://mikmik.site/heroes/sse_endpoint.php?driver_id=$driverId")
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
        val eventSource = EventSource.Factory().newEventSource(request, eventSourceListener)
    }

    private fun handleNewOrders(data: String) {
        // Parse the JSON data and show a notification if there are pending orders
        // Example: [{"order_id": "1", "user_id": "100", "assignment_time": "2025-04-10"}]
        val orders = parseOrdersFromJson(data)
        if (orders.isNotEmpty()) {
            sendNotification("You have new pending orders")
        }
    }

    private fun sendNotification(message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, "order_notifications")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setContentTitle("New Pending Orders")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        notificationManager.notify(1, builder.build())
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
}

data class Order(
    val orderId: String,
    val userId: String,
    val assignmentTime: String
)
