package com.contexa.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SEED의 글자 단계 하나(font-size.tN / line-height.tN). 글꼴은 기기 기본을 쓴다. */
private fun step(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)

private val Bold = FontWeight.Bold
private val Medium = FontWeight.Medium

/** 제목은 굵게(700), 본문은 보통(400). 괄호 안이 SEED의 단계 이름이다. */
val ContexaTypography = Typography(
    displayLarge = step(40, 52, Bold), // t13
    displayMedium = step(32, 42, Bold), // t12
    displaySmall = step(28, 38, Bold), // t11
    headlineLarge = step(26, 35, Bold), // t10
    headlineMedium = step(24, 32, Bold), // t9
    headlineSmall = step(22, 30, Bold), // t8
    titleLarge = step(20, 27, Bold), // t7
    titleMedium = step(16, 22, Bold), // t5
    titleSmall = step(14, 19, Bold), // t4
    bodyLarge = step(16, 22), // t5
    bodyMedium = step(14, 19), // t4
    bodySmall = step(13, 18), // t3
    labelLarge = step(14, 19, Bold), // t4, 버튼·칩 글자
    labelMedium = step(12, 16, Medium), // t2, 배지
    labelSmall = step(11, 15, Medium), // t1
)

/** 모서리: 입력 창·메뉴 12(r3), 카드 16(r4), 대화 상자 20(r5). 버튼과 배지는 부품에서 따로 정한다. */
val ContexaShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
