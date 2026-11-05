package app.finn.deepsystem.ui.theme

import android.app.Activity
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalReduceMotion = compositionLocalOf { false }

private val DarkColorScheme = darkColorScheme(
    primary = FinnIndigo,
    onPrimary = OnSurfaceDark,
    primaryContainer = SurfaceDarkElevated,
    onPrimaryContainer = OnSurfaceDark,
    secondary = FinnCyan,
    onSecondary = OnSurfaceDark,
    tertiary = FinnViolet,
    onTertiary = OnSurfaceDark,
    background = BackgroundDarkTop,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceDarkElevated,
    onSurfaceVariant = OnSurfaceDarkVariant,
    outline = BorderDark,
    error = ErrorRose
)

private val LightColorScheme = lightColorScheme(
    primary = FinnIndigoDark,
    onPrimary = OnSurfaceLight,
    primaryContainer = SurfaceLightElevated,
    onPrimaryContainer = OnSurfaceLight,
    secondary = FinnCyan,
    onSecondary = OnSurfaceLight,
    tertiary = FinnViolet,
    onTertiary = OnSurfaceLight,
    background = BackgroundLightTop,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceLightElevated,
    onSurfaceVariant = OnSurfaceLightVariant,
    outline = BorderLight,
    error = ErrorRose
)

fun isSystemReduceMotionEnabled(context: Context): Boolean {
    return try {
        val durationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        durationScale == 0f
    } catch (_: Throwable) {
        false
    }
}

@Composable
fun FinnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    forceReduceMotion: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemReduceMotion = remember(context) { isSystemReduceMotionEnabled(context) }
    val reduceMotion = forceReduceMotion ?: systemReduceMotion

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
