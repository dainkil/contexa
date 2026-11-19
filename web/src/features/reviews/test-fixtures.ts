import type {
  Edge,
  NodeRef,
  NodeType,
  NodeView,
  RelType,
  ReviewItem,
  ReviewStatus,
  Source,
} from '@/api/types'

/** 검토 화면 테스트용 묶음 만들기. */

export const ref = (type: NodeType, id: string, revision = 1): NodeRef => ({
  type,
  id,
  revision,
})

export function view(
  type: NodeType,
  id: string,
  label: string,
  status: ReviewStatus = 'CANDIDATE',
  states: Record<string, string> = {}
): NodeView {
  return {
    ref: ref(type, id),
    projectId: 'p1',
    status,
    label,
    states,
    confirmedBy: null,
    confirmedAt: null,
  }
}

export function edge(
  id: string,
  rel: RelType,
  src: NodeRef,
  dst: NodeRef,
  status: ReviewStatus = 'CANDIDATE',
  evidenceSpanIds: string[] = []
): Edge {
  return {
    id,
    projectId: 'p1',
    rel,
    src,
    dst,
    status,
    evidenceSpanIds,
    confirmedBy: null,
    confirmedAt: null,
    ontologyVersion: '1',
    version: 1,
  }
}

export function source(
  id: string,
  seq: number,
  text: string,
  documentId = 'doc-1',
  speaker: string | null = '민지'
): Source {
  return { span: ref('SPAN', id), documentId, seq, speaker, text }
}

/**
 * 할 일 "인쇄 발주" 묶음: 22번 발언이 근거이고, 조건 "예산 승인"을 먼저 필요로 하며,
 * 남의 개인 메모에서 나온 조건 하나에도 기댄다(그 카드는 볼 수 없다).
 */
export function printTask(overrides: Partial<ReviewItem> = {}): ReviewItem {
  const task = view('TASK', 't1', '인쇄 발주', 'CANDIDATE', {
    workStatus: 'PLANNED',
    acceptance: 'PENDING',
  })
  return {
    id: 'r1',
    version: 3,
    state: 'OPEN',
    owner: task.ref,
    card: task,
    edges: [
      edge('e-ev', 'EVIDENCED_BY', task.ref, ref('SPAN', 's22'), 'CANDIDATE', [
        's22',
      ]),
      edge('e-req', 'REQUIRES', task.ref, ref('CONDITION', 'c1'), 'CANDIDATE', [
        's22',
      ]),
      edge(
        'e-hidden',
        'REQUIRES',
        task.ref,
        ref('CONDITION', 'c-memo'),
        'CANDIDATE',
        ['s-memo']
      ),
    ],
    related: [view('CONDITION', 'c1', '예산 승인')],
    sources: [source('s22', 22, '예산 승인 전까지 인쇄는 보류입니다.')],
    ...overrides,
  }
}
