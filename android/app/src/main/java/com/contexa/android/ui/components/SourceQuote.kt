package com.contexa.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.contexa.android.api.Source
import com.contexa.android.ui.theme.evidence

/**
 * 근거 발언 원문. 누르면 원문 뷰어에서 그 발언을 연다.
 *
 * @param documentTitle 문서 이름. 모르면 "문서"라고 적는다.
 */
@Composable
fun SourceQuote(source: Source, documentTitle: String?, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.evidence,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                source.text,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                listOfNotNull(source.speaker, "${documentTitle ?: "문서"} ${source.seq}번 발언").joinToString(" · "),
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
