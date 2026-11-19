import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Loader2, Upload } from 'lucide-react'
import { toast } from 'sonner'
import { uploadVersion } from '@/api/documentClient'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { documentsKey } from './queries'
import { TextFileButton } from './text-file-button'

/**
 * 같은 문서의 새 판 올리기. 이전 판과 그 발언은 그대로 남는다.
 * 내용이 이전 판과 같으면 server가 거절한다(오류 문장은 토스트로 뜬다).
 */
export function NewVersionDialog({
  projectId,
  documentId,
  nextVersionNo,
  open,
  onOpenChange,
}: {
  projectId: string
  documentId: string
  nextVersionNo: number
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [content, setContent] = useState('')
  const [touched, setTouched] = useState(false)
  const empty = content.trim().length === 0

  const upload = useMutation({
    mutationFn: (value: string) => uploadVersion(projectId, documentId, value),
    onSuccess: async (started) => {
      toast.success(`${started.version.versionNo}판을 올렸습니다.`)
      setContent('')
      setTouched(false)
      onOpenChange(false)
      await queryClient.invalidateQueries({ queryKey: documentsKey(projectId) })
      navigate({
        to: '/p/$projectId/documents/$documentId',
        params: { projectId, documentId },
        search: {},
      })
    },
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='max-h-[90svh] overflow-y-auto sm:max-w-2xl'>
        <DialogHeader>
          <DialogTitle>{nextVersionNo}판 올리기</DialogTitle>
          <DialogDescription>
            고친 회의록 전체를 붙여 넣으세요. 이전 판과 그 판의 발언·근거는
            지워지지 않습니다.
          </DialogDescription>
        </DialogHeader>
        <form
          id='upload-version'
          className='grid gap-2'
          onSubmit={(e) => {
            e.preventDefault()
            setTouched(true)
            if (!empty) upload.mutate(content)
          }}
        >
          <div className='flex flex-wrap items-center justify-between gap-2'>
            <Label htmlFor='version-content'>본문</Label>
            <TextFileButton onLoad={(text) => setContent(text)} />
          </div>
          <Textarea
            id='version-content'
            dir='auto'
            rows={10}
            className='max-h-[50svh] font-mono text-sm'
            value={content}
            aria-invalid={touched && empty}
            onChange={(e) => setContent(e.target.value)}
          />
          {touched && empty && (
            <p className='text-sm text-destructive'>
              본문을 붙여 넣거나 파일을 불러오세요.
            </p>
          )}
        </form>
        <DialogFooter>
          <Button
            type='submit'
            form='upload-version'
            disabled={upload.isPending}
          >
            {upload.isPending ? (
              <Loader2 className='animate-spin' />
            ) : (
              <Upload />
            )}
            올리기
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
