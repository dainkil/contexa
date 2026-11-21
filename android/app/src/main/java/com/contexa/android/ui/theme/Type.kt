package com.contexa.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 글자는 네 가지만 쓴다. 크기는 모바일 웹에서 흔히 쓰는 값이다(본문 16).
 *   제목   24 굵게  → headlineSmall   화면 제목
 *   소제목 18 굵게  → titleMedium     카드 제목, 구역 제목
 *   본문   16       → bodyLarge       내용
 *   버튼   14 굵게  → labelLarge      버튼, 꼬리표, 구역 이름표
 * 화면 코드는 위 네 이름만 쓴다. Material 부품이 속으로 쓰는 나머지 이름도 이 넷 가운데 하나로 맞춰 둔다.
 */
private fun style(size: Int, line: Int, weight: FontWeight) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)

private val Title = style(24, 32, FontWeight.Bold)
private val Subtitle = style(18, 26, FontWeight.Bold)
private val Body = style(16, 24, FontWeight.Normal)
private val Button = style(14, 20, FontWeight.SemiBold)

val ContexaTypography = Typography(
    displayLarge = Title,
    displayMedium = Title,
    displaySmall = Title,
    headlineLarge = Title,
    headlineMedium = Title,
    headlineSmall = Title,
    titleLarge = Subtitle,
    titleMedium = Subtitle,
    titleSmall = Subtitle,
    bodyLarge = Body,
    bodyMedium = Body,
    bodySmall = Body,
    labelLarge = Button,
    labelMedium = Button,
    labelSmall = Button,
)

/** 모서리: 입력 창·메뉴 12(r3), 카드 16(r4), 대화 상자 20(r5). 버튼과 배지는 부품에서 따로 정한다. */
val ContexaShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
