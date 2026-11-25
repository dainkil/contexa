package com.contexa.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.ui.components.BackButton
import com.contexa.android.ui.components.ScreenTitle

/** 프로젝트 목록에서 여는 설정 화면. 프로젝트 안에서는 아래 탭이 SettingsContent를 바로 그린다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) },
    ) { inner ->
        SettingsContent(viewModel, modifier = Modifier.padding(inner))
    }
}

/**
 * 설정 내용: 위에 내 프로필, 그 아래 구역별로 나눈 줄 목록.
 *
 * @param role 프로젝트 안에서 열었을 때 그 프로젝트에서의 내 역할(소유자·검토자·구성원). 밖에서는 null.
 * @param projectSection 프로젝트 안에서 열었을 때 내 정보와 계정 사이에 넣을 구역(구성원). 밖에서는 null.
 */
@Composable
fun SettingsContent(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    role: String? = null,
    projectSection: (@Composable () -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    val name = state.name ?: state.email.substringBefore('@')

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("설정", modifier = Modifier.padding(horizontal = 16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.email,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Band()
        GroupLabel("내 정보")
        InfoRow("이름", state.name ?: "-")
        InfoRow("이메일", state.email)
        role?.let { InfoRow("이 프로젝트에서", it) }
        state.loadError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        projectSection?.invoke()

        Band()
        GroupLabel("계정")
        ActionRow("다른 계정으로 로그인", onClick = viewModel::signOut, enabled = !state.signingOut)
        ActionRow(
            if (state.signingOut) "로그아웃하는 중…" else "로그아웃",
            onClick = { confirming = true },
            color = MaterialTheme.colorScheme.error,
            enabled = !state.signingOut,
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("로그아웃할까요?", style = MaterialTheme.typography.titleMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    viewModel.signOut()
                }) { Text("로그아웃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("취소") } },
        )
    }
}

/** 구역 사이의 굵은 회색 띠. */
@Composable
internal fun Band() {
    Spacer(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerLow))
}

@Composable
internal fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
