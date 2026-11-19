import { queryOptions } from '@tanstack/react-query'
import { getDocument, listDocuments } from '@/api/documentClient'
import type { Uuid } from '@/api/types'
import { POLL_MS, isActive } from './status'

export const documentsKey = (projectId: Uuid) =>
  ['projects', projectId, 'documents'] as const

/** 문서 목록. 처리 중인 문서가 있으면 POLL_MS마다 다시 불러오고, 모두 끝나면 멈춘다. */
export const documentsQueryOptions = (projectId: Uuid) =>
  queryOptions({
    queryKey: documentsKey(projectId),
    queryFn: () => listDocuments(projectId),
    refetchInterval: (query) =>
      query.state.data?.some((d) => isActive(d.latest)) ? POLL_MS : false,
  })

export type DocumentAt = { version?: number; span?: Uuid }

/** 문서 한 건. 연 판이 처리 중이면 POLL_MS마다 다시 불러온다. */
export const documentQueryOptions = (
  projectId: Uuid,
  documentId: Uuid,
  at: DocumentAt
) =>
  queryOptions({
    queryKey: [...documentsKey(projectId), documentId, at] as const,
    queryFn: () => getDocument(projectId, documentId, at),
    refetchInterval: (query) => {
      const data = query.state.data
      if (!data) return false
      const opened = data.versions.find(
        (v) => v.sourceVersionId === data.opened.sourceVersionId
      )
      return isActive(opened ?? null) ? POLL_MS : false
    },
  })
