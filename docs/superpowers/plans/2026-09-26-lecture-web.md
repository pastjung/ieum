# 실시간 수업 강사 웹 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 강사가 웹에서 로그인해 수업을 만들고, 시작해서 마이크로 음성을 송출하고, 종료할 수 있게 한다. 나머지 화면 요소(학생 진행 상태·문제 패널·개설 2~3단계)는 목 데이터로 채운다.

**Architecture:** 웹을 공용 `@ieum/api-client`(platform `WEB`)에 연결하고, 수업 API 호출은 `domains/lecture/api`, LiveKit 송출은 `domains/lecture/live`가 맡는다. 페이지는 이 둘을 조합만 한다. 테스트는 vitest(node 환경)로 순수 로직·API 호출·LiveKit 제어부만 검증하고, 화면은 빌드·린트와 수동 E2E로 확인한다.

**Tech Stack:** React 19, react-router-dom 7, Vite 6, TypeScript 5.8, `@ieum/api-client`(workspace), `livekit-client@2.22.3`, vitest

**Spec:** `docs/superpowers/specs/2026-09-26-live-lecture-mvp-design.md` §2, §6 / 계약 원본 `docs/product/api-spec.md` §8.2, §8.5 (dev 병합 후 dev 기준) / 화면 원본: 와이어프레임 `이음 강사웹.html` 01a·02·03

## Global Constraints

- 브랜치: `feat/lecture-web` (최신 `dev`에서 분기 — `chore/web-monorepo`·`feat/lecture-api` MR이 `dev`에 병합된 뒤 시작), MR 대상 `dev`
- 작업 위치: `front/apps/web` 만 (+ lockfile). backend·mobile·packages는 건드리지 않는다
- 커밋: `type(lecture): 한글 제목` + 빈 줄 + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- API (`/api/v1` 접두는 baseUrl에 포함, 개발 중에는 Vite 프록시가 `/api` → `localhost:8080`):
  - `POST /auth-sessions` `{username, password}` + 헤더 `X-Client-Platform: WEB` → 201 `{accessToken, tokenType, accessTokenExpiresAt, user{userId, username, email, role, emailVerified, level, createdAt}}` (WEB은 refresh 필드가 없다)
  - `GET /lectures?status&page&size` → `{items, page, size, totalElements, totalPages}`
  - `POST /lectures` `{title, description?, scheduledAt?}` → 201 강사 스냅샷
  - `GET /lectures/{lectureId}` → 강사 스냅샷
  - `PUT /lectures/{lectureId}/status` `{status: "OPEN"|"CLOSED"}` → 200 강사 스냅샷 (같은 상태 재요청은 방 작업만 재시도)
  - `POST /lectures/{lectureId}/media-tokens` → 201 `{serverUrl, token, expiresAt}`
- 강사 스냅샷 필드: `lectureId`(문자열), `title`, `description`, `status`(`SCHEDULED|OPEN|CLOSED`), `scheduledAt`, `startedAt`, `endedAt`, `currentProblem`(null), `createdAt`, `participationCode`(6자리), `linkedProblems`(배열). 시각은 ISO 8601 UTC
- 오류: 강사 아님 `INSTRUCTOR_ACCESS_REQUIRED`(403), 없음·타인 수업 `LECTURE_NOT_FOUND`(404), 상태 불일치 `INVALID_LECTURE_STATE`(409), 음성 서버 장애 `MEDIA_SERVER_UNAVAILABLE`(503 — 같은 요청 재시도로 복구), 입력 오류 `INVALID_REQUEST_INPUT`(422)
- 인증: access token은 `sessionStorage`, 만료(401)되면 재발급 없이 로그인 화면으로. INSTRUCTOR가 아니면 로그인시키지 않는다
- 강사 토큰은 OPEN일 때만 발급되고 마이크 송출 권한을 준다. 학습자는 숨김 참가자라 강사 화면에서도 보이지 않는다 → 학생 진행 그리드는 **전부 목**
- 수업 종료(`CLOSED`)가 503이면 강사에게 재시도를 안내한다(방 삭제가 안 되면 학습자가 방에 남는다)
- 디자인: **시각 원본은 와이어프레임 `C:/Users/SSAFY/Desktop/이음 강사웹.html`** — 01a(수업 목록)·02(수업 개설 · 1단계 기본 정보)·03(실시간 수업 진행)과 공통 크롬(흰 상단 바·좌측 사이드바)을 그 파일의 인라인 `style` 값대로 옮긴다. 글꼴은 Noto Sans KR, 코드·숫자·타이머는 IBM Plex Mono(Google Fonts). 스타일은 기존 관례대로 인라인 `style` + `theme`/`styles` 상수. UI 라이브러리를 추가하지 않는다
- ESLint `@typescript-eslint/no-explicit-any: error` — `any` 금지
- 검증: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
- `.env*` 파일은 읽거나 고치지 않는다

## File Structure (모두 `front/apps/web/` 아래)

| 파일 | 책임 |
| --- | --- |
| `package.json`, `vitest.config.ts` | 의존성(`@ieum/api-client`, `livekit-client`, `vitest`)과 test 스크립트 |
| `src/shared/api/apiClient.ts` | `@ieum/api-client` 인스턴스(WEB), 세션 만료 핸들러 |
| `src/shared/auth/tokenStore.ts` | sessionStorage 기반 `TokenStore` + 로그인 사용자 보관 |
| `src/shared/auth/authApi.ts` | 로그인·로그아웃, 강사 여부 확인 |
| `index.html` | 웹폰트(Noto Sans KR·IBM Plex Mono) 링크, body 기본 스타일 |
| `src/shared/ui/theme.ts` | 와이어프레임 디자인 토큰(색·모서리·글꼴·레이아웃 치수) |
| `src/shared/ui/styles.ts` | 버튼·입력·카드 공용 인라인 스타일 |
| `src/shared/ui/Logo.tsx`, `SidebarLayout.tsx`, `PageHeader.tsx` | 로고, 01a·02 공통 사이드바 레이아웃, 브레드크럼·제목 헤더 |
| `src/shared/ui/errorMessage.ts` | `ApiError` → 사용자 문구 |
| `src/domains/lecture/model/types.ts` | 강사 스냅샷·목록·토큰 타입 |
| `src/domains/lecture/api/lectureApi.ts` | 수업 API 호출 |
| `src/domains/lecture/live/instructorRoom.ts` | LiveKit 접속·마이크 송출·해제 |
| `src/domains/lecture/mock/liveMock.ts` | 03 화면 목 데이터 |
| `src/app/RequireInstructor.tsx`, `src/app/AppShell.tsx`, `src/app/router.tsx` | 인증 가드·흰 상단 바(01a·02)·라우팅(03은 AppShell 밖) |
| `src/pages/LoginPage.tsx`, `LectureListPage.tsx`, `LectureCreatePage.tsx`, `LiveLecturePage.tsx` | 화면 |
| `src/**/__tests__/*.test.ts` | vitest 테스트 |

---

### Task 0: 작업 브랜치 준비

**Files:** 없음 (git 작업)

- [ ] **Step 1: 선행 MR 병합 확인 후 최신 dev에서 분기**

`chore/web-monorepo`(웹 이전)와 `feat/lecture-api`(수업 API)가 `dev`에 병합돼 있어야 한다. 원래 폴더에서:

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git fetch origin
git ls-tree -d origin/dev front/apps/web >/dev/null && git ls-tree origin/dev apps/backend/src/main/java/com/itda/lecture/controller | grep -q LectureController && echo "선행 MR 병합됨"
git worktree add ../wt-lecture-web -b feat/lecture-web origin/dev
cd ../wt-lecture-web
git checkout feat/lecture -- docs/superpowers/plans/2026-09-26-lecture-web.md docs/superpowers/plans/2026-09-26-lecture-app.md
git commit -m "docs(lecture): 강사 웹과 학습자 앱 연동 구현 계획을 세운다"   # 본문 끝에 Co-Authored-By 줄
cd front && pnpm install
```

첫 확인이 "선행 MR 병합됨"을 출력하지 않으면 중단하고 사람에게 알린다. 두 계획 문서가 이미 dev에 있으면 이 커밋은 건너뛴다. push는 하지 않는다.

Expected: `wt-lecture-web/front/apps/web` 존재, `pnpm --filter @ieum/web build` 성공

---

### Task 1: 공용 API 클라이언트와 강사 로그인 로직

**Files:**
- Modify: `front/apps/web/package.json` (pnpm이 수정), `front/pnpm-lock.yaml`
- Create: `front/apps/web/vitest.config.ts`
- Create: `front/apps/web/src/shared/auth/tokenStore.ts`
- Create: `front/apps/web/src/shared/api/apiClient.ts`
- Create: `front/apps/web/src/shared/auth/authApi.ts`
- Create: `front/apps/web/src/shared/ui/errorMessage.ts`
- Test: `front/apps/web/src/shared/auth/__tests__/tokenStore.test.ts`, `authApi.test.ts`, `front/apps/web/src/shared/ui/__tests__/errorMessage.test.ts`

**Interfaces:**
- Produces:
  - `createWebTokenStore(storage: Storage): WebTokenStore` — `TokenStore`(`getAccess`, `getRefresh`→항상 `null`, `save`, `clear`) + `saveAccess(token)`, `getUser(): SignedInUser | null`, `saveUser(user)`
  - `webTokenStore: WebTokenStore` (기본 `window.sessionStorage`, 모듈 로드 시가 아니라 첫 접근 시 조회)
  - `interface SignedInUser { userId: string; username: string; role: string }`
  - `api` (`ApiClient`), `setSessionExpiredHandler(handler: () => void): void`
  - `createAuthApi(client: ApiClient, store: WebTokenStore)` → `{ signIn(username, password): Promise<SignedInUser>, signOut(): void, currentUser(): SignedInUser | null }`; `authApi` (기본 인스턴스)
  - `class InstructorOnlyError extends Error`
  - `errorMessage(error: unknown): string`

- [ ] **Step 1: 의존성과 테스트 도구**

```bash
cd front
pnpm --filter @ieum/web add @ieum/api-client@workspace:* livekit-client@2.22.3
pnpm --filter @ieum/web add -D vitest@^3
```

`front/apps/web/package.json` 의 `scripts`에 추가:

```json
    "test": "vitest run",
```

`front/apps/web/vitest.config.ts`:

```ts
import { defineConfig } from 'vitest/config'

// 브라우저 API가 필요 없는 로직만 테스트한다. 화면은 build·lint와 수동 E2E로 확인한다.
export default defineConfig({
  test: { environment: 'node', include: ['src/**/__tests__/**/*.test.ts'] },
})
```

`tsconfig.json`의 `include`에 `vitest.config.ts`를 추가한다(`vite.config.ts` 옆).

- [ ] **Step 2: 실패하는 테스트 작성**

`front/apps/web/src/shared/auth/__tests__/tokenStore.test.ts`:

```ts
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
```

`front/apps/web/src/shared/auth/__tests__/authApi.test.ts`:

```ts
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
```

`front/apps/web/src/shared/ui/__tests__/errorMessage.test.ts`:

```ts
import { ApiError } from '@ieum/api-client'
import { describe, expect, it } from 'vitest'
import { InstructorOnlyError } from '../../auth/authApi'
import { errorMessage } from '../errorMessage'

describe('errorMessage', () => {
  it.each([
    ['INVALID_CREDENTIALS', '아이디 또는 비밀번호를 확인해 주세요.'],
    ['EMAIL_VERIFICATION_REQUIRED', '이메일 인증을 마친 뒤 로그인해 주세요.'],
    ['LECTURE_NOT_FOUND', '수업을 찾을 수 없어요.'],
    ['INVALID_LECTURE_STATE', '지금 수업 상태에서는 할 수 없는 작업이에요. 새로고침해 주세요.'],
    ['MEDIA_SERVER_UNAVAILABLE', '음성 서버에 연결하지 못했어요. 같은 버튼을 다시 눌러 주세요.'],
    ['INVALID_REQUEST_INPUT', '입력값을 확인해 주세요.'],
    ['NETWORK_UNAVAILABLE', '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.'],
  ])('%s', (code, text) => {
    expect(errorMessage(new ApiError(400, code, 'server text'))).toBe(text)
  })

  it('강사 전용 오류', () => {
    expect(errorMessage(new InstructorOnlyError())).toBe('강사 계정으로만 로그인할 수 있어요.')
  })

  it('알 수 없는 오류', () => {
    expect(errorMessage(new Error('boom'))).toBe('요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.')
  })
})
```

- [ ] **Step 3: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — 모듈이 없다

- [ ] **Step 4: 구현**

`front/apps/web/src/shared/auth/tokenStore.ts`:

```ts
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
```

`front/apps/web/src/shared/api/apiClient.ts`:

```ts
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
```

`front/apps/web/src/shared/auth/authApi.ts`:

```ts
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
```

`front/apps/web/src/shared/ui/errorMessage.ts`:

```ts
import { ApiError } from '@ieum/api-client'
import { InstructorOnlyError } from '../auth/authApi'

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: '아이디 또는 비밀번호를 확인해 주세요.',
  EMAIL_VERIFICATION_REQUIRED: '이메일 인증을 마친 뒤 로그인해 주세요.',
  LECTURE_NOT_FOUND: '수업을 찾을 수 없어요.',
  INVALID_LECTURE_STATE: '지금 수업 상태에서는 할 수 없는 작업이에요. 새로고침해 주세요.',
  MEDIA_SERVER_UNAVAILABLE: '음성 서버에 연결하지 못했어요. 같은 버튼을 다시 눌러 주세요.',
  INVALID_REQUEST_INPUT: '입력값을 확인해 주세요.',
  NETWORK_UNAVAILABLE: '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.',
}

