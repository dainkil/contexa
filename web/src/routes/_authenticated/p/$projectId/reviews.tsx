import { createFileRoute } from '@tanstack/react-router'
import { ReviewsPlaceholder } from '@/features/project-pages/reviews'

export const Route = createFileRoute('/_authenticated/p/$projectId/reviews')({
  component: ReviewsPlaceholder,
})
