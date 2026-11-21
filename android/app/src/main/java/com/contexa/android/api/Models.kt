package com.contexa.android.api

import kotlinx.serialization.Serializable

/*
 * server 응답 타입. 원본은 Java 쪽이다.
 *  - contract 패키지: server/src/main/java/com/contexa/server/contract
 *  - 컨트롤러 응답: identity·review·entity·document·pipeline·generation 패키지의 record
 * Java record가 바뀌면 여기도 같이 고친다. UUID와 Instant(ISO 8601)는 String으로 받는다.
 */

// ── contract: enum ─────────────────────────────────────────────

enum class NodeType { TOPIC, DECISION_ITEM, DECISION_EVENT, CONDITION, TASK, ENTITY, SPAN, ARTIFACT }

enum class RelType {
    HAS_ITEM, HAS_EVENT, SUPERSEDES, WITHDRAWS, REQUIRES, SATISFIED_BY, DERIVED_FROM,
    ASSIGNED_TO, EVIDENCED_BY, SPOKEN_BY, MENTIONS, USES, CONFLICTS_WITH,
}

/** 카드와 줄이 함께 쓰는 확인 상태. CANDIDATE = AI 후보(검토 대기). */
enum class ReviewStatus { CANDIDATE, CONFIRMED, HELD, REJECTED, RETRACTED }

/** BASIS = 근거 쪽(앞으로), IMPACT = 영향 쪽(거꾸로). */
enum class Direction { BASIS, IMPACT }

enum class OpenIssueKind { HELD_NODE, HELD_EDGE, CONTESTED_DECISION, UNMET_CONDITION, AMBIGUOUS_NAME }

enum class ScopeView { TRACE, CURRENT_STATE, DECISION_HISTORY, TASKS, RESOLVE_ENTITY, SEARCH_EVIDENCE }

// ── contract: record ───────────────────────────────────────────

@Serializable
data class NodeRef(val type: NodeType, val id: String, val revision: Int)

@Serializable
data class NodeKey(val type: NodeType, val id: String)

@Serializable
data class EdgeRef(val id: String, val version: Int)

@Serializable
data class EvidenceRef(val span: NodeRef, val edgeId: String, val status: ReviewStatus)

@Serializable
data class Fact(
    val node: NodeRef,
    val status: ReviewStatus,
    val label: String,
    val states: Map<String, String> = emptyMap(),
    val confirmedBy: String? = null,
    val confirmedAt: String? = null,
    val evidence: List<EvidenceRef> = emptyList(),
)

@Serializable
data class Edge(
    val id: String,
    val projectId: String,
    val rel: RelType,
    val src: NodeRef,
    val dst: NodeRef,
    val status: ReviewStatus,
    val evidenceSpanIds: List<String> = emptyList(),
    val confirmedBy: String? = null,
    val confirmedAt: String? = null,
    val ontologyVersion: String,
    val version: Int,
)

@Serializable
data class OpenIssue(val kind: OpenIssueKind, val node: NodeRef? = null, val edgeId: String? = null)

/** 근거 발언 원문. */
@Serializable
data class Source(
    val span: NodeRef,
    val documentId: String,
    val seq: Int,
    val speaker: String? = null,
    val text: String,
)

@Serializable
data class Scope(
    val projectId: String,
    val view: ScopeView,
    val focus: NodeRef? = null,
    val direction: Direction? = null,
    val depth: Int? = null,
)

/** 읽기 응답은 항상 이 모양이다. */
@Serializable
data class ContextPacket(
    val asOf: String,
    val scope: Scope,
    val facts: List<Fact> = emptyList(),
    val relations: List<Edge> = emptyList(),
    val open: List<OpenIssue> = emptyList(),
    val sources: List<Source> = emptyList(),
)

@Serializable
data class NodeView(
    val ref: NodeRef,
    val projectId: String,
    val status: ReviewStatus,
    val label: String,
    val states: Map<String, String> = emptyMap(),
    val confirmedBy: String? = null,
    val confirmedAt: String? = null,
)

@Serializable
data class StaleRef(val node: NodeRef? = null, val edge: EdgeRef? = null, val currentVersion: Int? = null)

// ── identity ───────────────────────────────────────────────────

enum class Role { OWNER, REVIEWER, MEMBER }

@Serializable
data class ProjectSummary(val id: String, val title: String, val role: Role)

// ── entity ─────────────────────────────────────────────────────

@Serializable
data class NameForm(val lang: String, val form: String, val status: String, val evidenceSpanId: String? = null)

@Serializable
data class NameCard(val entity: NodeView, val names: List<NameForm> = emptyList())

// ── review ─────────────────────────────────────────────────────

enum class Verdict { CONFIRM, HOLD, REJECT, RETRACT }

enum class ReviewState { OPEN, DONE }

enum class TaskStatus { PLANNED, IN_PROGRESS, ON_HOLD, DONE, CANCELLED }

/**
 * 검토함의 카드 묶음 하나. server review/ReviewItem.java
 * card는 발언 묶음(누가 말했나·누구를 언급했나)처럼 카드 내용이 없는 묶음이면 null이다.
 */
