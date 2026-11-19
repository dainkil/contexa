import { describe, expect, it } from 'vitest'
import {
  buildAction,
  bundleVerdicts,
  canApply,
  edgeChoices,
  resultMessage,
} from './action'
import { edge, printTask, ref, view } from './test-fixtures'

describe('buildAction', () => {
  it('sends only the card verdict and lets the server apply it to the lines', () => {
    const action = buildAction(printTask(), 'CONFIRM', {})

    expect(action).toMatchObject({ card: 'CONFIRM', expectedVersion: 3 })
    expect(action).not.toHaveProperty('edges')
    expect(action).not.toHaveProperty('corrections')
  })

  it('adds the lines judged on their own', () => {
    const action = buildAction(printTask(), 'CONFIRM', {
      'e-hidden': 'REJECT',
      'e-req': 'FOLLOW',
    })

    expect(action?.edges).toEqual({ 'e-hidden': 'REJECT' })
  })

  it('saves corrections without a verdict', () => {
    const action = buildAction(printTask(), null, {}, { title: '포스터 인쇄' })

    expect(action).toMatchObject({ corrections: { title: '포스터 인쇄' } })
    expect(action).not.toHaveProperty('card')
  })

  it('returns null when there is nothing to send', () => {
    expect(buildAction(printTask(), null, {})).toBeNull()
  })

  it('writes the verdict on every line it applies to when the bundle has no card', () => {
    const span = ref('SPAN', 's1')
    const item = printTask({
      owner: span,
      card: null,
      edges: [
        edge('a', 'SPOKEN_BY', span, ref('ENTITY', 'p1')),
        edge('b', 'MENTIONS', span, ref('ENTITY', 'p2'), 'CONFIRMED'),
        edge('c', 'MENTIONS', span, ref('ENTITY', 'p3'), 'HELD'),
      ],
    })

    expect(buildAction(item, 'CONFIRM', {})?.edges).toEqual({
      a: 'CONFIRM',
      c: 'CONFIRM',
    })
    expect(buildAction(item, 'RETRACT', {})?.edges).toEqual({ b: 'RETRACT' })
    expect(buildAction(item, 'REJECT', { a: 'CONFIRM' })?.edges).toEqual({
      a: 'CONFIRM',
      c: 'REJECT',
    })
    expect(buildAction(item, 'CONFIRM', {})).not.toHaveProperty('card')
  })

  it('gives the same request the same key so a double click is applied once', () => {
    const key = (verdict: 'CONFIRM' | 'HOLD', version = 3) =>
      buildAction(printTask({ version }), verdict, {})?.idempotencyKey

    expect(key('CONFIRM')).toBe(key('CONFIRM'))
    expect(key('CONFIRM')).not.toBe(key('HOLD'))
    expect(key('CONFIRM')).not.toBe(key('CONFIRM', 4))
  })
})

describe('bundleVerdicts', () => {
  const withCard = (status: 'CANDIDATE' | 'HELD' | 'CONFIRMED' | 'REJECTED') =>
    printTask({ card: view('TASK', 't1', '인쇄 발주', status) })

  it('follows the card status', () => {
    expect(bundleVerdicts(withCard('CANDIDATE'))).toEqual([
      'CONFIRM',
      'HOLD',
      'REJECT',
    ])
    expect(bundleVerdicts(withCard('HELD'))).toEqual(['CONFIRM', 'REJECT'])
    expect(bundleVerdicts(withCard('REJECTED'))).toEqual([])
  })

  it('offers confirming new lines on an open bundle whose card is confirmed', () => {
    expect(bundleVerdicts(withCard('CONFIRMED'))).toEqual([
      'CONFIRM',
      'RETRACT',
    ])
    expect(bundleVerdicts({ ...withCard('CONFIRMED'), state: 'DONE' })).toEqual(
      ['RETRACT']
    )
  })

  it('looks at the lines when there is no card', () => {
    const span = ref('SPAN', 's1')
    const item = printTask({
      owner: span,
      card: null,
      edges: [edge('b', 'MENTIONS', span, ref('ENTITY', 'p2'), 'CONFIRMED')],
    })

    expect(bundleVerdicts(item)).toEqual(['RETRACT'])
  })
})

describe('edgeChoices', () => {
  const t = ref('TASK', 't1')
  const c = ref('CONDITION', 'c1')

  it('only offers moves the server allows', () => {
    expect(edgeChoices(edge('e', 'REQUIRES', t, c, 'CANDIDATE'))).toEqual([
      'FOLLOW',
      'CONFIRM',
      'HOLD',
      'REJECT',
    ])
    expect(edgeChoices(edge('e', 'REQUIRES', t, c, 'HELD'))).toEqual([
      'FOLLOW',
      'CONFIRM',
      'REJECT',
    ])
    expect(edgeChoices(edge('e', 'REQUIRES', t, c, 'CONFIRMED'))).toEqual([
      'FOLLOW',
      'RETRACT',
    ])
    expect(canApply('CONFIRM', 'REJECTED')).toBe(false)
  })
})

describe('resultMessage', () => {
  const impact = (affected: string) => ({
    changed: { type: 'TASK' as const, id: 't1' },
    affected: { type: 'TASK' as const, id: affected },
    distance: 1,
  })

  it('says how many cards need another look', () => {
    expect(
      resultMessage('CONFIRM', false, [impact('a'), impact('a'), impact('b')])
    ).toBe('확인했습니다. 이 변경에 기대는 카드 2개는 다시 확인이 필요합니다.')
    expect(resultMessage('CONFIRM', true, [])).toBe('고쳐서 확인했습니다.')
    expect(resultMessage(null, true, [])).toBe('고친 내용을 저장했습니다.')
    expect(resultMessage('REJECT', false, [])).toBe('거절했습니다.')
  })
})
