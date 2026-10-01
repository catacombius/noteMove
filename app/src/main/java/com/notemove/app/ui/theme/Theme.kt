package com.notemove.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.notemove.core.model.TrackColors

object NM {
    val bg = Color(0xFF141414)
    val surface = Color(0xFF1F1F1F)
    val surfaceHigh = Color(0xFF2A2A2A)
    val pad = Color(0xFF2E2E2E)
    val padDim = Color(0xFF232323)
    val line = Color(0xFF3A3A3A)
    val text = Color(0xFFECECEC)
    val textDim = Color(0xFF9A9A9A)
    val accent = Color(0xFFFF764D)
    val play = Color(0xFF3DDC84)
    val record = Color(0xFFFF4040)
    val queued = Color(0xFFFFD54F)
    val solo = Color(0xFF8BC5FF)

    fun track(index: Int) = Color(TrackColors.argb(index))
}

private val scheme = darkColorScheme(
    primary = NM.accent,
    onPrimary = Color.Black,
    secondary = Color(0xFF8BC5FF),
    background = NM.bg,
    onBackground = NM.text,
    surface = NM.surface,
    onSurface = NM.text,
    surfaceVariant = NM.surfaceHigh,
    onSurfaceVariant = NM.textDim,
    surfaceContainer = NM.surface,
    surfaceContainerHigh = NM.surfaceHigh,
    surfaceContainerHighest = NM.surfaceHigh,
    outline = NM.line,
    error = NM.record,
)

private val typography = Typography().let { t ->
    t.copy(
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun NoteMoveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
