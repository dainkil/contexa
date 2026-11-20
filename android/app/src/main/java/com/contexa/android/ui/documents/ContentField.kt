package com.contexa.android.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 이보다 긴 본문은 입력칸에 넣지 않고 앞부분만 보여 준다(긴 글을 입력칸에 넣으면 화면이 멈춘다). */
const val EDITABLE_CONTENT_CHARS = 20_000

/** 본문 칸: 붙여 넣거나 .txt 파일을 불러온다. */
@Composable
fun ContentField(
    content: String,
    onChange: (String) -> Unit,
    onLoad: (text: String, fileName: String) -> Unit,
    onReject: (String) -> Unit,
    error: String?,
    placeholder: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("본문", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextFileButton(onLoad = onLoad, onReject = onReject)
        }
        if (content.length > EDITABLE_CONTENT_CHARS) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "불러온 본문 ${"%,d".format(content.length)}자. 길어서 앞부분만 보여 줍니다.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        content.take(600),
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { onChange("") }) { Text("지우고 다시 넣기") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } else {
            OutlinedTextField(
                value = content,
                onValueChange = onChange,
                placeholder = placeholder?.let { { Text(it) } },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp),
            )
        }
    }
}
