package com.repforge.healthbridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(primary = Color(0xFF99D5B5), primaryContainer = Color(0xFF164F39),
        surface = Color(0xFF101510), onSurface = Color(0xFFE0E4DC))
    else lightColorScheme(primary = Color(0xFF326B55), primaryContainer = Color(0xFFB5F1D2),
        surface = Color(0xFFF5FBF4), onSurface = Color(0xFF181D19))
    MaterialExpressiveTheme(colorScheme = colors, content = content)
}
