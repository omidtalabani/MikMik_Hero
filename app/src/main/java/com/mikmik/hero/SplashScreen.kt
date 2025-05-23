package com.mikmik.hero

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onSplashScreenComplete: () -> Unit) {
    LaunchedEffect(key1 = true) {
        delay(2000) // 2 seconds delay
        onSplashScreenComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0077B6)), // Dark blue background, change color as needed
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "MikMik Hero",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}