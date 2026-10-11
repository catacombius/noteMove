package com.spectraseq.app.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.spectraseq.core.model.TrackColors

/** A dark colour palette for the whole UI. */
data class Palette(
    val name: String,
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val pad: Color,
    val padDim: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val play: Color = Color(0xFF3DDC84),
    val record: Color = Color(0xFFFF4040),
    val queued: Color = Color(0xFFFFD54F),
    val solo: Color = Color(0xFF8BC5FF),
)

object Palettes {
    val EMBER = Palette(
        "Ember", bg = Color(0xFF141414), surface = Color(0xFF1F1F1F), surfaceHigh = Color(0xFF2A2A2A), pad = Color(0xFF2E2E2E),
        padDim = Color(0xFF232323), line = Color(0xFF3A3A3A), text = Color(0xFFECECEC), textDim = Color(0xFF9A9A9A), accent = Color(0xFFFF764D),
    )
    val MIDNIGHT = Palette(
        "Midnight", bg = Color(0xFF0F1420), surface = Color(0xFF171E2D), surfaceHigh = Color(0xFF212A3D), pad = Color(0xFF253047),
        padDim = Color(0xFF1A2232), line = Color(0xFF34405A), text = Color(0xFFE6ECF7), textDim = Color(0xFF8D9BB5), accent = Color(0xFF6EA8FF),
        solo = Color(0xFFB59CFF),
    )
    val NEON = Palette(
        "Neon", bg = Color(0xFF0A0A10), surface = Color(0xFF13131D), surfaceHigh = Color(0xFF1D1D2B), pad = Color(0xFF1F1F30),
        padDim = Color(0xFF161622), line = Color(0xFF2E2E46), text = Color(0xFFF1EEFF), textDim = Color(0xFF9C97C0), accent = Color(0xFF22E6FF),
        play = Color(0xFF39FF9C), record = Color(0xFFFF2E88), queued = Color(0xFFFFE14D), solo = Color(0xFFFF6BF3),
    )
    val OLED = Palette(
        "OLED", bg = Color(0xFF000000), surface = Color(0xFF0C0C0C), surfaceHigh = Color(0xFF181818), pad = Color(0xFF1C1C1C),
        padDim = Color(0xFF111111), line = Color(0xFF2A2A2A), text = Color(0xFFF2F2F2), textDim = Color(0xFF8C8C8C), accent = Color(0xFFFFB13D),
    )
    val GRAPHITE = Palette(
        "Graphite", bg = Color(0xFF1A1B1D), surface = Color(0xFF232427), surfaceHigh = Color(0xFF2E3034), pad = Color(0xFF34363B),
        padDim = Color(0xFF282A2E), line = Color(0xFF44474D), text = Color(0xFFE8E8E8), textDim = Color(0xFFA0A3A8), accent = Color(0xFFC8CDD4),
        solo = Color(0xFF9FD3C7),
    )
    val ALL = listOf(EMBER, MIDNIGHT, NEON, OLED, GRAPHITE)

    /** Quick accent choices; any colour can be picked with the hue / saturation picker. */
    val ACCENTS = listOf(
        Color(0xFFFF764D), Color(0xFFFFB13D), Color(0xFFF7E04A), Color(0xFF8BE04A), Color(0xFF3DDC97),
        Color(0xFF22E6FF), Color(0xFF6EA8FF), Color(0xFF9C7CFF), Color(0xFFE56BFF), Color(0xFFFF5C93),
    )
}

/** How track colours are drawn in the app (exported Live sets always keep Live's palette indices). */
enum class TrackColorSet(val label: String) { LIVE("Classic"), PASTEL("Pastel"), VIVID("Vivid") }

/**
 * App colours. Every value reads the current palette, so changing the theme recomposes the whole UI.
 * (The object keeps its historic short name.)
 */
object NM {
    var palette by mutableStateOf(Palettes.EMBER)
    /** Accent override chosen by the user, or null for the palette's own accent. */
    var accentOverride by mutableStateOf<Color?>(null)
    var trackSet by mutableStateOf(TrackColorSet.LIVE)

    val bg get() = palette.bg
    val surface get() = palette.surface
    val surfaceHigh get() = palette.surfaceHigh
    val pad get() = palette.pad
    val padDim get() = palette.padDim
    val line get() = palette.line
    val text get() = palette.text
    val textDim get() = palette.textDim
    val accent get() = accentOverride ?: palette.accent
    val play get() = palette.play
    val record get() = palette.record
    val queued get() = palette.queued
    val solo get() = palette.solo

    fun track(index: Int): Color {
        val base = Color(TrackColors.argb(index))
        return when (trackSet) {
            TrackColorSet.LIVE -> base
            TrackColorSet.PASTEL -> adjust(base, satMul = 0.55f, valueMin = 0.92f)
            TrackColorSet.VIVID -> adjust(base, satMul = 1.35f, valueMin = 0.85f)
        }
    }

    private fun adjust(c: Color, satMul: Float, valueMin: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hsv[1] = (hsv[1] * satMul).coerceIn(0f, 1f)
        hsv[2] = maxOf(hsv[2], valueMin)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}

/** Interface size (UI scale) per screen; 1 = Android's own size. */
object UiScale {
    var cover by mutableFloatStateOf(1f)
    var inner by mutableFloatStateOf(1f)
}

/** Loads and saves the appearance settings (theme, accent, track colours, interface size). */
object Appearance {
    private const val PREFS = "settings"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        NM.palette = Palettes.ALL.firstOrNull { it.name == p.getString("palette", null) } ?: Palettes.EMBER
        NM.accentOverride = if (p.contains("accent")) Color(p.getInt("accent", 0)) else null
        NM.trackSet = TrackColorSet.entries.firstOrNull { it.name == p.getString("track_colors", null) } ?: TrackColorSet.LIVE
        UiScale.cover = p.getFloat("ui_scale_cover", 1f)
        UiScale.inner = p.getFloat("ui_scale_inner", 1f)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("palette", NM.palette.name)
            NM.accentOverride?.let { putInt("accent", it.toArgb()) } ?: remove("accent")
            putString("track_colors", NM.trackSet.name)
            putFloat("ui_scale_cover", UiScale.cover)
            putFloat("ui_scale_inner", UiScale.inner)
        }.apply()
    }
}

private val typography = Typography().let { t ->
    t.copy(
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SpectraSeqTheme(content: @Composable () -> Unit) {
    val palette = NM.palette
    val accent = NM.accent
    val scheme = remember(palette, accent) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.Black,
            secondary = palette.solo,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceHigh,
            onSurfaceVariant = palette.textDim,
            surfaceContainer = palette.surface,
            surfaceContainerLow = palette.surface,
            surfaceContainerHigh = palette.surfaceHigh,
            surfaceContainerHighest = palette.surfaceHigh,
            outline = palette.line,
            error = palette.record,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
