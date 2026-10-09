package dev.local.record.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Restrained green accent with official Material 3 Expressive typography, shapes and motion. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecordTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFA5D0AE),
            secondary = Color(0xFFBCCCBF),
            surface = Color(0xFF111813),
            background = Color(0xFF111813),
            surfaceContainer = Color(0xFF1D271F),
            surfaceContainerLow = Color(0xFF172019),
            onSurface = Color(0xFFE0E8DE),
            onSurfaceVariant = Color(0xFFC0CBBD)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF436651),
            secondary = Color(0xFF526355),
            surface = Color(0xFFF8FAF6),
            background = Color(0xFFF8FAF6),
            surfaceContainer = Color(0xFFEDF1EB),
            surfaceContainerLow = Color(0xFFF1F4EE),
            onSurface = Color(0xFF19221B),
            onSurfaceVariant = Color(0xFF536055)
        )
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}
