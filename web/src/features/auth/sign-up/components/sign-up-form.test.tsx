import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, type RenderResult } from 'vitest-browser-react'
import { type Locator, userEvent } from 'vitest/browser'
import { SignUpForm } from './sign-up-form'

const navigate = vi.fn()
const signIn = vi.fn()
const api = vi.hoisted(() => ({ signUp: vi.fn(), login: vi.fn() }))
const toastError = vi.hoisted(() => vi.fn())

vi.mock('@/api/authClient', () => api)

vi.mock('sonner', () => ({ toast: { error: toastError } }))

vi.mock('@/stores/auth-store', () => ({
  useAuthStore: () => ({ auth: { signIn } }),
}))

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return { ...actual, useNavigate: () => navigate }
})

describe('SignUpForm', () => {
  let screen: RenderResult
  let nameInput: Locator
  let emailInput: Locator
  let passwordInput: Locator
  let confirmPasswordInput: Locator
  let submitButton: Locator

  beforeEach(async () => {
    vi.clearAllMocks()
    screen = await render(<SignUpForm />)
    nameInput = screen.getByRole('textbox', { name: /^이름$/ })
    emailInput = screen.getByRole('textbox', { name: /^이메일$/ })
    passwordInput = screen.getByLabelText(/^비밀번호$/)
    confirmPasswordInput = screen.getByLabelText(/^비밀번호 확인$/)
    submitButton = screen.getByRole('button', { name: /^가입하기$/ })
  })

  async function fillValid(password = 'password1') {
    await userEvent.fill(nameInput, '아흐마드')
    await userEvent.fill(emailInput, 'a@b.com')
    await userEvent.fill(passwordInput, password)
    await userEvent.fill(confirmPasswordInput, password)
  }

  it('shows validation messages when submitting an empty form', async () => {
    await userEvent.click(submitButton)

    await expect
      .element(screen.getByText('이름을 입력하세요.'))
      .toBeInTheDocument()
    await expect
      .element(screen.getByText('이메일을 입력하세요.'))
      .toBeInTheDocument()
    await expect
      .element(screen.getByText('비밀번호를 입력하세요.'))
      .toBeInTheDocument()
    expect(api.signUp).not.toHaveBeenCalled()
  })

  it('requires the same minimum password length as the server', async () => {
    await fillValid('1234567')
    await userEvent.click(submitButton)

    await expect
      .element(screen.getByText('비밀번호는 8자 이상이어야 합니다.'))
      .toBeInTheDocument()
    expect(api.signUp).not.toHaveBeenCalled()
  })

  it('shows a mismatch error when passwords differ', async () => {
    await fillValid()
    await userEvent.fill(confirmPasswordInput, 'password2')
    await userEvent.click(submitButton)

    await expect
      .element(screen.getByText('비밀번호가 서로 다릅니다.'))
      .toBeInTheDocument()
  })

  it('signs up, logs in and goes to the project list', async () => {
    api.signUp.mockResolvedValue('user-id')
    api.login.mockResolvedValue('server-token')

    await fillValid()
    await userEvent.click(submitButton)

    await vi.waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({ to: '/projects', replace: true })
    )
    expect(api.signUp).toHaveBeenCalledWith({
      email: 'a@b.com',
      displayName: '아흐마드',
      password: 'password1',
    })
    expect(api.login).toHaveBeenCalledWith({
      email: 'a@b.com',
      password: 'password1',
    })
    expect(signIn).toHaveBeenCalledWith('server-token', 'a@b.com')
  })

  it('shows the server message when the email is already taken', async () => {
    const error = new AxiosError('Bad Request')
    error.response = {
      status: 400,
      data: { error: 'BAD_REQUEST', message: '이미 가입된 이메일이다' },
    } as AxiosError['response']
    api.signUp.mockRejectedValue(error)

    await fillValid()
    await userEvent.click(submitButton)

    await vi.waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('이미 가입된 이메일이다')
    )
    expect(api.login).not.toHaveBeenCalled()
    expect(signIn).not.toHaveBeenCalled()
    await expect.element(submitButton).toBeEnabled()
  })
})
