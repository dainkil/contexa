/**
 * server 응답 타입. 원본은 Java 쪽이다.
 *  - contract 패키지: server/src/main/java/com/contexa/server/contract
 *  - 컨트롤러 응답: identity·review·entity·document·pipeline 패키지의 record
 * Java record가 바뀌면 여기도 같이 고친다. UUID는 string, Instant는 ISO 8601 string으로 온다.
 */

export type Uuid = string
export type IsoInstant = string

// ── contract: enum ─────────────────────────────────────────────

export type NodeType =
  | 'TOPIC'
  | 'DECISION_ITEM'
  | 'DECISION_EVENT'
  | 'CONDITION'
  | 'TASK'
  | 'ENTITY'
  | 'SPAN'
  | 'ARTIFACT'

export type RelType =
  | 'HAS_ITEM'
  | 'HAS_EVENT'
  | 'SUPERSEDES'
  | 'WITHDRAWS'
  | 'REQUIRES'
  | 'SATISFIED_BY'
  | 'DERIVED_FROM'
  | 'ASSIGNED_TO'
  | 'EVIDENCED_BY'
  | 'SPOKEN_BY'
  | 'MENTIONS'
  | 'USES'
  | 'CONFLICTS_WITH'

/** 카드와 줄이 함께 쓰는 확인 상태. CANDIDATE = AI 후보(검토 대기). */
export type ReviewStatus =
  | 'CANDIDATE'
  | 'CONFIRMED'
  | 'HELD'
  | 'REJECTED'
  | 'RETRACTED'

/** BASIS = 근거 쪽(앞으로), IMPACT = 영향 쪽(거꾸로). */
export type Direction = 'BASIS' | 'IMPACT'

export type OpenIssueKind =
  | 'HELD_NODE'
  | 'HELD_EDGE'
  | 'CONTESTED_DECISION'
  | 'UNMET_CONDITION'
  | 'AMBIGUOUS_NAME'

export type ScopeView =
  | 'TRACE'
  | 'CURRENT_STATE'
  | 'DECISION_HISTORY'
  | 'TASKS'
  | 'RESOLVE_ENTITY'
  | 'SEARCH_EVIDENCE'

// ── contract: record ───────────────────────────────────────────

export interface NodeRef {
  type: NodeType
  id: Uuid
  revision: number
}

export interface NodeKey {
  type: NodeType
  id: Uuid
}

export interface EdgeRef {
  id: Uuid
  version: number
}

export interface EvidenceRef {
  span: NodeRef
  edgeId: Uuid
  status: ReviewStatus
}

export interface Fact {
  node: NodeRef
  status: ReviewStatus
  label: string
  states: Record<string, string>
  confirmedBy: Uuid | null
  confirmedAt: IsoInstant | null
  evidence: EvidenceRef[]
}

export interface Edge {
  id: Uuid
  projectId: Uuid
  rel: RelType
  src: NodeRef
  dst: NodeRef
  status: ReviewStatus
  evidenceSpanIds: Uuid[]
  confirmedBy: Uuid | null
  confirmedAt: IsoInstant | null
  ontologyVersion: string
  version: number
}

export interface OpenIssue {
  kind: OpenIssueKind
  node: NodeRef | null
  edgeId: Uuid | null
}

/** 근거 발언 원문. */
export interface Source {
  span: NodeRef
  documentId: Uuid
  seq: number
  speaker: string | null
  text: string
}

export interface Scope {
  projectId: Uuid
  view: ScopeView
  focus: NodeRef | null
  direction: Direction | null
  depth: number | null
}

/** 읽기 응답은 항상 이 모양이다. */
export interface ContextPacket {
  asOf: IsoInstant
  scope: Scope
  facts: Fact[]
  relations: Edge[]
  open: OpenIssue[]
  sources: Source[]
}

export interface NodeView {
  ref: NodeRef
  projectId: Uuid
  status: ReviewStatus
  label: string
  states: Record<string, string>
  confirmedBy: Uuid | null
  confirmedAt: IsoInstant | null
}

export interface StaleRef {
  node: NodeRef | null
  edge: EdgeRef | null
  currentVersion: number | null
}

// ── identity ───────────────────────────────────────────────────

export type Role = 'OWNER' | 'REVIEWER' | 'MEMBER'

export interface ProjectSummary {
  id: Uuid
  title: string
  role: Role
}

// ── entity ─────────────────────────────────────────────────────

export interface NameForm {
  lang: string
  form: string
  status: string
  evidenceSpanId: Uuid | null
}

export interface NameCard {
  entity: NodeView
  names: NameForm[]
}

