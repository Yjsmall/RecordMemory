package dev.local.record.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.local.record.settings.AppAppearance

/** Warm neutral surfaces, ink-green accents and a shared, restrained type scale. */
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
            primary = Color(0xFFB4CFBF),
            onPrimary = Color(0xFF193D31),
            primaryContainer = Color(0xFF243F34),
            onPrimaryContainer = Color(0xFFD4E6DA),
            secondary = Color(0xFFBDC7BE),
            secondaryContainer = Color(0xFF303B34),
            onSecondaryContainer = Color(0xFFDCE5DD),
            surface = Color(0xFF151916),
            background = Color(0xFF151916),
            surfaceContainerLowest = Color(0xFF1E2420),
            surfaceContainer = Color(0xFF292F2A),
            surfaceContainerLow = Color(0xFF202621),
            surfaceContainerHigh = Color(0xFF303730),
            surfaceContainerHighest = Color(0xFF384039),
            onSurface = Color(0xFFE9EBE5),
            onSurfaceVariant = Color(0xFFADB7AD),
            outline = Color(0xFF778277),
            outlineVariant = Color(0xFF3D473E)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF315C49),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE2EDE4),
            onPrimaryContainer = Color(0xFF2C4B3B),
            secondary = Color(0xFF626B61),
            secondaryContainer = Color(0xFFE8EDE5),
            onSecondaryContainer = Color(0xFF3C493D),
            surface = Color(0xFFF5F5F0),
            background = Color(0xFFF5F5F0),
            surfaceContainerLowest = Color(0xFFFFFEFA),
            surfaceContainer = Color(0xFFECEEE7),
            surfaceContainerLow = Color(0xFFF0F1EA),
            surfaceContainerHigh = Color(0xFFE6E9E0),
            surfaceContainerHighest = Color(0xFFE0E4DA),
            onSurface = Color(0xFF222B24),
            onSurfaceVariant = Color(0xFF626E63),
            outline = Color(0xFF818B7E),
            outlineVariant = Color(0xFFDCE1D6)
        )
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = Typography(
            headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
            titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)),
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
