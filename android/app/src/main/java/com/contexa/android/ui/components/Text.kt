package com.contexa.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

/** 화면 제목. 아래에 설명 글을 붙이지 않는다. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = modifier.padding(top = 8.dp, bottom = 4.dp))
}

/** 카드 안 구역 하나: 초록 이름표와 그 아래 내용. */
@Composable
fun Section(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

/** "이름  값" 한 줄. 이름은 옅게, 값은 본문으로. */
@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            modifier = Modifier.weight(1f),
        )
    }
}

/** 위 막대의 뒤로 가기. */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
    }
}

/**
 * 작게 접어 둔 자세한 내용. 처음에는 "자세히"만 보이고, 누르면 글이 펼쳐진다.
 * 작업 오류처럼 사용자에게 바로 보여 주기에는 거친 원래 문장을 둘 때 쓴다.
 */
@Composable
fun DetailToggle(detail: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (open) "자세히 접기" else "자세히",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
        )
        if (open) {
            Text(
                detail,
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                color = color,
            )
        }
    }
}
