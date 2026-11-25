package com.contexa.android.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.contexa.android.api.VersionStatus
import com.contexa.android.ui.components.DetailToggle
import com.contexa.android.ui.components.PrimaryButton
import kotlinx.coroutines.delay

@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.PROGRESS -> MaterialTheme.colorScheme.tertiary
    Tone.DONE -> MaterialTheme.colorScheme.primary
    Tone.ERROR -> MaterialTheme.colorScheme.error
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

/**
 * 원문 뷰어 위쪽의 처리 상태. 처리가 끝난 문서에는 아무것도 그리지 않는다.
 * 실패하면 안내와 다시 시도 버튼을(원래 오류 문장은 "자세히" 안에), 오래 기다리면 늦어진다는 안내를 보여 준다.
 *
 * @param onRetry 실패한 작업을 다시 맡긴다. 누를 수 있는지는 server가 정한다(시킨 사람이나 OWNER). 아니면 retryError에 이유가 온다
 */
@Composable
fun ProcessingNotice(
    version: VersionStatus?,
    modifier: Modifier = Modifier,
    retrying: Boolean = false,
    retryError: String? = null,
    onRetry: () -> Unit = {},
) {
    val progress = progressOf(version)
    if (progress.stage == Stage.READY) return
    val now = rememberNow(progress.active)
    val job = progress.job

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress.active) {
                CircularProgressIndicator(Modifier.size(16.dp), color = toneColor(progress.tone), strokeWidth = 2.dp)
            }
            Text(progress.label, style = MaterialTheme.typography.bodyLarge, color = toneColor(progress.tone))
        }
        if (waitingTooLong(progress, now)) {
            Text(
                QUEUE_DELAY_MESSAGE,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (progress.stage == Stage.FAILED && job != null) {
            Text(
                ANALYSIS_FAILED_MESSAGE,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            failureDetail(job)?.let { DetailToggle(it) }
            if (retryableJob(progress) != null) {
                PrimaryButton("다시 시도", onClick = onRetry, loading = retrying, modifier = Modifier.padding(top = 4.dp))
            }
            retryError?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
