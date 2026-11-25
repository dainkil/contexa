package com.contexa.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/*
 * 모양은 당근의 SEED Design을 따른다(github.com/daangn/seed-design, Apache-2.0, 2026-11-21에 옮긴 값).
 * 색·모서리·글자 크기는 packages/rootage의 토큰이고, 옆에 적은 이름이 SEED의 토큰 이름이다.
 * 강조색만 당근 주황 대신 SEED의 초록 단계를 쓴다. 당근의 로고·캐릭터 같은 브랜드 자산은 쓰지 않는다.
 * 기기 배경화면 색(dynamic color)은 쓰지 않는다. 어느 기기에서나 같은 색이어야 한다.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF079171), // green-700
    onPrimary = Color.White, // fg.on-brand-solid
    primaryContainer = Color(0xFFEDFAF6), // green-100
    onPrimaryContainer = Color(0xFF075445), // green-900
    secondary = Color(0xFF2A3038), // bg.neutral-solid = gray-900
    onSecondary = Color.White, // fg.on-neutral-solid
    secondaryContainer = Color(0xFFF3F4F5), // bg.neutral-weak = gray-200
    onSecondaryContainer = Color(0xFF1A1C20),
    tertiary = Color(0xFF135FCD), // blue-800
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEFF6FF), // bg.informative-weak = blue-100
    onTertiaryContainer = Color(0xFF0B4596), // fg.informative-contrast = blue-900
    error = Color(0xFFFA342C), // fg.critical = red-700
    onError = Color.White,
    errorContainer = Color(0xFFFDF0F0), // bg.critical-weak = red-100
    onErrorContainer = Color(0xFF921708), // fg.critical-contrast = red-900
    background = Color.White, // bg.layer-default
    onBackground = Color(0xFF1A1C20), // fg.neutral = gray-1000
    surface = Color.White,
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFF3F4F5), // bg.neutral-weak
    onSurfaceVariant = Color(0xFF555D6D), // fg.neutral-muted = gray-800
    outline = Color(0xFFD1D3D8), // gray-500
    outlineVariant = Color(0xFFDCDEE3), // stroke.neutral-weak ≈ gray-400
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8F9), // bg.neutral-muted = gray-100
    surfaceContainer = Color(0xFFF7F8F9),
    surfaceContainerHigh = Color.White, // bg.layer-floating (대화 상자)
    surfaceContainerHighest = Color(0xFFF3F4F5),
    inverseSurface = Color(0xFF2A3038),
    inverseOnSurface = Color.White,
    // 어두운 바탕(알림 막대) 위의 버튼 글자. 정하지 않으면 Material 기본 보라가 나온다
    inversePrimary = Color(0xFF93E5C0), // 어두운 모드의 green-900
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF1B946D), // green-600
    onPrimary = Color.White,
    primaryContainer = Color(0xFF20362E), // green-200
    onPrimaryContainer = Color(0xFF93E5C0), // green-900
    secondary = Color(0xFFF3F4F5), // bg.neutral-solid = gray-1000
    onSecondary = Color(0xFF16171B), // fg.on-neutral-solid = gray-100
    secondaryContainer = Color(0xFF2B2E35), // bg.neutral-weak = gray-300
    onSecondaryContainer = Color(0xFFF3F4F5),
    tertiary = Color(0xFF83BCF9), // blue-800
    onTertiary = Color(0xFF16171B),
    tertiaryContainer = Color(0xFF202742), // blue-100
    onTertiaryContainer = Color(0xFFB9D7FB), // blue-900
    error = Color(0xFFFF6E60), // red-700
    onError = Color(0xFF16171B),
    errorContainer = Color(0xFF322323), // red-100
    onErrorContainer = Color(0xFFF8C5C3), // red-900
    background = Color(0xFF16171B), // bg.layer-default = gray-100
    onBackground = Color(0xFFF3F4F5), // gray-1000
    surface = Color(0xFF16171B),
    onSurface = Color(0xFFF3F4F5),
    surfaceVariant = Color(0xFF2B2E35), // gray-300
    onSurfaceVariant = Color(0xFFDCDEE3), // gray-800
    outline = Color(0xFF5B606A), // gray-500
    outlineVariant = Color(0xFF393D46), // gray-400
    surfaceContainerLowest = Color(0xFF000000), // bg.layer-basement = gray-00
    surfaceContainerLow = Color(0xFF1D2025), // bg.neutral-muted = gray-200
    surfaceContainer = Color(0xFF1D2025),
    surfaceContainerHigh = Color(0xFF1D2025), // bg.layer-floating = gray-200
    surfaceContainerHighest = Color(0xFF2B2E35),
    inverseSurface = Color(0xFFF3F4F5),
    inverseOnSurface = Color(0xFF16171B),
    inversePrimary = Color(0xFF079171), // 밝은 모드의 green-700
)

/** 근거 발언을 칠하는 노랑(bg.warning-weak = yellow-100). */
val ColorScheme.evidence: Color
    get() = if (background.luminance() > 0.5f) Color(0xFFFFF7DE) else Color(0xFF302819)

/** 누를 수 없는 버튼의 바탕과 글자(bg.disabled, fg.disabled). */
val ColorScheme.disabledContainer: Color
    get() = if (background.luminance() > 0.5f) Color(0xFFF3F4F5) else Color(0xFF2B2E35)

val ColorScheme.disabledContent: Color
    get() = if (background.luminance() > 0.5f) Color(0xFFD1D3D8) else Color(0xFF5B606A)

/** 기기 설정(밝게·어둡게)을 따른다. */
@Composable
fun ContexaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = ContexaTypography,
        shapes = ContexaShapes,
        content = content,
    )
}
