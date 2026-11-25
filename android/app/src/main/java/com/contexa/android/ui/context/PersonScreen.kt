package com.contexa.android.ui.context

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.api.NameCard
import com.contexa.android.api.NameForm
import com.contexa.android.api.OpenIssueKind
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Source
import com.contexa.android.ui.components.BackButton
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.SecondaryButton
import com.contexa.android.ui.components.Section
import com.contexa.android.ui.components.SourceQuote
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.reviews.ENTITY_KIND_LABEL
import com.contexa.android.ui.reviews.statusLabel
import kotlinx.coroutines.launch

/**
 * 인물·기관 한 명: 언어별 표기(확인·거절), 표기가 나온 발언, 이 인물을 언급한 다른 발언, 이름이 겹치는 다른 인물.
 * 같은 사람으로 합치는 버튼은 없다. 겹치는 인물은 눌러서 따로 열어 본다.
 *
 * @param canReview    표기 확인·거절은 소유자·검토자만 한다(server도 같은 규칙으로 막는다)
 * @param onOpenPerson 이름이 겹치는 다른 인물을 연다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    canReview: Boolean,
    onBack: () -> Unit,
    onOpenSource: (Source) -> Unit,
    onOpenPerson: (entityId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            viewModel.noticeShown()
            scope.launch { snackbar.showSnackbar(message) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("이름 확인", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        val card = state.card
        when {
            card != null -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item { PersonHeader(card) }
                item {
                    NamesSection(card, state, canReview, viewModel::act, onOpenSource)
                }
                val others = nameEvidence(card).others
                if (others.isNotEmpty()) {
                    item {
                        Section("언급한 발언") {
                            others.forEach { source ->
                                SourceQuote(source, state.documentTitles[source.documentId], onOpen = { onOpenSource(source) })
                            }
                        }
                    }
                }
                item { SimilarSection(state, onOpenPerson) }
                state.loadError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }

            state.loading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("불러오지 못했습니다.", style = MaterialTheme.typography.titleMedium)
                state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
            }
        }
    }
}

@Composable
private fun PersonHeader(card: NameCard) {
    val entity = card.entity
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            entity.states["kind"]?.let { ENTITY_KIND_LABEL[it] } ?: "인물·기관",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            entity.label,
            style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
            modifier = Modifier.fillMaxWidth(),
        )
        Chips {
            if (entity.status != ReviewStatus.CONFIRMED) Tag(statusLabel(entity.status))
            if (entity.states["accountLinked"] == "true") Tag("계정 연결됨")
        }
    }
}

/** 언어별 표기. 표기마다 상태와, 그 표기가 나온 발언 하나, 확인·거절 버튼. */
@Composable
private fun NamesSection(
    card: NameCard,
    state: PersonUiState,
    canReview: Boolean,
    onAct: (NameForm, NameAction) -> Unit,
    onOpenSource: (Source) -> Unit,
) {
    val groups = nameGroups(card.names)
    val evidence = nameEvidence(card).byForm
    Section("표기") {
        if (groups.isEmpty()) {
            Text(
                "아직 언어별 표기가 없습니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            groups.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(group.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    group.forms.forEach { form ->
                        NameLine(
                            form = form,
                            source = evidence[form],
                            documentTitle = evidence[form]?.let { state.documentTitles[it.documentId] },
                            actions = if (canReview) nameActions(form.status) else emptyList(),
                            busy = state.busy == form,
                            enabled = state.busy == null,
                            onAct = { onAct(form, it) },
                            onOpenSource = onOpenSource,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NameLine(
    form: NameForm,
    source: Source?,
    documentTitle: String?,
    actions: List<NameAction>,
    busy: Boolean,
    enabled: Boolean,
    onAct: (NameAction) -> Unit,
    onOpenSource: (Source) -> Unit,
) {
    val rejected = form.status == "REJECTED"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                form.form,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = if (rejected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            val confirmed = form.status == "CONFIRMED"
            Tag(
                nameStatusLabel(form.status),
                color = if (confirmed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                container = if (confirmed) MaterialTheme.colorScheme.primaryContainer else null,
            )
        }
        source?.let { SourceQuote(it, documentTitle, onOpen = { onOpenSource(it) }) }
        if (actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    if (action == NameAction.CONFIRM) {
                        PrimaryButton("확인", onClick = { onAct(action) }, enabled = enabled, loading = busy, modifier = Modifier.weight(1f))
                    } else {
                        SecondaryButton("거절", onClick = { onAct(action) }, enabled = enabled, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 이름이 겹치는 다른 인물·기관. 합치지 않고, 눌러서 따로 열어 본다. */
@Composable
private fun SimilarSection(state: PersonUiState, onOpenPerson: (String) -> Unit) {
    Section("이름이 겹치는 인물") {
        val similar = state.similar
        when {
            state.similarError != null -> Text(
                state.similarError,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            similar == null -> CircularProgressIndicator(Modifier.padding(4.dp))
            similar.isEmpty() -> Text(
                "없습니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Text(
                    "같은 사람인지는 직접 판단하세요. 자동으로 합치지 않습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    similar.forEachIndexed { index, person ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenPerson(person.entity.node.id) }.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                person.entity.label,
                                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "‘${person.via}’ 표기가 겹칩니다",
                                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Chips {
                                ReviewStatusTag(person.entity)
                                if (person.ambiguous) Tag(openLabel(OpenIssueKind.AMBIGUOUS_NAME))
                            }
                        }
                    }
                }
            }
        }
    }
}
