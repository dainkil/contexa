import { createFileRoute } from '@tanstack/react-router'
import { ContextPlaceholder } from '@/features/project-pages/context'

export const Route = createFileRoute('/_authenticated/p/$projectId/context')({
  component: ContextPlaceholder,
})
