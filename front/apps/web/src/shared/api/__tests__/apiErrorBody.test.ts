import { ApiError, createClient, type TokenStore } from '@ieum/api-client'
import { describe, expect, it } from 'vitest'

/** 클라이언트가 실제로 읽는 필드만 가진 가짜 응답 */
function fakeResponse(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body === undefined ? '' : JSON.stringify(body)),
  } as unknown as Response
}

const tokens: TokenStore = {
  getAccess: () => 'access',
  getRefresh: async () => null,
  save: async () => {},
  clear: async () => {},
}

function clientReturning(status: number, body?: unknown) {
  return createClient({
    baseUrl: '/api/v1',
    platform: 'WEB',
    tokens,
    onSessionExpired: () => {},
    fetchFn: (async () => fakeResponse(status, body)) as unknown as typeof fetch,
  })
}

describe('ApiError.body', () => {
  it('오류 응답 본문 전체를 남겨 공통 필드 밖의 값(candidates)을 읽을 수 있다', async () => {
    const body = {
      code: 'PROBLEM_TEXT_AMBIGUOUS',
      message: '어떤 문제인지 골라 주세요',
      candidates: [{ input: '초성 ㄱ', displayText: '초성 ㄱ' }],
    }
    const error = await clientReturning(409, body)
      .post('/lectures/1/problems', { targetText: 'ㄱ' })
      .catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('PROBLEM_TEXT_AMBIGUOUS')
    expect((error as ApiError).body).toEqual(body)
  })

  it('본문이 없는 오류는 body가 undefined다', async () => {
    const error = await clientReturning(503).get('/x').catch((e: unknown) => e)

    expect((error as ApiError).code).toBe('UNKNOWN_ERROR')
    expect((error as ApiError).body).toBeUndefined()
  })

  it('기존 3인자 생성도 그대로 동작한다', () => {
    const error = new ApiError(400, 'INVALID_REQUEST_INPUT', 'm')
    expect(error.body).toBeUndefined()
    expect(error.code).toBe('INVALID_REQUEST_INPUT')
  })
})
