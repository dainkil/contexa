package com.contexa.android.ui.context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.api.ContextPacket
import com.contexa.android.api.Direction
import com.contexa.android.api.Fact
import com.contexa.android.api.Source
import com.contexa.android.ui.components.SourceQuote
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.reviews.formatDay
import com.contexa.android.ui.reviews.stateLabel
import com.contexa.android.ui.reviews.typeLabel

/** 결정 항목 하나의 이력: 제안부터 확정·변경·철회까지 일어난 순서대로, 근거 발언과 함께. */
@Composable
fun HistoryScreen(viewModel: PacketViewModel, onBack: () -> Unit, onOpenSource: (Source) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PacketScaffold("결정 이력", state, onBack, viewModel::reload) { packet ->
        val item = packet.facts.find { it.node.id == packet.scope.focus?.id }
        val events = historyEvents(packet)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("결정 항목", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    item?.label ?: "이 항목을 읽을 수 없습니다.",
                    style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth(),
                )
                Chips {
                    item?.states?.get("decisionState")?.let { DecisionStateTag(it) }
                    item?.let { ReviewStatusTag(it) }
                }
            }
        }
        if (events.isEmpty()) {
            item {
                Text(
                    "아직 기록된 사건이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(events, key = { it.node.id }) { event ->
            val current = event.node.id == item?.states?.get("currentEventId")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chips {
                        event.states["kind"]?.let { Tag(stateLabel("kind", it)) }
                        if (current) {
                            Tag(
                                "지금 유효",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                container = MaterialTheme.colorScheme.primaryContainer,
                            )
                        } else {
                            event.states["standing"]?.takeIf { it != "ACTIVE" }?.let { Tag(stateLabel("standing", it)) }
                        }
                        ReviewStatusTag(event)
                        event.states["occurredAt"]?.let {
                            Text(
                                formatDay(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        event.label,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    sourcesOf(event, packet).forEach { source ->
                        SourceQuote(source, state.documentTitles[source.documentId], onOpen = { onOpenSource(source) })
                    }
                }
            }
        }
    }
}

/** 카드 하나에서 줄을 따라가 본다: 무엇에 기대고 있나(근거 쪽), 무엇이 여기에 기대나(영향 쪽). */
@Composable
fun TraceScreen(viewModel: PacketViewModel, onBack: () -> Unit, onOpenSource: (Source) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PacketScaffold("근거 따라가기", state, onBack, viewModel::reload) { packet ->
        val focus = packet.facts.find { it.node.id == packet.scope.focus?.id }
        val linked = linkedCards(packet)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    focus?.let { typeLabel(it.node.type) } ?: "카드",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    focus?.label ?: "이 카드를 읽을 수 없습니다.",
                    style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth(),
                )
                Chips {
                    Choice("기대고 있는 것", selected = state.direction == Direction.BASIS) { viewModel.pick(Direction.BASIS) }
                    Choice("여기에 기대는 것", selected = state.direction == Direction.IMPACT) { viewModel.pick(Direction.IMPACT) }
                }
            }
        }
        if (linked.isEmpty() && packet.sources.isEmpty()) {
            item {
                Text(
                    if (state.direction == Direction.BASIS) "이어진 근거가 없습니다." else "여기에 기대는 카드가 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(linked, key = { it.fact.node.id }) { link -> LinkedCard(link) }
        if (packet.sources.isNotEmpty()) {
            item { Text("근거 발언", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(packet.sources, key = { "s:${it.span.id}" }) { source ->
                SourceQuote(source, state.documentTitles[source.documentId], onOpen = { onOpenSource(source) })
            }
        }
    }
}

@Composable
private fun LinkedCard(link: Linked) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                listOfNotNull(link.relation ?: "한 다리 건너", typeLabel(link.fact.node.type)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                link.fact.label,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
            Chips {
                stateTags(link.fact).forEach { Tag(it) }
                ReviewStatusTag(link.fact)
            }
        }
    }
}

/** 카드 종류마다 한눈에 봐야 하는 상태만 고른다. */
private fun stateTags(fact: Fact): List<String> =
    listOf("decisionState", "kind", "standing", "state", "workStatus").mapNotNull { name ->
        fact.states[name]?.takeIf { !(name == "standing" && it == "ACTIVE") }?.let { stateLabel(name, it) }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PacketScaffold(
    title: String,
    state: PacketUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    content: LazyListScope.(ContextPacket) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text("맥락") } })
        },
    ) { inner ->
        val packet = state.packet
        when {
            packet != null -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content(packet)
                state.loadError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }

            state.loading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("불러오지 못했습니다.", style = MaterialTheme.typography.titleMedium)
                state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onRetry) { Text("다시 불러오기") }
            }
        }
    }
}
