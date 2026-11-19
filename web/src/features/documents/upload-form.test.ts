import { describe, expect, it } from 'vitest'
import { DEFAULTS, toDocumentInput } from './upload-form'

describe('toDocumentInput', () => {
  it('trims the title and keeps the body as written', () => {
    const body = '  민지: 예산 승인 전까지 인쇄는 보류입니다.\n'
    expect(
      toDocumentInput({ ...DEFAULTS, title: '  3차 회의  ', content: body })
    ).toEqual({
      title: '3차 회의',
      kind: 'TRANSCRIPT',
      language: 'ko',
      visibility: 'PROJECT',
      externalOk: false,
      content: body,
    })
  })

  it('sends null for a document that mixes languages', () => {
    expect(
      toDocumentInput({
        ...DEFAULTS,
        title: 't',
        content: 'c',
        language: 'mixed',
      }).language
    ).toBeNull()
  })

  it('never shares a private memo outside', () => {
    expect(
      toDocumentInput({
        ...DEFAULTS,
        title: 't',
        content: 'c',
        visibility: 'PRIVATE',
        externalOk: true,
      }).externalOk
    ).toBe(false)
    expect(
      toDocumentInput({
        ...DEFAULTS,
        title: 't',
        content: 'c',
        externalOk: true,
      }).externalOk
    ).toBe(true)
  })
})
