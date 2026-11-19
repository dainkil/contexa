import { infiniteQueryOptions } from '@tanstack/react-query'
import { listReviews } from '@/api/reviewClient'
import type { NodeType, ReviewState, Uuid } from '@/api/types'

export const reviewsKey = (projectId: Uuid) =>
  ['projects', projectId, 'reviews'] as const

/** 검토함. 한 쪽씩 불러오고 "더 보기"로 다음 쪽을 잇는다. */
export const reviewsQueryOptions = (
  projectId: Uuid,
  state: ReviewState,
  ownerType?: NodeType
) =>
  infiniteQueryOptions({
    queryKey: [...reviewsKey(projectId), state, ownerType ?? 'ALL'] as const,
    queryFn: ({ pageParam }) =>
      listReviews(projectId, { state, ownerType, after: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
  })
