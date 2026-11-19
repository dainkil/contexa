import { beforeEach, describe, expect, it, vi } from 'vitest'

async function importAuthStore() {
  const { useAuthStore } = await import('./auth-store')
  return useAuthStore
}

describe('useAuthStore', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.resetModules()
  })

  it('starts signed out when nothing is stored', async () => {
    const useAuthStore = await importAuthStore()

    expect(useAuthStore.getState().auth.accessToken).toBe('')
    expect(useAuthStore.getState().auth.email).toBe('')
  })

  it('keeps the session in sessionStorage so a reload reads it back', async () => {
    const useAuthStore = await importAuthStore()
    useAuthStore.getState().auth.signIn('session-token', 'user@example.com')

    vi.resetModules()
    const reloaded = await importAuthStore()

    expect(reloaded.getState().auth.accessToken).toBe('session-token')
    expect(reloaded.getState().auth.email).toBe('user@example.com')
  })

  it('reset clears the token, the email and the stored session', async () => {
    const useAuthStore = await importAuthStore()
    useAuthStore.getState().auth.signIn('will-be-cleared', 'user@example.com')

    useAuthStore.getState().auth.reset()

    expect(useAuthStore.getState().auth.accessToken).toBe('')
    expect(useAuthStore.getState().auth.email).toBe('')

    vi.resetModules()
    const reloaded = await importAuthStore()
    expect(reloaded.getState().auth.accessToken).toBe('')
  })

  it('ignores a corrupted stored value', async () => {
    sessionStorage.setItem('contexa.session', '{not json')

    const useAuthStore = await importAuthStore()

    expect(useAuthStore.getState().auth.accessToken).toBe('')
  })

  it('does not write the token to cookies', async () => {
    const useAuthStore = await importAuthStore()
    useAuthStore.getState().auth.signIn('secret-token', 'user@example.com')

    expect(document.cookie).not.toContain('secret-token')
  })
})
