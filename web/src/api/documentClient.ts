import { http, projectPath } from './http'
import type {
  DocumentInput,
  DocumentStatus,
  DocumentView,
  IngestStarted,
  Uuid,
} from './types'

/** 문서 올리기와 원문 읽기. 기준: server api/DocumentController.java */

/** 문서를 올린다. 올리면 "발언으로 자르기" 작업이 바로 맡겨진다. */
export async function uploadDocument(
  projectId: Uuid,
  input: DocumentInput
): Promise<IngestStarted> {
  const { data } = await http.post<IngestStarted>(
    projectPath(projectId, '/documents'),
    input
  )
  return data
}

/** 이미 있는 문서의 새 판을 올린다. 이전 판과 내용이 같으면 server가 409로 거절한다. */
export async function uploadVersion(
  projectId: Uuid,
  documentId: Uuid,
  content: string
): Promise<IngestStarted> {
  const { data } = await http.post<IngestStarted>(
    projectPath(
      projectId,
      `/documents/${encodeURIComponent(documentId)}/versions`
    ),
    { content }
  )
  return data
}

/** 읽을 수 있는 문서. 최근에 올린 것부터, 가장 최근 판의 처리 상태와 함께. */
export async function listDocuments(
  projectId: Uuid
): Promise<DocumentStatus[]> {
  const { data } = await http.get<DocumentStatus[]>(
    projectPath(projectId, '/documents')
  )
  return data
}

/**
 * 문서 한 건을 연다. 아무것도 주지 않으면 가장 최근 판,
 * version을 주면 그 판, span(근거 발언 id)을 주면 그 발언이 들어 있는 판이다.
 */
export async function getDocument(
  projectId: Uuid,
  documentId: Uuid,
  at: { version?: number; span?: Uuid } = {}
): Promise<DocumentView> {
  const { data } = await http.get<DocumentView>(
    projectPath(projectId, `/documents/${encodeURIComponent(documentId)}`),
    { params: at.span ? { span: at.span } : { version: at.version } }
  )
  return data
}
