import { describe, expect, it } from 'vitest'
import type { JobBrief, JobStatus, VersionStatus } from '@/api/types'
import {
  STALE_QUEUE_MS,
  languageLabel,
  progressOf,
  waitingTooLong,
} from './status'

const CREATED = '2026-11-19T10:00:00Z'

function job(status: JobStatus, lastError: string | null = null): JobBrief {
  return {
    id: crypto.randomUUID(),
    status,
    attempts: status === 'QUEUED' ? 0 : 1,
    lastError,
    createdAt: CREATED,
    finishedAt: null,
  }
}

function version(
  parse: JobBrief | null,
  extract: JobBrief | null = null,
  spanCount = 0
): VersionStatus {
  return {
    sourceVersionId: crypto.randomUUID(),
    versionNo: 1,
    createdAt: CREATED,
    spanCount,
    parse,
    extract,
  }
}

describe('progressOf', () => {
  it('follows parse then extract', () => {
    expect(progressOf(version(job('QUEUED')))).toMatchObject({
      stage: 'queued',
      label: '처리 대기',
      active: true,
    })
    expect(progressOf(version(job('RUNNING')))).toMatchObject({
      stage: 'parsing',
      active: true,
    })
    expect(progressOf(version(job('SUCCEEDED'), null, 3))).toMatchObject({
      stage: 'extracting',
      label: '후보 뽑기 대기',
      active: true,
    })
    expect(
      progressOf(version(job('SUCCEEDED'), job('RUNNING'), 3))
    ).toMatchObject({ stage: 'extracting', label: '후보 뽑는 중' })
    expect(
      progressOf(version(job('SUCCEEDED'), job('SUCCEEDED'), 3))
    ).toMatchObject({ stage: 'ready', label: '처리 완료', active: false })
  })

  it('reports which step failed and keeps the failed job', () => {
    const failedParse = job('FAILED', '자를 수 없는 문서')
    expect(progressOf(version(failedParse))).toMatchObject({
      stage: 'failed',
      label: '자르기 실패',
      tone: 'error',
      active: false,
      job: failedParse,
    })
    expect(
      progressOf(version(job('SUCCEEDED'), job('FAILED', 'rate limit'), 2))
    ).toMatchObject({ stage: 'failed', label: '후보 뽑기 실패' })
  })

  it('treats spans without any job as done (seeded data)', () => {
    expect(progressOf(version(null, null, 4))).toMatchObject({
      stage: 'ready',
      active: false,
    })
    expect(progressOf(version(null, null, 0)).stage).toBe('unknown')
    expect(progressOf(null).stage).toBe('unknown')
  })
})

describe('waitingTooLong', () => {
  const created = Date.parse(CREATED)

  it('flags a queued job nobody picked up for a while', () => {
    const progress = progressOf(version(job('QUEUED')))
    expect(waitingTooLong(progress, created + STALE_QUEUE_MS - 1)).toBe(false)
    expect(waitingTooLong(progress, created + STALE_QUEUE_MS + 1)).toBe(true)
  })

  it('does not flag running or finished jobs', () => {
    const later = created + STALE_QUEUE_MS * 10
    expect(waitingTooLong(progressOf(version(job('RUNNING'))), later)).toBe(
      false
    )
    expect(
      waitingTooLong(
        progressOf(version(job('SUCCEEDED'), job('SUCCEEDED'), 1)),
        later
      )
    ).toBe(false)
  })
})

describe('languageLabel', () => {
  it('names known codes and mixed documents', () => {
    expect(languageLabel('ar')).toBe('아랍어')
    expect(languageLabel(null)).toBe('여러 언어')
    expect(languageLabel('fr')).toBe('fr')
  })
})
