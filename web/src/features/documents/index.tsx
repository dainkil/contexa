import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, getRouteApi } from '@tanstack/react-router'
import { Lock, Upload } from 'lucide-react'
import { serverErrorMessage } from '@/lib/handle-server-error'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { ProjectPage } from '@/features/project-pages/page-shell'
import { StatusBadge } from './processing-status'
import { documentsQueryOptions } from './queries'
import { KIND_LABEL, formatInstant, languageLabel } from './status'
import { UploadDialog } from './upload-dialog'

const route = getRouteApi('/_authenticated/p/$projectId')

/** 문서 목록: 올린 문서와 가장 최근 판의 처리 상태. 처리 중이면 저절로 갱신된다. */
export function Documents() {
  const { projectId } = route.useParams()
  const documents = useQuery(documentsQueryOptions(projectId))
  const [uploading, setUploading] = useState(false)

  return (
    <ProjectPage
      title='문서와 전사문'
      description='회의록과 업무 문서를 올리고, 처리 상태와 원문을 확인합니다.'
      actions={
        <Button onClick={() => setUploading(true)}>
          <Upload />
          문서 올리기
        </Button>
      }
    >
      {documents.isPending && (
        <div className='grid gap-2'>
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className='h-10 w-full' />
          ))}
        </div>
      )}

      {documents.isError && (
        <p className='text-sm text-destructive'>
          {serverErrorMessage(documents.error)}
        </p>
      )}

      {documents.isSuccess && documents.data.length === 0 && (
        <div className='flex flex-col items-center gap-3 rounded-md border border-dashed px-4 py-12 text-center'>
          <p className='text-lg font-medium'>
            다음 회의 전에, 지난 회의록부터 올려 보세요.
          </p>
          <p className='max-w-md text-sm text-muted-foreground'>
            올린 회의록은 발언 단위로 잘리고, AI가 뽑은 결정·할 일 후보는
            검토함에서 사람이 확인한 뒤에만 확정됩니다.
          </p>
          <Button onClick={() => setUploading(true)}>
            <Upload />
            문서 올리기
          </Button>
        </div>
      )}

      {documents.isSuccess && documents.data.length > 0 && (
        <div className='rounded-md border'>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>제목</TableHead>
                <TableHead className='hidden md:table-cell'>종류</TableHead>
                <TableHead className='hidden md:table-cell'>언어</TableHead>
                <TableHead className='hidden sm:table-cell'>판</TableHead>
                <TableHead>처리 상태</TableHead>
                <TableHead className='hidden lg:table-cell'>올린 때</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {documents.data.map(({ document, versionCount, latest }) => (
                <TableRow key={document.id}>
                  <TableCell className='max-w-[16rem] sm:max-w-md'>
                    <div className='flex items-center gap-2'>
                      <Link
                        to='/p/$projectId/documents/$documentId'
                        params={{ projectId, documentId: document.id }}
                        className='truncate font-medium hover:underline'
                      >
                        <bdi>{document.title}</bdi>
                      </Link>
                      {document.visibility === 'PRIVATE' && (
                        <Badge variant='secondary' className='shrink-0'>
                          <Lock aria-hidden />
                          나만 보기
                        </Badge>
                      )}
                    </div>
                  </TableCell>
                  <TableCell className='hidden md:table-cell'>
                    {KIND_LABEL[document.kind]}
                  </TableCell>
                  <TableCell className='hidden md:table-cell'>
                    {languageLabel(document.language)}
                  </TableCell>
                  <TableCell className='hidden sm:table-cell'>
                    {versionCount}판
                  </TableCell>
                  <TableCell>
                    <StatusBadge version={latest} />
                  </TableCell>
                  <TableCell className='hidden text-muted-foreground lg:table-cell'>
                    {formatInstant(latest?.createdAt ?? document.createdAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <UploadDialog
        projectId={projectId}
        open={uploading}
        onOpenChange={setUploading}
      />
    </ProjectPage>
  )
}
