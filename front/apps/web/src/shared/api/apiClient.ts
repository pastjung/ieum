import { createClient } from '@ieum/api-client'
import { webTokenStore } from '../auth/tokenStore'

let sessionExpiredHandler: () => void = () => {}

/** 401에서 재발급할 수 없으면(WEB) 호출된다. 라우터가 로그인 화면으로 보내는 핸들러를 등록한다 */
export function setSessionExpiredHandler(handler: () => void): void {
  sessionExpiredHandler = handler
}

// 개발 중에는 Vite 프록시(/api → localhost:8080), 운영에서는 같은 도메인의 nginx가 /api를 backend로 넘긴다
export const api = createClient({
  baseUrl: '/api/v1',
  platform: 'WEB',
  tokens: webTokenStore,
  onSessionExpired: () => sessionExpiredHandler(),
})
