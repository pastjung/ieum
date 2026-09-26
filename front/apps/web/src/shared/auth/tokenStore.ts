import type { AuthSessionTokens, TokenStore } from '@ieum/api-client'

export interface SignedInUser {
  userId: string
  username: string
  role: string
}

export interface WebTokenStore extends TokenStore {
  saveAccess(token: string): void
  getUser(): SignedInUser | null
  saveUser(user: SignedInUser): void
}

const ACCESS_KEY = 'ieum.web.accessToken'
const USER_KEY = 'ieum.web.user'

/**
 * WEB 인증 보관소. WEB 로그인 응답에는 refresh token이 없으므로(API 명세 3.3.1) access token만 둔다.
 * getRefresh가 null이면 api-client는 401에서 재발급을 시도하지 않고 세션 만료로 처리한다 — 다시 로그인한다.
 */
export function createWebTokenStore(storage: Storage): WebTokenStore {
  return {
    getAccess: () => storage.getItem(ACCESS_KEY),
    getRefresh: async () => null,
    save: async (tokens: AuthSessionTokens) => storage.setItem(ACCESS_KEY, tokens.accessToken),
    clear: async () => {
      storage.removeItem(ACCESS_KEY)
      storage.removeItem(USER_KEY)
    },
    saveAccess: (token) => storage.setItem(ACCESS_KEY, token),
    getUser: () => {
      const raw = storage.getItem(USER_KEY)
      if (!raw) return null
      try {
        return JSON.parse(raw) as SignedInUser
      } catch {
        return null
      }
    },
    saveUser: (user) => storage.setItem(USER_KEY, JSON.stringify(user)),
  }
}

/** 브라우저 기본 보관소. 테스트(node)에서 import만 해도 터지지 않게 첫 사용 때 sessionStorage를 조회한다 */
export const webTokenStore: WebTokenStore = new Proxy({} as WebTokenStore, {
  get(_target, property) {
    return createWebTokenStore(window.sessionStorage)[property as keyof WebTokenStore]
  },
})