/** 서버 message를 그대로 보여 주지 않고 사유별 문구를 쓴다 */
export function errorMessage(error: unknown): string {
  if (error instanceof InstructorOnlyError) return '강사 계정으로만 로그인할 수 있어요.'
  if (error instanceof ApiError && MESSAGES[error.code]) return MESSAGES[error.code]
  return '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.'
}
```

- [ ] **Step 5: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: 테스트 PASS(3 + 4 + 9), lint·build 성공

- [ ] **Step 6: Commit**

```bash
git add front/apps/web front/pnpm-lock.yaml
git commit -m "feat(lecture): 강사 웹을 공용 API 클라이언트와 강사 로그인에 연결한다"
```

---

### Task 2: 수업 API

**Files:**
- Create: `front/apps/web/src/domains/lecture/model/types.ts`
- Create: `front/apps/web/src/domains/lecture/api/lectureApi.ts`
- Test: `front/apps/web/src/domains/lecture/api/__tests__/lectureApi.test.ts`

**Interfaces:**
- Consumes: Task 1 `api` (`ApiClient`)
- Produces:
  - `type LectureStatus = 'SCHEDULED' | 'OPEN' | 'CLOSED'`
  - `interface InstructorLecture { lectureId; title; description: string | null; status; scheduledAt: string | null; startedAt: string | null; endedAt: string | null; createdAt: string; participationCode: string }`
  - `interface LecturePage { items: InstructorLecture[]; page: number; size: number; totalElements: number; totalPages: number }`
  - `interface MediaToken { serverUrl: string; token: string; expiresAt: string }`
  - `interface CreateLectureInput { title: string; description?: string; scheduledAt?: string }`
  - `createLectureApi(client: ApiClient)` → `{ list(page?: number, size?: number), create(input), get(lectureId), changeStatus(lectureId, status: 'OPEN' | 'CLOSED'), issueMediaToken(lectureId) }`; `lectureApi` (기본 인스턴스)
  - `toScheduledAtUtc(localDateTime: string): string` — `<input type="datetime-local">` 값 → ISO UTC

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/web/src/domains/lecture/api/__tests__/lectureApi.test.ts`:

```ts
import type { ApiClient } from '@ieum/api-client'
import { describe, expect, it, vi } from 'vitest'
import { createLectureApi, toScheduledAtUtc } from '../lectureApi'

function fakeClient() {
  return { get: vi.fn(async () => ({})), post: vi.fn(async () => ({})), put: vi.fn(async () => ({})) }
}

describe('lectureApi', () => {
  it('내 수업 목록을 최신순 페이지로 조회한다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).list(0, 20)
    expect(client.get).toHaveBeenCalledWith('/lectures?page=0&size=20')
  })

  it('빈 설명과 시작 시각은 보내지 않는다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).create({ title: '약자 4회차', description: '  ' })
    expect(client.post).toHaveBeenCalledWith('/lectures', { title: '약자 4회차' })
  })

  it('설명과 시작 시각이 있으면 함께 보낸다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).create({
      title: 't',
      description: '규칙 중심',
      scheduledAt: '2026-09-26T01:40:00.000Z',
    })
    expect(client.post).toHaveBeenCalledWith('/lectures', {
      title: 't',
      description: '규칙 중심',
      scheduledAt: '2026-09-26T01:40:00.000Z',
    })
  })

  it('상태 변경과 음성 토큰 경로', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    await lectures.get('42')
    await lectures.changeStatus('42', 'OPEN')
    await lectures.issueMediaToken('42')

    expect(client.get).toHaveBeenCalledWith('/lectures/42')
    expect(client.put).toHaveBeenCalledWith('/lectures/42/status', { status: 'OPEN' })
    expect(client.post).toHaveBeenCalledWith('/lectures/42/media-tokens')
  })

  it('datetime-local 값을 UTC ISO로 바꾼다', () => {
    const local = '2026-09-26T10:40'
    expect(toScheduledAtUtc(local)).toBe(new Date(local).toISOString())
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — 모듈이 없다

- [ ] **Step 3: 구현**

`front/apps/web/src/domains/lecture/model/types.ts`:

```ts
/** 강사 웹이 쓰는 수업 응답 (API 명세 8.2·8.5절). ID는 10진수 문자열이다 */
export type LectureStatus = 'SCHEDULED' | 'OPEN' | 'CLOSED'

export interface InstructorLecture {
  lectureId: string
  title: string
  description: string | null
  status: LectureStatus
  scheduledAt: string | null
  startedAt: string | null
  endedAt: string | null
  createdAt: string
  participationCode: string
}

