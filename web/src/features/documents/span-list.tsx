import { useEffect, useRef } from 'react'
import { Pencil } from 'lucide-react'
import type { Span } from '@/api/types'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

/**
 * 한 판의 발언 목록. highlightId인 발언은 근거 하이라이트(노랑)로 칠하고 그 자리로 스크롤한다.
 * 원문은 고치지 않고 그대로 보여 준다. 언어가 섞이므로 본문은 dir="auto", 화자는 <bdi>.
 */
export function SpanList({
  spans,
  highlightId,
  onRevise,
}: {
  spans: Span[]
  highlightId?: string
  /** 있으면 발언마다 정정 버튼을 둔다(소유자·검토자만). */
  onRevise?: (span: Span) => void
}) {
  const highlighted = useRef<HTMLLIElement>(null)

  // 근거 발언이 바뀔 때만 스크롤한다. 처리 상태를 다시 불러와도 읽던 자리를 빼앗지 않는다.
  useEffect(() => {
    highlighted.current?.scrollIntoView({ block: 'center' })
  }, [highlightId])

  return (
    <ol aria-label='발언' className='grid gap-1'>
      {spans.map((span) => {
        const isHighlighted = span.ref.id === highlightId
        return (
          <li
            key={span.ref.id}
            id={`span-${span.ref.id}`}
            ref={isHighlighted ? highlighted : undefined}
            aria-current={isHighlighted ? 'true' : undefined}
            data-highlighted={isHighlighted ? '' : undefined}
            className={cn(
              'group flex scroll-mt-24 gap-3 rounded-md px-2 py-2',
              isHighlighted &&
                'bg-evidence text-evidence-foreground ring-2 ring-evidence-foreground/20'
            )}
          >
            <span
              className={cn(
                'w-8 shrink-0 pt-0.5 text-end text-xs tabular-nums',
                isHighlighted ? 'opacity-70' : 'text-muted-foreground'
              )}
              aria-label={`${span.seq}번 발언`}
            >
              {span.seq}
            </span>
            <div className='grid min-w-0 flex-1 gap-0.5'>
              {(span.speakerLabel || span.ref.revision > 1) && (
                <div className='flex flex-wrap items-center gap-2 text-sm font-semibold'>
                  {span.speakerLabel && <bdi>{span.speakerLabel}</bdi>}
                  {span.ref.revision > 1 && (
                    <Badge variant='outline' className='font-normal'>
                      정정됨
                    </Badge>
                  )}
                </div>
              )}
              <p dir='auto' className='break-words whitespace-pre-wrap'>
                {span.text}
              </p>
            </div>
            {onRevise && (
              <Button
                variant='ghost'
                size='icon'
                aria-label={`${span.seq}번 발언 정정`}
                title='발언 정정'
                // 넓은 화면에서는 줄에 마우스를 올리거나 키보드로 갔을 때만 보인다
                className='size-8 shrink-0 focus-visible:opacity-100 sm:opacity-0 sm:group-hover:opacity-100'
                onClick={() => onRevise(span)}
              >
                <Pencil />
              </Button>
            )}
          </li>
        )
      })}
    </ol>
  )
}
