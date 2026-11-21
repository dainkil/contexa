package com.contexa.android.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.contexa.android.ui.components.TagShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.contexa.android.api.VersionStatus
import kotlinx.coroutines.delay

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.PROGRESS -> MaterialTheme.colorScheme.tertiary
    Tone.DONE -> MaterialTheme.colorScheme.primary
    Tone.ERROR -> MaterialTheme.colorScheme.error
}

/** 목록에 쓰는 짧은 처리 상태. */
@Composable
fun StatusChip(version: VersionStatus?, modifier: Modifier = Modifier) {
    val progress = progressOf(version)
    val color = toneColor(progress.tone)
    Surface(
        shape = TagShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (progress.tone == Tone.PROGRESS) {
                CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 2.dp)
            }
            Text(progress.label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** 지금 시각. 기다리는 작업이 있을 때만 주기적으로 갱신한다. */
@Composable
private fun rememberNow(enabled: Boolean, intervalMs: Long = 5000): Long {
    val now by produceState(System.currentTimeMillis(), enabled) {
        while (enabled) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

private enum class StepState { TODO, DOING, DONE, FAILED }

private fun stepStates(progress: Progress): Pair<StepState, StepState> = when (progress.stage) {
    Stage.QUEUED -> StepState.TODO to StepState.TODO
    Stage.PARSING -> StepState.DOING to StepState.TODO
    Stage.EXTRACTING -> StepState.DONE to StepState.DOING
    Stage.READY -> StepState.DONE to StepState.DONE
    Stage.FAILED ->
        if (progress.failedAt == FailedAt.PARSE) StepState.FAILED to StepState.TODO else StepState.DONE to StepState.FAILED
    Stage.CANCELLED, Stage.UNKNOWN -> StepState.TODO to StepState.TODO
}

@Composable
private fun Step(state: StepState, label: String) {
    val color = when (state) {
        StepState.TODO -> MaterialTheme.colorScheme.onSurfaceVariant
        StepState.DOING -> MaterialTheme.colorScheme.onSurface
        StepState.DONE -> MaterialTheme.colorScheme.onSurface
        StepState.FAILED -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state) {
            StepState.DOING ->
                CircularProgressIndicator(Modifier.size(12.dp), color = MaterialTheme.colorScheme.tertiary, strokeWidth = 2.dp)
            StepState.DONE -> Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            StepState.FAILED -> Text("!", color = color, style = MaterialTheme.typography.bodyMedium)
            StepState.TODO -> Text("○", color = color, style = MaterialTheme.typography.bodyMedium)
        }
        Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 원문 뷰어 위쪽의 처리 단계: 발언으로 자르기 → 후보 뽑기.
 * 실패하면 시도 횟수와 오류 문장을, 오래 기다리면 일꾼을 확인하라는 안내를 보여 준다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProcessingSteps(version: VersionStatus?, modifier: Modifier = Modifier) {
    val progress = progressOf(version)
    val now = rememberNow(progress.active)
    val (parse, extract) = stepStates(progress)
    val job = progress.job

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(version)
                Step(parse, "발언으로 자르기")
                Step(extract, "후보 뽑기")
            }

            if (waitingTooLong(progress, now)) {
                Text(
                    "30초 넘게 처리를 시작하지 않았습니다. AI 일꾼이 켜져 있는지 확인하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (progress.stage == Stage.FAILED && job != null) {
                Text(
                    "${job.attempts}번 시도했지만 끝내지 못했습니다. 문서 내용은 그대로 남아 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                job.lastError?.let { lastError ->
                    var shown by rememberSaveable { mutableStateOf(false) }
                    TextButton(onClick = { shown = !shown }) { Text(if (shown) "오류 내용 접기" else "오류 내용") }
                    if (shown) {
                        Text(
                            lastError,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
