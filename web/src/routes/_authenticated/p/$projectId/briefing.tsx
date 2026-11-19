import { createFileRoute } from '@tanstack/react-router'
import { BriefingPlaceholder } from '@/features/project-pages/briefing'

export const Route = createFileRoute('/_authenticated/p/$projectId/briefing')({
  component: BriefingPlaceholder,
})
