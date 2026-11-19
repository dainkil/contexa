import type { NodeType, NodeView } from '@/api/types'
import { ENTITY_KIND_LABEL, EVENT_KINDS, EVENT_KIND_LABEL } from './labels'

/**
 * 검토하면서 고칠 수 있는 카드 값. server가 받는 이름과 같다
 * (decision/DecisionTask.updateFields, entity/EntityReview.updateFields).
 * 첫 칸은 카드 이름(label)으로 보이는 값이다.
 */
export interface FieldSpec {
  name: string
  label: string
  kind: 'text' | 'textarea' | 'date' | 'select'
  options?: { value: string; label: string }[]
  /** 비울 수 없는 값. 날짜는 비우면 "정하지 않음"이다. */
  required?: boolean
}

const options = (labels: Record<string, string>, values: string[]) =>
  values.map((value) => ({ value, label: labels[value] ?? value }))

export const FIELDS: Partial<Record<NodeType, FieldSpec[]>> = {
  TOPIC: [{ name: 'title', label: '제목', kind: 'text', required: true }],
  DECISION_ITEM: [
    { name: 'title', label: '제목', kind: 'text', required: true },
  ],
  DECISION_EVENT: [
    { name: 'statement', label: '내용', kind: 'textarea', required: true },
    {
      name: 'kind',
      label: '유형',
      kind: 'select',
      options: options(EVENT_KIND_LABEL, EVENT_KINDS),
      required: true,
    },
    { name: 'occurredAt', label: '일어난 날', kind: 'date' },
  ],
  CONDITION: [
    { name: 'description', label: '내용', kind: 'textarea', required: true },
  ],
  TASK: [
    { name: 'title', label: '제목', kind: 'text', required: true },
    { name: 'dueAt', label: '기한', kind: 'date' },
  ],
  ENTITY: [
    { name: 'name', label: '이름', kind: 'text', required: true },
    {
      name: 'kind',
      label: '종류',
      kind: 'select',
      options: options(ENTITY_KIND_LABEL, ['PERSON', 'ORGANIZATION']),
      required: true,
    },
  ],
}

export type FieldValues = Record<string, string>

export function fieldsOf(type: NodeType): FieldSpec[] {
  return FIELDS[type] ?? []
}

/** ISO 시각 → <input type="date"> 값(이 브라우저 기준 날짜). */
export function toDateInput(iso: string | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** <input type="date"> 값 → 그날 0시(이 브라우저 기준)의 ISO 시각. */
export function fromDateInput(value: string): string {
  const [y, m, d] = value.split('-').map(Number)
  return new Date(y, m - 1, d).toISOString()
}

/** 카드의 지금 값. */
export function currentValues(card: NodeView): FieldValues {
  const values: FieldValues = {}
  fieldsOf(card.ref.type).forEach((spec, i) => {
    const raw = i === 0 ? card.label : card.states[spec.name]
    values[spec.name] = spec.kind === 'date' ? toDateInput(raw) : (raw ?? '')
  })
  return values
}

/** 필수인데 비어 있는 칸의 이름표. */
export function missingFields(card: NodeView, values: FieldValues): string[] {
  return fieldsOf(card.ref.type)
    .filter((spec) => spec.required && !(values[spec.name] ?? '').trim())
    .map((spec) => spec.label)
}

/**
 * 바뀐 값만 server가 받는 모양(corrections)으로 만든다.
 * 글은 앞뒤 공백을 지우고, 날짜는 그날 0시의 ISO 시각으로, 지운 날짜는 null(정하지 않음)로 보낸다.
 */
export function changedFields(
  card: NodeView,
  values: FieldValues
): Record<string, unknown> {
  const before = currentValues(card)
  const changed: Record<string, unknown> = {}
  for (const spec of fieldsOf(card.ref.type)) {
    const now = (values[spec.name] ?? '').trim()
    if (now === before[spec.name].trim()) continue
    if (spec.kind === 'date') {
      changed[spec.name] = now === '' ? null : fromDateInput(now)
    } else {
      changed[spec.name] = now
    }
  }
  return changed
}
