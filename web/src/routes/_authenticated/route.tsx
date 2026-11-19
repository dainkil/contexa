import { createFileRoute, Outlet, redirect } from '@tanstack/react-router'
import { useAuthStore } from '@/stores/auth-store'

/**
 * 로그인해야 들어오는 화면들의 문. 토큰이 없으면 로그인으로 보낸다.
 * 토큰이 만료·폐기된 경우는 server가 401을 주고, main.tsx가 로그인으로 보낸다.
 * 권한 판단(이 프로젝트를 볼 수 있나)은 server가 한다.
 */
export const Route = createFileRoute('/_authenticated')({
  beforeLoad: ({ location }) => {
    if (!useAuthStore.getState().auth.accessToken) {
      throw redirect({ to: '/sign-in', search: { redirect: location.href } })
    }
  },
  component: Outlet,
})
