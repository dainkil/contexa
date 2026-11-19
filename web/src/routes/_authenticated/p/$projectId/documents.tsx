import { createFileRoute } from '@tanstack/react-router'
import { DocumentsPlaceholder } from '@/features/project-pages/documents'

export const Route = createFileRoute('/_authenticated/p/$projectId/documents')({
  component: DocumentsPlaceholder,
})
