import { NotYet, ProjectPage } from './page-shell'

export function DocumentsPlaceholder() {
  return (
    <ProjectPage
      title='문서와 전사문'
      description='회의록을 올리고, 처리 상태와 원문을 확인합니다.'
    >
      <NotYet>
        다음 묶음에서 만듭니다: 문서 올리기, 처리 상태(작업), 원문 뷰어와 근거
        하이라이트.
      </NotYet>
    </ProjectPage>
  )
}
