import type { ApiError, ApiResponse } from './types'

const BASE_URL = '/api/v1'

/** 최소 fetch 래퍼 — 이후 토큰 재발급(1-3) 인터셉터를 여기에 추가한다 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...getAuthHeader(),
      ...init?.headers,
    },
    ...init,
  })

  const body = (await res.json()) as ApiResponse<T> | ApiError

  if (!res.ok || !body.success) {
    const err = body as ApiError
    throw new Error(err.error?.message ?? `HTTP ${res.status}`)
  }

  return (body as ApiResponse<T>).data
}

function getAuthHeader(): Record<string, string> {
  const token = sessionStorage.getItem('accessToken')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(data) }),
  patch: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'PATCH', body: JSON.stringify(data) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
