import axios from 'axios'
import { useAuthStore } from '@/stores/auth-store'

/**
 * server로 가는 모든 요청이 지나는 인스턴스. 화면 컴포넌트는 이것을 직접 쓰지 않고
 * contextClient·reviewClient·authClient만 쓴다.
 *
 * 개발 중에는 Vite가 /api를 127.0.0.1:8090으로 프록시한다(vite.config.ts).
 * 401 처리(로그아웃 → /sign-in)는 main.tsx의 QueryClient에서 한 곳으로 한다.
 */
export const http = axios.create({
  baseURL: '/api/v1',
  headers: { Accept: 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = useAuthStore.getState().auth.accessToken
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 프로젝트 하위 주소. projectId는 경로 조각이므로 인코딩한다. */
export function projectPath(projectId: string, path: string): string {
  return `/projects/${encodeURIComponent(projectId)}${path}`
}
