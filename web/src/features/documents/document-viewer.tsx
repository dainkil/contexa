import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, getRouteApi } from '@tanstack/react-router'
import { ArrowLeft, FilePlus2, Globe, Lock } from 'lucide-react'
import type { Span } from '@/api/types'
import { serverErrorMessage } from '@/lib/handle-server-error'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { ProjectPage } from '@/features/project-pages/page-shell'
import { projectsQueryOptions } from '@/features/projects/queries'
import { NewVersionDialog } from './new-version-dialog'
import { ProcessingSteps } from './processing-status'
import { documentQueryOptions } from './queries'
import { ReviseSpanDialog } from './revise-span-dialog'
import { SpanList } from './span-list'
import { KIND_LABEL, formatInstant, languageLabel } from './status'

const route = getRouteApi('/_authenticated/p/$projectId/documents/$documentId')

/**
 * 원문 뷰어. 판을 골라 발언을 순서대로 본다.
 * 주소에 span(근거 발언 id)이 있으면 그 발언이 들어 있는 판을 열고 노란색으로 칠한다.
 */
export function DocumentViewer() {
  const { projectId, documentId } = route.useParams()
  const search = route.useSearch()
  const navigate = route.useNavigate()
  const doc = useQuery(documentQueryOptions(projectId, documentId, search))
  const [addingVersion, setAddingVersion] = useState(false)
  const [revising, setRevising] = useState<Span | null>(null)
  const role = useQuery(projectsQueryOptions()).data?.find(
    (p) => p.id === projectId
  )?.role
  const canRevise = role === 'OWNER' || role === 'REVIEWER'

  const backLink = (
    <Link
      to='/p/$projectId/documents'
      params={{ projectId }}
      className='mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground'
    >
      <ArrowLeft className='size-4 rtl:rotate-180' aria-hidden />
      문서 목록
    </Link>
  )

  if (doc.isPending) {
    return (
      <ProjectPage title='문서' description='불러오는 중…' before={backLink}>
        <div className='grid gap-2'>
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className='h-10 w-full' />
          ))}
        </div>
      </ProjectPage>
    )
  }

  if (doc.isError) {
    return (
      <ProjectPage
        title='이 문서를 열 수 없습니다'
        description='없는 문서이거나, 다른 사람의 개인 메모이거나, 이 프로젝트의 문서가 아닙니다.'
        before={backLink}
      >
        <p className='text-sm text-destructive'>
          {serverErrorMessage(doc.error)}
        </p>
      </ProjectPage>
    )
  }

  const { document, versions, opened } = doc.data
  const openedVersion =
    versions.find((v) => v.sourceVersionId === opened.sourceVersionId) ?? null
  const latestNo = versions[versions.length - 1]?.versionNo ?? opened.versionNo
  const highlighted = search.span
    ? opened.spans.find((s) => s.ref.id === search.span)
    : undefined

  return (
    <ProjectPage
      before={backLink}
      title={<bdi>{document.title}</bdi>}
      description={
        <div className='mt-1 flex flex-wrap items-center gap-2 text-sm'>
          <Badge variant='secondary'>{KIND_LABEL[document.kind]}</Badge>
          <Badge variant='outline'>{languageLabel(document.language)}</Badge>
          {document.visibility === 'PRIVATE' ? (
            <Badge variant='outline'>
              <Lock aria-hidden />
              나만 보기
            </Badge>
          ) : (
            <Badge variant='outline'>구성원 모두</Badge>
          )}
          {document.externalOk && (
            <Badge variant='outline'>
              <Globe aria-hidden />
              외부 공유 허용
            </Badge>
          )}
        </div>
      }
      actions={
        <>
          <Select
            value={String(opened.versionNo)}
            onValueChange={(value) =>
              navigate({ search: { version: Number(value) } })
            }
          >
            <SelectTrigger aria-label='판 고르기' className='w-auto'>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {versions.map((v) => (
                <SelectItem key={v.versionNo} value={String(v.versionNo)}>
                  {v.versionNo}판 · {formatInstant(v.createdAt)}
                  {v.versionNo === latestNo && ' (최신)'}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button variant='outline' onClick={() => setAddingVersion(true)}>
            <FilePlus2 />새 판 올리기
          </Button>
        </>
      }
    >
      <div className='grid gap-4'>
        <ProcessingSteps projectId={projectId} version={openedVersion} />

        {opened.versionNo !== latestNo && (
          <p className='rounded-md border px-3 py-2 text-sm break-keep text-muted-foreground'>
            {`지금 보는 것은 ${opened.versionNo}판입니다. 최신은 ${latestNo}판입니다. `}
            <Link
              to='/p/$projectId/documents/$documentId'
              params={{ projectId, documentId }}
              search={{}}
              className='font-medium whitespace-nowrap text-primary hover:underline'
            >
              최신 판 보기
            </Link>
          </p>
        )}

        {highlighted && (
          <p className='text-sm text-muted-foreground'>
            근거로 연결된 {highlighted.seq}번 발언을 노란색으로 표시했습니다.
          </p>
        )}

        {opened.spans.length > 0 ? (
          <SpanList
            spans={opened.spans}
            highlightId={search.span}
            onRevise={canRevise ? setRevising : undefined}
          />
        ) : (
          <div className='grid gap-2'>
            <p className='text-sm text-muted-foreground'>
              아직 발언으로 자르지 않았습니다. 자르기가 끝나면 발언마다 번호가
              붙고, 근거 링크로 바로 찾아올 수 있습니다.
            </p>
            <pre
              dir='auto'
              className='rounded-md border bg-muted/40 p-3 font-sans text-sm break-words whitespace-pre-wrap'
            >
              {opened.content}
            </pre>
          </div>
        )}
      </div>

      {revising && (
        <ReviseSpanDialog
          key={`${revising.ref.id}:${revising.ref.revision}`}
          projectId={projectId}
          span={revising}
          onClose={() => setRevising(null)}
        />
      )}

      <NewVersionDialog
        projectId={projectId}
        documentId={documentId}
        nextVersionNo={latestNo + 1}
        open={addingVersion}
        onOpenChange={setAddingVersion}
      />
    </ProjectPage>
  )
}
