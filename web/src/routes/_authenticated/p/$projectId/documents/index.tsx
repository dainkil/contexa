import { createFileRoute } from '@tanstack/react-router'
import { Documents } from '@/features/documents'

export const Route = createFileRoute('/_authenticated/p/$projectId/documents/')(
  {
    component: Documents,
  }
)
