package com.contexa.android.ui.documents

import com.contexa.android.api.DocumentKind
import com.contexa.android.api.DocumentStatus
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
    /** 자르기 작업이 처리 순서를 기다린다 */
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

/** 다시 맡길 수 있는 작업. 자르기나 뽑기가 실패로 끝났을 때만 있다(취소된 작업은 다시 맡기지 않는다). */
fun retryableJob(progress: Progress): JobBrief? =
    progress.job?.takeIf { progress.stage == Stage.FAILED && it.status == JobStatus.FAILED }

/** 진행 중인 작업이 있으면 이 간격(ms)으로 다시 불러온다. */
const val POLL_MS = 2000L

/** 작업이 이보다 오래 시작되지 않으면 늦어지고 있다고 안내한다. */
const val STALE_QUEUE_MS = 30_000L

const val QUEUE_DELAY_MESSAGE = "처리를 기다리는 작업이 많아 시작이 늦어지고 있습니다. 잠시 뒤에 다시 확인해 주세요."

const val ANALYSIS_FAILED_MESSAGE = "분석을 끝내지 못했습니다. 문서 내용은 그대로 남아 있습니다. 다시 시도해 보세요."

/** 실패한 작업의 자세한 내용(시도 횟수와 원래 오류 문장). "자세히"를 눌러야 보인다. */
fun failureDetail(job: JobBrief): String? {
    val tried = if (job.attempts > 0) "${job.attempts}번 시도함" else null
    return listOfNotNull(tried, job.lastError?.takeIf { it.isNotBlank() }).joinToString("\n").ifEmpty { null }
}

// 뽑힌 후보가 없거나 모두 이미 있는 카드에 합쳐졌을 수도 있으므로 "후보가 생겼다"고 단정하지 않는다
const val ANALYSIS_DONE_MESSAGE = "분석이 끝났습니다. 검토함에서 결과를 확인하세요."

/**
 * 자료함이 지켜보는 문서.
 *
 * @param watching 아직 처리 중이어서 다음에 끝났는지 볼 문서 id
 * @param finished 지켜보던 문서 중 이번에 후보를 검토함에 넣은 것이 있나
 */
data class AnalysisWatch(val watching: Set<String>, val finished: Boolean)

/**
 * 새로 불러온 목록으로 지켜볼 문서를 갱신한다. 처음 열었을 때 이미 끝나 있던 문서는 알리지 않는다.
 * 실패·취소로 끝난 문서는 알리지 않는다(자료함에 그대로 표시된다).
 * 목록에 아직 없는 문서(방금 올려 목록이 따라오지 못한 것)는 계속 지켜본다.
 */
fun finishedAnalysis(watching: Set<String>, documents: List<DocumentStatus>): AnalysisWatch {
    val finished = documents.any { it.document.id in watching && progressOf(it.latest).stage == Stage.READY }
    val listed = documents.map { it.document.id }.toSet()
    val active = documents.filter { isActive(it.latest) }.map { it.document.id }.toSet()
    return AnalysisWatch(active + (watching - listed), finished)
}

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