// ── review ─────────────────────────────────────────────────────

export type Verdict = 'CONFIRM' | 'HOLD' | 'REJECT' | 'RETRACT'

export type ReviewState = 'OPEN' | 'DONE'

export type TaskStatus =
  | 'PLANNED'
  | 'IN_PROGRESS'
  | 'ON_HOLD'
  | 'DONE'
  | 'CANCELLED'

/**
 * 검토함의 카드 묶음 하나. server review/ReviewItem.java
 * card는 발언 묶음(누가 말했나·누구를 언급했나)처럼 카드 내용이 없는 묶음이면 null이다.
 */
export interface ReviewItem {
  id: Uuid
  version: number
  state: ReviewState
  owner: NodeRef
  card: NodeView | null
  edges: Edge[]
  /** 줄 건너편 카드. 근거를 하나도 읽을 수 없는 카드는 빠진다(줄은 edges에 남는다). */
  related: NodeView[]
  /** 줄이 가리키거나 근거로 대는 발언의 원문. 읽을 수 있는 문서의 것만. */
  sources: Source[]
}

/** 검토함 한 쪽. next를 after로 보내면 다음 쪽이 온다. 더 없으면 null. */
export interface ReviewPage {
  items: ReviewItem[]
  next: string | null
}

export interface Impact {
  changed: NodeKey
  affected: NodeKey
  distance: number
}

export interface ReviewResult {
  item: ReviewItem
  impacts: Impact[]
}

export interface Changed {
  node: NodeRef
  impacts: Impact[]
}

// ── document · job · pipeline ─────────────────────────────────

/** 문서의 종류. server document/DocumentKind.java */
export type DocumentKind =
  | 'TRANSCRIPT'
  | 'DOCUMENT'
  | 'REGULATION'
  | 'MEMO'
  | 'ROSTER'

/** PROJECT = 구성원 모두, PRIVATE = 올린 사람만(개인 메모). */
export type Visibility = 'PROJECT' | 'PRIVATE'

export type JobStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'

/** 새로 올리는 문서. language가 null이면 여러 언어가 섞인 문서다. */
export interface DocumentInput {
  title: string
  kind: DocumentKind
  language: string | null
  visibility: Visibility
  externalOk: boolean
  content: string
}

export interface SourceVersionRef {
  documentId: Uuid
  sourceVersionId: Uuid
  versionNo: number
}

/** 문서를 올린 결과: 저장된 판과 "자르기" 작업. */
export interface IngestStarted {
  version: SourceVersionRef
  jobId: Uuid
}

export interface DocumentInfo {
  id: Uuid
  title: string
  kind: DocumentKind
  language: string | null
  visibility: Visibility
  externalOk: boolean
  createdAt: IsoInstant
}

/** 화면에 보여 줄 작업 상태(pipeline/JobBrief.java). */
export interface JobBrief {
  id: Uuid
  status: JobStatus
  attempts: number
  lastError: string | null
  createdAt: IsoInstant
  finishedAt: IsoInstant | null
}

/** 문서의 한 판과 그 판을 처리하는 작업들. parse = 발언으로 자르기, extract = 후보 뽑기. */
export interface VersionStatus {
  sourceVersionId: Uuid
  versionNo: number
  createdAt: IsoInstant
  spanCount: number
  parse: JobBrief | null
  extract: JobBrief | null
}

/** 문서 목록의 한 줄. latest = 가장 최근 판. */
export interface DocumentStatus {
  document: DocumentInfo
  versionCount: number
  latest: VersionStatus | null
}

/** 발언 하나. seq는 그 판 안에서의 순서, speakerLabel은 전사문의 화자 표기 그대로. */
export interface Span {
  ref: NodeRef
  projectId: Uuid
  documentId: Uuid
  sourceVersionId: Uuid
  seq: number
  speakerLabel: string | null
  text: string
}

/** 원문 뷰어가 받는 것(pipeline/DocumentView.java). */
export interface DocumentView {
  document: DocumentInfo
  versions: VersionStatus[]
  opened: {
    sourceVersionId: Uuid
    versionNo: number
    spans: Span[]
    /** 아직 발언으로 자르지 않았을 때만 원문 전체. */
    content: string | null
  }
}

/** server의 오류 응답 본문(ApiExceptionHandler). */
export interface ApiError {
  error:
    | 'NOT_FOUND'
    | 'BAD_REQUEST'
    | 'UNAUTHENTICATED'
    | 'FORBIDDEN'
    | 'STALE'
    | 'NOT_POSSIBLE'
  message: string
}
