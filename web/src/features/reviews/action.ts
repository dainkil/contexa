import type { ReviewActionInput } from '@/api/reviewClient'
import type {
  Edge,
  Impact,
  ReviewItem,
  ReviewStatus,
  Uuid,
  Verdict,
} from '@/api/types'

/**
 * 검토 판단을 server 요청(ActionRequest)으로 만드는 규칙. 화면과 떼어 두고 테스트한다.
 * 상태 규칙은 server contract/ReviewStatus.canMoveTo, review/ReviewImpact.followCard와 같다.
 */

/** 줄 하나에 대한 고름. FOLLOW = 따로 정하지 않음(카드 판단이나 묶음 버튼을 따른다). */
export type EdgeChoice = 'FOLLOW' | Verdict

export const VERDICT_LABEL: Record<Verdict, string> = {
  CONFIRM: '확인',
  HOLD: '보류',
  REJECT: '거절',
  RETRACT: '철회',
}

const isPending = (status: ReviewStatus) =>
  status === 'CANDIDATE' || status === 'HELD'

/** 이 상태에 이 판단을 내릴 수 있나. */
export function canApply(verdict: Verdict, status: ReviewStatus): boolean {
  switch (verdict) {
    case 'CONFIRM':
    case 'REJECT':
      return isPending(status)
    case 'HOLD':
      return status === 'CANDIDATE'
    case 'RETRACT':
      return status === 'CONFIRMED'
  }
}

/**
 * 묶음 아래에 놓을 판단 버튼.
 * 카드가 있으면 카드 상태로 정한다. 이미 확인된 카드의 묶음이 열려 있으면 새로 들어온 줄이 있다는 뜻이라
 * "확인"(카드는 그대로, 후보 줄을 확인)과 "철회"를 둔다.
 * 카드가 없는 발언 묶음은 줄 상태로 정하고, 판단은 따로 고르지 않은 줄 모두에 적용한다.
 */
export function bundleVerdicts(item: ReviewItem): Verdict[] {
  const card = item.card
  if (card) {
    switch (card.status) {
      case 'CANDIDATE':
        return ['CONFIRM', 'HOLD', 'REJECT']
      case 'HELD':
        return ['CONFIRM', 'REJECT']
      case 'CONFIRMED':
        return item.state === 'OPEN' ? ['CONFIRM', 'RETRACT'] : ['RETRACT']
      default:
        return []
    }
  }
  const all: Verdict[] = ['CONFIRM', 'HOLD', 'REJECT', 'RETRACT']
  return all.filter((v) => item.edges.some((e) => canApply(v, e.status)))
}

/** 줄마다 고를 수 있는 것. 첫 값(FOLLOW)이 기본이다. */
export function edgeChoices(edge: Edge): EdgeChoice[] {
  const verdicts: Verdict[] = ['CONFIRM', 'HOLD', 'REJECT', 'RETRACT']
  return ['FOLLOW', ...verdicts.filter((v) => canApply(v, edge.status))]
}

/**
 * 판단 요청을 만든다. 보낼 것이 없으면 null.
 *
 * @param verdict     묶음 버튼으로 내린 판단. 고친 내용만 저장할 때는 null
 * @param choices     줄마다 따로 고른 판단
 * @param corrections 바뀐 카드 값(fields.changedFields)
 */
export function buildAction(
  item: ReviewItem,
  verdict: Verdict | null,
  choices: Record<Uuid, EdgeChoice>,
  corrections: Record<string, unknown> = {}
): ReviewActionInput | null {
  const edges: Record<Uuid, Verdict> = {}
  for (const edge of item.edges) {
    const choice = choices[edge.id] ?? 'FOLLOW'
    if (choice !== 'FOLLOW') {
      edges[edge.id] = choice
    } else if (!item.card && verdict && canApply(verdict, edge.status)) {
      // 카드가 없으면 server가 따라갈 카드 판단이 없으니 줄마다 적어 보낸다
      edges[edge.id] = verdict
    }
  }

  const body: Omit<ReviewActionInput, 'idempotencyKey'> = {
    expectedVersion: item.version,
  }
  if (item.card && verdict) body.card = verdict
  if (item.card && Object.keys(corrections).length > 0) {
    body.corrections = corrections
  }
  if (Object.keys(edges).length > 0) body.edges = edges
  if (!body.card && !body.corrections && !body.edges) return null

  // 같은 판에 같은 요청을 두 번 보내면(두 번 누름, 다시 보내기) server가 한 번만 처리한다.
  // 다른 요청은 다른 키가 되고, 그사이 묶음이 바뀌었으면 server가 409로 막는다.
  const key = `${item.id}:${item.version}:${hash(JSON.stringify(body))}`
  return { ...body, idempotencyKey: key }
}

/** FNV-1a 32비트. 요청 내용을 짧은 키로 줄이는 데만 쓴다. */
function hash(text: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return h.toString(36)
}

/** 처리한 뒤 알림 문장. 영향받는 카드가 있으면 다시 확인이 필요하다고 덧붙인다. */
export function resultMessage(
  verdict: Verdict | null,
  corrected: boolean,
  impacts: Impact[]
): string {
  let done: string
  if (verdict === 'CONFIRM')
    done = corrected ? '고쳐서 확인했습니다.' : '확인했습니다.'
  else if (verdict) done = `${VERDICT_LABEL[verdict]}했습니다.`
  else
    done = corrected ? '고친 내용을 저장했습니다.' : '줄 판단을 반영했습니다.'

  const affected = new Set(impacts.map((i) => i.affected.id)).size
  return affected > 0
    ? `${done} 이 변경에 기대는 카드 ${affected}개는 다시 확인이 필요합니다.`
    : done
}
