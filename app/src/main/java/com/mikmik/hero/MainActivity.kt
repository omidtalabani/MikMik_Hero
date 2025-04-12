package com.mikmik.hero

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.mikmik.hero.ui.theme.MikMikDeliveryTheme

class MainActivity : ComponentActivity(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var connectivityManager: ConnectivityManager

    private var webViewId = 100001 // Custom ID for finding WebView
    private var gpsDialogShowing = false
    private var internetDialogShowing = false
    private var gpsDialog: AlertDialog? = null
    private var internetDialog: AlertDialog? = null
    private var appContentSet = false

    // Only keep the WebView reference for background service
    private var webViewReference: WebView? = null

    // GPS monitoring runnable with immediate detection of enabling
    private val gpsMonitorRunnable = object : Runnable {
        override fun run() {
            // Check if GPS is enabled
            if (!isGpsEnabled()) {
                if (!gpsDialogShowing) {
                    showEnableLocationDialog()
                }
            } else {
                // GPS is now enabled, dismiss dialog if it's showing
                dismissGpsDialog()
            }
            // Schedule next check
            Handler(Looper.getMainLooper()).postDelayed(this, 1000) // Check every second for faster response
        }
    }

    // Network callback with immediate actions
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            // Internet is now available, dismiss dialog and continue app flow
            runOnUiThread {
                dismissInternetDialog()
                // If GPS is also enabled and we were showing a dialog, proceed
                if (isGpsEnabled() && (gpsDialogShowing || internetDialogShowing)) {
                    continueAppFlow()
                }
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            // Internet connection lost
            runOnUiThread {
                showInternetRequiredDialog()
            }
        }
    }

    // Handle new intents, especially from notifications
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Set this intent as the current intent
        setIntent(intent)

        // Check if we have a target URL from a notification
        val targetUrl = intent.getStringExtra("TARGET_URL")
        if (targetUrl != null) {
            Log.d("MainActivity", "Received TARGET_URL: $targetUrl")
            // Find existing WebView and load the URL
            findViewById<WebView>(webViewId)?.loadUrl(targetUrl)
        }
    }

    // Method to start background service
    private fun startBackgroundService() {
        // Extract driver_id from WebView cookies and store it in the service
        webViewReference?.let { webView ->
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://mikmik.site/heroes")

            if (cookies != null) {
                for (cookie in cookies.split(";")) {
                    val trimmedCookie = cookie.trim()
                    if (trimmedCookie.startsWith("driver_id=")) {
                        val driverId = trimmedCookie.substring("driver_id=".length)
                        // Set the driver ID in the service
                        CookieSenderService.setDriverId(driverId)
                        break
                    }
                }
            }
        }

        // Start the background service
        val serviceIntent = Intent(this, CookieSenderService::class.java)

        // On Android 8.0+, start as foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure window to respect system windows like the status bar
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize system services
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "order_notifications"
            val channel = NotificationChannel(
                channelId,
                "Order Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Start GPS monitoring with faster checks (every second)
        Handler(Looper.getMainLooper()).post(gpsMonitorRunnable)

        // First check if GPS and internet are enabled
        if (!isGpsEnabled()) {
            showEnableLocationDialog()
        } else if (!isInternetConnected()) {
            showInternetRequiredDialog()
        } else {
            // Both are enabled, continue with normal app flow
            continueAppFlow()
        }
    }

    private fun isGpsEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun isInternetConnected(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun showEnableLocationDialog() {
        if (gpsDialogShowing) return

        gpsDialogShowing = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Using the standard location settings intent
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
                return
            } catch (e: Exception) {
                e.printStackTrace()
                // Fall back to normal dialog if panel isn't available
            }
        }

        // For older versions or if panel fails, use the regular dialog
        gpsDialog = AlertDialog.Builder(this)
            .setTitle("GPS Required")
            .setMessage("This app requires GPS to be enabled for location tracking. Please enable location services to continue.")
            .setCancelable(false)
            .setPositiveButton("Enable GPS") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .create()

        gpsDialog?.setOnDismissListener {
            gpsDialogShowing = false
        }

        gpsDialog?.show()
    }

    private fun dismissGpsDialog() {
        if (gpsDialogShowing && gpsDialog != null && gpsDialog!!.isShowing) {
            try {
                gpsDialog?.dismiss()
            } catch (e: Exception) {
                // Ignore if dialog is no longer showing
            }
            gpsDialogShowing = false

            // If internet is also connected, continue the app flow
            if (isInternetConnected()) {
                continueAppFlow()
            }
        }
    }

    private fun showInternetRequiredDialog() {
        if (internetDialogShowing) return

        internetDialogShowing = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Use the wireless settings instead
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                return
            } catch (e: Exception) {
                e.printStackTrace()
                // Fall back to normal dialog
            }
        }

        internetDialog = AlertDialog.Builder(this)
            .setTitle("Internet Required")
            .setMessage("This app requires an active internet connection. Please enable Wi-Fi or mobile data to continue.")
            .setCancelable(false)
            .setPositiveButton("Network Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .create()

        internetDialog?.setOnDismissListener {
            internetDialogShowing = false
        }

        internetDialog?.show()
    }

    private fun dismissInternetDialog() {
        if (internetDialogShowing && internetDialog != null && internetDialog!!.isShowing) {
            try {
                internetDialog?.dismiss()
            } catch (e: Exception) {
                // Ignore if dialog is not showing anymore
            }
            internetDialogShowing = false

            // If GPS is also enabled, continue the app flow
            if (isGpsEnabled()) {
                continueAppFlow()
            }
        }
    }

    private fun continueAppFlow() {
        // Only proceed if all requirements are met and we haven't set content yet
        if (!gpsDialogShowing && !internetDialogShowing && !appContentSet) {
            appContentSet = true

            // Check if we're coming back from the permission activity
            val skipSplash = intent.getBooleanExtra("SKIP_SPLASH", false)

            setContent {
                MikMikDeliveryTheme {
                    val showSplash = remember { mutableStateOf(!skipSplash) }

                    if (showSplash.value) {
                        SplashScreen {
                            // When splash screen completes, check permissions
                            if (areLocationPermissionsGranted()) {
                                // If permissions already granted, show main content
                                showSplash.value = false
                                // Start location updates
                                startLocationUpdates()
                            } else {
                                // If permissions not granted, start permission activity
                                startActivity(Intent(this, PermissionActivity::class.java))
                                finish()
                            }
                        }
                    } else {
                        MainContent()
                    }
                }
            }

            // If coming back from permission activity, start location updates
            if (skipSplash) {
                startLocationUpdates()
            }

            // Start the background service after WebView is created
            Handler(Looper.getMainLooper()).postDelayed({
                startBackgroundService()
            }, 3000) // Wait for WebView to initialize and load cookies
        }
    }

    private fun areLocationPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {
        try {
            // Check if we have permission before requesting updates
            if (areLocationPermissionsGranted()) {
                // Request location updates from GPS provider
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            5000, // Update every 5 seconds
                            10f,  // Or when moved 10 meters
                            this
                        )
                    }
                }

                // Also request from network provider for better accuracy
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            5000,
                            10f,
                            this
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            // Handle permission rejection
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // LocationListener implementation
    override fun onLocationChanged(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude

        // Find WebView and inject location data
        findViewById<WebView>(webViewId)?.evaluateJavascript(
            "javascript:updateLocation($latitude, $longitude)",
            null
        )
    }

    // Required by LocationListener interface
    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        // If GPS provider is disabled, show dialog
        if (provider == LocationManager.GPS_PROVIDER) {
            showEnableLocationDialog()
        }
    }

    override fun onResume() {
        super.onResume()

        // When returning from settings, check if enabled
        if (isGpsEnabled()) {
            dismissGpsDialog()
        }

        if (isInternetConnected()) {
            dismissInternetDialog()
        }

        // If both are enabled now, continue
        if (isGpsEnabled() && isInternetConnected()) {
            continueAppFlow()
        }

        // No need to start the service here, it runs independently
    }

    override fun onPause() {
        super.onPause()

        // No need to stop the service here, it should keep running
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dismiss any open dialogs
        dismissGpsDialog()
        dismissInternetDialog()

        // Clean up resources
        if (this::locationManager.isInitialized) {
            try {
                locationManager.removeUpdates(this)
            } catch (e: SecurityException) {
                // Handle security exception if permission was revoked
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (this::connectivityManager.isInitialized) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                // Ignore if callback wasn't registered
            }
        }

        // Remove callbacks
        Handler(Looper.getMainLooper()).removeCallbacks(gpsMonitorRunnable)

        // No need to clean up the service, system manages it
    }

    @Composable
    fun MainContent() {
        // Check if we were launched from a notification with a specific URL
        val targetUrl = remember {
            intent.getStringExtra("TARGET_URL") ?: "https://mikmik.site/heroes"
        }

        Scaffold(
            modifier = Modifier.statusBarsPadding()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                MikMikWebView(url = targetUrl)
            }
        }
    }

    @Composable
    fun MikMikWebView(url: String) {
        var isLoading by remember { mutableStateOf(true) }
        val webViewRef = remember { mutableStateOf<WebView?>(null) }
        val swipeRefreshState = rememberSwipeRefreshState(isRefreshing = isLoading)
        val context = LocalContext.current

        SwipeRefresh(
            state = swipeRefreshState,
            onRefresh = {
                performHapticFeedback(context) // Haptic feedback for refresh
                webViewRef.value?.reload()
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // Set custom ID to find WebView later
                            id = webViewId

                            // Save reference to the WebView for background service
                            webViewReference = this

                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                setSupportZoom(true)
                                cacheMode = WebSettings.LOAD_DEFAULT
                                databaseEnabled = true
                            }

                            // Enable haptic feedback
                            setHapticFeedbackEnabled(true)

                            // Add JavaScript interface for haptic feedback
                            addJavascriptInterface(object {
                                @android.webkit.JavascriptInterface
                                fun onElementClicked(isClickable: Boolean) {
                                    if (isClickable) {
                                        performHapticFeedback(context)
                                    }
                                }
                            }, "HapticFeedback")

                            // WebViewClient implementation
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    swipeRefreshState.isRefreshing = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    swipeRefreshState.isRefreshing = false

                                    // Inject JavaScript for clickable detection and location handling
                                    view?.evaluateJavascript("""
                                        (function() {
                                            // Helper function to check if element is clickable
                                            function isElementClickable(element) {
                                                if (!element) return false;
                                                
                                                // Check for common clickable properties
                                                const clickableTag = ['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA'];
                                                if (clickableTag.includes(element.tagName)) return true;
                                                
                                                // Check for role attributes
                                                if (element.getAttribute('role') === 'button') return true;
                                                
                                                // Check for event listeners (approximate check)
                                                const style = window.getComputedStyle(element);
                                                if (style.cursor === 'pointer') return true;
                                                
                                                // Check for onclick attributes
                                                if (element.hasAttribute('onclick') || 
                                                    element.hasAttribute('ng-click') || 
                                                    element.hasAttribute('@click') ||
                                                    element.onclick) return true;
                                                
                                                // Check for known class names that indicate clickability
                                                const classList = Array.from(element.classList);
                                                if (classList.some(cls => 
                                                    cls.includes('btn') || 
                                                    cls.includes('button') || 
                                                    cls.includes('clickable') || 
                                                    cls.includes('link'))) return true;
                                                    
                                                return false;
                                            }
                                            
                                            // Add click capture to the document
                                            document.addEventListener('click', function(e) {
                                                // Check if the clicked element or any of its parents are clickable
                                                let target = e.target;
                                                let isClickable = false;
                                                
                                                // Check target and its ancestors for clickability
                                                while (target && target !== document && !isClickable) {
                                                    isClickable = isElementClickable(target);
                                                    if (!isClickable) {
                                                        target = target.parentElement;
                                                    }
                                                }
                                                
                                                // Call Android with the result
                                                window.HapticFeedback.onElementClicked(isClickable);
                                            }, true);
                                            
                                            // Define function to receive location updates
                                            window.updateLocation = function(latitude, longitude) {
                                                console.log("Location updated: " + latitude + ", " + longitude);
                                                
                                                // Create a custom event that the website can listen for
                                                const event = new CustomEvent('locationUpdate', {
                                                    detail: { latitude, longitude }
                                                });
                                                document.dispatchEvent(event);
                                            };
                                        })();
                                    """.trimIndent(), null)
                                }

                                // Handle URL schemes (existing code)
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    request?.let {
                                        val uri = it.url
                                        val url = uri.toString()

                                        // Handle special URL schemes - provide haptic feedback before attempting to open
                                        performHapticFeedback(context)

                                        // Check for WhatsApp links
                                        if (url.startsWith("whatsapp:") || url.contains("wa.me")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW)
                                                    intent.data = Uri.parse("market://details?id=com.whatsapp")
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                } catch (e2: Exception) {
                                                    return false
                                                }
                                            }
                                        }

                                        // Check for Viber links
                                        else if (url.startsWith("viber:") || url.startsWith("viber://")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW)
                                                    intent.data = Uri.parse("market://details?id=com.viber.voip")
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                } catch (e2: Exception) {
                                                    return false
                                                }
                                            }
                                        }

                                        // Check for Telegram links
                                        else if (url.startsWith("tg:") || url.contains("t.me/")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW)
                                                    intent.data = Uri.parse("market://details?id=org.telegram.messenger")
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                } catch (e2: Exception) {
                                                    return false
                                                }
                                            }
                                        }

                                        // Handle email links
                                        else if (url.startsWith("mailto:")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                return false
                                            }
                                        }

                                        // Handle phone links
                                        else if (url.startsWith("tel:")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_DIAL)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                return false
                                            }
                                        }

                                        // Handle SMS links
                                        else if (url.startsWith("sms:")) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                intent.data = uri
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                return true
                                            } catch (e: Exception) {
                                                return false
                                            }
                                        }

                                        // For all other URLs, let the WebView handle them
                                        return false
                                    }

                                    return super.shouldOverrideUrlLoading(view, request)
                                }
                            }

                            // Save reference to WebView
                            webViewRef.value = this

                            loadUrl(url)
                        }
                    }
                )

                // Show loading indicator
                if (isLoading && !swipeRefreshState.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }

        // Handle back button press with haptic feedback
        BackHandler {
            performHapticFeedback(context) // Haptic feedback for back button
            webViewRef.value?.let { webView ->
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Can't go back further in WebView history, so exit the app
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }
}

// Helper function for haptic feedback
private fun performHapticFeedback(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        // For API 26 and above
        vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        // For older APIs
        @Suppress("DEPRECATION")
        vibrator.vibrate(20)
    }
}