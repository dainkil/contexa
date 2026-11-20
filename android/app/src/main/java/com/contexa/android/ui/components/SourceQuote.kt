package com.contexa.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.contexa.android.api.Source
import com.contexa.android.ui.theme.evidence

/**
 * 근거 발언 원문. 검토함의 근거와 답변의 인용이 같이 쓴다. "원문에서 보기"를 누르면 원문 뷰어에서 그 발언을 연다.
 *
 * @param documentTitle 문서 이름. 모르면 "문서"라고 적는다.
 * @param number        답변 본문의 [1], [2]에 맞춘 인용 번호. 검토함에서는 쓰지 않는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceQuote(source: Source, documentTitle: String?, onOpen: () -> Unit, number: Int? = null) {
    Surface(color = MaterialTheme.colorScheme.evidence, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp)) {
            if (number != null) {
                Text("[$number]", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                source.text,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                source.speaker?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            textDirection = TextDirection.Content,
                        ),
                    )
                }
                Text(
                    "${documentTitle ?: "문서"} · ${source.seq}번 발언",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpen) {
                    Text("원문에서 보기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}