export interface LecturePage {
  items: InstructorLecture[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface MediaToken {
  serverUrl: string
  token: string
  expiresAt: string
}

export interface CreateLectureInput {
  title: string
  description?: string
  scheduledAt?: string
}
```

`front/apps/web/src/domains/lecture/api/lectureApi.ts`:

```ts
import type { ApiClient } from '@ieum/api-client'
import { api } from '../../../shared/api/apiClient'
import type { CreateLectureInput, InstructorLecture, LecturePage, MediaToken } from '../model/types'

export function createLectureApi(client: ApiClient) {
  return {
    list: (page = 0, size = 20) => client.get<LecturePage>(`/lectures?page=${page}&size=${size}`),

    create(input: CreateLectureInput) {
      const body: CreateLectureInput = { title: input.title.trim() }
      const description = input.description?.trim()
      if (description) body.description = description
      if (input.scheduledAt) body.scheduledAt = input.scheduledAt
      return client.post<InstructorLecture>('/lectures', body)
    },

    get: (lectureId: string) => client.get<InstructorLecture>(`/lectures/${lectureId}`),

    /** 같은 상태를 다시 보내면 서버가 방 작업만 재시도한다 — 503 복구 경로 (API 명세 8.5절) */
    changeStatus: (lectureId: string, status: 'OPEN' | 'CLOSED') =>
      client.put<InstructorLecture>(`/lectures/${lectureId}/status`, { status }),

    /** 강사 토큰은 OPEN일 때만 발급된다. 발급할 때 서버가 방을 다시 열어 metadata를 복구한다 */
    issueMediaToken: (lectureId: string) => client.post<MediaToken>(`/lectures/${lectureId}/media-tokens`),
  }
}

/** `<input type="datetime-local">`의 로컬 시각을 서버가 받는 UTC ISO로 바꾼다 */
export function toScheduledAtUtc(localDateTime: string): string {
  return new Date(localDateTime).toISOString()
}

export const lectureApi = createLectureApi(api)
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: PASS (Task 1 테스트 + 5)

- [ ] **Step 5: Commit**

```bash
git add front/apps/web/src/domains/lecture
git commit -m "feat(lecture): 강사 웹의 수업 API 호출을 추가한다"
```

---

### Task 3: 강사 음성 송출 모듈

**Files:**
- Create: `front/apps/web/src/domains/lecture/live/instructorRoom.ts`
- Test: `front/apps/web/src/domains/lecture/live/__tests__/instructorRoom.test.ts`

**Interfaces:**
- Consumes: Task 2 `MediaToken`
- Produces:
  - `type RoomState = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'`
  - `interface InstructorRoom { setMicEnabled(enabled: boolean): Promise<void>; micEnabled(): boolean; disconnect(): Promise<void> }`
  - `connectInstructorRoom(token: MediaToken, onState: (state: RoomState) => void): Promise<InstructorRoom>` — 접속 후 마이크 켬

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/web/src/domains/lecture/live/__tests__/instructorRoom.test.ts`:

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { connectInstructorRoom } from '../instructorRoom'

type Listener = (...args: unknown[]) => void

const rooms: FakeRoom[] = []

class FakeRoom {
  listeners = new Map<string, Listener[]>()
  connect = vi.fn(async () => undefined)
  disconnect = vi.fn(async () => {
    this.emit('disconnected')
  })
  localParticipant = { setMicrophoneEnabled: vi.fn(async () => undefined) }
  constructor() {
    rooms.push(this)
  }
  on(event: string, listener: Listener) {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), listener])
    return this
  }
  emit(event: string, ...args: unknown[]) {
    ;(this.listeners.get(event) ?? []).forEach((l) => l(...args))
  }
}

vi.mock('livekit-client', () => ({
  Room: vi.fn().mockImplementation(() => new FakeRoom()),
  RoomEvent: { Reconnecting: 'reconnecting', Reconnected: 'reconnected', Disconnected: 'disconnected' },
}))

const token = { serverUrl: 'wss://openvidu.i-eum.site', token: 't', expiresAt: 'x' }

beforeEach(() => {
  rooms.length = 0
})

describe('instructorRoom', () => {
  it('접속하고 마이크를 켠다', async () => {
    const states: string[] = []
    const room = await connectInstructorRoom(token, (s) => states.push(s))

    expect(rooms[0].connect).toHaveBeenCalledWith('wss://openvidu.i-eum.site', 't')
    expect(rooms[0].localParticipant.setMicrophoneEnabled).toHaveBeenCalledWith(true)
    expect(room.micEnabled()).toBe(true)
    expect(states).toEqual(['connecting', 'connected'])
  })

  it('마이크를 끄고 켠다', async () => {
    const room = await connectInstructorRoom(token, () => {})

    await room.setMicEnabled(false)

    expect(rooms[0].localParticipant.setMicrophoneEnabled).toHaveBeenLastCalledWith(false)
    expect(room.micEnabled()).toBe(false)
  })

  it('재연결과 해제 상태를 알린다', async () => {
    const states: string[] = []
    await connectInstructorRoom(token, (s) => states.push(s))

    rooms[0].emit('reconnecting')
    rooms[0].emit('reconnected')
    rooms[0].emit('disconnected')

    expect(states).toEqual(['connecting', 'connected', 'reconnecting', 'connected', 'disconnected'])
  })

  it('접속이 실패하면 오류를 던지고 해제 상태를 알린다', async () => {
    const states: string[] = []
    const { Room } = await import('livekit-client')
    vi.mocked(Room).mockImplementationOnce(() => {
      const room = new FakeRoom()
      room.connect.mockRejectedValue(new Error('ws failed'))
      return room as never
    })

    await expect(connectInstructorRoom(token, (s) => states.push(s))).rejects.toThrow('ws failed')
    expect(states).toEqual(['connecting', 'disconnected'])
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — 모듈이 없다

- [ ] **Step 3: 구현**

`front/apps/web/src/domains/lecture/live/instructorRoom.ts`:

```ts
import { Room, RoomEvent } from 'livekit-client'
import type { MediaToken } from '../model/types'

export type RoomState = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

export interface InstructorRoom {
  setMicEnabled(enabled: boolean): Promise<void>
  micEnabled(): boolean
  disconnect(): Promise<void>
}

/**
 * 강사 → 학습자 단방향 음성 (API 명세 8.5절). 강사 토큰은 마이크 송출 권한만 있다.
 * 학습자는 숨김 참가자라 여기서 참가자 목록을 볼 수 없다.
 * 브라우저 마이크 권한은 HTTPS 또는 localhost에서만 열린다.
 */
export async function connectInstructorRoom(
  token: MediaToken,
  onState: (state: RoomState) => void,
): Promise<InstructorRoom> {
  const room = new Room()
  let mic = false

  room.on(RoomEvent.Reconnecting, () => onState('reconnecting'))
  room.on(RoomEvent.Reconnected, () => onState('connected'))
  room.on(RoomEvent.Disconnected, () => onState('disconnected'))

  onState('connecting')
  try {
    await room.connect(token.serverUrl, token.token)
  } catch (error) {
    onState('disconnected')
    throw error
  }
  onState('connected')

  await room.localParticipant.setMicrophoneEnabled(true)
  mic = true

  return {
    async setMicEnabled(enabled) {
      await room.localParticipant.setMicrophoneEnabled(enabled)
      mic = enabled
    },
    micEnabled: () => mic,
    disconnect: () => room.disconnect(),
  }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint`
Expected: PASS (+4), lint 통과

- [ ] **Step 5: Commit**

```bash
git add front/apps/web/src/domains/lecture/live
git commit -m "feat(lecture): 강사 마이크 송출용 음성 방 연결을 추가한다"
```

---

### Task 4: 로그인 화면, 인증 가드, 라우팅, 상단 바

화면 기준: 와이어프레임 `C:/Users/SSAFY/Desktop/이음 강사웹.html`의 공통 크롬(01a·02의 흰 상단 바 64px + 흰 좌측 사이드바 240px). 값은 그 파일의 인라인 `style`에서 옮겼다. 화면 영역 배경은 `#F7F8FA`이고, `#F0F1F3`은 와이어프레임 캔버스(바깥) 배경이다.

**Files:**
- Modify: `front/apps/web/index.html` (웹폰트·기본 스타일)
- Create: `front/apps/web/src/shared/ui/theme.ts`
- Create: `front/apps/web/src/shared/ui/styles.ts` (버튼·입력·카드 공용 스타일)
- Create: `front/apps/web/src/shared/ui/Logo.tsx`
- Create: `front/apps/web/src/shared/ui/SidebarLayout.tsx`
- Create: `front/apps/web/src/shared/ui/PageHeader.tsx`
- Create: `front/apps/web/src/pages/LoginPage.tsx`
- Create: `front/apps/web/src/app/RequireInstructor.tsx`
- Create: `front/apps/web/src/app/AppShell.tsx`
- Modify: `front/apps/web/src/app/router.tsx`
- Delete: `front/apps/web/src/pages/HomePage.tsx`

**Interfaces:**
- Consumes: Task 1 `authApi`, `setSessionExpiredHandler`, `errorMessage`
- Produces:
  - `theme` 상수(`color`, `radius`, `font`, `layout`)
  - `buttonStyle(variant: ButtonVariant, size?: 'md' | 'sm'): CSSProperties`, `type ButtonVariant = 'primary' | 'outline' | 'mintOutline' | 'muted' | 'dangerOutline' | 'darkOutline'`, `inactive`, `dimmed`, `fieldWrap`, `fieldLabel`, `fieldStyle`, `cardStyle` (모두 `CSSProperties`)
  - `<Logo />`, `<SidebarLayout sidebarTitle sidebar gap? maxWidth?>`, `<PageHeader crumbs title subtitle? actions?>`
  - 라우트 `/login`, `/lectures`(`AppShell` 안). `/lectures/new`는 Task 5, `/lectures/:lectureId/live`는 Task 6이 추가한다(03은 `AppShell` 밖)

레이아웃 방식: `AppShell`은 흰 상단 바와 `<Outlet />`만 그린다. 사이드바가 있는 화면(01a·02)은 각 페이지가 `SidebarLayout`으로 자기 사이드바 내용을 넘긴다 — 페이지마다 사이드바 내용이 달라서 이것이 가장 단순하다.

- [ ] **Step 1: 웹폰트와 기본 스타일**

와이어프레임은 Google Fonts의 Noto Sans KR(400·500·700·900)과 IBM Plex Mono(500·700)를 쓴다. `front/apps/web/index.html`을 다음으로 바꾼다(기존 `<title>` 유지):

```html
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>잇다 — 강사용 웹</title>
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;500;700;900&family=IBM+Plex+Mono:wght@500;700&display=swap"
    />
    <style>
      body { margin: 0; background: #F7F8FA; color: #16181D; font-family: 'Noto Sans KR', sans-serif; }
      a { color: #046B52; }
      a:hover { color: #00A87E; }
    </style>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

(`a` 색은 와이어프레임 `<style>`의 값이다. 전역 `box-sizing`은 바꾸지 않는다 — 와이어프레임도 기본값 `content-box`라 그대로 옮겨야 크기가 맞는다.)

- [ ] **Step 2: 디자인 토큰**

`front/apps/web/src/shared/ui/theme.ts`:

```ts
/**
 * 강사 웹 디자인 토큰. 원본: 와이어프레임 `이음 강사웹.html`(라이트 민트) 01a·02·03의 인라인 style 값.
 * 인라인 style에서 쓴다. 값을 바꾸면 와이어프레임과 나란히 비교해 확인한다.
 */
export const theme = {
  color: {
    canvas: '#F0F1F3', // 로그인 배경·진행 막대 트랙
    bg: '#F7F8FA', // 화면 배경·종료 행·미착수 카드·문제 은행 카드
    surface: '#FFFFFF',
    border: '#DCE0E6',
    borderSoft: '#E4EAED', // 미착수 카드 테두리
    borderStrong: '#B4BCC4', // 빈 점·미입장 카드 테두리
    fill: '#EDF0F2', // 점형 칸·출제된 문항
    text: '#16181D',
    textSub: '#5B626D',
    textMuted: '#8A939B',
    primary: '#00C896',
    primaryStrong: '#00A87E', // 버튼 그림자·강조 테두리
    primaryDark: '#046B52', // 민트 위 글자·활성 탭
    primarySoft: '#E3FBF4', // 진행 중 행·활성 사이드바·정답 카드
    primaryTint: '#B9EFDF', // 진행 막대 정답 구간
    onPrimary: '#FFFFFF',
    live: '#F6465D', // 수업 중 점·수업 종료 버튼·장치 끊김 점
    danger: '#C43549', // 오류 문구
    warningSoft: '#FFF7D6', // 오답 카드
    warningTint: '#FFE99B', // 진행 막대 오답 구간
    warningLine: '#FFD400', // 오답 범례 테두리·재연결 중
    warningText: '#8A6D00', // 오답 글자
    dark: '#16181D', // 03 상단 바·내 마이크 카드
    darkRaised: '#2C2C30', // 03 참여 코드 칩
    darkLine: '#48484C', // 03 어두운 버튼 테두리·구분선
    darkTextSub: '#B9B9BD', // 03 어두운 배경 위 보조 글자
  },
  radius: { small: 6, control: 8, panel: 10, card: 12, pill: 99 },
  font: {
    sans: "'Noto Sans KR', sans-serif",
    mono: "'IBM Plex Mono', monospace", // 코드·숫자·타이머
  },
  layout: { headerHeight: 64, sidebarWidth: 240, pagePadding: 32, livePanelWidth: 420 },
} as const
```

와이어프레임 글자 크기(참고, 각 컴포넌트에 직접 쓴다): h1 28/900(자간 -.02em), 카드 제목 17/800, 본문·버튼 14, 칩·보조 13, 캡션 12, 사이드바 섹션·상태 알약 11.5, 로고 21/900, 03 지금 문제 42/900.

- [ ] **Step 3: 공용 UI 조각**

`front/apps/web/src/shared/ui/styles.ts`:

```ts
import type { CSSProperties } from 'react'
import { theme } from './theme'

const c = theme.color

export type ButtonVariant = 'primary' | 'outline' | 'mintOutline' | 'muted' | 'dangerOutline' | 'darkOutline'

const BUTTON_BASE: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 6,
  borderRadius: theme.radius.control,
  fontFamily: 'inherit',
  textDecoration: 'none',
  whiteSpace: 'nowrap',
  cursor: 'pointer',
  flexShrink: 0,
}

const VARIANTS: Record<ButtonVariant, CSSProperties> = {
  // 새 수업 개설·수업 진행: 민트 채움 + 아래 그림자
  primary: { border: 0, background: c.primary, color: c.onPrimary, fontWeight: 800, boxShadow: `0 3px 0 ${c.primaryStrong}` },
  // 취소·한 번 더 읽어주기
  outline: { border: `1.5px solid ${c.text}`, background: c.surface, color: c.text, fontWeight: 700 },
  // 수업 시작(예정 행)·다음 문제 선택
  mintOutline: { border: `1.5px solid ${c.primaryStrong}`, background: c.surface, color: c.primaryDark, fontWeight: 700 },
  // 회차 리포트(종료 행)·로그아웃
  muted: { border: `1.5px solid ${c.border}`, background: c.surface, color: c.textSub, fontWeight: 700 },
  // 03 수업 종료
  dangerOutline: { border: `1.5px solid ${c.live}`, background: 'transparent', color: c.live, fontWeight: 700 },
  // 03 어두운 바·마이크 카드 위 버튼
  darkOutline: { border: `1.5px solid ${c.darkLine}`, background: 'transparent', color: '#FFFFFF', fontWeight: 700 },
}

const SIZES: Record<'md' | 'sm', CSSProperties> = {
  md: { padding: '11px 18px', fontSize: 14 },
  sm: { padding: '9px 16px', fontSize: 13 },
}

/** Link와 button 모두에 쓰는 버튼 모양 */
export function buttonStyle(variant: ButtonVariant, size: 'md' | 'sm' = 'md'): CSSProperties {
  return { ...BUTTON_BASE, ...SIZES[size], ...VARIANTS[variant] }
}

/** 목(준비 중) 컨트롤: 와이어프레임 모양을 유지하고 누를 수 없다는 것만 커서로 알린다 */
export const inactive: CSSProperties = { cursor: 'default' }

/** 요청 중이라 잠시 누를 수 없는 실제 컨트롤 */
export const dimmed: CSSProperties = { opacity: 0.55, cursor: 'not-allowed' }

export const fieldWrap: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 6 }

export const fieldLabel: CSSProperties = { fontSize: 13, fontWeight: 700, color: c.textSub }

export const fieldStyle: CSSProperties = {
  width: '100%',
  minHeight: 42,
  padding: '9px 14px',
  border: `1.5px solid ${c.border}`,
  borderRadius: theme.radius.control,
  fontSize: 14,
  fontWeight: 700,
  fontFamily: 'inherit',
  color: c.text,
  background: c.surface,
  boxSizing: 'border-box',
}

export const cardStyle: CSSProperties = {
  background: c.surface,
  border: `1.5px solid ${c.border}`,
  borderRadius: theme.radius.card,
}
```

`front/apps/web/src/shared/ui/Logo.tsx`:

```tsx
import { theme } from './theme'

const dot = { width: 5, height: 5, borderRadius: '50%', background: theme.color.primary }

/** 와이어프레임 로고: I + 세로 두 점 + EUM + 민트 "강사" 알약 */
export function Logo() {
  return (
    <span
      role="img"
      aria-label="I:EUM 강사"
      style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 21, fontWeight: 900, letterSpacing: '-.02em', color: theme.color.text }}
    >
      I
      <span style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
        <span style={dot} />
        <span style={dot} />
      </span>
      EUM
      <span
        style={{
          marginLeft: 8,
          padding: '3px 9px',
          borderRadius: theme.radius.pill,
          background: theme.color.primarySoft,
          fontSize: 11.5,
          fontWeight: 700,
          color: theme.color.primaryDark,
          letterSpacing: 0,
        }}
      >
        강사
      </span>
    </span>
  )
}
```

`front/apps/web/src/shared/ui/SidebarLayout.tsx`:

```tsx
import type { ReactNode } from 'react'
import { theme } from './theme'

interface SidebarLayoutProps {
  /** 사이드바 섹션 이름 (예: 내 강의, 개설 단계) */
  sidebarTitle: string
  sidebar: ReactNode
  children: ReactNode
  /** 본문 세로 간격. 01a 20, 02 22 */
  gap?: number
  maxWidth?: number
}

/** 01a·02 공통: 흰 좌측 사이드바(240px) + 본문(padding 32). AppShell의 Outlet 안에서 남은 높이를 채운다 */
export function SidebarLayout({ sidebarTitle, sidebar, children, gap = 20, maxWidth }: SidebarLayoutProps) {
  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
      <nav
        aria-label={sidebarTitle}
        style={{
          width: theme.layout.sidebarWidth,
          flexShrink: 0,
          padding: '24px 16px',
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
          background: theme.color.surface,
          borderRight: `1.5px solid ${theme.color.border}`,
        }}
      >
        <div style={{ padding: '0 12px 8px', fontSize: 11.5, fontWeight: 700, color: theme.color.textMuted, letterSpacing: '.06em' }}>
          {sidebarTitle}
        </div>
        {sidebar}
      </nav>
      <main
        style={{
          flex: 1,
          minWidth: 0,
          maxWidth,
          padding: theme.layout.pagePadding,
          display: 'flex',
          flexDirection: 'column',
          gap,
        }}
      >
        {children}
      </main>
    </div>
  )
}
```

`front/apps/web/src/shared/ui/PageHeader.tsx`:

```tsx
import { Fragment, type ReactNode } from 'react'
import { theme } from './theme'

interface PageHeaderProps {
  /** 마지막 항목이 현재 화면(민트 굵게) */
  crumbs: string[]
  title: string
  subtitle?: string
  actions?: ReactNode
}

