import { create } from 'zustand'

/**
 * 로그인 상태. server가 준 불투명 토큰(Bearer)과 로그인한 이메일만 둔다.
 *
 * 임시 방식: sessionStorage에 둔다(탭을 닫으면 사라진다).
 * 원칙은 HttpOnly·Secure 쿠키다. server가 쿠키 로그인을 지원하면(3단계)
 * 이 저장소는 토큰을 들고 있지 않게 바꾼다.
 */

const STORAGE_KEY = 'contexa.session'

interface Session {
  accessToken: string
  email: string
}

const EMPTY: Session = { accessToken: '', email: '' }

function readSession(): Session {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return EMPTY
    const parsed = JSON.parse(raw) as Partial<Session>
    return {
      accessToken:
        typeof parsed.accessToken === 'string' ? parsed.accessToken : '',
      email: typeof parsed.email === 'string' ? parsed.email : '',
    }
  } catch {
    return EMPTY
  }
}

function writeSession(session: Session | null) {
  try {
    if (session) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    else sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // 저장소를 쓸 수 없는 환경(사생활 보호 모드 등)에서는 메모리에만 둔다.
  }
}

interface AuthState {
  auth: Session & {
    signIn: (accessToken: string, email: string) => void
    reset: () => void
  }
}

export const useAuthStore = create<AuthState>()((set) => ({
  auth: {
    ...readSession(),
    signIn: (accessToken, email) => {
      writeSession({ accessToken, email })
      set((state) => ({ auth: { ...state.auth, accessToken, email } }))
    },
    reset: () => {
      writeSession(null)
      set((state) => ({ auth: { ...state.auth, ...EMPTY } }))
    },
  },
}))
