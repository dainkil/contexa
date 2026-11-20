package com.contexa.android.ui.documents

import com.contexa.android.api.DocumentKind
import com.contexa.android.api.Impact
import com.contexa.android.api.JobBrief
import com.contexa.android.api.JobStatus
import com.contexa.android.api.VersionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 문서 한 판의 처리 단계. 판 하나에는 작업이 두 개 붙는다.
 *   parse(발언으로 자르기, 규칙) → extract(카드·줄 후보 뽑기, 모델) → 검토함
 */
enum class Stage {
    /** 자르기 작업이 일꾼을 기다린다 */
    QUEUED,
    PARSING,

    /** 잘랐고, 후보 뽑기를 기다리거나 하는 중 */
    EXTRACTING,

    /** 후보를 검토함에 넣었다(또는 처리 기록 없이 발언만 있다) */
    READY,
    FAILED,
    CANCELLED,

    /** 처리 기록도 발언도 없다 */
    UNKNOWN,
}

enum class Tone { MUTED, PROGRESS, DONE, ERROR }

enum class FailedAt { PARSE, EXTRACT }

data class Progress(
    val stage: Stage,
    val label: String,
    val tone: Tone,
    /** 아직 끝나지 않아서 다시 불러와야 하나 */
    val active: Boolean,
    /** 실패했거나 기다리는 작업. 시도 횟수·오류·기다린 시간을 보여 줄 때 쓴다 */
    val job: JobBrief?,
    /** 실패했다면 어느 단계에서 */
    val failedAt: FailedAt? = null,
)

private val NO_RECORD = Progress(Stage.UNKNOWN, "처리 기록 없음", Tone.MUTED, active = false, job = null)
private val DONE = Progress(Stage.READY, "처리 완료", Tone.DONE, active = false, job = null)

fun progressOf(version: VersionStatus?): Progress {
    if (version == null) return NO_RECORD
    val parse = version.parse
        // 예시 데이터처럼 작업 없이 발언만 들어간 판
        ?: return if (version.spanCount > 0) DONE else NO_RECORD

    when (parse.status) {
        JobStatus.QUEUED -> return Progress(Stage.QUEUED, "처리 대기", Tone.MUTED, active = true, job = parse)
        JobStatus.RUNNING -> return Progress(Stage.PARSING, "발언으로 자르는 중", Tone.PROGRESS, active = true, job = parse)
        JobStatus.FAILED ->
            return Progress(Stage.FAILED, "자르기 실패", Tone.ERROR, active = false, job = parse, failedAt = FailedAt.PARSE)
        JobStatus.CANCELLED -> return Progress(Stage.CANCELLED, "처리 취소됨", Tone.MUTED, active = false, job = parse)
        JobStatus.SUCCEEDED -> Unit
    }

    // 자르기가 끝나면 server가 같은 트랜잭션에서 뽑기 작업을 올린다
    val extract = version.extract
    return when (extract?.status) {
        null, JobStatus.QUEUED -> Progress(Stage.EXTRACTING, "후보 뽑기 대기", Tone.PROGRESS, active = true, job = extract)
        JobStatus.RUNNING -> Progress(Stage.EXTRACTING, "후보 뽑는 중", Tone.PROGRESS, active = true, job = extract)
        JobStatus.FAILED ->
            Progress(Stage.FAILED, "후보 뽑기 실패", Tone.ERROR, active = false, job = extract, failedAt = FailedAt.EXTRACT)
        JobStatus.CANCELLED -> Progress(Stage.CANCELLED, "처리 취소됨", Tone.MUTED, active = false, job = extract)
        JobStatus.SUCCEEDED -> DONE
    }
}

/** 진행 중인 작업이 있으면 이 간격(ms)으로 다시 불러온다. */
const val POLL_MS = 2000L

/** 일꾼이 이보다 오래 작업을 가져가지 않으면 일꾼이 켜져 있는지 확인하라고 안내한다. */
const val STALE_QUEUE_MS = 30_000L

/** 기다리는 작업이 오래 방치됐나. 실행 중이거나 끝난 작업은 해당하지 않는다. */
fun waitingTooLong(progress: Progress, nowMillis: Long): Boolean {
    val job = progress.job
    if (!progress.active || job == null || job.status != JobStatus.QUEUED) return false
    val created = try {
        Instant.parse(job.createdAt).toEpochMilli()
    } catch (_: DateTimeParseException) {
        return false
    }
    return nowMillis - created > STALE_QUEUE_MS
}

fun isActive(version: VersionStatus?): Boolean = progressOf(version).active

fun kindLabel(kind: DocumentKind): String = when (kind) {
    DocumentKind.TRANSCRIPT -> "회의 전사문"
    DocumentKind.DOCUMENT -> "업무 문서"
    DocumentKind.REGULATION -> "규정"
    DocumentKind.MEMO -> "메모"
    DocumentKind.ROSTER -> "참석자 명단"
}

data class Language(val code: String, val label: String)

/**
 * 고를 수 있는 언어. 언어를 늘리려면 여기에 한 줄을 더한다.
 * server에는 code를 그대로 보낸다. 여러 언어가 섞였으면 null을 보낸다.
 */
val LANGUAGES = listOf(Language("ko", "한국어"), Language("en", "영어"), Language("ar", "아랍어"))

const val MIXED_LANGUAGE = "mixed"

fun languageLabel(code: String?): String {
    if (code == null) return "여러 언어"
    return LANGUAGES.find { it.code == code }?.label ?: code
}

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.KOREA)

/** 저장은 ISO 8601, 화면에서만 지역화한다. */
fun formatInstant(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = try {
    dateFormat.format(Instant.parse(iso).atZone(zone))
} catch (_: DateTimeParseException) {
    iso
}

/** 정정한 뒤 알림 문장. 이 발언에 기댄 카드가 있으면 다시 확인이 필요하다고 덧붙인다. */
fun revisedMessage(impacts: List<Impact>): String {
    val affected = impacts.map { it.affected.id }.toSet().size
    return if (affected > 0) {
        "발언을 정정했습니다. 이 발언에 기댄 카드 ${affected}개는 다시 확인이 필요합니다."
    } else {
        "발언을 정정했습니다."
    }
}
