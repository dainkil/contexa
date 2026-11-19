import { http, projectPath } from './http'
import type { ProjectSummary, Role, Uuid } from './types'

/** 가입·로그인·프로젝트. 기준: server api/AuthController.java */

export async function signUp(input: {
  email: string
  displayName: string
  password: string
}): Promise<Uuid> {
  const { data } = await http.post<{ userId: Uuid }>('/auth/signup', input)
  return data.userId
}

/** 토큰은 이때 한 번만 받는다. 이후 요청마다 Authorization: Bearer로 보낸다. */
export async function login(input: {
  email: string
  password: string
}): Promise<string> {
  const { data } = await http.post<{ token: string }>('/auth/login', input)
  return data.token
}

/** 내가 속한 프로젝트와 각 프로젝트에서의 역할. */
export async function listProjects(): Promise<ProjectSummary[]> {
  const { data } = await http.get<ProjectSummary[]>('/projects')
  return data
}

export async function createProject(title: string): Promise<Uuid> {
  const { data } = await http.post<{ id: Uuid }>('/projects', { title })
  return data.id
}

/** 가입한 사람을 이메일로 구성원에 넣거나 역할을 바꾼다. OWNER만 할 수 있다. */
export async function setMember(
  projectId: Uuid,
  input: { email: string; role: Role }
): Promise<Uuid> {
  const { data } = await http.put<{ userId: Uuid }>(
    projectPath(projectId, '/members'),
    input
  )
  return data.userId
}
