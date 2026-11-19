import { useQuery } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { currentState } from '@/api/contextClient'
import { serverErrorMessage } from '@/lib/handle-server-error'
import { NotYet, ProjectPage } from './page-shell'

export function ContextPlaceholder() {
  const { projectId } = useParams({ from: '/_authenticated/p/$projectId' })
  const state = useQuery({
    queryKey: ['projects', projectId, 'context', 'state'],
    queryFn: () => currentState(projectId),
  })

  return (
    <ProjectPage
      title='맥락과 할 일'
      description='지금 무엇이 결정됐는지, 누가 무엇을 해야 하는지, 무엇이 남았는지 봅니다.'
    >
      <NotYet>
        {state.isPending && '현재 상태를 불러오는 중…'}
        {state.isError && serverErrorMessage(state.error)}
        {state.isSuccess && (
          <>
            사실 {state.data.facts.length}건 · 미해결 {state.data.open.length}건
            · 기준 시점{' '}
            <time dateTime={state.data.asOf}>
              {new Date(state.data.asOf).toLocaleString()}
            </time>
            . 화면은 다음 묶음에서 만듭니다.
          </>
        )}
      </NotYet>
    </ProjectPage>
  )
}
