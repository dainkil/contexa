import { z } from 'zod'
import { createFileRoute } from '@tanstack/react-router'
import { DocumentViewer } from '@/features/documents/document-viewer'

/** version = 열 판 번호, span = 근거 발언 id(그 발언이 든 판을 열고 노랗게 칠한다). 둘 중 하나만 쓴다. */
const searchSchema = z.object({
  version: z.coerce.number().int().positive().optional().catch(undefined),
  span: z.uuid().optional().catch(undefined),
})

export const Route = createFileRoute(
  '/_authenticated/p/$projectId/documents/$documentId'
)({
  component: DocumentViewer,
  validateSearch: searchSchema,
})
