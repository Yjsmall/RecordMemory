package dev.local.record.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.local.record.settings.AppAppearance

/** Restrained green accent with official Material 3 Expressive typography, shapes and motion. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecordTheme(appearance: AppAppearance = AppAppearance.SYSTEM, dynamicColors: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
    }
    val context = LocalContext.current
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    val colors = if (dynamicColors && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        darkColorScheme(
            primary = Color(0xFFA5D0AE),
            onPrimary = Color(0xFF133822),
            primaryContainer = Color(0xFF2C4E37),
            onPrimaryContainer = Color(0xFFBCE9C6),
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
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD0E8D1),
            onPrimaryContainer = Color(0xFF12361D),
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
