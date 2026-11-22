package com.kalibox.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun KaliTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF367BF0),
            secondary = Color(0xFF00BFA5),
            background = Color(0xFF0B0F14),
            surface = Color(0xFF131A22),
            surfaceVariant = Color(0xFF1B2430),
            error = Color(0xFFFF6B6B),
        ),
        content = content,
    )
}
