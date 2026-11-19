import { NotYet, ProjectPage } from './page-shell'

export function BriefingPlaceholder() {
  return (
    <ProjectPage
      title='브리핑'
      description='새로 들어온 사람을 위한 인수인계 브리핑입니다.'
    >
      <NotYet>다음 묶음에서 만듭니다.</NotYet>
    </ProjectPage>
  )
}
