package com.contexa.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.api.Member
import com.contexa.android.api.Role
import com.contexa.android.ui.components.ChoiceChip
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.projects.roleLabel

/**
 * 프로젝트 설정의 구성원 구역. 모두 목록을 보고, 소유자만 넣고 역할을 바꾼다.
 *
 * @param onNotice 넣거나 바꾼 뒤 한 번 보여 줄 알림
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MembersSection(viewModel: MembersViewModel, onNotice: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val manage = canManageMembers(state.myRole)
    // 내 소유자 권한을 내려놓으려 할 때 한 번 더 묻는다
    var demoting by remember { mutableStateOf<Pair<Member, Role>?>(null) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            viewModel.noticeShown()
            onNotice(it)
        }
    }

    Band()
    GroupLabel(if (state.members.isEmpty()) "구성원" else "구성원 ${state.members.size}명")

    when {
        state.loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        else -> state.members.forEach { member ->
            MemberRow(
                member = member,
                editable = manage && member.email != null,
                busy = state.changingId == member.userId,
                enabled = state.changingId == null,
                onPick = { role ->
                    if (losesOwnership(member, role)) demoting = member to role else viewModel.changeRole(member, role)
                },
            )
        }
    }

    (state.loadError ?: state.changeError)?.let { message ->
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            if (state.loadError != null) TextButton(onClick = viewModel::refresh) { Text("다시 불러오기") }
        }
    }

    if (manage) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("구성원 넣기", style = MaterialTheme.typography.titleMedium)
            Text(
                "이미 가입한 사람의 이메일을 적으면 이 프로젝트에 바로 들어옵니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::editEmail,
                label = { Text("이메일") },
                singleLine = true,
                enabled = !state.inviting,
                isError = state.inviteError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                INVITE_ROLES.forEach { role ->
                    ChoiceChip(roleLabel(role), selected = state.role == role, onClick = { viewModel.pickRole(role) })
                }
            }
            Text(
                roleDescription(state.role),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.inviteError?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
            PrimaryButton(
                "넣기",
                onClick = viewModel::invite,
                enabled = state.email.isNotBlank(),
                loading = state.inviting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else if (state.members.isNotEmpty()) {
        Text(
            "구성원을 넣거나 역할을 바꾸는 것은 소유자만 할 수 있습니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        )
    }

    demoting?.let { (member, role) ->
        AlertDialog(
            onDismissRequest = { demoting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("내 역할을 ${withRo(roleLabel(role))} 바꿀까요?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "소유자 권한을 잃어 구성원을 넣거나 역할을 바꿀 수 없게 됩니다. 다른 소유자만 되돌릴 수 있습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    demoting = null
                    viewModel.changeRole(member, role)
                }) { Text("바꾸기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { demoting = null }) { Text("취소") } },
        )
    }
}

/** 구성원 한 줄: 이름(나), 이메일, 역할. 소유자에게는 역할이 고르기 버튼이다. */
@Composable
private fun MemberRow(member: Member, editable: Boolean, busy: Boolean, enabled: Boolean, onPick: (Role) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (member.me) "${member.displayName} (나)" else member.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            member.email?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!editable) {
            Tag(roleLabel(member.role))
        } else if (busy) {
            Box(Modifier.padding(end = 16.dp)) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { open = true }, enabled = enabled) { Text("${roleLabel(member.role)} ▾") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    INVITE_ROLES.forEach { role ->
                        DropdownMenuItem(
                            text = { Text(roleLabel(role), style = MaterialTheme.typography.bodyLarge) },
                            onClick = {
                                open = false
                                if (role != member.role) onPick(role)
                            },
                        )
                    }
                }
            }
        }
    }
}
