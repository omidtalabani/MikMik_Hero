package com.mikmik.hero

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mikmik.hero.ui.theme.MikMikDeliveryTheme

class PermissionActivity : ComponentActivity() {
    private var permissionDialogShown = false

    // For requesting location permissions
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.any { it.value }) {
            // At least one permission granted, proceed to background permission if needed
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                requestBackgroundPermission()
            } else {
                proceedToMainActivity()
            }
        } else {
            // No permissions granted, show explanation
            showPermissionExplanationDialog()
        }
    }

    // For requesting background location permission (Android 10+)
    private val requestBackgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Even if denied, we can still proceed as background is optional
        proceedToMainActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MikMikDeliveryTheme {
                PermissionScreen(
                    onRequestPermission = { checkAndRequestLocationPermissions() }
                )
            }
        }

        // Start permission check immediately
        checkAndRequestLocationPermissions()
    }

    override fun onResume() {
        super.onResume()

        // When returning from Settings, check permissions again
        if (permissionDialogShown) {
            permissionDialogShown = false
            checkAndRequestLocationPermissions()
        }
    }

    private fun checkAndRequestLocationPermissions() {
        val foregroundPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (foregroundPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            // Foreground location permissions are granted

            // Check for background permission on Android 10+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED) {
                    // All permissions granted
                    proceedToMainActivity()
                } else {
                    // Request background location
                    requestBackgroundPermission()
                }
            } else {
                // On Android 9 and below, proceed directly
                proceedToMainActivity()
            }
        } else {
            // Need to request foreground location permissions
            requestPermissionsLauncher.launch(foregroundPermissions)
        }
    }

    private fun requestBackgroundPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Background Location Access")
                .setMessage("This app needs access to your location all the time to provide delivery tracking services. Please select 'Allow all the time' on the next screen.")
                .setPositiveButton("Continue") { _, _ ->
                    requestBackgroundPermissionLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showPermissionExplanationDialog() {
        permissionDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("Location Permission Required")
            .setMessage("MikMik Hero needs location permission to function properly. Please grant location access to continue using the app.")
            .setPositiveButton("App Settings") { _, _ ->
                // Take the user to app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Try Again") { _, _ ->
                // Request permissions again
                checkAndRequestLocationPermissions()
            }
            .setCancelable(false)
            .show()
    }

    private fun proceedToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("SKIP_SPLASH", true))
        finish()
    }
}

@Composable
fun PermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0077B6)), // Use same color as splash screen for consistency
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Location Permission Required",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "MikMik Hero needs location permission to track deliveries and provide optimal service.",
                fontSize = 18.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(
                    text = "Grant Permission",
                    fontSize = 18.sp
                )
            }
        }
    }
}