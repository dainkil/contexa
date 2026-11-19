import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  GENERIC_ERROR_MESSAGE,
  NETWORK_ERROR_MESSAGE,
  handleServerError,
} from './handle-server-error'

const toastError = vi.hoisted(() => vi.fn())

vi.mock('sonner', () => ({
  toast: {
    error: toastError,
  },
}))

beforeEach(() => {
  vi.mocked(toastError).mockClear()
})

function axiosErrorWith(status: number, data: unknown) {
  const error = new AxiosError('Request failed')
  error.response = { status, data } as AxiosError['response']
  return error
}

describe('handleServerError', () => {
  it('shows the generic message when the error is not recognised', () => {
    handleServerError(new Error('boom'))

    expect(toastError).toHaveBeenCalledWith(GENERIC_ERROR_MESSAGE)
  })

  it('shows the server message from { error, message }', () => {
    handleServerError(
      axiosErrorWith(400, {
        error: 'BAD_REQUEST',
        message: '이미 가입된 이메일이다',
      })
    )

    expect(toastError).toHaveBeenCalledWith('이미 가입된 이메일이다')
  })

  it('falls back to the generic message when the body has no message', () => {
    handleServerError(axiosErrorWith(500, {}))

    expect(toastError).toHaveBeenCalledWith(GENERIC_ERROR_MESSAGE)
  })

  it('falls back to the generic message when the message is empty', () => {
    handleServerError(
      axiosErrorWith(400, { error: 'BAD_REQUEST', message: '' })
    )

    expect(toastError).toHaveBeenCalledWith(GENERIC_ERROR_MESSAGE)
  })

  it('says the server is unreachable when there is no response', () => {
    handleServerError(new AxiosError('Network Error'))

    expect(toastError).toHaveBeenCalledWith(NETWORK_ERROR_MESSAGE)
  })

  it('logs the error to the console in development', () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {})
    const err = new Error('logged')

    handleServerError(err)

    expect(log).toHaveBeenCalledWith(err)
    log.mockRestore()
  })

  it('does not log the error to the console in production', () => {
    vi.stubEnv('DEV', false)
    const log = vi.spyOn(console, 'log').mockImplementation(() => {})

    handleServerError(new Error('not logged'))

    expect(log).not.toHaveBeenCalled()
    log.mockRestore()
  })
})