/** 01a·02 공통: 브레드크럼 + h1(28/900) + 부제, 오른쪽 버튼 */
export function PageHeader({ crumbs, title, subtitle, actions }: PageHeaderProps) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', gap: 24 }}>
      <div>
        <nav
          aria-label="현재 위치"
          style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, color: theme.color.textMuted, marginBottom: 6 }}
        >
          {crumbs.map((crumb, i) => (
            <Fragment key={crumb}>
              {i > 0 && <span aria-hidden="true">›</span>}
              <span style={i === crumbs.length - 1 ? { color: theme.color.primaryDark, fontWeight: 700 } : undefined}>{crumb}</span>
            </Fragment>
          ))}
        </nav>
        <h1 style={{ margin: 0, fontSize: 28, fontWeight: 900, letterSpacing: '-.02em' }}>{title}</h1>
        {subtitle && <p style={{ margin: '4px 0 0', fontSize: 14, color: theme.color.textSub }}>{subtitle}</p>}
      </div>
      {actions && <div style={{ display: 'flex', gap: 12, flexShrink: 0 }}>{actions}</div>}
    </div>
  )
}
```

- [ ] **Step 4: 로그인 화면**

와이어프레임에 로그인 화면은 없다. 같은 토큰으로 캔버스 배경 위 흰 카드 + 로고 + 민트 버튼으로 만든다. 로직은 그대로다.

`front/apps/web/src/pages/LoginPage.tsx`:

```tsx
import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { authApi } from '../shared/auth/authApi'
import { errorMessage } from '../shared/ui/errorMessage'
import { Logo } from '../shared/ui/Logo'
import { buttonStyle, cardStyle, dimmed, fieldLabel, fieldStyle, fieldWrap } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const expired = new URLSearchParams(location.search).get('expired') === '1'

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setPending(true)
    setError(null)
    try {
      await authApi.signIn(String(form.get('username') ?? ''), String(form.get('password') ?? ''))
      navigate('/lectures', { replace: true })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setPending(false)
    }
  }

  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        background: theme.color.canvas,
        fontFamily: theme.font.sans,
        color: theme.color.text,
      }}
    >
      <form
        onSubmit={handleSubmit}
        style={{
          ...cardStyle,
          width: 400,
          maxWidth: 'calc(100vw - 32px)',
          boxSizing: 'border-box',
          padding: 32,
          display: 'flex',
          flexDirection: 'column',
          gap: 18,
        }}
      >
        <Logo />
        <h1 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>강사 로그인</h1>
        {expired && (
          <p role="status" style={{ margin: 0, fontSize: 13, color: theme.color.textSub }}>
            로그인이 만료됐어요. 다시 로그인해 주세요.
          </p>
        )}
        <label style={fieldWrap}>
          <span style={fieldLabel}>아이디</span>
          <input name="username" autoComplete="username" required style={fieldStyle} />
        </label>
        <label style={fieldWrap}>
          <span style={fieldLabel}>비밀번호</span>
          <input name="password" type="password" autoComplete="current-password" required style={fieldStyle} />
        </label>
        {error && (
          <p role="alert" style={{ margin: 0, fontSize: 13, fontWeight: 700, color: theme.color.danger }}>
            {error}
          </p>
        )}
        <button type="submit" disabled={pending} style={{ ...buttonStyle('primary'), width: '100%', ...(pending ? dimmed : {}) }}>
          {pending ? '로그인 중…' : '로그인'}
        </button>
      </form>
    </main>
  )
}
```

- [ ] **Step 5: 인증 가드와 상단 바**

`front/apps/web/src/app/RequireInstructor.tsx`:

```tsx
import { useEffect } from 'react'
import { Navigate, Outlet, useNavigate } from 'react-router-dom'
import { setSessionExpiredHandler } from '../shared/api/apiClient'
import { authApi } from '../shared/auth/authApi'

/** 로그인한 INSTRUCTOR만 통과시킨다. API가 401로 세션 만료를 알리면 로그인 화면으로 보낸다 */
export function RequireInstructor() {
  const navigate = useNavigate()
  const user = authApi.currentUser()

  useEffect(() => {
    setSessionExpiredHandler(() => {
      authApi.signOut()
      navigate('/login?expired=1', { replace: true })
    })
    return () => setSessionExpiredHandler(() => {})
  }, [navigate])

  if (!user || user.role !== 'INSTRUCTOR') return <Navigate to="/login" replace />
  return <Outlet />
}
```

`front/apps/web/src/app/AppShell.tsx` (와이어프레임 01a·02 상단 바: 높이 64, padding 0 32px, 아래 테두리 1.5px):

```tsx
import { Link, Outlet, useNavigate } from 'react-router-dom'
import { authApi } from '../shared/auth/authApi'
import { Logo } from '../shared/ui/Logo'
import { buttonStyle } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

const c = theme.color

export function AppShell() {
  const navigate = useNavigate()
  const user = authApi.currentUser()

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        background: c.bg,
        color: c.text,
        fontFamily: theme.font.sans,
      }}
    >
      <header
        style={{
          height: theme.layout.headerHeight,
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 32px',
          background: c.surface,
          borderBottom: `1.5px solid ${c.border}`,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 40 }}>
          <Link to="/lectures" style={{ textDecoration: 'none' }}>
            <Logo />
          </Link>
          {/* 대시보드·수강생·문제 은행은 MVP 범위 밖이라 비활성 표시만 한다 */}
          <nav aria-label="주 메뉴" style={{ display: 'flex', gap: 26, fontSize: 14, fontWeight: 500, color: c.textSub }}>
            <span aria-disabled="true">대시보드</span>
            <Link
              to="/lectures"
              aria-current="page"
              style={{ color: c.primaryDark, fontWeight: 800, boxShadow: `0 3px 0 ${c.primary}`, paddingBottom: 2, textDecoration: 'none' }}
            >
              교육
            </Link>
            <span aria-disabled="true">수강생</span>
            <span aria-disabled="true">문제 은행</span>
          </nav>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          {/* 목: 검색은 MVP 범위 밖 — 와이어프레임 자리만 둔다 */}
          <div
            aria-hidden="true"
            style={{ padding: '8px 16px', borderRadius: theme.radius.control, border: `1.5px solid ${c.border}`, fontSize: 13, color: c.textSub, background: c.surface }}
          >
            학생·수업 검색
          </div>
          <span
            title={user?.username}
            style={{
              width: 34,
              height: 34,
              borderRadius: '50%',
              background: c.primary,
              color: c.onPrimary,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 13,
              fontWeight: 700,
            }}
          >
            {user?.username.charAt(0).toUpperCase()}
          </span>
          <button
            type="button"
            onClick={() => {
              authApi.signOut()
              navigate('/login', { replace: true })
            }}
            style={{ ...buttonStyle('muted', 'sm'), padding: '8px 12px' }}
          >
            로그아웃
          </button>
        </div>
      </header>
      <Outlet />
    </div>
  )
}
```

- [ ] **Step 6: 라우터**

`front/apps/web/src/app/router.tsx` 를 다음으로 바꾼다. 인증 메일 중계 라우트 두 개와 `/signup`은 그대로 둔다. `LectureListPage`는 Task 5에서 만들므로, 이 태스크에서는 임시 컴포넌트를 둔다.

```tsx
import { createBrowserRouter, Navigate } from 'react-router-dom'
import { AppActionRedirectPage } from '../pages/AppActionRedirectPage'
import { LoginPage } from '../pages/LoginPage'
import { SignupPage } from '../pages/SignupPage'
import { AppShell } from './AppShell'
import { RequireInstructor } from './RequireInstructor'

// Task 5에서 LectureListPage로 교체한다
function LecturesPlaceholder() {
  return <p style={{ padding: 24 }}>수업 목록</p>
}

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/lectures" replace /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },
  {
    element: <RequireInstructor />,
    children: [
      {
        element: <AppShell />,
        children: [{ path: '/lectures', element: <LecturesPlaceholder /> }],
      },
    ],
  },
  {
    path: '/auth/verify-email',
    element: (
      <AppActionRedirectPage
        appPath="auth/verify-email"
        title="이메일 인증"
        description="잇다 앱에서 인증을 마무리하고 있어요. 앱이 열리면 잠시 기다려 주세요."
      />
    ),
  },
  {
    path: '/auth/reset-password',
    element: (
      <AppActionRedirectPage
        appPath="auth/reset-password/confirm"
        title="비밀번호 재설정"
        description="잇다 앱에서 새 비밀번호를 입력할 수 있어요. 앱이 열리면 잠시 기다려 주세요."
      />
    ),
  },
])
```

`AppActionRedirectPage`의 두 라우트는 **기존 props를 그대로** 옮긴다(위 문구가 기존과 다르면 기존 문구를 쓴다). 기존 파일의 메일 링크 경로 주석도 그대로 옮긴다. `HomePage.tsx`는 더 이상 쓰지 않으므로 삭제한다.

- [ ] **Step 7: 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: 모두 성공

- [ ] **Step 8: Commit**

```bash
git add -A front/apps/web/index.html front/apps/web/src
git commit -m "feat(lecture): 강사 로그인 화면과 인증 가드를 추가한다"
```

---

### Task 5: 수업 목록(01a)과 수업 개설(02)

화면 기준: 와이어프레임 01a(수업 목록)·02(수업 개설 · 1단계 기본 정보). 데이터 연결(API 호출·필드·라우트·오류 처리·폼 필드 이름 `title`·`description`·`scheduledAt`)은 그대로이고 JSX·스타일만 와이어프레임을 따른다. 우리 모델에는 강의(코스) 계층이 없으므로 사이드바 "내 강의"에는 실제 수업 수를 보여 주는 "전체 수업" 하나만 둔다. API에 없는 값은 와이어프레임 문구를 그대로 쓰고 `/* 목 */`로 표시한다.

**Files:**
- Create: `front/apps/web/src/pages/LectureListPage.tsx`
- Create: `front/apps/web/src/pages/LectureCreatePage.tsx`
- Modify: `front/apps/web/src/app/router.tsx` (임시 컴포넌트 제거, 두 라우트 추가)

**Interfaces:**
- Consumes: Task 2 `lectureApi.list/create`, `toScheduledAtUtc`, `InstructorLecture`, `LectureStatus`; Task 1 `errorMessage`; Task 4 `theme`, `buttonStyle`, `inactive`, `dimmed`, `fieldWrap`, `fieldLabel`, `fieldStyle`, `cardStyle`, `SidebarLayout`, `PageHeader`
- Produces: 라우트 `/lectures`, `/lectures/new`

- [ ] **Step 1: 목록 화면 (01a)**

동작: 상태 칩(전체·진행 중·예정·종료)은 불러온 목록을 클라이언트에서 거른다. 목록은 와이어프레임처럼 진행 중 → 예정 → 종료 순서로 보이고, 같은 상태 안에서는 서버 순서(최신순)를 유지한다.

`front/apps/web/src/pages/LectureListPage.tsx`:

```tsx
import { useEffect, useState, type CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { lectureApi } from '../domains/lecture/api/lectureApi'
import type { InstructorLecture, LectureStatus } from '../domains/lecture/model/types'
import { errorMessage } from '../shared/ui/errorMessage'
import { PageHeader } from '../shared/ui/PageHeader'
import { SidebarLayout } from '../shared/ui/SidebarLayout'
import { buttonStyle, inactive } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

const c = theme.color

type Filter = 'ALL' | LectureStatus

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'ALL', label: '전체' },
  { key: 'OPEN', label: '진행 중' },
  { key: 'SCHEDULED', label: '예정' },
  { key: 'CLOSED', label: '종료' },
]

const STATUS_ORDER: Record<LectureStatus, number> = { OPEN: 0, SCHEDULED: 1, CLOSED: 2 }

const STATUS_LABEL: Record<LectureStatus, string> = { SCHEDULED: '예정', OPEN: '진행 중', CLOSED: '종료' }

const PILL_BASE: CSSProperties = { fontSize: 11.5, fontWeight: 700, borderRadius: theme.radius.pill, padding: '2px 10px', flexShrink: 0 }

const PILL: Record<LectureStatus, CSSProperties> = {
  OPEN: { ...PILL_BASE, padding: '3px 10px', fontWeight: 800, background: c.primary, color: c.onPrimary },
  SCHEDULED: { ...PILL_BASE, border: `1.5px solid ${c.textSub}`, color: c.textSub },
  CLOSED: { ...PILL_BASE, background: c.border, color: c.textSub },
}

const ROW: CSSProperties = {
  borderRadius: theme.radius.card,
  padding: '18px 22px',
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 24,
}

const ROW_BODY: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 5, minWidth: 0 }

const ROW_TITLE_LINE: CSSProperties = { display: 'flex', alignItems: 'center', gap: 10 }

const CHIP: CSSProperties = {
  padding: '9px 16px',
  borderRadius: theme.radius.control,
  border: `1.5px solid ${c.border}`,
  background: c.surface,
  color: c.textSub,
  fontSize: 13,
  fontFamily: 'inherit',
  cursor: 'pointer',
}

const CHIP_ACTIVE: CSSProperties = { ...CHIP, border: `1.5px solid ${c.text}`, background: c.text, color: '#FFFFFF', fontWeight: 700 }

function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
}

function day(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric' })
}

