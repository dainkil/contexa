import { describe, expect, it } from 'vitest'
import {
  changedFields,
  currentValues,
  fromDateInput,
  missingFields,
  toDateInput,
} from './fields'
import { view } from './test-fixtures'

// 이 브라우저 기준 12월 5일 0시. 테스트가 어느 시간대에서 돌아도 같은 날짜가 되게 만든다.
const dec5 = new Date(2026, 11, 5).toISOString()

describe('currentValues', () => {
  it('reads the label and the date fields of each card type', () => {
    const task = view('TASK', 't1', '인쇄 발주', 'CANDIDATE', { dueAt: dec5 })
    expect(currentValues(task)).toEqual({
      title: '인쇄 발주',
      dueAt: '2026-12-05',
    })

    const event = view('DECISION_EVENT', 'e1', '대면 개최', 'CANDIDATE', {
      kind: 'PROPOSAL',
    })
    expect(currentValues(event)).toEqual({
      statement: '대면 개최',
      kind: 'PROPOSAL',
      occurredAt: '',
    })
  })
})

describe('changedFields', () => {
  const task = view('TASK', 't1', '인쇄 발주', 'CANDIDATE', { dueAt: dec5 })

  it('sends nothing when nothing changed, even with extra spaces', () => {
    expect(
      changedFields(task, { title: ' 인쇄 발주 ', dueAt: '2026-12-05' })
    ).toEqual({})
  })

  it('sends only the changed values, dates as instants, and a cleared date as null', () => {
    expect(
      changedFields(task, { title: '포스터 인쇄', dueAt: '2026-12-05' })
    ).toEqual({ title: '포스터 인쇄' })
    expect(
      changedFields(task, { title: '인쇄 발주', dueAt: '2026-12-12' })
    ).toEqual({ dueAt: new Date(2026, 11, 12).toISOString() })
    expect(changedFields(task, { title: '인쇄 발주', dueAt: '' })).toEqual({
      dueAt: null,
    })
  })

  it('catches a proposal that was taken as a decision', () => {
    const event = view('DECISION_EVENT', 'e1', '대면 개최', 'CANDIDATE', {
      kind: 'DECISION',
    })
    expect(
      changedFields(event, {
        statement: '대면 개최',
        kind: 'PROPOSAL',
        occurredAt: '',
      })
    ).toEqual({ kind: 'PROPOSAL' })
  })
})

describe('missingFields', () => {
  it('names required fields that are empty', () => {
    const condition = view('CONDITION', 'c1', '예산 승인')
    expect(missingFields(condition, { description: '  ' })).toEqual(['내용'])
    expect(missingFields(condition, { description: '예산 승인' })).toEqual([])
  })
})

describe('date input', () => {
  it('round-trips a local date', () => {
    expect(toDateInput(fromDateInput('2026-02-28'))).toBe('2026-02-28')
    expect(toDateInput(undefined)).toBe('')
  })
})
