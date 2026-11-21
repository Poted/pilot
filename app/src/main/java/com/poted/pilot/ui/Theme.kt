package com.poted.pilot.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val label: Color,
    val text: Color,
    val accent: Color,
    val rec: Color,
    val ready: Color,
    val warn: Color,
    val sleep: Color,
)

/** Anthracite rather than pure black: easier on the eyes and still dark outdoors at night. */
private val DarkColors = AppColors(
    background = Color(0xFF1E2024),
    surface = Color(0xFF282B31),
    surfaceHigh = Color(0xFF32363D),
    outline = Color(0xFF41464F),
    label = Color(0xFF9DA4B0),
    text = Color(0xFFECEEF2),
    accent = Color(0xFF7AB0FF),
    rec = Color(0xFFE5484D),
    ready = Color(0xFF3DD68C),
    warn = Color(0xFFF5A524),
    sleep = Color(0xFF7480FF),
)

private val LightColors = AppColors(
    background = Color(0xFFF1F2F4),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFE7E9ED),
    outline = Color(0xFFD3D7DE),
    label = Color(0xFF5E6573),
    text = Color(0xFF1B1D21),
    accent = Color(0xFF2F6FE0),
    rec = Color(0xFFD93036),
    ready = Color(0xFF1E9E5E),
    warn = Color(0xFFC27400),
    sleep = Color(0xFF4A58E0),
)

private val LocalAppColors = staticCompositionLocalOf { DarkColors }

/** Theme-aware colours; read inside composables. */
object Palette {
    val Background: Color @Composable get() = LocalAppColors.current.background
    val Surface: Color @Composable get() = LocalAppColors.current.surface
    val SurfaceHigh: Color @Composable get() = LocalAppColors.current.surfaceHigh
    val Outline: Color @Composable get() = LocalAppColors.current.outline
    val Label: Color @Composable get() = LocalAppColors.current.label
    val Text: Color @Composable get() = LocalAppColors.current.text
    val Accent: Color @Composable get() = LocalAppColors.current.accent
    val Rec: Color @Composable get() = LocalAppColors.current.rec
    val Ready: Color @Composable get() = LocalAppColors.current.ready
    val Warn: Color @Composable get() = LocalAppColors.current.warn
    val Sleep: Color @Composable get() = LocalAppColors.current.sleep
}

enum class ThemeMode(val label: String) { SYSTEM("Auto"), LIGHT("Light"), DARK("Dark") }

/** The chosen theme, persisted in shared preferences. */
object ThemeSetting {
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode

    fun load(context: Context) {
        val name = context.getSharedPreferences("pilot", Context.MODE_PRIVATE).getString("theme", null)
        _mode.value = ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.SYSTEM
    }

    fun cycle(context: Context) {
        val next = ThemeMode.entries[(_mode.value.ordinal + 1) % ThemeMode.entries.size]
        _mode.value = next
        context.getSharedPreferences("pilot", Context.MODE_PRIVATE).edit().putString("theme", next.name).apply()
    }
}

@Composable
fun PilotTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = Color(0xFF0B1A33), secondary = c.accent,
            background = c.background, onBackground = c.text, surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.label, outline = c.outline, error = c.rec,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = Color.White, secondary = c.accent,
            background = c.background, onBackground = c.text, surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.label, outline = c.outline, error = c.rec,
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val ctl = WindowCompat.getInsetsController(window, view)
            ctl.isAppearanceLightStatusBars = !dark
            ctl.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
