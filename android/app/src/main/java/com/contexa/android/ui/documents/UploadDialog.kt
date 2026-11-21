package com.contexa.android.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.contexa.android.api.DocumentKind
import com.contexa.android.api.Visibility
import com.contexa.android.ui.components.ChoiceChip
import com.contexa.android.ui.components.FullScreenDialog
import com.contexa.android.ui.components.PrimaryButton

/** 문서 올리기 창. 올리면 그 문서의 원문 뷰어로 간다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UploadDialog(
    form: UploadFormValues,
    errors: UploadFormErrors,
    uploading: Boolean,
    uploadError: String?,
    onEdit: ((UploadFormValues) -> UploadFormValues) -> Unit,
    onReject: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    val isPrivate = form.visibility == Visibility.PRIVATE

    FullScreenDialog(
        title = "문서 올리기",
        onClose = onClose,
        action = { PrimaryButton("올리기", onClick = onSubmit, loading = uploading) },
    ) {
        Text(
            "이미 글로 옮긴 회의록이나 업무 문서를 붙여 넣으세요. 올리면 발언 단위로 자르고, AI가 결정·할 일 후보를 뽑아 검토함에 넣습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = form.title,
            onValueChange = { value -> onEdit { it.copy(title = value) } },
            label = { Text("제목") },
            placeholder = { Text("예: 3차 회의 전사문 (11월 12일)") },
            singleLine = true,
            isError = errors.title != null,
            supportingText = errors.title?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            modifier = Modifier.fillMaxWidth(),
        )

        Choice("종류", hint = "회의 전사문은 줄마다 화자를 나눠 자릅니다.") {
            DocumentKind.entries.forEach { kind ->
                ChoiceChip(kindLabel(kind), selected = form.kind == kind, onClick = { onEdit { it.copy(kind = kind) } })
            }
        }

        Choice("언어") {
            LANGUAGES.forEach { language ->
                ChoiceChip(language.label, selected = form.language == language.code, onClick = { onEdit { it.copy(language = language.code) } })
            }
            ChoiceChip("여러 언어가 섞임", selected = form.language == MIXED_LANGUAGE, onClick = { onEdit { it.copy(language = MIXED_LANGUAGE) } })
        }

        Choice("누가 읽나요") {
            ChoiceChip("프로젝트 구성원 모두", selected = !isPrivate, onClick = { onEdit { it.copy(visibility = Visibility.PROJECT) } })
            ChoiceChip("나만 (개인 메모)", selected = isPrivate, onClick = { onEdit { it.copy(visibility = Visibility.PRIVATE) } })
        }

        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = !isPrivate && form.externalOk,
                enabled = !isPrivate,
                role = Role.Checkbox,
                onValueChange = { checked -> onEdit { it.copy(externalOk = checked) } },
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = !isPrivate && form.externalOk, onCheckedChange = null, enabled = !isPrivate)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("외부로 나가는 답변의 근거로 써도 됩니다", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "외부 AI 에이전트와 외부용 답변은 이 표시가 있는 문서만 읽습니다. 개인 메모는 외부에 공유할 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ContentField(
            content = form.content,
            onChange = { value -> onEdit { it.copy(content = value) } },
            onLoad = { text, fileName ->
                onEdit { it.copy(content = text, title = it.title.ifBlank { titleFromFileName(fileName) }) }
            },
            onReject = onReject,
            error = errors.content,
            placeholder = "민지: 예산 승인 전까지 인쇄는 보류입니다.\nAhmed: OK, then we wait.",
        )

        uploadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(label: String, hint: String? = null, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
