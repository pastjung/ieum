import { describe, expect, it } from 'vitest'
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

describe('webTokenStore', () => {
  it('access token과 사용자를 보관하고 지운다', async () => {
    const store = createWebTokenStore(memoryStorage())
    store.saveAccess('a1')
    store.saveUser({ userId: '7', username: 'teacher', role: 'INSTRUCTOR' })

    expect(store.getAccess()).toBe('a1')
    expect(store.getUser()).toEqual({ userId: '7', username: 'teacher', role: 'INSTRUCTOR' })

    await store.clear()
    expect(store.getAccess()).toBeNull()
    expect(store.getUser()).toBeNull()
  })

  it('WEB은 refresh token을 보관하지 않는다', async () => {
    const store = createWebTokenStore(memoryStorage())
    await store.save({ accessToken: 'a2', refreshToken: 'r' })

    expect(store.getAccess()).toBe('a2')
    expect(await store.getRefresh()).toBeNull()
  })

  it('손상된 사용자 정보는 없는 것으로 본다', () => {
    const storage = memoryStorage()
    storage.setItem('ieum.web.user', '{not json')
    expect(createWebTokenStore(storage).getUser()).toBeNull()
  })
})
