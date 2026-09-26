import { ApiError, type ApiClient } from '@ieum/api-client'
import { describe, expect, it, vi } from 'vitest'
import { createAuthApi, InstructorOnlyError } from '../authApi'
import { createWebTokenStore } from '../tokenStore'

function memoryStorage(): Storage {
  const data = new Map<string, string>()
  return {
    get length() {
      return data.size
    },
    clear: () => data.clear(),
    getItem: (k) => data.get(k) ?? null,
    key: (i) => [...data.keys()][i] ?? null,
    removeItem: (k) => void data.delete(k),
    setItem: (k, v) => void data.set(k, v),
  }
}

function fakeClient(response: unknown): ApiClient & { post: ReturnType<typeof vi.fn> } {
  return { get: vi.fn(), put: vi.fn(), post: vi.fn(async () => response) } as never
}

const session = (role: string) => ({
  accessToken: 'access',
  tokenType: 'Bearer',
  accessTokenExpiresAt: '2026-09-26T01:15:00.000Z',
  user: { userId: '7', username: 'teacher', email: 't@x', role, emailVerified: true, level: null, createdAt: 'x' },
})

describe('authApi', () => {
  it('강사가 로그인하면 토큰과 사용자를 보관한다', async () => {
    const store = createWebTokenStore(memoryStorage())
    const client = fakeClient(session('INSTRUCTOR'))

    const user = await createAuthApi(client, store).signIn('teacher', 'pw')

    expect(client.post).toHaveBeenCalledWith('/auth-sessions', { username: 'teacher', password: 'pw' }, { auth: false })
    expect(user).toEqual({ userId: '7', username: 'teacher', role: 'INSTRUCTOR' })
    expect(store.getAccess()).toBe('access')
  })

  it('강사가 아니면 거부하고 아무것도 보관하지 않는다', async () => {
    const store = createWebTokenStore(memoryStorage())

    await expect(createAuthApi(fakeClient(session('LEARNER')), store).signIn('learner', 'pw')).rejects.toBeInstanceOf(
      InstructorOnlyError,
    )
    expect(store.getAccess()).toBeNull()
  })

  it('로그인 실패 오류는 그대로 전달한다', async () => {
    const store = createWebTokenStore(memoryStorage())
    const client = fakeClient(null)
    client.post.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'x'))

    await expect(createAuthApi(client, store).signIn('a', 'b')).rejects.toBeInstanceOf(ApiError)
  })

  it('로그아웃하면 보관한 정보를 지운다', async () => {
    const store = createWebTokenStore(memoryStorage())
    const auth = createAuthApi(fakeClient(session('INSTRUCTOR')), store)
    await auth.signIn('teacher', 'pw')

    auth.signOut()

    expect(auth.currentUser()).toBeNull()
    expect(store.getAccess()).toBeNull()
  })
})
