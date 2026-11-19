import type { DocumentKind, Impact, JobBrief, VersionStatus } from '@/api/types'

/**
 * 문서 한 판의 처리 단계. 판 하나에는 작업이 두 개 붙는다.
 *   parse(발언으로 자르기, 규칙) → extract(카드·줄 후보 뽑기, 모델) → 검토함
 */
export type Stage =
  | 'queued' // 자르기 작업이 일꾼을 기다린다
  | 'parsing'
  | 'extracting' // 잘랐고, 후보 뽑기를 기다리거나 하는 중
  | 'ready' // 후보를 검토함에 넣었다(또는 처리 기록 없이 발언만 있다)
  | 'failed'
  | 'cancelled'
  | 'unknown' // 처리 기록도 발언도 없다

export type Tone = 'muted' | 'progress' | 'done' | 'error'

export interface Progress {
  stage: Stage
  label: string
  tone: Tone
  /** 아직 끝나지 않아서 다시 불러와야 하나 */
  active: boolean
  /** 실패했거나 기다리는 작업. 시도 횟수·오류·기다린 시간을 보여 줄 때 쓴다 */
  job: JobBrief | null
  /** 실패했다면 어느 단계에서 */
  failedAt?: 'parse' | 'extract'
}

export function progressOf(version: VersionStatus | null): Progress {
  if (!version) {
    return {
      stage: 'unknown',
      label: '처리 기록 없음',
      tone: 'muted',
      active: false,
      job: null,
    }
  }
  const { parse, extract, spanCount } = version

  if (!parse) {
    // 예시 데이터처럼 작업 없이 발언만 들어간 판
    return spanCount > 0
      ? {
          stage: 'ready',
          label: '처리 완료',
          tone: 'done',
          active: false,
          job: null,
        }
      : {
          stage: 'unknown',
          label: '처리 기록 없음',
          tone: 'muted',
          active: false,
          job: null,
        }
  }
  switch (parse.status) {
    case 'QUEUED':
      return {
        stage: 'queued',
        label: '처리 대기',
        tone: 'muted',
        active: true,
        job: parse,
      }
    case 'RUNNING':
      return {
        stage: 'parsing',
        label: '발언으로 자르는 중',
        tone: 'progress',
        active: true,
        job: parse,
      }
    case 'FAILED':
      return {
        stage: 'failed',
        label: '자르기 실패',
        tone: 'error',
        active: false,
        job: parse,
        failedAt: 'parse',
      }
    case 'CANCELLED':
      return {
        stage: 'cancelled',
        label: '처리 취소됨',
        tone: 'muted',
        active: false,
        job: parse,
      }
    case 'SUCCEEDED':
      break
  }

  // 자르기가 끝나면 server가 같은 트랜잭션에서 뽑기 작업을 올린다
  switch (extract?.status) {
    case undefined:
    case 'QUEUED':
      return {
        stage: 'extracting',
        label: '후보 뽑기 대기',
        tone: 'progress',
        active: true,
        job: extract ?? null,
      }
    case 'RUNNING':
      return {
        stage: 'extracting',
        label: '후보 뽑는 중',
        tone: 'progress',
        active: true,
        job: extract,
      }
    case 'FAILED':
      return {
        stage: 'failed',
        label: '후보 뽑기 실패',
        tone: 'error',
        active: false,
        job: extract,
        failedAt: 'extract',
      }
    case 'CANCELLED':
      return {
        stage: 'cancelled',
        label: '처리 취소됨',
        tone: 'muted',
        active: false,
        job: extract,
      }
    case 'SUCCEEDED':
      return {
        stage: 'ready',
        label: '처리 완료',
        tone: 'done',
        active: false,
        job: null,
      }
  }
}

/** 진행 중인 작업이 있으면 이 간격(ms)으로 다시 불러온다. */
export const POLL_MS = 2000

/** 일꾼이 이보다 오래 작업을 가져가지 않으면 일꾼이 켜져 있는지 확인하라고 안내한다. */
export const STALE_QUEUE_MS = 30_000

/** 기다리는 작업이 오래 방치됐나. 실행 중이거나 끝난 작업은 해당하지 않는다. */
export function waitingTooLong(progress: Progress, now: number): boolean {
  const job = progress.job
  if (!progress.active || !job || job.status !== 'QUEUED') return false
  return now - Date.parse(job.createdAt) > STALE_QUEUE_MS
}

export function isActive(version: VersionStatus | null): boolean {
  return progressOf(version).active
}

export const KIND_LABEL: Record<DocumentKind, string> = {
  TRANSCRIPT: '회의 전사문',
  DOCUMENT: '업무 문서',
  REGULATION: '규정',
  MEMO: '메모',
  ROSTER: '참석자 명단',
}

/**
 * 고를 수 있는 언어. 언어를 늘리려면 여기에 한 줄을 더한다.
 * server에는 code를 그대로 보낸다. 여러 언어가 섞였으면 null을 보낸다.
 */
export const LANGUAGES: { code: string; label: string }[] = [
  { code: 'ko', label: '한국어' },
  { code: 'en', label: '영어' },
  { code: 'ar', label: '아랍어' },
]

export const MIXED_LANGUAGE = 'mixed'

export function languageLabel(code: string | null): string {
  if (code === null) return '여러 언어'
  return LANGUAGES.find((l) => l.code === code)?.label ?? code
}

const dateFormat = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

/** 저장은 ISO 8601, 화면에서만 지역화한다. */
export function formatInstant(iso: string): string {
  return dateFormat.format(new Date(iso))
}

/** 정정한 뒤 알림 문장. 이 발언에 기댄 카드가 있으면 다시 확인이 필요하다고 덧붙인다. */
export function revisedMessage(impacts: Impact[]): string {
  const affected = new Set(impacts.map((i) => i.affected.id)).size
  return affected > 0
    ? `발언을 정정했습니다. 이 발언에 기댄 카드 ${affected}개는 다시 확인이 필요합니다.`
    : '발언을 정정했습니다.'
}
