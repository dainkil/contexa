package com.contexa.android.ui.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.contexa.android.api.DocumentStatus
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.ScreenTitle

/** 자료함 탭: 올린 문서와 가장 최근 판의 처리 상태. 처리 중이면 저절로 갱신된다. */
@Composable
fun DocumentsScreen(viewModel: DocumentsViewModel, onOpen: (documentId: String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 화면이 보이는 동안에만 불러온다. 다른 화면에서 돌아오면 다시 시작한다.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, viewModel) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenTitle("문서와 전사문")
                PrimaryButton("문서 올리기", onClick = viewModel::openForm, modifier = Modifier.fillMaxWidth())
            }
        }

        state.loadError?.let { message ->
            item {
                Column {
                    Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
                }
            }
        }

        when {
            state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.documents.isEmpty() && state.loadError == null -> item {
                Text(
                    "아직 올린 문서가 없습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> items(state.documents, key = { it.document.id }) { row ->
                DocumentRow(row, onClick = { onOpen(row.document.id) })
            }
        }
    }

    state.form?.let { form ->
        UploadDialog(
            form = form,
            errors = state.formErrors,
            uploading = state.uploading,
            uploadError = state.uploadError,
            onEdit = viewModel::editForm,
            onReject = viewModel::uploadFailed,
            onSubmit = { viewModel.upload(onUploaded = onOpen) },
            onClose = viewModel::closeForm,
        )
    }
}

/** 문서 한 줄: 제목, 그 아래 올린 시각과 처리 여부. */
@Composable
private fun DocumentRow(row: DocumentStatus, onClick: () -> Unit) {
    val document = row.document
    val progress = progressOf(row.latest)
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 제목은 사용자가 쓴 글이라 내용에 따라 방향을 정한다(아랍어 등).
            Text(
                document.title,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    formatInstant(row.latest?.createdAt ?: document.createdAt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(progress.label, style = MaterialTheme.typography.bodyLarge, color = toneColor(progress.tone))
            }
        }
    }
}
