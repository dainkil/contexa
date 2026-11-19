import { http, projectPath } from './http'
import type {
  Changed,
  Impact,
  NodeRef,
  NodeType,
  ReviewItem,
  ReviewResult,
  ReviewState,
  TaskStatus,
  Uuid,
  Verdict,
} from './types'

/**
 * 결정 정보를 바꾸는 동작은 모두 이 파일을 거친다(모듈 지도 B.10).
 * 확인·정정·보류·거절·철회, 업무 상태, 조건 충족, 이름 표기, 발언 정정.
 * 검토함 조회와 영향 조회도 같은 컨트롤러라 여기 둔다.
 * 기준: server api/ReviewController.java
 */

const enc = encodeURIComponent

/** 검토함. state를 생략하면 server 기본값 OPEN(확인할 것이 남은 묶음). */
export async function listReviews(
  projectId: Uuid,
  filter: { state?: ReviewState; ownerType?: NodeType } = {}
): Promise<ReviewItem[]> {
  const { data } = await http.get<ReviewItem[]>(
    projectPath(projectId, '/reviews'),
    { params: filter }
  )
  return data
}

export async function getReview(
  projectId: Uuid,
  reviewId: Uuid
): Promise<ReviewItem> {
  const { data } = await http.get<ReviewItem>(
    projectPath(projectId, `/reviews/${enc(reviewId)}`)
  )
  return data
}

export interface ReviewActionInput {
  /** 카드 자체에 대한 판단. 생략하면 줄만 처리한다. */
  card?: Verdict
  /** 카드 내용 정정(필드 → 새 값). */
  corrections?: Record<string, unknown>
  /** 줄 id → 판단 */
  edges?: Record<Uuid, Verdict>
  /** 줄 id → 근거 발언 id 목록 */
  edgeEvidence?: Record<Uuid, Uuid[]>
  /** 내가 읽은 묶음 버전. 그사이 바뀌었으면 409(STALE). */
  expectedVersion: number
  /** 같은 요청을 두 번 보내도 한 번만 처리되게 하는 키. */
  idempotencyKey: string
}

/** 묶음을 확인·보류·거절·철회·정정한다. */
export async function applyReviewAction(
  projectId: Uuid,
  reviewId: Uuid,
  action: ReviewActionInput
): Promise<ReviewResult> {
  const { data } = await http.post<ReviewResult>(
    projectPath(projectId, `/reviews/${enc(reviewId)}/actions`),
    action
  )
  return data
}

export async function changeTaskStatus(
  projectId: Uuid,
  taskId: Uuid,
  input: { status: TaskStatus; expectedRevision: number }
): Promise<Changed> {
  const { data } = await http.post<Changed>(
    projectPath(projectId, `/tasks/${enc(taskId)}/status`),
    input
  )
  return data
}

/** 담당자 본인이 업무를 수락하거나 거절한다. */
export async function acceptTask(
  projectId: Uuid,
  taskId: Uuid,
  accepted: boolean
): Promise<Changed> {
  const { data } = await http.post<Changed>(
    projectPath(projectId, `/tasks/${enc(taskId)}/acceptance`),
    { accepted }
  )
  return data
}

/** 조건이 이 카드로 충족됐다고 기록한다. 근거 발언이 필요하다. */
export async function satisfyCondition(
  projectId: Uuid,
  conditionId: Uuid,
  input: { by: NodeRef; evidence: Uuid[] }
): Promise<Changed> {
  const { data } = await http.post<Changed>(
    projectPath(projectId, `/conditions/${enc(conditionId)}/satisfied`),
    input
  )
  return data
}

/** 이 인물·기관을 이 언어로 이렇게 적는다고 확인(confirmed=true)하거나 거절한다. */
export async function setEntityName(
  projectId: Uuid,
  entityId: Uuid,
  input: {
    lang: string
    form: string
    confirmed: boolean
    evidenceSpanId?: Uuid
  }
): Promise<Changed> {
  const { data } = await http.post<Changed>(
    projectPath(projectId, `/entities/${enc(entityId)}/names`),
    input
  )
  return data
}

/** 이 인물이 이 이메일의 계정이라고 확인한다. email이 null이면 연결을 끊는다. */
export async function linkEntityAccount(
  projectId: Uuid,
  entityId: Uuid,
  email: string | null
): Promise<Changed> {
  const { data } = await http.post<Changed>(
    projectPath(projectId, `/entities/${enc(entityId)}/account`),
    { email }
  )
  return data
}

/** 발언 내용을 정정한다(전사 오류 등). 영향받는 카드는 server가 찾아 돌려준다. */
export async function reviseSpan(
  projectId: Uuid,
  spanId: Uuid,
  input: { expectedRevision: number; text: string }
): Promise<Changed> {
  const { data } = await http.post<Changed>(
    projectPath(projectId, `/spans/${enc(spanId)}/revisions`),
    input
  )
  return data
}

/** 이 카드가 바뀌면 다시 확인해야 하는 카드들. */
export async function listImpacts(
  projectId: Uuid,
  node: { type: NodeType; id: Uuid }
): Promise<Impact[]> {
  const { data } = await http.get<Impact[]>(
    projectPath(projectId, '/impacts'),
    { params: node }
  )
  return data
}
