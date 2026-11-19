import { useMemo } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link, getRouteApi } from '@tanstack/react-router'
import { Eye, Loader2 } from 'lucide-react'
import type { ReviewState } from '@/api/types'
import { serverErrorMessage } from '@/lib/handle-server-error'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { documentsQueryOptions } from '@/features/documents/queries'
import { ProjectPage } from '@/features/project-pages/page-shell'
import { projectsQueryOptions } from '@/features/projects/queries'
import { FILTER_TYPES, type FilterType, TYPE_LABEL } from './labels'
import { reviewsQueryOptions } from './queries'
import { ReviewCard } from './review-card'

const route = getRouteApi('/_authenticated/p/$projectId/reviews')

const ALL = 'ALL'

/**
 * 검토함: AI가 뽑은 후보를 카드 묶음 단위로 확인·보류·거절·정정한다.
 * 탭(확인할 것 / 처리한 것)과 종류 거르기는 주소(?state=&type=)에 남는다.
 */
export function Reviews() {
  const { projectId } = route.useParams()
  const search = route.useSearch()
  const navigate = route.useNavigate()
  const state: ReviewState = search.state ?? 'OPEN'
  const type = search.type

  const projects = useQuery(projectsQueryOptions())
  const role = projects.data?.find((p) => p.id === projectId)?.role
  const canReview = role === 'OWNER' || role === 'REVIEWER'

  const documents = useQuery(documentsQueryOptions(projectId))
  const documentTitles = useMemo(
    () =>
      new Map(
        (documents.data ?? []).map((d) => [d.document.id, d.document.title])
      ),
    [documents.data]
  )

  const reviews = useInfiniteQuery(reviewsQueryOptions(projectId, state, type))
  const items = reviews.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <ProjectPage
      title='검토함'
      description='AI가 뽑은 후보를 근거 원문과 함께 보고, 카드 묶음 단위로 확인·보류·거절합니다. 확인한 것만 현재 결정과 할 일에 반영됩니다.'
    >
      <div className='mb-4 flex flex-wrap items-center justify-between gap-2'>
        <Tabs
          value={state}
          onValueChange={(value) =>
            navigate({
              search: (prev) => ({ ...prev, state: value as ReviewState }),
            })
          }
        >
          <TabsList>
            <TabsTrigger value='OPEN'>확인할 것</TabsTrigger>
            <TabsTrigger value='DONE'>처리한 것</TabsTrigger>
          </TabsList>
        </Tabs>
        <Select
          value={type ?? ALL}
          onValueChange={(value) =>
            navigate({
              search: (prev) => ({
                ...prev,
                type: value === ALL ? undefined : (value as FilterType),
              }),
            })
          }
        >
          <SelectTrigger aria-label='종류 거르기' className='w-40'>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>모든 종류</SelectItem>
            {FILTER_TYPES.map((t) => (
              <SelectItem key={t} value={t}>
                {TYPE_LABEL[t]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {role === 'MEMBER' && (
        <p className='mb-4 flex items-center gap-2 rounded-md border px-3 py-2 text-sm text-muted-foreground'>
          <Eye className='size-4 shrink-0' aria-hidden />
          확인·정정은 소유자와 검토자가 합니다. 구성원은 볼 수만 있습니다.
        </p>
      )}

      {reviews.isPending && (
        <div className='grid gap-3'>
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className='h-40 w-full' />
          ))}
        </div>
      )}

      {reviews.isError && (
        <p className='text-sm text-destructive'>
          {serverErrorMessage(reviews.error)}
        </p>
      )}

      {reviews.isSuccess && items.length === 0 && !reviews.hasNextPage && (
        <div className='flex flex-col items-center gap-2 rounded-md border border-dashed px-4 py-12 text-center'>
          {state === 'OPEN' ? (
            <>
              <p className='text-lg font-medium'>확인할 것이 없습니다.</p>
              <p className='max-w-md text-sm text-muted-foreground'>
                문서를 올리면 AI가 뽑은 결정·할 일 후보가 여기로 들어옵니다.
              </p>
              <Link
                to='/p/$projectId/documents'
                params={{ projectId }}
                className='text-sm font-medium text-primary hover:underline'
              >
                문서 올리러 가기
              </Link>
            </>
          ) : (
            <p className='text-sm text-muted-foreground'>
              아직 처리한 묶음이 없습니다.
            </p>
          )}
        </div>
      )}

      {items.length > 0 && (
        <div className='grid gap-3'>
          {items.map((item) => (
            <ReviewCard
              // 묶음이 바뀌면 고르던 판단과 고치던 값을 비운다
              key={`${item.id}:${item.version}`}
              projectId={projectId}
              item={item}
              canReview={canReview}
              documentTitles={documentTitles}
            />
          ))}
        </div>
      )}

      {reviews.hasNextPage && (
        <div className='mt-4 flex justify-center'>
          <Button
            variant='outline'
            disabled={reviews.isFetchingNextPage}
            onClick={() => reviews.fetchNextPage()}
          >
            {reviews.isFetchingNextPage && <Loader2 className='animate-spin' />}
            더 보기
          </Button>
        </div>
      )}
    </ProjectPage>
  )
}
