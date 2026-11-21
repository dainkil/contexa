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
 * 모양은 developer.android.com을 따른다(2026-11-21에 잰 값).
 * 흰 바탕, 옅은 회색 구역(#F8F9FA), 1px 테두리(#DADCE0) 카드, Android 초록(#00AF57) 알약 버튼.
 * 기기 배경화면 색(dynamic color)은 쓰지 않는다. 어느 기기에서나 같은 색이어야 한다.
 */
private val Green = Color(0xFF00AF57)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F6EC),
    onPrimaryContainer = Color(0xFF0D652D),
    secondary = Color(0xFF202124),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F3F4),
    onSecondaryContainer = Color(0xFF202124),
    tertiary = Color(0xFF174EA6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8F0FE),
    onTertiaryContainer = Color(0xFF174EA6),
    error = Color(0xFFD93025),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFFA50E0E),
    background = Color.White,
    onBackground = Color(0xFF202124),
    surface = Color.White,
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    outline = Color(0xFF80868B),
    outlineVariant = Color(0xFFDADCE0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFF8F9FA),
    surfaceContainerHigh = Color(0xFFF1F3F4),
    surfaceContainerHighest = Color(0xFFE8EAED),
)

// 사이트의 어두운 모드는 재지 않았다. 같은 초록에 Google의 어두운 회색 계열로 맞췄다.
private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0D652D),
    onPrimaryContainer = Color(0xFFCEEAD6),
    secondary = Color(0xFFE8EAED),
    onSecondary = Color(0xFF202124),
    secondaryContainer = Color(0xFF3C4043),
    onSecondaryContainer = Color(0xFFE8EAED),
    tertiary = Color(0xFF8AB4F8),
    onTertiary = Color(0xFF202124),
    tertiaryContainer = Color(0xFF283A5C),
    onTertiaryContainer = Color(0xFFD2E3FC),
    error = Color(0xFFF28B82),
    onError = Color(0xFF202124),
    errorContainer = Color(0xFF5C2B29),
    onErrorContainer = Color(0xFFFAD2CF),
    background = Color(0xFF202124),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF202124),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF3C4043),
    onSurfaceVariant = Color(0xFF9AA0A6),
    outline = Color(0xFF80868B),
    outlineVariant = Color(0xFF3C4043),
    surfaceContainerLowest = Color(0xFF17181A),
    surfaceContainerLow = Color(0xFF292A2D),
    surfaceContainer = Color(0xFF292A2D),
    surfaceContainerHigh = Color(0xFF303134),
    surfaceContainerHighest = Color(0xFF3C4043),
)

/** 근거 발언을 칠하는 노랑. */
val ColorScheme.evidence: Color
    get() = if (background.luminance() > 0.5f) Color(0xFFFEF7C3) else Color(0xFF4A4000)

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
