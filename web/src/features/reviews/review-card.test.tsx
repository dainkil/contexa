import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render } from 'vitest-browser-react'
import { userEvent } from 'vitest/browser'
import type { ReviewItem } from '@/api/types'
import { ReviewCard } from './review-card'
import { printTask } from './test-fixtures'

const api = vi.hoisted(() => ({ applyReviewAction: vi.fn() }))

vi.mock('@/api/reviewClient', () => api)
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@tanstack/react-router', () => ({
  // 원문 링크가 어디로 가는지만 본다
  Link: ({
    to,
    params,
    search,
    children,
  }: {
    to: string
    params: Record<string, string>
    search: Record<string, string>
    children: React.ReactNode
  }) => (
    <a
      href={`${to.replace(/\$(\w+)/g, (_, name: string) => params[name])}?${new URLSearchParams(search)}`}
    >
      {children}
    </a>
  ),
}))

function show(item: ReviewItem, canReview = true) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ReviewCard
        projectId='p1'
        item={item}
        canReview={canReview}
        documentTitles={new Map([['doc-1', '3차 회의 전사문']])}
      />
    </QueryClientProvider>
  )
}

describe('ReviewCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.applyReviewAction.mockResolvedValue({ item: printTask(), impacts: [] })
  })

  it('shows the evidence quote with a link that highlights it in the source', async () => {
    const screen = await show(printTask())

    await expect
      .element(screen.getByRole('heading', { name: '인쇄 발주' }))
      .toBeInTheDocument()
    await expect
      .element(screen.getByText('예산 승인 전까지 인쇄는 보류입니다.'))
      .toBeInTheDocument()
    await expect
      .element(screen.getByRole('link', { name: '원문에서 보기' }))
      .toHaveAttribute('href', '/p/p1/documents/doc-1?span=s22')
    await expect
      .element(screen.getByText('3차 회의 전사문 22번 발언').first())
      .toBeInTheDocument()
  })

  it('names the card at the other end, and hides one whose evidence I cannot read', async () => {
    const screen = await show(printTask())

    await expect
      .element(screen.getByText('예산 승인', { exact: true }))
      .toBeInTheDocument()
    await expect
      .element(screen.getByText('조건 (볼 수 없는 카드)'))
      .toBeInTheDocument()
  })

  it('lets a member read but not judge', async () => {
    const screen = await show(printTask(), false)

    await expect
      .element(screen.getByText('예산 승인 전까지 인쇄는 보류입니다.'))
      .toBeInTheDocument()
    expect(screen.getByRole('button').elements()).toHaveLength(0)
    expect(screen.getByRole('combobox').elements()).toHaveLength(0)
  })

  it('confirms the bundle with the version I saw', async () => {
    const screen = await show(printTask())

    await userEvent.click(screen.getByRole('button', { name: '확인' }))

    expect(api.applyReviewAction).toHaveBeenCalledWith(
      'p1',
      'r1',
      expect.objectContaining({ card: 'CONFIRM', expectedVersion: 3 })
    )
  })

  it('rejects one line while confirming the card', async () => {
    const screen = await show(printTask())

    await userEvent.click(
      screen.getByRole('combobox', { name: '먼저 필요한 것 줄 판단' }).last()
    )
    await userEvent.click(screen.getByRole('option', { name: '이 줄만 거절' }))
    await userEvent.click(screen.getByRole('button', { name: '확인' }))

    expect(api.applyReviewAction).toHaveBeenCalledWith(
      'p1',
      'r1',
      expect.objectContaining({
        card: 'CONFIRM',
        edges: { 'e-hidden': 'REJECT' },
      })
    )
  })

  it('asks before rejecting, because it cannot be undone', async () => {
    const screen = await show(printTask())

    await userEvent.click(screen.getByRole('button', { name: '거절' }))
    expect(api.applyReviewAction).not.toHaveBeenCalled()
    await expect
      .element(screen.getByText('이 묶음을 거절할까요?'))
      .toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '거절' }).last())
    expect(api.applyReviewAction).toHaveBeenCalledWith(
      'p1',
      'r1',
      expect.objectContaining({ card: 'REJECT' })
    )
  })

  it('confirms with a corrected title, sending only what changed', async () => {
    const screen = await show(printTask())

    await userEvent.click(screen.getByRole('button', { name: '고치기' }))
    const title = screen.getByLabelText('제목')
    await userEvent.clear(title)
    await userEvent.fill(title, '포스터 인쇄 발주')
    await userEvent.click(screen.getByRole('button', { name: '고쳐서 확인' }))

    expect(api.applyReviewAction).toHaveBeenCalledWith(
      'p1',
      'r1',
      expect.objectContaining({
        card: 'CONFIRM',
        corrections: { title: '포스터 인쇄 발주' },
      })
    )
  })

  it('saves a changed event kind without judging the card', async () => {
    const screen = await show(
      printTask({
        card: {
          ...printTask().card!,
          ref: { type: 'DECISION_EVENT', id: 't1', revision: 1 },
          label: '승인되면 바로 인쇄 발주',
          states: { kind: 'DECISION', standing: 'ACTIVE' },
        },
      })
    )
    await expect.element(screen.getByText('확정')).toBeInTheDocument()
    expect(screen.getByText('유효').elements()).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: '고치기' }))
    const saveOnly = screen.getByRole('button', { name: '고친 내용만 저장' })
    await expect.element(saveOnly).toBeDisabled()
    await userEvent.click(screen.getByRole('combobox', { name: '유형' }))
    await userEvent.click(screen.getByRole('option', { name: '제안' }))
    await expect.element(saveOnly).toBeEnabled()
    await userEvent.click(saveOnly)

    const sent = api.applyReviewAction.mock.calls[0][2]
    expect(sent.corrections).toEqual({ kind: 'PROPOSAL' })
    expect(sent).not.toHaveProperty('card')
  })

  it('will not save an empty title', async () => {
    const screen = await show(printTask())

    await userEvent.click(screen.getByRole('button', { name: '고치기' }))
    await userEvent.clear(screen.getByLabelText('제목'))

    await expect
      .element(screen.getByText('비울 수 없는 칸: 제목'))
      .toBeInTheDocument()
    await expect
      .element(screen.getByRole('button', { name: '고쳐서 확인' }))
      .toBeDisabled()
  })
})
