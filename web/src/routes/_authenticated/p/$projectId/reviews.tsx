import { z } from 'zod'
import { createFileRoute } from '@tanstack/react-router'
import { Reviews } from '@/features/reviews'
import { FILTER_TYPES } from '@/features/reviews/labels'

/** state = 탭(OPEN 확인할 것, DONE 처리한 것), type = 카드 종류 거르기. */
const searchSchema = z.object({
  state: z.enum(['OPEN', 'DONE']).optional().catch(undefined),
  type: z.enum(FILTER_TYPES).optional().catch(undefined),
})

export const Route = createFileRoute('/_authenticated/p/$projectId/reviews')({
  component: Reviews,
  validateSearch: searchSchema,
})
