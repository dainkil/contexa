import { useEffect, useState } from 'react'
import { Link } from '@tanstack/react-router'
import {
  AlertCircle,
  CheckCircle2,
  Circle,
  Clock,
  Loader2,
  type LucideIcon,
} from 'lucide-react'
import type { VersionStatus } from '@/api/types'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { type Progress, type Tone, progressOf, waitingTooLong } from './status'

const TONE_ICON: Record<Tone, LucideIcon> = {
  muted: Clock,
  progress: Loader2,
  done: CheckCircle2,
  error: AlertCircle,
}

const TONE_CLASS: Record<Tone, string> = {
  muted: 'text-muted-foreground',
  progress: 'text-primary',
  done: 'text-emerald-700 dark:text-emerald-400',
  error: 'text-destructive',
}

/** 목록에 쓰는 짧은 처리 상태. */
export function StatusBadge({ version }: { version: VersionStatus | null }) {
  const progress = progressOf(version)
  const Icon = TONE_ICON[progress.tone]
  return (
    <Badge variant='outline' className={cn('gap-1', TONE_CLASS[progress.tone])}>
      <Icon
        aria-hidden
        className={cn(progress.tone === 'progress' && 'animate-spin')}
      />
      {progress.label}
    </Badge>
  )
}

/** 지금 시각. 기다리는 작업이 있을 때만 주기적으로 갱신한다. */
function useNow(enabled: boolean, intervalMs = 5000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!enabled) return
    const timer = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(timer)
  }, [enabled, intervalMs])
  return now
}

type StepState = 'todo' | 'doing' | 'done' | 'failed'

function stepStates(progress: Progress): [StepState, StepState] {
  switch (progress.stage) {
    case 'queued':
      return ['todo', 'todo']
    case 'parsing':
      return ['doing', 'todo']
    case 'extracting':
      return ['done', 'doing']
    case 'ready':
      return ['done', 'done']
    case 'failed':
      return progress.failedAt === 'parse'
        ? ['failed', 'todo']
        : ['done', 'failed']
    case 'cancelled':
    case 'unknown':
      return ['todo', 'todo']
  }
}

const STEP_ICON: Record<StepState, LucideIcon> = {
  todo: Circle,
  doing: Loader2,
  done: CheckCircle2,
  failed: AlertCircle,
}

function Step({ state, label }: { state: StepState; label: string }) {
  const Icon = STEP_ICON[state]
  return (
    <li
      className={cn(
        'flex items-center gap-1.5',
        state === 'todo' && 'text-muted-foreground',
        state === 'failed' && 'text-destructive'
      )}
    >
      <Icon
        aria-hidden
        className={cn(
          'size-4 shrink-0',
          state === 'doing' && 'animate-spin text-primary',
          state === 'done' && 'text-emerald-700 dark:text-emerald-400'
        )}
      />
      {label}
    </li>
  )
}

/**
 * 원문 뷰어 위쪽의 처리 단계: 발언으로 자르기 → 후보 뽑기 → 검토함.
 * 실패하면 시도 횟수와 오류 문장을, 오래 기다리면 일꾼을 확인하라는 안내를 보여 준다.
 */
export function ProcessingSteps({
  projectId,
  version,
}: {
  projectId: string
  version: VersionStatus | null
}) {
  const progress = progressOf(version)
  const now = useNow(progress.active)
  const [parse, extract] = stepStates(progress)
  const job = progress.job

  return (
    <section
      aria-label='처리 상태'
      className='grid gap-2 rounded-md border p-3 text-sm'
    >
      <div className='flex flex-wrap items-center gap-x-4 gap-y-1'>
        <StatusBadge version={version} />
        <ol className='flex flex-wrap items-center gap-x-4 gap-y-1'>
          <Step state={parse} label='발언으로 자르기' />
          <Step state={extract} label='후보 뽑기' />
        </ol>
        {progress.stage === 'ready' && (
          <Link
            to='/p/$projectId/reviews'
            params={{ projectId }}
            className='font-medium text-primary underline-offset-4 hover:underline'
          >
            검토함에서 확인하기
          </Link>
        )}
      </div>

      {waitingTooLong(progress, now) && (
        <p className='text-muted-foreground'>
          30초 넘게 처리를 시작하지 않았습니다. AI 일꾼이 켜져 있는지
          확인하세요.
        </p>
      )}

      {progress.stage === 'failed' && job && (
        <div className='text-destructive'>
          <p>
            {job.attempts}번 시도했지만 끝내지 못했습니다. 문서 내용은 그대로
            남아 있습니다.
          </p>
          {job.lastError && (
            <details className='mt-1'>
              <summary className='cursor-pointer'>오류 내용</summary>
              <p dir='auto' className='mt-1 break-words whitespace-pre-wrap'>
                {job.lastError}
              </p>
            </details>
          )}
        </div>
      )}
    </section>
  )
}
