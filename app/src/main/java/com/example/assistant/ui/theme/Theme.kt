package com.example.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 香槟金主色上的文字（深色保证对比度） */
private val ColorOnPrimary = Color(0xFF1A1300)

/**
 * 深墨夜景配色（glassmorphism，与浮动界面 FloatingPanelActivity 一致）。
 * App 固定深色主题（不跟随系统亮/暗，也不用 Material You 动态取色——统一视觉风格）。
 */
private val NightColorScheme = darkColorScheme(
    primary = ChampagneGold,
    onPrimary = ColorOnPrimary,
    primaryContainer = ChampagneGoldContainer,
    onPrimaryContainer = OnChampagneGoldContainer,
    secondary = NightSecondary,
    onSecondary = NightBackground,
    secondaryContainer = NightSecondaryContainer,
    onSecondaryContainer = OnNightSecondaryContainer,
    tertiary = NightInfo,
    onTertiary = NightBackground,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnBackground,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceContainerLowest = NightBackgroundDeep,
    surfaceContainerLow = NightBackground,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceVariant,
    surfaceContainerHighest = NightSurfaceHigh,
    outline = NightOutline,
    outlineVariant = NightOutline,
    inverseSurface = NightOnBackground,
    inverseOnSurface = NightBackground,
    inversePrimary = ChampagneGold,
    scrim = NightBackgroundDeep
)

@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightColorScheme,
        typography = Typography,
        content = content
    )
}
