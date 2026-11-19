import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, type RenderResult } from 'vitest-browser-react'
import { type Locator, userEvent } from 'vitest/browser'
import { UserAuthForm } from './user-auth-form'

const navigate = vi.fn()
const signIn = vi.fn()
const login = vi.hoisted(() => vi.fn())
const toastError = vi.hoisted(() => vi.fn())

vi.mock('@/api/authClient', () => ({ login }))

vi.mock('sonner', () => ({ toast: { error: toastError } }))

vi.mock('@/stores/auth-store', () => ({
  useAuthStore: () => ({ auth: { signIn } }),
}))

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return { ...actual, useNavigate: () => navigate }
})

describe('UserAuthForm', () => {
  let screen: RenderResult
  let emailInput: Locator
  let passwordInput: Locator
  let submitButton: Locator

  async function renderForm(redirectTo?: string) {
    screen = await render(<UserAuthForm redirectTo={redirectTo} />)
    emailInput = screen.getByRole('textbox', { name: /^이메일$/ })
    passwordInput = screen.getByLabelText(/^비밀번호$/)
    submitButton = screen.getByRole('button', { name: /^로그인$/ })
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows validation messages when submitting an empty form', async () => {
    await renderForm()

    await userEvent.click(submitButton)

    await expect
      .element(screen.getByText('이메일을 입력하세요.'))
      .toBeInTheDocument()
    await expect
      .element(screen.getByText('비밀번호를 입력하세요.'))
      .toBeInTheDocument()
    expect(login).not.toHaveBeenCalled()
  })

  it('logs in, stores the token and goes to the project list', async () => {
    login.mockResolvedValue('server-token')
    await renderForm()

    await userEvent.fill(emailInput, 'a@b.com')
    await userEvent.fill(passwordInput, 'password1')
    await userEvent.click(submitButton)

    await vi.waitFor(() =>
      expect(login).toHaveBeenCalledWith({
        email: 'a@b.com',
        password: 'password1',
      })
    )
    expect(signIn).toHaveBeenCalledWith('server-token', 'a@b.com')
    expect(navigate).toHaveBeenCalledWith({ to: '/projects', replace: true })
  })

  it('returns to an in-app redirect after login', async () => {
    login.mockResolvedValue('server-token')
    await renderForm('/p/abc/reviews')

    await userEvent.fill(emailInput, 'a@b.com')
    await userEvent.fill(passwordInput, 'password1')
    await userEvent.click(submitButton)

    await vi.waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({
        to: '/p/abc/reviews',
        replace: true,
      })
    )
  })

  it('ignores an outside redirect', async () => {
    login.mockResolvedValue('server-token')
    await renderForm('https://evil.example/')

    await userEvent.fill(emailInput, 'a@b.com')
    await userEvent.fill(passwordInput, 'password1')
    await userEvent.click(submitButton)

    await vi.waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({ to: '/projects', replace: true })
    )
  })

  it('shows the server message and stays signed out when login fails', async () => {
    const error = new AxiosError('Unauthorized')
    error.response = {
      status: 401,
      data: {
        error: 'UNAUTHENTICATED',
        message: '이메일 또는 비밀번호가 맞지 않는다',
      },
    } as AxiosError['response']
    login.mockRejectedValue(error)
    await renderForm()

    await userEvent.fill(emailInput, 'a@b.com')
    await userEvent.fill(passwordInput, 'wrong-password')
    await userEvent.click(submitButton)

    await vi.waitFor(() =>
      expect(toastError).toHaveBeenCalledWith(
        '이메일 또는 비밀번호가 맞지 않는다'
      )
    )
    expect(signIn).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
    await expect.element(submitButton).toBeEnabled()
  })
})
