import { useQuery } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { listReviews } from '@/api/reviewClient'
import { serverErrorMessage } from '@/lib/handle-server-error'
import { NotYet, ProjectPage } from './page-shell'

export function ReviewsPlaceholder() {
  const { projectId } = useParams({ from: '/_authenticated/p/$projectId' })
  const reviews = useQuery({
    queryKey: ['projects', projectId, 'reviews', 'OPEN'],
    queryFn: () => listReviews(projectId),
  })

  return (
    <ProjectPage
      title='검토함'
      description='AI가 뽑은 후보를 카드 묶음 단위로 확인·정정·보류·거절합니다.'
    >
      <NotYet>
        {reviews.isPending && '검토할 묶음을 불러오는 중…'}
        {reviews.isError && serverErrorMessage(reviews.error)}
        {reviews.isSuccess &&
          `확인할 것이 남은 묶음 ${reviews.data.length}개. 검토 화면은 다음 묶음에서 만듭니다.`}
      </NotYet>
    </ProjectPage>
  )
}