function Code({ value }: { value: string }) {
  return <span style={{ fontFamily: theme.font.mono, fontWeight: 700 }}>{value}</span>
}

function LectureRow({ lecture }: { lecture: InstructorLecture }) {
  const liveUrl = `/lectures/${lecture.lectureId}/live`

  if (lecture.status === 'OPEN') {
    return (
      <li style={{ ...ROW, background: c.primarySoft, border: `1.5px solid ${c.primaryStrong}` }}>
        <div style={ROW_BODY}>
          <div style={ROW_TITLE_LINE}>
            <span style={PILL.OPEN}>{STATUS_LABEL.OPEN}</span>
            <span style={{ fontSize: 16, fontWeight: 800 }}>{lecture.title}</span>
          </div>
          <div style={{ fontSize: 13, color: c.primaryDark }}>
            {clock(lecture.startedAt ?? lecture.createdAt)} 시작 · {'14문항' /* 목 */} · 코드 <Code value={lecture.participationCode} /> ·{' '}
            {'입장 18/24' /* 목 */}
          </div>
        </div>
        <Link to={liveUrl} style={{ ...buttonStyle('primary'), padding: '10px 18px' }}>
          수업 진행 →
        </Link>
      </li>
    )
  }

  if (lecture.status === 'SCHEDULED') {
    return (
      <li style={{ ...ROW, background: c.surface, border: `1.5px solid ${c.border}` }}>
        <div style={ROW_BODY}>
          <div style={ROW_TITLE_LINE}>
            <span style={PILL.SCHEDULED}>{STATUS_LABEL.SCHEDULED}</span>
            <span style={{ fontSize: 15.5, fontWeight: 700 }}>{lecture.title}</span>
          </div>
          <div style={{ fontSize: 13, color: c.textSub }}>
            {lecture.scheduledAt ? `${day(lecture.scheduledAt)} ${clock(lecture.scheduledAt)}` : '시작 시각 미정'} · 코드{' '}
            <Code value={lecture.participationCode} />
          </div>
        </div>
        <Link to={liveUrl} style={buttonStyle('mintOutline', 'sm')}>
          수업 시작 →
        </Link>
      </li>
    )
  }

  return (
    <li style={{ ...ROW, padding: '16px 22px', background: c.bg, border: `1.5px dashed ${c.border}` }}>
      <div style={{ ...ROW_BODY, gap: 4 }}>
        <div style={ROW_TITLE_LINE}>
          <span style={PILL.CLOSED}>{STATUS_LABEL.CLOSED}</span>
          <span style={{ fontSize: 15, fontWeight: 700, color: c.textSub }}>{lecture.title}</span>
        </div>
        <div style={{ fontSize: 13, color: c.textSub }}>
          {day(lecture.endedAt ?? lecture.startedAt ?? lecture.createdAt)} · {'참여 22명 · 정답률 76%' /* 목 */}
        </div>
      </div>
      {/* 목: 회차 리포트(06)는 MVP 범위 밖 */}
      <button type="button" disabled title="준비 중" style={{ ...buttonStyle('muted', 'sm'), ...inactive }}>
        회차 리포트
      </button>
    </li>
  )
}

export function LectureListPage() {
  const [lectures, setLectures] = useState<InstructorLecture[] | null>(null)
  const [total, setTotal] = useState(0)
  const [filter, setFilter] = useState<Filter>('ALL')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    lectureApi
      .list(0, 50)
      .then((page) => {
        setLectures(page.items)
        setTotal(page.totalElements)
      })
      .catch((e: unknown) => setError(errorMessage(e)))
  }, [])

  const all = lectures ?? []
  const count = (key: Filter) => (key === 'ALL' ? all.length : all.filter((l) => l.status === key).length)
  const visible = all
    .filter((l) => filter === 'ALL' || l.status === filter)
    .sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status])

  return (
    <SidebarLayout
      sidebarTitle="내 강의"
      maxWidth={1400}
      sidebar={
        <div
          aria-current="page"
          style={{ padding: '10px 12px', borderRadius: theme.radius.control, background: c.primarySoft, display: 'flex', justifyContent: 'space-between' }}
        >
          <span style={{ fontSize: 14, fontWeight: 800, color: c.primaryDark }}>전체 수업</span>
          <span style={{ fontFamily: theme.font.mono, fontSize: 13, fontWeight: 700, color: c.primaryDark }}>{total}</span>
        </div>
      }
    >
      <PageHeader
        crumbs={['교육', '수업 목록']}
        title="수업 목록"
        subtitle={`전체 ${total}회차`}
        actions={
          <Link to="/lectures/new" style={{ ...buttonStyle('primary'), padding: '11px 22px' }}>
            새 수업 개설
          </Link>
        }
      />

      <div role="group" aria-label="상태 필터" style={{ display: 'flex', gap: 8 }}>
        {FILTERS.map(({ key, label }) => (
          <button
            key={key}
            type="button"
            aria-pressed={filter === key}
            onClick={() => setFilter(key)}
            style={filter === key ? CHIP_ACTIVE : CHIP}
          >
            {label} {count(key)}
          </button>
        ))}
      </div>

      {error && (
        <p role="alert" style={{ margin: 0, fontSize: 14, fontWeight: 700, color: c.danger }}>
          {error}
        </p>
      )}
      {lectures === null && !error && <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>불러오는 중…</p>}
      {lectures !== null && all.length === 0 && (
        <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>아직 만든 수업이 없어요. 새 수업을 개설해 보세요.</p>
      )}
      {all.length > 0 && visible.length === 0 && (
        <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>이 상태의 수업이 없어요.</p>
      )}

      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
        {visible.map((lecture) => (
          <LectureRow key={lecture.lectureId} lecture={lecture} />
        ))}
      </ul>

      {lectures !== null && (
        <div style={{ marginTop: 'auto', display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 13, color: c.textSub }}>
          <span>
            {total}회차 중 {visible.length}개 표시
          </span>
        </div>
      )}
    </SidebarLayout>
  )
}
```

- [ ] **Step 2: 개설 화면 (02)**

실제 입력은 수업 이름·설명(와이어프레임에 없어 같은 입력 모양의 선택 textarea로 둔다)·시작 시각뿐이다. 단계·난이도·문항당 제한·두 토글은 와이어프레임 모양 그대로 비활성(목)이다. MVP는 한 단계라 헤더의 주 버튼이 바로 "개설하고 코드 발급"이고, 사이드바 2·3단계는 갈 수 없는 상태로 보인다.

`front/apps/web/src/pages/LectureCreatePage.tsx`:

```tsx
import { useState, type CSSProperties, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { lectureApi, toScheduledAtUtc } from '../domains/lecture/api/lectureApi'
import { errorMessage } from '../shared/ui/errorMessage'
import { PageHeader } from '../shared/ui/PageHeader'
import { SidebarLayout } from '../shared/ui/SidebarLayout'
import { buttonStyle, cardStyle, dimmed, fieldLabel, fieldStyle, fieldWrap, inactive } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

const c = theme.color
const FORM_ID = 'lecture-create-form'
const STEPS = ['기본 정보', '문제 세트', '참여 방식'] as const

/* 목: 아래 값들은 수업 API에 없다 — 와이어프레임 모양만 보여 주고 비활성으로 둔다 */
const LEVELS = ['촉지', '자음', '모음', '약자', '약어', '단어', '문장'] as const
const MOCK_LEVEL = '약자'
const DIFFICULTIES = ['초급', '중급', '고급'] as const
const MOCK_DIFFICULTY = '중급'
const MOCK_TOGGLES = [
  { label: '오답을 복습 목록에 담기', hint: '학생 앱 오늘의 복습에 자동 추가', on: true },
  { label: '정답 즉시 공개', hint: '끄면 수업 종료 후 함께 확인', on: false },
]

const LEVEL_CHIP: CSSProperties = {
  padding: '7px 14px',
  borderRadius: theme.radius.control,
  border: `1.5px solid ${c.border}`,
  background: c.surface,
  color: c.textSub,
  fontSize: 13,
  fontFamily: 'inherit',
  ...inactive,
}
const LEVEL_CHIP_ON: CSSProperties = { ...LEVEL_CHIP, border: `1.5px solid ${c.primary}`, background: c.primary, color: c.onPrimary, fontWeight: 800 }

const SEGMENT: CSSProperties = {
  flex: 1,
  padding: '9px 0',
  borderRadius: theme.radius.control,
  border: `1.5px solid ${c.border}`,
  background: c.surface,
  color: c.textSub,
  fontSize: 13,
  fontFamily: 'inherit',
  textAlign: 'center',
  ...inactive,
}
const SEGMENT_ON: CSSProperties = { ...SEGMENT, border: `1.5px solid ${c.text}`, background: c.text, color: '#FFFFFF', fontWeight: 700 }

function StepItem({ index, label }: { index: number; label: string }) {
  const active = index === 0
  return (
    <div
      aria-current={active ? 'step' : undefined}
      aria-disabled={active ? undefined : true}
      title={active ? undefined : '준비 중'}
      style={{
        padding: 12,
        borderRadius: theme.radius.control,
        background: active ? c.primarySoft : 'transparent',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
      }}
    >
      <span
        style={{
          width: 22,
          height: 22,
          borderRadius: '50%',
          fontFamily: theme.font.mono,
          fontSize: 12,
          fontWeight: 700,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          ...(active ? { background: c.primary, color: c.onPrimary } : { border: `1.5px solid ${c.textMuted}`, color: c.textMuted }),
        }}
      >
        {index + 1}
      </span>
      <span style={active ? { fontSize: 14, fontWeight: 800, color: c.primaryDark } : { fontSize: 14, color: c.textSub }}>{label}</span>
    </div>
  )
}

export function LectureCreatePage() {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const scheduledLocal = String(form.get('scheduledAt') ?? '')
    setPending(true)
    setError(null)
    try {
      await lectureApi.create({
        title: String(form.get('title') ?? ''),
        description: String(form.get('description') ?? ''),
        scheduledAt: scheduledLocal ? toScheduledAtUtc(scheduledLocal) : undefined,
      })
      navigate('/lectures', { replace: true })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setPending(false)
    }
  }

  return (
    <SidebarLayout
      sidebarTitle="개설 단계"
      gap={22}
      sidebar={STEPS.map((step, i) => (
        <StepItem key={step} index={i} label={step} />
      ))}
    >
      <PageHeader
        crumbs={['교육', '수업 목록', '새 수업 개설']}
        title="수업 개설"
        subtitle="개설하면 참여 코드가 바로 발급돼요"
        actions={
          <>
            <Link to="/lectures" style={buttonStyle('outline')}>
              취소
            </Link>
            <button
              type="submit"
              form={FORM_ID}
              disabled={pending}
              style={{ ...buttonStyle('primary'), padding: '11px 22px', ...(pending ? dimmed : {}) }}
            >
              {pending ? '개설 중…' : '개설하고 코드 발급'}
            </button>
          </>
        }
      />

      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'flex-start' }}>
        <form
          id={FORM_ID}
          onSubmit={handleSubmit}
          style={{ ...cardStyle, width: 720, maxWidth: '100%', boxSizing: 'border-box', padding: 32, display: 'flex', flexDirection: 'column', gap: 18 }}
        >
          <h2 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>1단계 · 기본 정보</h2>

          <label style={fieldWrap}>
            <span style={fieldLabel}>수업 이름</span>
            <input name="title" required maxLength={150} placeholder="약자 4회차 · 약자 규칙 중심" style={fieldStyle} />
          </label>

          <label style={fieldWrap}>
            <span style={fieldLabel}>설명 (선택)</span>
            <textarea name="description" rows={3} style={{ ...fieldStyle, fontWeight: 400, resize: 'vertical' }} />
          </label>

          {/* 목 */}
          <div style={fieldWrap}>
            <span id="lecture-level-label" style={fieldLabel}>
              단계
            </span>
            <div role="group" aria-labelledby="lecture-level-label" style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {LEVELS.map((level) => (
                <button
                  key={level}
                  type="button"
                  disabled
                  title="준비 중"
                  aria-pressed={level === MOCK_LEVEL}
                  style={level === MOCK_LEVEL ? LEVEL_CHIP_ON : LEVEL_CHIP}
                >
                  {level}
                </button>
              ))}
            </div>
          </div>

          {/* 목 */}
          <div style={fieldWrap}>
            <span id="lecture-difficulty-label" style={fieldLabel}>
              난이도
            </span>
            <div role="group" aria-labelledby="lecture-difficulty-label" style={{ display: 'flex', gap: 8 }}>
              {DIFFICULTIES.map((difficulty) => (
                <button
                  key={difficulty}
                  type="button"
                  disabled
                  title="준비 중"
                  aria-pressed={difficulty === MOCK_DIFFICULTY}
                  style={difficulty === MOCK_DIFFICULTY ? SEGMENT_ON : SEGMENT}
                >
                  {difficulty}
                </button>
              ))}
            </div>
          </div>

          <div style={{ display: 'flex', gap: 14 }}>
            <label style={{ ...fieldWrap, flex: 1 }}>
              <span style={fieldLabel}>시작 시각 (선택)</span>
              <input name="scheduledAt" type="datetime-local" style={{ ...fieldStyle, fontFamily: theme.font.mono }} />
            </label>
            {/* 목 */}
            <label style={{ ...fieldWrap, flex: 1 }}>
              <span style={fieldLabel}>문항당 제한</span>
              <input value="30초" readOnly disabled title="준비 중" style={{ ...fieldStyle, fontFamily: theme.font.mono, color: c.text, ...inactive }} />
            </label>
          </div>

          {/* 목 */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {MOCK_TOGGLES.map((toggle) => (
              <div key={toggle.label} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <span style={{ fontSize: 14, fontWeight: 700 }}>{toggle.label}</span>
                  <span style={{ fontSize: 12, color: c.textSub }}>{toggle.hint}</span>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={toggle.on}
                  aria-label={toggle.label}
                  disabled
                  title="준비 중"
                  style={{
                    width: 44,
                    height: 24,
                    padding: 0,
                    border: 0,
                    borderRadius: theme.radius.pill,
                    background: toggle.on ? c.primary : c.border,
                    position: 'relative',
                    flexShrink: 0,
                    ...inactive,
                  }}
                >
                  <span
                    style={{
                      position: 'absolute',
                      top: 3,
                      width: 18,
                      height: 18,
                      borderRadius: '50%',
                      background: '#FFFFFF',
                      ...(toggle.on ? { right: 3 } : { left: 3, boxShadow: '0 1px 3px rgba(0,0,0,.2)' }),
                    }}
                  />
                </button>
              </div>
            ))}
          </div>

          {error && (
            <p role="alert" style={{ margin: 0, fontSize: 13, fontWeight: 700, color: c.danger }}>
              {error}
            </p>
          )}
        </form>
      </div>
    </SidebarLayout>
  )
}
```

- [ ] **Step 3: 라우터 연결**

`router.tsx`에서 `LecturesPlaceholder`를 지우고 `AppShell`의 children을 다음으로 바꾼다:

```tsx
        children: [
          { path: '/lectures', element: <LectureListPage /> },
          { path: '/lectures/new', element: <LectureCreatePage /> },
        ],
