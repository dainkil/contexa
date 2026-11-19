import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render } from 'vitest-browser-react'
import { userEvent } from 'vitest/browser'
import type { Span } from '@/api/types'
import { ReviseSpanDialog } from './revise-span-dialog'

const api = vi.hoisted(() => ({ reviseSpan: vi.fn() }))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock('@/api/reviewClient', () => api)
vi.mock('sonner', () => ({ toast }))

const SPAN: Span = {
  ref: { type: 'SPAN', id: 's4', revision: 2 },
  projectId: 'p1',
  documentId: 'd1',
  sourceVersionId: 'v1',
  seq: 4,
  speakerLabel: '준호',
  text: '11월 28일에 인쇄 발주 넣을게요.',
}

describe('ReviseSpanDialog', () => {
  const onClose = vi.fn()

  beforeEach(() => vi.clearAllMocks())

  async function open() {
    return render(
      <QueryClientProvider client={new QueryClient()}>
        <ReviseSpanDialog projectId='p1' span={SPAN} onClose={onClose} />
      </QueryClientProvider>
    )
  }

  it('saves the corrected text with the revision I saw and reports the impact', async () => {
    api.reviseSpan.mockResolvedValue({
      node: { ...SPAN.ref, revision: 3 },
      impacts: [
        {
          changed: { type: 'SPAN', id: 's4' },
          affected: { type: 'TASK', id: 't1' },
          distance: 1,
        },
      ],
    })
    const screen = await open()
    const save = screen.getByRole('button', { name: '정정하기' })
    await expect.element(save).toBeDisabled()

    const text = screen.getByLabelText('준호')
    await userEvent.clear(text)
    await userEvent.fill(text, ' 12월 5일에 인쇄 발주 넣을게요. ')
    await userEvent.click(save)

    expect(api.reviseSpan).toHaveBeenCalledWith('p1', 's4', {
      expectedRevision: 2,
      text: '12월 5일에 인쇄 발주 넣을게요.',
    })
    await vi.waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(toast.success).toHaveBeenCalledWith(
      '발언을 정정했습니다. 이 발언에 기댄 카드 1개는 다시 확인이 필요합니다.'
    )
  })

  it('does not send an empty span', async () => {
    const screen = await open()

    await userEvent.clear(screen.getByLabelText('준호'))

    await expect
      .element(screen.getByRole('button', { name: '정정하기' }))
      .toBeDisabled()
  })
})
