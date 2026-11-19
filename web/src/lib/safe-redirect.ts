/**
 * 로그인 뒤 돌아갈 주소. 같은 사이트 안의 경로만 받는다.
 * "https://…"나 "//host" 같은 바깥 주소는 버리고 fallback으로 보낸다.
 */
export function safeRedirect(
  redirect: string | undefined,
  fallback = '/projects'
): string {
  if (!redirect) return fallback
  if (!redirect.startsWith('/') || redirect.startsWith('//')) return fallback
  if (redirect.startsWith('/\\')) return fallback
  return redirect
}
