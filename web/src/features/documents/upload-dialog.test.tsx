import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, type RenderResult } from 'vitest-browser-react'
import { userEvent } from 'vitest/browser'
import { UploadDialog } from './upload-dialog'

const navigate = vi.fn()
const api = vi.hoisted(() => ({
  uploadDocument: vi.fn(),
  uploadVersion: vi.fn(),
  listDocuments: vi.fn(),
  getDocument: vi.fn(),
}))

vi.mock('@/api/documentClient', () => api)
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return { ...actual, useNavigate: () => navigate }
})

describe('UploadDialog', () => {
  let screen: RenderResult
  const onOpenChange = vi.fn()

  beforeEach(async () => {
    vi.clearAllMocks()
    screen = await render(
      <QueryClientProvider client={new QueryClient()}>
        <UploadDialog projectId='p1' open onOpenChange={onOpenChange} />
      </QueryClientProvider>
    )
  })

  it('asks for a title and a body before uploading', async () => {
    await userEvent.click(screen.getByRole('button', { name: '올리기' }))

    await expect
      .element(screen.getByText('제목을 입력하세요.'))
      .toBeInTheDocument()
    await expect
      .element(screen.getByText('본문을 붙여 넣거나 파일을 불러오세요.'))
      .toBeInTheDocument()
    expect(api.uploadDocument).not.toHaveBeenCalled()
  })

  it('turns off outside sharing for a private memo', async () => {
    const share = screen.getByRole('checkbox', { name: /외부로 나가는 답변/ })
    await expect.element(share).toBeEnabled()
    await userEvent.click(
      screen.getByRole('radio', { name: '나만 (개인 메모)' })
    )
    await expect.element(share).toBeDisabled()
  })

  it('uploads and opens the new document', async () => {
    api.uploadDocument.mockResolvedValue({
      version: { documentId: 'doc-1', sourceVersionId: 'v-1', versionNo: 1 },
      jobId: 'job-1',
    })

    await userEvent.fill(
      screen.getByRole('textbox', { name: '제목' }),
      ' 3차 회의 '
    )
    await userEvent.fill(
      screen.getByRole('textbox', { name: '본문' }),
      '민지: 예산 승인 전까지 인쇄는 보류입니다.'
    )
    await userEvent.click(
      screen.getByRole('checkbox', { name: /외부로 나가는 답변/ })
    )
    await userEvent.click(screen.getByRole('button', { name: '올리기' }))

    await vi.waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({
        to: '/p/$projectId/documents/$documentId',
        params: { projectId: 'p1', documentId: 'doc-1' },
      })
    )
    expect(api.uploadDocument).toHaveBeenCalledWith('p1', {
      title: '3차 회의',
      kind: 'TRANSCRIPT',
      language: 'ko',
      visibility: 'PROJECT',
      externalOk: true,
      content: '민지: 예산 승인 전까지 인쇄는 보류입니다.',
    })
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
