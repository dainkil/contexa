import type { NodeType, RelType, ReviewStatus } from '@/api/types'

/** 카드 종류. */
export const TYPE_LABEL: Record<NodeType, string> = {
  TOPIC: '안건',
  DECISION_ITEM: '결정 항목',
  DECISION_EVENT: '결정 사건',
  CONDITION: '조건',
  TASK: '할 일',
  ENTITY: '인물·기관',
  SPAN: '발언',
  ARTIFACT: '답변·초안',
}

/** 검토함에서 거를 수 있는 종류. 자주 보는 것부터. 주소(?type=)에도 이 값만 받는다. */
export const FILTER_TYPES = [
  'DECISION_EVENT',
  'TASK',
  'CONDITION',
  'DECISION_ITEM',
  'TOPIC',
  'ENTITY',
  'SPAN',
] as const satisfies readonly NodeType[]

export type FilterType = (typeof FILTER_TYPES)[number]

export type Tone = 'candidate' | 'held' | 'confirmed' | 'closed'

/** 카드·줄의 확인 상태. */
export const STATUS: Record<ReviewStatus, { label: string; tone: Tone }> = {
  CANDIDATE: { label: 'AI 후보', tone: 'candidate' },
  HELD: { label: '보류', tone: 'held' },
  CONFIRMED: { label: '확인됨', tone: 'confirmed' },
  REJECTED: { label: '거절됨', tone: 'closed' },
  RETRACTED: { label: '철회됨', tone: 'closed' },
}

/** 결정 사건의 유형. 확정·변경만 현재 결정이 될 수 있다. */
export const EVENT_KIND_LABEL: Record<string, string> = {
  PROPOSAL: '제안',
  DISCUSSION: '논의',
  DEFERRAL: '보류(미룸)',
  DECISION: '확정',
  CHANGE: '변경',
  WITHDRAWAL: '철회',
}

export const EVENT_KINDS = Object.keys(EVENT_KIND_LABEL)

export const DECIDING_KINDS = new Set(['DECISION', 'CHANGE'])

const STATE_LABEL: Record<string, Record<string, string>> = {
  decisionState: {
    UNDECIDED: '아직 정해지지 않음',
    DECIDED: '결정됨',
    CONTESTED: '확정이 둘 겹침',
    WITHDRAWN: '유효한 결정 없음',
  },
  standing: {
    ACTIVE: '유효',
    SUPERSEDED: '다른 결정으로 대체됨',
    WITHDRAWN: '철회됨',
  },
  state: { UNMET: '아직 충족 안 됨', MET: '충족됨' },
  workStatus: {
    PLANNED: '예정',
    IN_PROGRESS: '진행 중',
    ON_HOLD: '멈춤',
    DONE: '끝남',
    CANCELLED: '취소',
  },
  acceptance: {
    PENDING: '담당자 수락 전',
    ACCEPTED: '담당자 수락',
    DECLINED: '담당자 거절',
  },
  kind: { ...EVENT_KIND_LABEL, PERSON: '사람', ORGANIZATION: '기관' },
}

export const ENTITY_KIND_LABEL: Record<string, string> = {
  PERSON: '사람',
  ORGANIZATION: '기관',
}

/** 카드 상태 값 하나를 읽을 수 있는 말로. 모르는 값은 그대로 둔다. */
export function stateLabel(name: string, value: string): string {
  return STATE_LABEL[name]?.[value] ?? value
}

/**
 * 묶음 주인 쪽에서 본 줄 이름. 줄은 기대는 쪽(src) 카드에 딸리므로 대부분 "나 → 건너편"이다.
 * 안건–항목, 항목–사건 줄만 아래쪽 카드(dst)에 딸려 "건너편 → 나"로 읽는다.
 */
export function relationLabel(rel: RelType, ownerIsSrc: boolean): string {
  if (!ownerIsSrc) {
    if (rel === 'HAS_ITEM') return '속한 안건'
    if (rel === 'HAS_EVENT') return '속한 결정 항목'
  }
  return OUTGOING[rel]
}

const OUTGOING: Record<RelType, string> = {
  HAS_ITEM: '딸린 결정 항목',
  HAS_EVENT: '딸린 사건',
  SUPERSEDES: '대체하는 결정',
  WITHDRAWS: '철회하는 결정',
  REQUIRES: '먼저 필요한 것',
  SATISFIED_BY: '충족시킨 것',
  DERIVED_FROM: '나온 곳',
  ASSIGNED_TO: '담당',
  EVIDENCED_BY: '근거',
  SPOKEN_BY: '말한 사람',
  MENTIONS: '언급한 대상',
  USES: '참고한 것',
  CONFLICTS_WITH: '충돌하는 것',
}

/** 저장된 시각(ISO)을 날짜만 지역화해 보여 준다. */
export function formatDay(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(
    new Date(iso)
  )
}