```

import에 `LectureListPage`, `LectureCreatePage`를 추가한다.

- [ ] **Step 4: 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: 모두 성공

- [ ] **Step 5: Commit**

```bash
git add front/apps/web/src
git commit -m "feat(lecture): 강사 웹에 수업 목록과 수업 개설 화면을 추가한다"
```

---

### Task 6: 실시간 수업 화면(03)

화면 기준: 와이어프레임 03(실시간 수업 진행). 흰 상단 바·사이드바 **없이** 어두운 수업 바(64px) + 본문(왼쪽 가변 / 오른쪽 420px 흰 패널)이다. 그래서 이 라우트는 `RequireInstructor` 안, `AppShell` 밖에 둔다.

**Files:**
- Create: `front/apps/web/src/domains/lecture/mock/liveMock.ts`
- Create: `front/apps/web/src/pages/LiveLecturePage.tsx`
- Modify: `front/apps/web/src/app/router.tsx` (라우트 추가 — `AppShell` 밖)

**Interfaces:**
- Consumes: Task 2 `lectureApi.get/changeStatus/issueMediaToken`, `InstructorLecture`, `LectureStatus`; Task 3 `connectInstructorRoom`, `InstructorRoom`, `RoomState`; Task 1 `errorMessage`; Task 4 `theme`, `buttonStyle`, `cardStyle`, `dimmed`, `inactive`
- Produces: 라우트 `/lectures/:lectureId/live`

화면 동작:
- 진입: `GET /lectures/{id}`. CLOSED면 "종료된 수업" 안내와 목록 링크가 보인다.
- 어두운 상단 바(실제 데이터): 상태 점·문구(SCHEDULED "시작 전" 회색 / OPEN "수업 중" 빨강 / CLOSED "종료" 회색), 수업 이름, 참여 코드 칩(mono), 경과 시간(`startedAt` 기준 1초마다 갱신, CLOSED는 `endedAt`에서 멈춤, 시작 전은 `--:--`). 와이어프레임에 없는 "← 목록" 링크를 왼쪽 끝에 둔다(이 화면에는 다른 이동 수단이 없다).
- SCHEDULED: 상단 바 "수업 시작"(민트) → `PUT status OPEN` → `POST media-tokens` → 음성 접속(마이크 켜짐).
- OPEN(새로고침 등으로 재진입): 자동으로 토큰 발급 → 음성 접속. 마이크 권한은 브라우저가 묻는다.
- 오른쪽 "내 마이크" 카드(실제 상태): 표시 문구 송출 중(연결 + 마이크 켬) / 마이크 꺼짐 / 연결 중 / 재연결 중 / 끊김, 수업 전·후는 "수업 시작 전"·"수업 종료". 연결되면 마이크 끄기·켜기 토글, OPEN인데 끊기면 "음성 다시 연결"(토큰 재발급 → 재접속). 음량 막대는 장식(목), "장치 선택"은 비활성.
- "수업 종료"(빨강 테두리): 확인창 → `PUT status CLOSED`. 성공하면 음성 해제 후 목록으로. 503(`MEDIA_SERVER_UNAVAILABLE`)이면 상태는 이미 CLOSED이므로 버튼이 "종료 다시 시도"로 바뀌고 같은 버튼이 다시 `CLOSED`를 보낸다.
- "일시 정지"는 비활성(목).
- 페이지를 떠나면 음성 방을 해제한다(수업 상태는 그대로).
- 목: 지금 문제 카드(점형 그림), 제출 진행 막대, 학생 진행 그리드(와이어프레임의 25명·점자 글리프·장치 점), 범례, 담아둔 세트, 문제 은행 바로 출제 — 버튼은 비활성.

- [ ] **Step 1: 목 데이터**

`front/apps/web/src/domains/lecture/mock/liveMock.ts` (값은 와이어프레임 03 그대로):

```ts
/**
 * 03 실시간 수업 화면의 목 데이터. 문제 출제·답안·학생 진행 상태 API는 MVP 범위 밖이다.
 * 학습자는 음성 방에서 숨김 참가자라 실제 입장 여부도 여기서는 알 수 없다.
 * 값은 와이어프레임 `이음 강사웹.html` 03 화면을 그대로 옮겼다.
 */
export type MockStudentState = 'correct' | 'wrong' | 'idle' | 'absent'

export interface MockStudent {
  name: string
  state: MockStudentState
  /** 제출한 점자(유니코드 점자 문자). 미착수·미입장은 null */
  glyph: string | null
  /** 정답: 걸린 시간, 오답: 틀린 이유, 그 외: 상태 문구 */
  detail: string
  /** 카드 우측 아래 점 — 점자 출력 장치 연결 여부 */
  deviceConnected: boolean
}

export const mockStudents: MockStudent[] = [
  { name: '강민서', state: 'correct', glyph: '⠛⠊', detail: '1.4초', deviceConnected: true },
  { name: '고은우', state: 'correct', glyph: '⠛⠊', detail: '2.1초', deviceConnected: true },
  { name: '김도윤', state: 'wrong', glyph: '⠓⠊', detail: '6점 누락', deviceConnected: false },
  { name: '김서아', state: 'correct', glyph: '⠛⠊', detail: '1.9초', deviceConnected: true },
  { name: '김유진', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '김하율', state: 'correct', glyph: '⠛⠊', detail: '2.6초', deviceConnected: true },
  { name: '문채원', state: 'wrong', glyph: '⠊⠛', detail: '칸 순서 바뀜', deviceConnected: true },
  { name: '박지호', state: 'correct', glyph: '⠛⠊', detail: '3.4초', deviceConnected: true },
  { name: '배시윤', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '서예린', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '성지안', state: 'correct', glyph: '⠛⠊', detail: '2.2초', deviceConnected: true },
  { name: '신하준', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '안소율', state: 'correct', glyph: '⠛⠊', detail: '1.7초', deviceConnected: true },
  { name: '오시우', state: 'wrong', glyph: '⠟⠊', detail: '3점 추가', deviceConnected: true },
  { name: '유하린', state: 'idle', glyph: null, detail: '미착수', deviceConnected: false },
  { name: '이서준', state: 'correct', glyph: '⠛⠊', detail: '4.1초', deviceConnected: true },
  { name: '이주원', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '장하빈', state: 'correct', glyph: '⠛⠊', detail: '2.8초', deviceConnected: true },
  { name: '정예준', state: 'wrong', glyph: '⠓⠊', detail: '6점 누락', deviceConnected: true },
  { name: '조윤서', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '최하은', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '한지우', state: 'correct', glyph: '⠛⠊', detail: '3.0초', deviceConnected: true },
  { name: '홍시현', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '황도현', state: 'absent', glyph: null, detail: '미입장', deviceConnected: false },
  { name: '황서진', state: 'absent', glyph: null, detail: '미입장', deviceConnected: false },
]

/** 제출 진행 카드 숫자 (와이어프레임 표기 그대로) */
export const mockProgress = { submitted: 14, total: 24, correct: 10, wrong: 4, idle: 10 }

export const mockCurrentProblem = {
  order: '3 / 14',
  text: '그리고',
  answerLabel: '1·3·5 / 2·4·6',
  /** 칸별 볼록한 점 번호(1~6). 그림은 answerLabel과 같은 점형으로 그린다 */
  answerCells: [
    [1, 3, 5],
    [2, 4, 6],
  ],
}

export type MockSetItemState = 'done' | 'current' | 'ready'

export interface MockSetItem {
  text: string
  state: MockSetItemState
  /** 출제됨 항목의 반 정답률 */
  rate?: string
}

export const mockProblemSet: MockSetItem[] = [
  { text: '그래서', state: 'done', rate: '88%' },
  { text: '그러나', state: 'done', rate: '71%' },
  { text: '그리고', state: 'current' },
  { text: '그러면', state: 'ready' },
  { text: '성', state: 'ready' },
  { text: '까', state: 'ready' },
]

export const mockProblemSetTotal = 14
export const mockProblemSetMore = 8

export const mockBankResult = { text: '정', glyph: '⠸⠻', note: "된소리 'ㅆ' · 반 정답률 50%" }
```

- [ ] **Step 2: 실시간 수업 화면**

로직(`releaseRoom`, `joinVoice`, 진입 effect와 해제, `run`, `start`/`reconnect`/`toggleMic`/`end`, 503 처리)은 이전 설계와 같다. 바뀐 것은 JSX·스타일과 상단 바 경과 시간(`now` 상태 + 1초 interval)뿐이다.

`front/apps/web/src/pages/LiveLecturePage.tsx`:

```tsx
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@ieum/api-client'
import { lectureApi } from '../domains/lecture/api/lectureApi'
import { connectInstructorRoom, type InstructorRoom, type RoomState } from '../domains/lecture/live/instructorRoom'
import {
  mockBankResult,
  mockCurrentProblem,
  mockProblemSet,
  mockProblemSetMore,
  mockProblemSetTotal,
  mockProgress,
  mockStudents,
  type MockSetItem,
  type MockStudent,
  type MockStudentState,
} from '../domains/lecture/mock/liveMock'
import type { InstructorLecture, LectureStatus } from '../domains/lecture/model/types'
import { errorMessage } from '../shared/ui/errorMessage'
import { buttonStyle, cardStyle, dimmed, inactive } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

const c = theme.color
const mono = theme.font.mono

const STATUS_BADGE: Record<LectureStatus, { label: string; color: string }> = {
  SCHEDULED: { label: '시작 전', color: c.textMuted },
  OPEN: { label: '수업 중', color: c.live },
  CLOSED: { label: '종료', color: c.textMuted },
}

interface MicIndicator {
  label: string
  color: string
  live: boolean
}

/** 내 마이크 카드의 상태 문구. 실제 수업 상태·음성 방 상태·마이크 상태로 정한다 */
function micIndicator(status: LectureStatus, room: RoomState, mic: boolean): MicIndicator {
  if (status === 'SCHEDULED') return { label: '수업 시작 전', color: c.darkTextSub, live: false }
  if (status === 'CLOSED') return { label: '수업 종료', color: c.darkTextSub, live: false }
  if (room === 'connecting') return { label: '연결 중', color: c.darkTextSub, live: false }
  if (room === 'reconnecting') return { label: '재연결 중', color: c.warningLine, live: false }
  if (room === 'disconnected') return { label: '끊김', color: c.live, live: false }
  return mic ? { label: '송출 중', color: c.primary, live: true } : { label: '마이크 꺼짐', color: c.darkTextSub, live: false }
}

/** 경과 시간 mm:ss (1시간 이상이면 h:mm:ss) */
function formatElapsed(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const mmss = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return hours > 0 ? `${hours}:${mmss}` : mmss
}

