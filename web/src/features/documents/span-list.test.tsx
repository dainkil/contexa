import { describe, expect, it, vi } from 'vitest'
import { render } from 'vitest-browser-react'
import type { Span } from '@/api/types'
import { SpanList } from './span-list'

function span(
  seq: number,
  text: string,
  speakerLabel: string | null,
  revision = 1
): Span {
  return {
    ref: {
      type: 'SPAN',
      id: `00000000-0000-4000-8000-00000000000${seq}`,
      revision,
    },
    projectId: 'p',
    documentId: 'd',
    sourceVersionId: 'v',
    seq,
    speakerLabel,
    text,
  }
}

const SPANS = [
  span(1, '예산 승인 전까지 인쇄는 보류입니다.', '민지'),
  span(2, 'الطباعة بعد الموافقة', 'أحمد'),
  span(3, 'OK, 그럼 다음 주에 다시 보죠.', null, 2),
]

describe('SpanList', () => {
  it('shows spans in order with speakers isolated and text direction left to the browser', async () => {
    const screen = await render(<SpanList spans={SPANS} />)

    const items = screen.getByRole('listitem').elements()
    expect(items.map((li) => li.querySelector('p')?.textContent)).toEqual(
      SPANS.map((s) => s.text)
    )
    expect(items[1].querySelector('bdi')?.textContent).toBe('أحمد')
    expect(
      items.every((li) => li.querySelector('p')?.getAttribute('dir') === 'auto')
    ).toBe(true)
    expect(items.some((li) => li.hasAttribute('data-highlighted'))).toBe(false)
    await expect.element(screen.getByText('정정됨')).toBeInTheDocument()
  })

  it('highlights the evidence span and scrolls to it', async () => {
    const scroll = vi.spyOn(Element.prototype, 'scrollIntoView')
    const target = SPANS[1].ref.id
    const screen = await render(<SpanList spans={SPANS} highlightId={target} />)

    const highlighted = screen
      .getByRole('listitem')
      .elements()
      .filter((li) => li.hasAttribute('data-highlighted'))
    expect(highlighted).toHaveLength(1)
    expect(highlighted[0].id).toBe(`span-${target}`)
    expect(highlighted[0].getAttribute('aria-current')).toBe('true')
    expect(highlighted[0].className).toContain('bg-evidence')
    expect(scroll).toHaveBeenCalledTimes(1)
    expect(scroll.mock.contexts[0]).toBe(highlighted[0])
    scroll.mockRestore()
  })

  it('offers a correction button per span when correcting is allowed', async () => {
    const onRevise = vi.fn()
    const screen = await render(<SpanList spans={SPANS} onRevise={onRevise} />)

    await screen.getByRole('button', { name: '3번 발언 정정' }).click()
    expect(onRevise).toHaveBeenCalledWith(SPANS[2])
  })

  it('has no correction buttons for someone who cannot correct', async () => {
    const screen = await render(<SpanList spans={SPANS} />)

    await expect
      .element(screen.getByText('예산 승인 전까지 인쇄는 보류입니다.'))
      .toBeInTheDocument()
    expect(screen.getByRole('button').elements()).toHaveLength(0)
  })
})
