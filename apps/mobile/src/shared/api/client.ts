import type { ApiError, ApiResponse } from './types';

/**
 * Android 에뮬레이터에서 host PC의 localhost는 10.0.2.2 이다.
 * 실기기 테스트 시 PC의 LAN IP로 교체하거나 .env 관리 도구 도입 후 치환한다.
 */
const BASE_URL = 'http://10.0.2.2:8080/api/v1';

let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...init?.headers,
    },
    ...init,
  });

  const body = (await res.json()) as ApiResponse<T> | ApiError;

  if (!res.ok || !body.success) {
    const err = body as ApiError;
    throw new Error(err.error?.message ?? `HTTP ${res.status}`);
  }

  return (body as ApiResponse<T>).data;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(data) }),
  patch: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'PATCH', body: JSON.stringify(data) }),
};
