import type { ApiClient } from '@ieum/api-client'
import { api } from '../api/apiClient'
import { webTokenStore, type SignedInUser, type WebTokenStore } from './tokenStore'

export class InstructorOnlyError extends Error {
  constructor() {
    super('강사 계정으로만 로그인할 수 있습니다.')
    this.name = 'InstructorOnlyError'
  }
}

interface WebAuthSessionResponse {
  accessToken: string
  user: { userId: string; username: string; role: string }
}

export function createAuthApi(client: ApiClient, store: WebTokenStore) {
  return {
    /** 강사 웹은 INSTRUCTOR만 쓴다. 다른 역할이면 토큰을 보관하지 않고 거부한다 */
    async signIn(username: string, password: string): Promise<SignedInUser> {
      const session = await client.post<WebAuthSessionResponse>(
        '/auth-sessions',
        { username, password },
        { auth: false },
      )
      if (session.user.role !== 'INSTRUCTOR') throw new InstructorOnlyError()
      const user: SignedInUser = {
        userId: session.user.userId,
        username: session.user.username,
        role: session.user.role,
      }
      store.saveAccess(session.accessToken)
      store.saveUser(user)
      return user
    },

    /** 서버 세션 폐기(auth-session-revocations)는 WEB refresh 쿠키가 없어 생략한다 — MVP 범위 밖 */
    signOut(): void {
      void store.clear()
    },

    currentUser(): SignedInUser | null {
      return store.getAccess() ? store.getUser() : null
    },
  }
}

export const authApi = createAuthApi(api, webTokenStore)