/** 목: 장식용 음량 막대 높이(%) */
const LEVEL_BARS = [40, 75, 55, 90, 35, 65, 50]

/** 2열 그리드는 왼쪽→오른쪽, 위→아래로 채워지므로 점 번호를 1·4 / 2·5 / 3·6 순서로 놓는다 */
const DOT_ORDER = [1, 4, 2, 5, 3, 6]

const STUDENT_LOOK: Record<MockStudentState, { card: CSSProperties; name: string; accent: string }> = {
  correct: { card: { background: c.primarySoft }, name: c.text, accent: c.primaryDark },
  wrong: { card: { background: c.warningSoft }, name: c.text, accent: c.warningText },
  idle: { card: { background: c.bg, border: `1.5px solid ${c.borderSoft}` }, name: c.textSub, accent: c.textSub },
  absent: { card: { border: `1.5px solid ${c.borderStrong}` }, name: c.textMuted, accent: c.textMuted },
}

const LEGEND: { label: string; swatch: CSSProperties }[] = [
  { label: '정답', swatch: { background: c.primarySoft, border: `1px solid ${c.primaryStrong}` } },
  { label: '오답', swatch: { background: c.warningSoft, border: `1px solid ${c.warningLine}` } },
  { label: '미착수', swatch: { background: c.bg, border: `1px solid ${c.borderSoft}` } },
  { label: '미입장', swatch: { border: `1.5px solid ${c.borderStrong}` } },
]

function BrailleCell({ dots }: { dots: readonly number[] }) {
  return (
    <div
      aria-hidden="true"
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(2, 12px)',
        gridAutoRows: '12px',
        gap: 5,
        padding: '8px 9px',
        background: c.fill,
        borderRadius: theme.radius.control,
      }}
    >
      {DOT_ORDER.map((dot) =>
        dots.includes(dot) ? (
          <span key={dot} style={{ width: 12, height: 12, borderRadius: '50%', background: c.text }} />
        ) : (
          <span key={dot} style={{ width: 12, height: 12, borderRadius: '50%', border: `2px solid ${c.borderStrong}`, boxSizing: 'border-box' }} />
        ),
      )}
    </div>
  )
}

function StudentCard({ student }: { student: MockStudent }) {
  const look = STUDENT_LOOK[student.state]
  return (
    <li
      style={{
        position: 'relative',
        borderRadius: theme.radius.control,
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        ...look.card,
      }}
    >
      <span style={{ fontSize: 14, fontWeight: 800, color: look.name }}>{student.name}</span>
      {student.glyph && <span style={{ fontSize: 24, lineHeight: 1, color: look.accent }}>{student.glyph}</span>}
      <span style={{ fontSize: 11.5, fontWeight: 700, color: look.accent, fontFamily: student.state === 'correct' ? mono : undefined }}>
        {student.detail}
      </span>
      <span
        role="img"
        aria-label={student.deviceConnected ? '장치 연결됨' : '장치 끊김'}
        style={{
          position: 'absolute',
          right: 9,
          bottom: 9,
          width: 11,
          height: 11,
          borderRadius: '50%',
          background: student.deviceConnected ? c.primaryDark : c.live,
          border: '2px solid #FFFFFF',
        }}
      />
    </li>
  )
}

function SetItem({ item }: { item: MockSetItem }) {
  const row: CSSProperties = {
    padding: '10px 13px',
    borderRadius: theme.radius.control,
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
  }
  if (item.state === 'done') {
    return (
      <li style={{ ...row, background: c.fill, color: c.textMuted }}>
        <span style={{ fontWeight: 700 }}>{item.text}</span>
        <span style={{ fontSize: 12, fontWeight: 700 }}>출제됨 · {item.rate}</span>
      </li>
    )
  }
  if (item.state === 'current') {
    return (
      <li aria-current="true" style={{ ...row, background: c.primarySoft, border: `1.5px solid ${c.primaryStrong}` }}>
        <span style={{ fontWeight: 900, color: c.primaryDark }}>{item.text}</span>
        <span style={{ fontSize: 12, fontWeight: 800, color: c.primaryDark }}>출제 중</span>
      </li>
    )
  }
  return (
    <li style={{ ...row, border: `1.5px solid ${c.border}` }}>
      <span style={{ fontWeight: 700 }}>{item.text}</span>
      <button
        type="button"
        disabled
        title="준비 중"
        style={{
          border: `1.5px solid ${c.text}`,
          borderRadius: theme.radius.small,
          padding: '4px 12px',
          fontSize: 12,
          fontWeight: 700,
          fontFamily: 'inherit',
          background: 'transparent',
          color: c.text,
          ...inactive,
        }}
      >
        출제
      </button>
    </li>
  )
}

