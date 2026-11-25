package com.contexa.android.ui.components

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalProvidableLocaleList
import androidx.compose.ui.text.intl.LocaleList
import java.util.Locale

/**
 * 안쪽을 한국어 설정으로 그린다. 앱 글은 모두 한국어인데, Material 부품(날짜 고르개의 달 이름·요일·안내 글)은
 * 기기 언어를 따라가 영어 기기에서는 영어로 섞여 나온다. 그런 부품만 이것으로 감싼다.
 *
 * 안내 글은 Context의 리소스에서, 날짜 형식(달·요일 이름)은 LocaleList에서 읽으므로 둘 다 바꾼다.
 * 대화상자는 창이 따로라 이 설정을 다시 덮는다. 대화상자 안쪽도 한 번 더 감싼다.
 */
@Composable
fun KoreanLocale(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val base = LocalConfiguration.current
    val configuration = remember(base) { Configuration(base).apply { setLocale(Locale.KOREA) } }
    val localized = remember(context, configuration) { context.createConfigurationContext(configuration) }
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides localized,
        LocalProvidableLocaleList provides KOREAN,
        content = content,
    )
}

private val KOREAN = LocaleList("ko-KR")
