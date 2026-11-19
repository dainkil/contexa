import { describe, expect, it } from 'vitest'
import { safeRedirect } from './safe-redirect'

describe('safeRedirect', () => {
  it('keeps an in-app path', () => {
    expect(safeRedirect('/p/abc/reviews?tab=1')).toBe('/p/abc/reviews?tab=1')
  })

  it.each([
    [undefined],
    [''],
    ['https://evil.example/'],
    ['//evil.example/'],
    ['/\\evil.example'],
    ['javascript:alert(1)'],
  ])('falls back for %s', (value) => {
    expect(safeRedirect(value)).toBe('/projects')
  })
})
