package com.contexa.android.ui.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.contexa.android.api.Visibility
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.Tag

/** 자료함: 올린 문서와 가장 최근 판의 처리 상태. 처리 중이면 저절로 갱신된다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    viewModel: DocumentsViewModel,
    projectTitle: String,
    onBack: () -> Unit,
    onOpen: (documentId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 화면이 보이는 동안에만 불러온다. 다른 화면에서 돌아오면 다시 시작한다.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, viewModel) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        projectTitle,
                        style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("프로젝트") } },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("문서와 전사문", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "회의록과 업무 문서를 올리고, 처리 상태와 원문을 확인합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryButton("문서 올리기", onClick = viewModel::openForm, modifier = Modifier.padding(top = 4.dp))
                }
            }

            state.loadError?.let { message ->
                item {
                    Column {
                        Text(message, color = MaterialTheme.colorScheme.error)
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

                state.documents.isEmpty() && state.loadError == null -> item { EmptyDocuments() }

                else -> items(state.documents, key = { it.document.id }) { row ->
                    DocumentRow(row, onClick = { onOpen(row.document.id) })
                }
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

@Composable
private fun EmptyDocuments() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("다음 회의 전에, 지난 회의록부터 올려 보세요.", style = MaterialTheme.typography.titleLarge)
            Text(
                "올린 회의록은 발언 단위로 잘리고, AI가 뽑은 결정·할 일 후보는 검토함에서 사람이 확인한 뒤에만 확정됩니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentRow(row: DocumentStatus, onClick: () -> Unit) {
    val document = row.document
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${kindLabel(document.kind)} · ${languageLabel(document.language)} · ${row.versionCount}판",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 제목은 사용자가 쓴 글이라 내용에 따라 방향을 정한다(아랍어 등).
            Text(
                document.title,
                style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(row.latest)
                if (document.visibility == Visibility.PRIVATE) Tag("나만 보기")
                Text(
                    formatInstant(row.latest?.createdAt ?: document.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
