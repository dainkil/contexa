import { http, projectPath } from './http'
import type {
  ContextPacket,
  Direction,
  EdgeRef,
  NameCard,
  NodeRef,
  NodeType,
  StaleRef,
  Uuid,
} from './types'

/**
 * 결정 정보 읽기. /context/*를 부르는 곳은 이 파일 하나뿐이다(모듈 지도 B.10).
 * 화면은 여기서 받은 ContextPacket만 그린다. 권한·상태 계산은 server가 한다.
 * 기준: server api/ContextController.java
 */

const ctx = (projectId: Uuid, path: string) =>
  projectPath(projectId, `/context${path}`)

/** 한 카드에서 줄을 따라간다. BASIS = 근거 쪽, IMPACT = 영향 쪽. */
export async function trace(
  projectId: Uuid,
  params: { type: NodeType; id: Uuid; direction?: Direction; depth?: number }
): Promise<ContextPacket> {
  const { data } = await http.get<ContextPacket>(ctx(projectId, '/trace'), {
    params,
  })
  return data
}

/** 지금 무엇이 결정됐고 무엇이 남았나. topicId를 주면 그 안건만. */
export async function currentState(
  projectId: Uuid,
  topicId?: Uuid
): Promise<ContextPacket> {
  const { data } = await http.get<ContextPacket>(ctx(projectId, '/state'), {
    params: topicId ? { topicId } : undefined,
  })
  return data
}

/** 결정 항목 하나가 언제, 어떻게 바뀌었나. */
export async function decisionHistory(
  projectId: Uuid,
  decisionItemId: Uuid
): Promise<ContextPacket> {
  const { data } = await http.get<ContextPacket>(
    ctx(projectId, `/history/${encodeURIComponent(decisionItemId)}`)
  )
  return data
}

/** 이 내용이 어느 발언에 있나. */
export async function searchEvidence(
  projectId: Uuid,
  query: string,
  k?: number
): Promise<ContextPacket> {
  const { data } = await http.post<ContextPacket>(ctx(projectId, '/search'), {
    query,
    k,
  })
  return data
}

/** 누가 무엇을 해야 하나. mine=true면 내게 맡겨진 할 일만. */
export async function tasks(
  projectId: Uuid,
  filter: { mine?: boolean; assignee?: Uuid; status?: string } = {}
): Promise<ContextPacket> {
  const { data } = await http.get<ContextPacket>(ctx(projectId, '/tasks'), {
    params: filter,
  })
  return data
}

/** 이 이름 표기가 누구(어느 기관)인가. 후보가 여럿이면 모두. */
export async function resolveEntity(
  projectId: Uuid,
  surface: string,
  lang?: string
): Promise<ContextPacket> {
  const { data } = await http.get<ContextPacket>(ctx(projectId, '/entities'), {
    params: { surface, lang },
  })
  return data
}

/** 인물·기관 한 명과 언어별 표기 전부. */
export async function nameCard(
  projectId: Uuid,
  entityId: Uuid
): Promise<NameCard> {
  const { data } = await http.get<NameCard>(
    ctx(projectId, `/entities/${encodeURIComponent(entityId)}`)
  )
  return data
}

/** 참고했던 카드·줄이 그사이 바뀌었는지. 바뀐 것만 돌아온다. */
export async function verifyCurrent(
  projectId: Uuid,
  refs: { nodes: NodeRef[]; edges: EdgeRef[] }
): Promise<StaleRef[]> {
  const { data } = await http.post<StaleRef[]>(ctx(projectId, '/verify'), refs)
  return data
}
