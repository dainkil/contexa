/**
 * server 응답 타입. 원본은 Java 쪽이다.
 *  - contract 패키지: server/src/main/java/com/contexa/server/contract
 *  - 컨트롤러 응답: identity·review·entity 패키지의 record
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

/** 검토함의 카드 묶음 하나. */
export interface ReviewItem {
  id: Uuid
  version: number
  state: ReviewState
  owner: NodeRef
  card: NodeView
  edges: Edge[]
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