export function LiveLecturePage() {
  const { lectureId = '' } = useParams()
  const navigate = useNavigate()
  const [lecture, setLecture] = useState<InstructorLecture | null>(null)
  const [roomState, setRoomState] = useState<RoomState>('disconnected')
  const [mic, setMic] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const roomRef = useRef<InstructorRoom | null>(null)

  const releaseRoom = useCallback(async () => {
    const room = roomRef.current
    roomRef.current = null
    if (room) await room.disconnect()
  }, [])

  const joinVoice = useCallback(async () => {
    await releaseRoom()
    const token = await lectureApi.issueMediaToken(lectureId)
    roomRef.current = await connectInstructorRoom(token, setRoomState)
    setMic(true)
  }, [lectureId, releaseRoom])

  useEffect(() => {
    let cancelled = false
    lectureApi
      .get(lectureId)
      .then(async (loaded) => {
        if (cancelled) return
        setLecture(loaded)
        if (loaded.status === 'OPEN') await joinVoice()
      })
      .catch((e) => !cancelled && setError(errorMessage(e)))
    return () => {
      cancelled = true
      void releaseRoom()
    }
  }, [lectureId, joinVoice, releaseRoom])

  // 경과 시간: 진행 중일 때만 1초마다 다시 그린다
  const ticking = lecture?.status === 'OPEN'
  useEffect(() => {
    if (!ticking) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [ticking])

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const start = () =>
    run(async () => {
      setLecture(await lectureApi.changeStatus(lectureId, 'OPEN'))
      await joinVoice()
    })

  const reconnect = () => run(joinVoice)

  const toggleMic = () =>
    run(async () => {
      const room = roomRef.current
      if (!room) return
      await room.setMicEnabled(!mic)
      setMic(room.micEnabled())
    })

  const end = () => {
    if (!window.confirm('수업을 종료할까요? 학생들의 음성 연결도 끝납니다.')) return
    void run(async () => {
      try {
        await lectureApi.changeStatus(lectureId, 'CLOSED')
      } catch (e) {
        // 상태는 저장됐고 방 삭제만 실패했다 — 같은 버튼으로 재시도한다 (API 명세 8.5절)
        if (e instanceof ApiError && e.code === 'MEDIA_SERVER_UNAVAILABLE') {
          setLecture((prev) => (prev ? { ...prev, status: 'CLOSED' } : prev))
        }
        throw e
      }
      await releaseRoom()
      navigate('/lectures', { replace: true })
    })
  }

  if (!lecture) {
    return (
      <main
        style={{
          minHeight: '100vh',
          padding: theme.layout.pagePadding,
          background: c.bg,
          fontFamily: theme.font.sans,
          color: c.text,
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
        }}
      >
        {error ? (
          <p role="alert" style={{ margin: 0, fontSize: 14, fontWeight: 700, color: c.danger }}>
            {error}
          </p>
        ) : (
          <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>불러오는 중…</p>
        )}
        <Link to="/lectures">수업 목록으로</Link>
      </main>
    )
  }

  const badge = STATUS_BADGE[lecture.status]
  const open = lecture.status === 'OPEN'
  const indicator = micIndicator(lecture.status, roomState, mic)
  const elapsedEnd = lecture.status === 'CLOSED' && lecture.endedAt ? Date.parse(lecture.endedAt) : now
  const elapsed = lecture.startedAt ? formatElapsed(elapsedEnd - Date.parse(lecture.startedAt)) : '--:--'
  const barButton: CSSProperties = { ...buttonStyle('darkOutline', 'sm'), fontSize: 13.5 }
  const micButton: CSSProperties = { ...buttonStyle('darkOutline', 'sm'), flex: 1, padding: 8, fontSize: 12.5, flexShrink: 1 }
  const percent = (value: number) => `${(value / mockProgress.total) * 100}%`

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: c.bg, color: c.text, fontFamily: theme.font.sans }}>
      {/* 실제: 수업 상태·이름·참여 코드·경과 시간·시작/종료 */}
      <header
        style={{
          height: theme.layout.headerHeight,
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 24,
          padding: '0 32px',
          background: c.dark,
          color: '#FFFFFF',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 20, minWidth: 0 }}>
          <Link to="/lectures" style={{ fontSize: 13, fontWeight: 700, color: c.darkTextSub, textDecoration: 'none', flexShrink: 0 }}>
            ← 목록
          </Link>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexShrink: 0 }}>
            <span style={{ width: 9, height: 9, borderRadius: '50%', background: badge.color }} />
            <span style={{ fontSize: 14, fontWeight: 800 }}>{badge.label}</span>
          </div>
          <div style={{ width: 1, height: 24, background: c.darkLine, flexShrink: 0 }} />
          <h1 style={{ margin: 0, fontSize: 15, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {lecture.title}
          </h1>
          <div
            style={{
              padding: '4px 12px',
              borderRadius: theme.radius.small,
              background: c.darkRaised,
              fontFamily: mono,
              fontSize: 13,
              fontWeight: 700,
              color: c.primary,
              flexShrink: 0,
            }}
          >
            코드 {lecture.participationCode}
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 18, flexShrink: 0 }}>
          <div style={{ fontSize: 13, color: c.darkTextSub }}>
            경과 <span style={{ fontFamily: mono, fontSize: 15, fontWeight: 700, color: '#FFFFFF' }}>{elapsed}</span>
          </div>
          {/* 목: 일시 정지는 MVP 범위 밖 */}
          <button type="button" disabled title="준비 중" style={{ ...barButton, ...inactive }}>
            일시 정지
          </button>
          {lecture.status === 'SCHEDULED' ? (
            <button
              type="button"
              disabled={busy}
              onClick={start}
              style={{ ...buttonStyle('primary', 'sm'), fontSize: 13.5, boxShadow: 'none', ...(busy ? dimmed : {}) }}
            >
              수업 시작
            </button>
          ) : (
            <button type="button" disabled={busy} onClick={end} style={{ ...buttonStyle('dangerOutline', 'sm'), fontSize: 13.5, ...(busy ? dimmed : {}) }}>
              {lecture.status === 'CLOSED' ? '종료 다시 시도' : '수업 종료'}
            </button>
          )}
        </div>
      </header>

      <div style={{ flex: 1, minHeight: 0, display: 'grid', gridTemplateColumns: `minmax(0, 1fr) ${theme.layout.livePanelWidth}px` }}>
        <main style={{ minWidth: 0, padding: theme.layout.pagePadding, display: 'flex', flexDirection: 'column', gap: 20 }}>
          {error && (
            <p role="alert" style={{ margin: 0, fontSize: 14, fontWeight: 700, color: c.danger }}>
              {error}
            </p>
          )}
          {lecture.status === 'CLOSED' && !error && (
            <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>
              종료된 수업이에요. <Link to="/lectures">수업 목록으로</Link>
            </p>
          )}

          {/* 목: 지금 문제 */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 32 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 22 }}>
              <div>
                <div style={{ fontSize: 13, color: c.textSub }}>지금 문제 · {mockCurrentProblem.order}</div>
                <div style={{ fontSize: 42, fontWeight: 900, letterSpacing: '-.02em', lineHeight: 1.1 }}>{mockCurrentProblem.text}</div>
              </div>
              <div
                style={{
                  ...cardStyle,
                  borderRadius: theme.radius.panel,
                  padding: '12px 16px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 16,
                }}
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                  <span style={{ fontSize: 12, color: c.textSub }}>정답 점형</span>
                  <span style={{ fontFamily: mono, fontSize: 14, fontWeight: 700 }}>{mockCurrentProblem.answerLabel}</span>
                </div>
                <div style={{ display: 'flex', gap: 10 }}>
                  {mockCurrentProblem.answerCells.map((dots, i) => (
                    <BrailleCell key={i} dots={dots} />
                  ))}
                </div>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 12, flexShrink: 0 }}>
              <button type="button" disabled title="준비 중" style={{ ...buttonStyle('outline'), ...inactive }}>
                한 번 더 읽어주기
              </button>
              <button type="button" disabled title="준비 중" style={{ ...buttonStyle('mintOutline'), fontWeight: 800, ...inactive }}>
                오른쪽에서 다음 문제 선택 ▸
              </button>
            </div>
          </div>

          {/* 목: 제출 진행 */}
          <div style={{ ...cardStyle, padding: '18px 24px', display: 'flex', alignItems: 'center', gap: 28, flexShrink: 0 }}>
            <div>
              <div style={{ fontSize: 12, color: c.textSub }}>제출</div>
              <div style={{ fontFamily: mono, fontSize: 24, fontWeight: 700 }}>
                {mockProgress.submitted} / {mockProgress.total}
              </div>
            </div>
            <div style={{ flex: 1, height: 10, borderRadius: theme.radius.pill, background: c.canvas, overflow: 'hidden', display: 'flex' }}>
              <span style={{ width: percent(mockProgress.correct), height: '100%', background: c.primaryTint }} />
              <span style={{ width: percent(mockProgress.wrong), height: '100%', background: c.warningTint }} />
            </div>
            <div style={{ display: 'flex', gap: 22 }}>
              {[
                { label: '정답', value: mockProgress.correct, color: c.primaryDark },
                { label: '오답', value: mockProgress.wrong, color: c.warningText },
                { label: '미착수', value: mockProgress.idle, color: c.textSub },
              ].map((stat) => (
                <div key={stat.label}>
                  <div style={{ fontSize: 12, color: c.textSub }}>{stat.label}</div>
                  <div style={{ fontFamily: mono, fontSize: 20, fontWeight: 700, color: stat.color }}>{stat.value}</div>
                </div>
              ))}
            </div>
          </div>

          {/* 목: 학생 진행 상태 — 학습자는 숨김 참가자라 실제 입장 여부도 알 수 없다 */}
          <section style={{ ...cardStyle, flex: 1, minHeight: 0, padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
              <h2 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>학생 진행 상태</h2>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, fontSize: 12, color: c.textSub }}>
                {LEGEND.map((item) => (
                  <span key={item.label} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                    <span style={{ width: 9, height: 9, borderRadius: 2, ...item.swatch }} />
                    {item.label}
                  </span>
                ))}
                <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                  <span style={{ width: 9, height: 9, borderRadius: '50%', background: c.primaryDark }} />
                  우측 아래 점 = 장치 연결 여부
                </span>
              </div>
            </div>
            <ul
              style={{
                listStyle: 'none',
                margin: 0,
                padding: 0,
                flex: 1,
                display: 'grid',
                gridTemplateColumns: 'repeat(6, minmax(0, 1fr))',
                gridAutoRows: 'minmax(104px, 1fr)',
                gap: 10,
              }}
            >
              {mockStudents.map((student) => (
                <StudentCard key={student.name} student={student} />
              ))}
            </ul>
          </section>
        </main>

        <aside
          style={{
            borderLeft: `1.5px solid ${c.border}`,
            background: c.surface,
            padding: '28px 24px',
            display: 'flex',
            flexDirection: 'column',
            gap: 18,
          }}
        >
          {/* 실제: 음성 송출 */}
          <section
            aria-label="내 마이크"
            style={{
              background: c.dark,
              borderRadius: theme.radius.card,
              padding: '18px 20px',
              display: 'flex',
              flexDirection: 'column',
              gap: 12,
              color: '#FFFFFF',
              flexShrink: 0,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: 14, fontWeight: 800 }}>내 마이크</span>
              <span role="status" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: indicator.color }}>
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: indicator.color }} />
                {indicator.label}
              </span>
            </div>
            {/* 목: 장식용 음량 막대 — 송출 중일 때만 민트 */}
            <div aria-hidden="true" style={{ display: 'flex', alignItems: 'flex-end', gap: 3, height: 30 }}>
              {LEVEL_BARS.map((height, i) => (
                <span
                  key={i}
                  style={{ width: 6, height: `${height}%`, borderRadius: 2, background: indicator.live ? c.primary : c.darkLine }}
                />
              ))}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              {open && roomState === 'connected' ? (
                <button type="button" disabled={busy} onClick={toggleMic} style={{ ...micButton, ...(busy ? dimmed : {}) }}>
                  {mic ? '마이크 끄기' : '마이크 켜기'}
                </button>
              ) : open && roomState === 'disconnected' ? (
                <button
                  type="button"
                  disabled={busy}
                  onClick={reconnect}
                  style={{ ...micButton, border: `1.5px solid ${c.primary}`, background: c.primary, ...(busy ? dimmed : {}) }}
                >
                  음성 다시 연결
                </button>
              ) : (
                <button type="button" disabled style={{ ...micButton, ...dimmed }}>
                  마이크 끄기
                </button>
              )}
              {/* 목: 장치 선택은 MVP 범위 밖 */}
              <button type="button" disabled title="준비 중" style={{ ...micButton, ...inactive }}>
                장치 선택
              </button>
            </div>
            <div style={{ fontSize: 11.5, color: c.darkTextSub }}>강사 → 학생 단방향 음성 · 녹음·저장 없음</div>
          </section>

          {/* 목: 담아둔 세트 */}
          <section style={{ display: 'flex', flexDirection: 'column', gap: 10, flex: 1, minHeight: 0 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
              <h2 style={{ margin: 0, fontSize: 14, fontWeight: 800 }}>담아둔 세트 · {mockProblemSetTotal}</h2>
              <span style={{ fontSize: 11.5, color: c.textSub }}>골라서 출제 · 순서 무관</span>
            </div>
            <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 7, fontSize: 13.5 }}>
              {mockProblemSet.map((item) => (
                <SetItem key={item.text} item={item} />
              ))}
            </ul>
            <div style={{ fontSize: 12, color: c.textSub, textAlign: 'center' }}>+ {mockProblemSetMore}개 더 보기</div>
          </section>

          {/* 목: 문제 은행에서 바로 출제 */}
          <section
            style={{
              ...cardStyle,
              background: c.bg,
              padding: '16px 18px',
              display: 'flex',
              flexDirection: 'column',
              gap: 10,
              flexShrink: 0,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
              <h2 style={{ margin: 0, fontSize: 14, fontWeight: 800 }}>문제 은행에서 바로 출제</h2>
              <span style={{ fontSize: 11.5, color: c.textSub }}>검수된 문항만</span>
            </div>
            <input
              disabled
              aria-label="문제 은행 검색"
              placeholder="글자 또는 규칙 검색 — 예: 된소리"
              style={{
                border: `1.5px solid ${c.border}`,
                borderRadius: theme.radius.control,
                background: c.surface,
                padding: '9px 13px',
                fontSize: 13,
                fontFamily: 'inherit',
                color: c.textSub,
                ...inactive,
              }}
            />
            <div
              style={{
                padding: '10px 13px',
                borderRadius: theme.radius.control,
                background: c.surface,
                border: `1.5px solid ${c.border}`,
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                <span style={{ fontSize: 11, fontWeight: 700, color: c.textMuted }}>검색 결과</span>
                <span style={{ fontSize: 13.5, fontWeight: 800 }}>
                  {mockBankResult.text} <span style={{ fontSize: 18 }}>{mockBankResult.glyph}</span>
                </span>
                <span style={{ fontSize: 11.5, color: c.danger }}>{mockBankResult.note}</span>
              </div>
              <button
                type="button"
                disabled
                title="준비 중"
                style={{
                  ...buttonStyle('primary', 'sm'),
                  borderRadius: theme.radius.small,
                  padding: '6px 14px',
                  fontSize: 12,
                  boxShadow: `0 2px 0 ${c.primaryStrong}`,
                  ...inactive,
                }}
              >
                바로 출제
              </button>
            </div>
          </section>
        </aside>
      </div>
    </div>
  )
}
```

- [ ] **Step 3: 라우터 연결 (AppShell 밖)**

03은 흰 상단 바·사이드바가 없으므로 `RequireInstructor`의 children에 `AppShell`과 나란히 둔다. `router.tsx`의 `RequireInstructor` 항목을 다음으로 바꾸고 `LiveLecturePage`를 import한다:

```tsx
  {
    element: <RequireInstructor />,
    children: [
      {
        element: <AppShell />,
        children: [
          { path: '/lectures', element: <LectureListPage /> },
          { path: '/lectures/new', element: <LectureCreatePage /> },
        ],
      },
      // 03 실시간 수업은 흰 상단 바 대신 자체 어두운 수업 바를 쓴다
      { path: '/lectures/:lectureId/live', element: <LiveLecturePage /> },
    ],
  },
```

- [ ] **Step 4: 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: 모두 성공. `react-hooks/exhaustive-deps` 경고가 나오면 의존성 배열을 경고대로 맞춘다(동작은 그대로)

- [ ] **Step 5: Commit**

```bash
git add front/apps/web/src
git commit -m "feat(lecture): 강사 웹에 실시간 수업 화면과 음성 송출을 연결한다"
```

---

### Task 7: 브라우저 E2E (사람과 함께)

**Files:** 없음 (확인 절차)

- [ ] **Step 1: backend와 웹 실행**

1. `C:/Users/SSAFY/Desktop/wt-lecture-api`에서 `docker compose up -d --build backend`
2. `C:/Users/SSAFY/Desktop/wt-lecture-web/front`에서 `pnpm dev:web` → `http://localhost:5173` (마이크 권한은 localhost에서 열린다)

- [ ] **Step 2: 시나리오**

1. 학습자 계정으로 로그인 → "강사 계정으로만 로그인할 수 있어요." 가 뜬다.
2. 강사 계정(`lecturetester1`)으로 로그인 → 수업 목록.
3. 새 수업 개설(이름만) → 목록에 `예정`과 참여 코드가 보인다. 상태 칩(진행 중·예정·종료)을 누르면 해당 행만 남는다.
4. `수업 시작 →` → 03 화면(흰 상단 바 없음, 어두운 수업 바) → "수업 시작" → 마이크 권한 허용 → 내 마이크 "송출 중", 경과 시간이 1초씩 오른다.
5. 학습자 토큰(meet.livekit.io) 또는 학습자 앱으로 입장 → **강사 음성이 들린다.**
6. 마이크 끄기 → "마이크 꺼짐"으로 바뀌고 학습자 쪽 소리가 멈춘다. 다시 켜기.
7. 새로고침 → OPEN이므로 자동으로 음성 재접속, 경과 시간은 실제 시작 시각 기준으로 이어진다.
8. "수업 종료" → 확인 → 목록으로, 상태 `종료`. 학습자 쪽 연결이 끊긴다.
9. sessionStorage의 `ieum.web.accessToken`을 지우거나 15분 뒤 API 호출 → 로그인 화면(`로그인이 만료됐어요`).

Expected: 1~8 통과. 결과는 MR 본문에 기록한다.

- [ ] **Step 3: 와이어프레임과 시각 비교**

브라우저 창을 1920×1080으로 두고 `C:/Users/SSAFY/Desktop/이음 강사웹.html`을 옆 탭에 연다. 01a ↔ `/lectures`, 02 ↔ `/lectures/new`, 03 ↔ `/lectures/{id}/live`(OPEN 상태)를 나란히 비교한다.
확인 항목: 상단 바 높이·로고·활성 탭 밑줄, 사이드바 폭과 활성 항목, h1 크기, 상태 칩·알약·행 카드 색(진행 중 민트 강조 행, 종료 점선 행), 02 폼 카드 폭(720)과 칩·세그먼트·토글 모양, 03 어두운 바·학생 카드 네 가지 색·범례·오른쪽 패널(420) 구성, Noto Sans KR·IBM Plex Mono 적용(개발자 도구 Computed → Rendered Fonts).
Expected: 아래 "의도적으로 뺀 요소" 외에는 배치·색·글자 크기가 와이어프레임과 같다. 다른 곳이 있으면 스크린샷을 MR 본문에 남긴다.

의도적으로 뺀 요소(데이터·기능이 없어서): 01a 사이드바의 여러 강의(코스 계층 없음 → "전체 수업" 하나), 예정 행의 "세트 수정"·"세트 편성"(주황 "세트 미편성" 행 포함), 목록 하단 페이지 이동 `◀ 1 / 3 ▶`, 02 사이드바 "임시 저장됨" 상자(자동 저장 없음). 추가한 요소: 상단 바 "로그아웃", 03 "← 목록" 링크, 02 "설명 (선택)" 입력.

---

## 완료 확인 (MR 전)

- [ ] `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
- [ ] `docker build --target test front` 성공
- [ ] Task 7 E2E 1~8 확인
- [ ] Task 7 Step 3 와이어프레임 시각 비교 확인 (01a·02·03)

## 알려진 한계

- WEB 로그아웃은 서버 세션 폐기를 하지 않는다(refresh 쿠키 미사용) — access token 만료(15분)까지 유효하다.
- 기존 `SignupPage`는 옛 임시 클라이언트(`shared/api/client.ts`)로 존재하지 않는 `/auth/signup`을 부른다. 이 계획 범위 밖이며 후속 이슈로 남긴다(강사 계정은 앱 가입 + DB 역할 변경).
- 운영에서 웹과 `/api`가 같은 도메인이어야 한다(현재 nginx 구성). 다른 도메인이면 CORS 허용 목록을 갱신해야 한다.
