import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { toast } from 'sonner'
import { reviseSpan } from '@/api/reviewClient'
import type { Span, Uuid } from '@/api/types'
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
import { revisedMessage } from './status'

/**
 * 발언 정정(전사 오류 고치기). 이전 내용은 server가 정정 이력으로 남긴다.
 * 내가 본 발언 버전을 함께 보내므로, 그사이 다른 사람이 고쳤으면 server가 막는다.
 * 열 때마다 부모가 key를 바꿔 입력칸을 그 발언 내용으로 다시 채운다.
 */
export function ReviseSpanDialog({
  projectId,
  span,
  onClose,
}: {
  projectId: Uuid
  span: Span
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [text, setText] = useState(span.text)
  const changed = text.trim().length > 0 && text.trim() !== span.text.trim()

  const revise = useMutation({
    mutationFn: () =>
      reviseSpan(projectId, span.ref.id, {
        expectedRevision: span.ref.revision,
        text: text.trim(),
      }),
    onSuccess: (result) => {
      toast.success(revisedMessage(result.impacts))
      onClose()
    },
    // 고쳤든 막혔든 원문과 검토함을 다시 읽는다
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: ['projects', projectId] }),
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className='max-h-[90svh] overflow-y-auto sm:max-w-xl'>
        <DialogHeader>
          <DialogTitle>{span.seq}번 발언 정정</DialogTitle>
          <DialogDescription>
            전사 오류만 고치세요. 이전 내용은 정정 이력으로 남고, 이 발언을
            근거로 쓴 카드는 다시 확인이 필요하다고 표시됩니다.
          </DialogDescription>
        </DialogHeader>
        <form
          id='revise-span'
          className='grid gap-2'
          onSubmit={(e) => {
            e.preventDefault()
            if (changed) revise.mutate()
          }}
        >
          <Label htmlFor='revise-span-text'>
            {span.speakerLabel ? <bdi>{span.speakerLabel}</bdi> : '내용'}
          </Label>
          <Textarea
            id='revise-span-text'
            dir='auto'
            rows={4}
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
        </form>
        <DialogFooter>
          <Button
            type='submit'
            form='revise-span'
            disabled={!changed || revise.isPending}
          >
            {revise.isPending && <Loader2 className='animate-spin' />}
            정정하기
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