@Serializable
data class ReviewItem(
    val id: String,
    val version: Int,
    val state: ReviewState,
    val owner: NodeRef,
    val card: NodeView? = null,
    val edges: List<Edge> = emptyList(),
    /** 줄 건너편 카드. 근거를 하나도 읽을 수 없는 카드는 빠진다(줄은 edges에 남는다). */
    val related: List<NodeView> = emptyList(),
    /** 줄이 가리키거나 근거로 대는 발언의 원문. 읽을 수 있는 문서의 것만. */
    val sources: List<Source> = emptyList(),
)

/** 검토함 한 쪽. next를 after로 보내면 다음 쪽이 온다. 더 없으면 null. */
@Serializable
data class ReviewPage(val items: List<ReviewItem> = emptyList(), val next: String? = null)

@Serializable
data class Impact(val changed: NodeKey, val affected: NodeKey, val distance: Int)

@Serializable
data class ReviewResult(val item: ReviewItem, val impacts: List<Impact> = emptyList())

@Serializable
data class Changed(val node: NodeRef, val impacts: List<Impact> = emptyList())

// ── document · job · pipeline ─────────────────────────────────

/** 문서의 종류. server document/DocumentKind.java */
enum class DocumentKind { TRANSCRIPT, DOCUMENT, REGULATION, MEMO, ROSTER }

/** PROJECT = 구성원 모두, PRIVATE = 올린 사람만(개인 메모). */
enum class Visibility { PROJECT, PRIVATE }

enum class JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** 새로 올리는 문서. language가 null이면 여러 언어가 섞인 문서다. */
@Serializable
data class DocumentInput(
    val title: String,
    val kind: DocumentKind,
    val language: String?,
    val visibility: Visibility,
    val externalOk: Boolean,
    val content: String,
)

@Serializable
data class SourceVersionRef(val documentId: String, val sourceVersionId: String, val versionNo: Int)

/** 문서를 올린 결과: 저장된 판과 "자르기" 작업. */
@Serializable
data class IngestStarted(val version: SourceVersionRef, val jobId: String)

@Serializable
data class DocumentInfo(
    val id: String,
    val title: String,
    val kind: DocumentKind,
    val language: String? = null,
    val visibility: Visibility,
    val externalOk: Boolean,
    val createdAt: String,
)

/** 화면에 보여 줄 작업 상태(pipeline/JobBrief.java). */
@Serializable
data class JobBrief(
    val id: String,
    val status: JobStatus,
    val attempts: Int,
    val lastError: String? = null,
    val createdAt: String,
    val finishedAt: String? = null,
)

/** 문서의 한 판과 그 판을 처리하는 작업들. parse = 발언으로 자르기, extract = 후보 뽑기. */
@Serializable
data class VersionStatus(
    val sourceVersionId: String,
    val versionNo: Int,
    val createdAt: String,
    val spanCount: Int,
    val parse: JobBrief? = null,
    val extract: JobBrief? = null,
)

/** 문서 목록의 한 줄. latest = 가장 최근 판. */
@Serializable
data class DocumentStatus(val document: DocumentInfo, val versionCount: Int, val latest: VersionStatus? = null)

/** 발언 하나. seq는 그 판 안에서의 순서, speakerLabel은 전사문의 화자 표기 그대로. */
@Serializable
data class Span(
    val ref: NodeRef,
    val projectId: String,
    val documentId: String,
    val sourceVersionId: String,
    val seq: Int,
    val speakerLabel: String? = null,
    val text: String,
)

@Serializable
data class OpenedVersion(
    val sourceVersionId: String,
    val versionNo: Int,
    val spans: List<Span> = emptyList(),
    /** 아직 발언으로 자르지 않았을 때만 원문 전체. */
    val content: String? = null,
)

/** 원문 뷰어가 받는 것(pipeline/DocumentView.java). */
@Serializable
data class DocumentView(
    val document: DocumentInfo,
    val versions: List<VersionStatus> = emptyList(),
    val opened: OpenedVersion,
)

// ── generation ─────────────────────────────────────────────────

/** server generation/GenKind.java */
enum class GenKind { ANSWER, BRIEFING, DRAFT }

enum class ArtifactStatus { PENDING, PUBLISHED, FAILED }

@Serializable
data class GenerationRequested(val artifactId: String, val jobId: String)

/** 글 목록의 한 줄(generation/GenerationWiki.ArtifactSummary). published가 false면 쓰는 중이거나 실패했다. */
@Serializable
data class ArtifactSummary(
    val id: String,
    val kind: GenKind,
    val question: String,
    val lang: String,
    val published: Boolean,
    val createdAt: String,
)

/** 글 하나(generation/ArtifactView.java). stale = 글을 쓴 뒤에 바뀐 근거. */
@Serializable
data class ArtifactView(
    val id: String,
    val kind: GenKind,
    val question: String,
    val lang: String,
    val status: ArtifactStatus,
    val content: String? = null,
    val citations: List<Source> = emptyList(),
    val stale: List<StaleRef> = emptyList(),
    val failure: String? = null,
    val createdAt: String,
    val publishedAt: String? = null,
)
