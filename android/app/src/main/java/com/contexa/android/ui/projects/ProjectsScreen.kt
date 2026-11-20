package com.contexa.android.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.api.ProjectSummary
import com.contexa.android.ui.components.PrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel, email: String, onOpen: (ProjectSummary) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    val submit = {
        viewModel.create(title) {
            title = ""
            onOpen(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contexa") },
                actions = { TextButton(onClick = viewModel::signOut) { Text("로그아웃") } },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("프로젝트", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "회의록과 결정사항은 프로젝트 안에서만 보입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { Text("내 프로젝트", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }

            when {
                state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.loadError != null -> item {
                    Column {
                        Text(state.loadError!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::load) { Text("다시 불러오기") }
                    }
                }

                state.projects.isEmpty() -> item {
                    Text(
                        "아직 속한 프로젝트가 없습니다. 아래에서 새로 만들거나, 프로젝트 소유자에게 구성원으로 넣어 달라고 요청하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> items(state.projects, key = { it.id }) { project ->
                    ProjectRow(project, onClick = { onOpen(project) })
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("새 프로젝트", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "만든 사람이 소유자가 됩니다. 구성원은 나중에 이메일로 넣습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("프로젝트 이름") },
                        placeholder = { Text("예: 2027 국제교류 행사 준비팀") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.createError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    PrimaryButton("만들기", onClick = submit, enabled = title.isNotBlank(), loading = state.creating)
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: ProjectSummary, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 프로젝트 이름은 사용자가 쓴 글이라 내용에 따라 방향을 정한다(아랍어 등).
            Text(
                project.title,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                roleLabel(project.role),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
