import { AxiosError } from 'axios'
import { toast } from 'sonner'

export const GENERIC_ERROR_MESSAGE = '요청을 처리하지 못했습니다.'
export const NETWORK_ERROR_MESSAGE =
  'server에 연결할 수 없습니다. server가 켜져 있는지 확인하세요.'
/** 409 STALE: 내가 본 뒤에 다른 사람이 먼저 바꿨다. server 문장에는 id·버전 번호가 들어 있어 대신 쓴다. */
export const STALE_MESSAGE =
  '그사이 다른 사람이 먼저 바꿨습니다. 새로 불러온 내용을 보고 다시 해 주세요.'

/**
 * server 오류를 사용자에게 보여 줄 문장으로 바꾼다.
 * server(ApiExceptionHandler)는 { error: 'BAD_REQUEST' 등, message: '사람이 읽을 문장' }을 돌려준다.
 */
export function serverErrorMessage(error: unknown): string {
  if (error instanceof AxiosError) {
    if (!error.response) return NETWORK_ERROR_MESSAGE
    const data: unknown = error.response.data
    if (data && typeof data === 'object' && 'error' in data) {
      if ((data as { error: unknown }).error === 'STALE') return STALE_MESSAGE
    }
    if (data && typeof data === 'object' && 'message' in data) {
      const message = (data as { message: unknown }).message
      if (typeof message === 'string' && message.length > 0) return message
    }
  }
  return GENERIC_ERROR_MESSAGE
}

export function handleServerError(error: unknown) {
  if (import.meta.env.DEV) {
    // eslint-disable-next-line no-console
    console.log(error)
  }
  toast.error(serverErrorMessage(error))
}
