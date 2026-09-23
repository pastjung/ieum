# 인증·계정 API 로컬 연동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `front/apps/mobile`의 인증·계정 목 서비스를 실제 백엔드 호출로 교체하고, 이후 모든 도메인이 재사용할 HTTP 경계(`@ieum/api-client`)를 만든다.

**Architecture:** `@ieum/api-client`가 transport만 담당한다 — base URL, `X-Client-Platform` 헤더, `Authorization` 주입, 401 single-flight 재발급, `{code, message}` → `ApiError` 변환. 저장소는 `TokenStore` 인터페이스로 주입받아 패키지가 React Native에 묶이지 않는다. 도메인 호출은 기존 `services/authService.ts`가 시그니처를 유지한 채 내부만 교체하므로 화면 수정이 최소화된다.

**Tech Stack:** TypeScript 5.9, pnpm workspace, Expo SDK 57 / React Native 0.86, zod 3.25, jest-expo 57, expo-secure-store

**Spec:** [docs/superpowers/specs/2026-09-23-auth-integration-design.md](../specs/2026-09-23-auth-integration-design.md)

## Global Constraints

- 모든 경로는 `/api/v1` 아래다 (`server.servlet.context-path`). `baseUrl`에 포함시키고 각 호출은 `/users` 같은 상대 경로만 쓴다.
- `X-Client-Platform` 값은 `MOBILE` 또는 `WEB`과 **정확히** 일치해야 한다. trim·대소문자 보정이 없다.
- 오류 응답 body는 `{code, message}`다. `message`는 한국어이며 사용자에게 그대로 보여도 된다.
- 성공 상태 코드: 가입·로그인·갱신 `201`, 메일 요청 `202`, 로그아웃·탈퇴 `204`, 내 정보 `200`. `res.ok` 로만 판정하고 특정 숫자를 기대하지 않는다.
- 모든 BIGINT ID는 10진수 **문자열**이다. `number`로 파싱하지 않는다.
- 접근성은 기능 요구사항이다. 사용자에게 보이는 모든 오류·상태 변화는 `announce()`를 거치고, 실패는 `hapticError()`를 함께 호출한다 (`docs/conventions/code/mobile.md`).
- `any` 금지. 새 파일은 `strict` 아래에서 타입이 통과해야 한다.
- 검증 명령: `cd front && pnpm --filter @ieum/mobile test`, `cd front && pnpm -r typecheck`
- 커밋 메시지는 `type(scope): 한글 요약` 형식이다. scope는 `user`를 쓴다. `--no-verify` 금지.

---

## File Structure

| 파일 | 책임 |
| --- | --- |
| `front/packages/api-client/package.json` | 워크스페이스 패키지 선언 (신규) |
| `front/packages/api-client/tsconfig.json` | `@ieum/domain`과 동일한 설정 (신규) |
| `front/packages/api-client/src/errors.ts` | `ApiError` 하나 (신규) |
| `front/packages/api-client/src/types.ts` | `TokenStore`, `ApiClient`, 설정 타입 (신규) |
| `front/packages/api-client/src/client.ts` | `createClient` — 요청 조립, 응답 해석, 401 갱신 (신규) |
| `front/packages/api-client/src/index.ts` | 공개 export (신규) |
| `front/packages/contracts/src/index.ts` | 응답 스키마 추가 (수정) |
| `front/apps/mobile/src/services/tokenStore.ts` | SecureStore 어댑터 (신규) |
| `front/apps/mobile/src/services/apiClient.ts` | 앱 전역 클라이언트 인스턴스 (신규) |
| `front/apps/mobile/src/services/authService.ts` | 목 → 실제 호출 (수정) |
| `front/apps/mobile/__tests__/apiClient.test.ts` | 클라이언트 단위 테스트 (신규) |

**테스트 위치**: 기존 `__tests__/domain.test.ts`가 `@ieum/domain`을 앱에서 import해 검증하는 패턴을 그대로 따른다. `api-client`에 별도 jest 설정을 만들지 않는다 — 도구를 늘리지 않고 이미 도는 jest-expo를 쓴다.

**스펙과의 차이**: 스펙 2절 파일 목록에 `apiClient.ts`가 빠져 있었다. 클라이언트 인스턴스를 만드는 자리가 필요해 추가한다.

---

### Task 1: `@ieum/api-client` 패키지와 요청·오류 변환

**Files:**
- Create: `front/packages/api-client/package.json`
- Create: `front/packages/api-client/tsconfig.json`
- Create: `front/packages/api-client/src/errors.ts`
- Create: `front/packages/api-client/src/types.ts`
- Create: `front/packages/api-client/src/client.ts`
- Create: `front/packages/api-client/src/index.ts`
- Test: `front/apps/mobile/__tests__/apiClient.test.ts`
- Modify: `front/apps/mobile/package.json` (의존성 한 줄 추가)

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `class ApiError extends Error { readonly status: number; readonly code: string }`
  - `interface TokenStore { getAccess(): string | null; getRefresh(): Promise<string | null>; save(tokens: AuthSessionTokens): Promise<void>; clear(): Promise<void> }`
  - `interface AuthSessionTokens { accessToken: string; refreshToken: string }`
  - `interface RequestOptions { body?: unknown; auth?: boolean }`
  - `interface ApiClient { get<T>(path: string, options?: RequestOptions): Promise<T>; post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> }`
  - `function createClient(config: ApiClientConfig): ApiClient`

- [ ] **Step 1: 패키지 선언 파일 두 개를 만든다**

`front/packages/api-client/package.json` — `@ieum/domain`과 같은 모양이다.

```json
{
  "name": "@ieum/api-client",
  "version": "0.0.0",
  "private": true,
  "exports": { ".": "./src/index.ts" },
  "scripts": { "typecheck": "tsc --noEmit" },
  "devDependencies": { "typescript": "~5.9.2" }
}
```

`front/packages/api-client/tsconfig.json`

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "lib": ["ES2022", "DOM"]
  },
  "include": ["src"]
}
```

`lib`에 `DOM`을 넣는 이유: `fetch`·`Response`·`Headers` 타입이 필요하다. `@ieum/domain`은 순수 계산이라 필요 없었다.

- [ ] **Step 2: `@ieum/mobile`이 이 패키지를 쓰도록 선언하고 링크한다**

`front/apps/mobile/package.json`의 `dependencies`에 한 줄을 추가한다. `@ieum/contracts` 바로 아래, 알파벳 순서 자리다.

```json
    "@ieum/api-client": "workspace:*",
```

그다음 워크스페이스를 다시 링크한다.

```bash
cd front && pnpm install
```

- [ ] **Step 3: 실패하는 테스트를 작성한다**

`front/apps/mobile/__tests__/apiClient.test.ts` 를 새로 만든다.

```ts
import { ApiError, createClient, type TokenStore } from "@ieum/api-client";

/** 클라이언트가 실제로 읽는 필드만 가진 가짜 응답. 런타임 fetch 구현에 의존하지 않는다 */
function fakeResponse(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body === undefined ? "" : JSON.stringify(body)),
  } as unknown as Response;
}

function memoryTokenStore(access: string | null, refresh: string | null) {
  const state = { access, refresh };
  const store: TokenStore = {
    getAccess: () => state.access,
    getRefresh: async () => state.refresh,
    save: async (tokens) => {
      state.access = tokens.accessToken;
      state.refresh = tokens.refreshToken;
    },
    clear: async () => {
      state.access = null;
      state.refresh = null;
    },
  };
  return { store, state };
}

describe("api-client 요청 조립", () => {
  it("base URL을 붙이고 플랫폼·인증 헤더를 보낸다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(200, { username: "hana" }));
    const { store } = memoryTokenStore("access-1", "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await api.get("/users/me");

    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/v1/users/me");
    const headers = init.headers as Record<string, string>;
    expect(headers["X-Client-Platform"]).toBe("MOBILE");
    expect(headers.Authorization).toBe("Bearer access-1");
  });

  it("auth:false면 Authorization을 붙이지 않는다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(201, { email: "a@b.c" }));
    const { store } = memoryTokenStore("access-1", "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await api.post("/users", { username: "hana" }, { auth: false });

    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
    expect(init.body).toBe(JSON.stringify({ username: "hana" }));
  });

  it("오류 body의 code를 ApiError로 옮긴다", async () => {
    const fetchFn = jest.fn(async () =>
      fakeResponse(409, { code: "ACCOUNT_IDENTIFIER_UNAVAILABLE", message: "사용할 수 없는 계정 정보입니다." }),
    );
    const { store } = memoryTokenStore(null, null);
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.post("/users", {}, { auth: false })).rejects.toThrow(ApiError);
    await expect(api.post("/users", {}, { auth: false })).rejects.toMatchObject({
      status: 409,
      code: "ACCOUNT_IDENTIFIER_UNAVAILABLE",
      message: "사용할 수 없는 계정 정보입니다.",
    });
  });

  it("204와 빈 body는 undefined를 돌려준다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(204));
    const { store } = memoryTokenStore("access-1", "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.post("/users/me/withdrawals", { currentPassword: "x" })).resolves.toBeUndefined();
  });

  it("네트워크 실패를 NETWORK_UNAVAILABLE로 바꾼다", async () => {
    const fetchFn = jest.fn(async () => {
      throw new TypeError("Network request failed");
    });
    const { store } = memoryTokenStore(null, null);
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.get("/users/me", { auth: false })).rejects.toMatchObject({
      code: "NETWORK_UNAVAILABLE",
      status: 0,
    });
  });
});
```

- [ ] **Step 4: 테스트가 실패하는지 확인한다**

```bash
cd front && pnpm --filter @ieum/mobile test
```

Expected: FAIL — `Cannot find module '@ieum/api-client'`

- [ ] **Step 5: `errors.ts`를 작성한다**

```ts
/** 네트워크 자체가 실패해 HTTP 상태가 없을 때 쓰는 code. 백엔드 code와 겹치지 않는다 */
export const NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE";

/**
 * 백엔드 공통 오류 계약 `{code, message}`를 옮긴 예외.
 *
 * 화면은 `code`로 분기하고 `message`는 그대로 보여 줄 수 있다 — 백엔드가 한국어 사용자 문구로 정의한다.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}
```

- [ ] **Step 6: `types.ts`를 작성한다**

```ts
export type ClientPlatform = "MOBILE" | "WEB";

/** 로그인·갱신 응답에서 클라이언트가 보관하는 두 값 */
export interface AuthSessionTokens {
  accessToken: string;
  refreshToken: string;
}

/**
 * 토큰 저장소. 패키지가 expo-secure-store를 직접 알지 않도록 주입받는다.
 *
 * `getAccess`만 동기다 — 매 요청 헤더에 쓰이므로 메모리에 있다고 전제한다.
 */
export interface TokenStore {
  getAccess(): string | null;
  getRefresh(): Promise<string | null>;
  save(tokens: AuthSessionTokens): Promise<void>;
  clear(): Promise<void>;
}

export interface RequestOptions {
  body?: unknown;
  /** 기본 true. false면 Authorization을 붙이지 않고 401 자동 갱신도 하지 않는다 */
  auth?: boolean;
}

export interface ApiClientConfig {
  /** `/api/v1`까지 포함한다. 끝에 슬래시를 두지 않는다 */
  baseUrl: string;
  platform: ClientPlatform;
  tokens: TokenStore;
  /** Refresh Token으로도 복구할 수 없을 때 한 번 호출된다 */
  onSessionExpired: () => void;
  /** 테스트 주입용. 생략하면 전역 fetch */
  fetchFn?: typeof fetch;
}

export interface ApiClient {
  get<T>(path: string, options?: RequestOptions): Promise<T>;
  post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
}
```

- [ ] **Step 7: `client.ts`를 작성한다 (이번 태스크는 갱신 없이)**

```ts
import { ApiError, NETWORK_UNAVAILABLE } from "./errors";
import type { ApiClient, ApiClientConfig, RequestOptions } from "./types";

export function createClient(config: ApiClientConfig): ApiClient {
  const fetchFn = config.fetchFn ?? fetch;

  function buildHeaders(options: RequestOptions): Record<string, string> {
    const headers: Record<string, string> = {
      "X-Client-Platform": config.platform,
    };
    // body가 없는 요청에 Content-Type을 붙이지 않는다 — 보낼 표현이 없다
    if (options.body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (options.auth !== false) {
      const accessToken = config.tokens.getAccess();
      if (accessToken) {
        headers.Authorization = `Bearer ${accessToken}`;
      }
    }
    return headers;
  }

  async function send<T>(method: string, path: string, options: RequestOptions): Promise<T> {
    let response: Response;
    try {
      response = await fetchFn(`${config.baseUrl}${path}`, {
        method,
        headers: buildHeaders(options),
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      });
    } catch {
      // 원인 메시지를 그대로 노출하지 않는다 — 사용자에게 의미가 없다
      throw new ApiError(0, NETWORK_UNAVAILABLE, "네트워크에 연결할 수 없어요.");
    }

    // 204와, 오류 상태에서 body를 생략한 응답을 같은 경로로 처리한다
    const text = await response.text();
    const payload: unknown = text === "" ? undefined : JSON.parse(text);

    if (!response.ok) {
      const body = (payload ?? {}) as { code?: string; message?: string };
      throw new ApiError(
        response.status,
        body.code ?? "UNKNOWN_ERROR",
        body.message ?? "요청을 처리하지 못했어요.",
      );
    }
    return payload as T;
  }

  return {
    get: <T,>(path: string, options: RequestOptions = {}) => send<T>("GET", path, options),
    post: <T,>(path: string, body?: unknown, options: RequestOptions = {}) =>
      send<T>("POST", path, { ...options, body }),
  };
}
```

- [ ] **Step 8: `index.ts`를 작성한다**

```ts
export { ApiError, NETWORK_UNAVAILABLE } from "./errors";
export { createClient } from "./client";
export type {
  ApiClient,
  ApiClientConfig,
  AuthSessionTokens,
  ClientPlatform,
  RequestOptions,
  TokenStore,
} from "./types";
```

- [ ] **Step 9: 테스트와 타입체크를 통과시킨다**

```bash
cd front && pnpm --filter @ieum/mobile test && pnpm -r typecheck
```

Expected: PASS (5개 테스트), typecheck 오류 없음

- [ ] **Step 10: 커밋한다**

```bash
git add front/packages/api-client front/apps/mobile/package.json front/apps/mobile/__tests__/apiClient.test.ts front/pnpm-lock.yaml
git commit -m "feat(user): API 클라이언트 패키지와 오류 변환 추가"
```

---

### Task 2: 401 자동 갱신 (single-flight, 1회 재시도)

**Files:**
- Modify: `front/packages/api-client/src/client.ts`
- Test: `front/apps/mobile/__tests__/apiClient.test.ts` (describe 블록 추가)

**Interfaces:**
- Consumes: Task 1의 `createClient`, `ApiError`, `TokenStore`, `RequestOptions`
- Produces: 동작 변경만. 공개 시그니처는 그대로다

- [ ] **Step 1: 실패하는 테스트를 추가한다**

`apiClient.test.ts` 파일 끝에 다음 describe를 덧붙인다. 상단의 `fakeResponse`·`memoryTokenStore`를 그대로 재사용한다.

```ts
const SESSION = {
  accessToken: "access-2",
  tokenType: "Bearer",
  accessTokenExpiresAt: "2026-09-23T00:10:00.000Z",
  refreshToken: "refresh-2",
  refreshTokenExpiresAt: "2026-10-23T00:00:00.000Z",
  user: { userId: "1", username: "hana" },
};

describe("api-client 401 자동 갱신", () => {
  it("401을 받으면 갱신하고 원요청을 한 번 재시도한다", async () => {
    const fetchFn = jest
      .fn()
      .mockResolvedValueOnce(fakeResponse(401, { code: "UNAUTHORIZED", message: "인증이 필요합니다." }))
      .mockResolvedValueOnce(fakeResponse(201, SESSION))
      .mockResolvedValueOnce(fakeResponse(200, { username: "hana" }));
    const { store, state } = memoryTokenStore("stale", "refresh-1");
    const onSessionExpired = jest.fn();
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired,
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.get("/users/me")).resolves.toEqual({ username: "hana" });

    expect(fetchFn).toHaveBeenCalledTimes(3);
    expect(fetchFn.mock.calls[1][0]).toBe("http://localhost:8080/api/v1/access-tokens");
    expect(state.access).toBe("access-2");
    expect(state.refresh).toBe("refresh-2");
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it("갱신이 실패하면 토큰을 지우고 만료를 알린 뒤 원래 401을 던진다", async () => {
    const fetchFn = jest
      .fn()
      .mockResolvedValueOnce(fakeResponse(401, { code: "UNAUTHORIZED", message: "인증이 필요합니다." }))
      .mockResolvedValueOnce(
        fakeResponse(401, { code: "INVALID_REFRESH_TOKEN", message: "다시 로그인해 주세요." }),
      );
    const { store, state } = memoryTokenStore("stale", "refresh-1");
    const onSessionExpired = jest.fn();
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired,
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.get("/users/me")).rejects.toMatchObject({ status: 401, code: "UNAUTHORIZED" });

    expect(fetchFn).toHaveBeenCalledTimes(2);
    expect(state.access).toBeNull();
    expect(state.refresh).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("Refresh Token이 없으면 갱신을 시도하지 않는다", async () => {
    const fetchFn = jest
      .fn()
      .mockResolvedValueOnce(fakeResponse(401, { code: "UNAUTHORIZED", message: "인증이 필요합니다." }));
    const { store } = memoryTokenStore("stale", null);
    const onSessionExpired = jest.fn();
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired,
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.get("/users/me")).rejects.toMatchObject({ status: 401 });

    expect(fetchFn).toHaveBeenCalledTimes(1);
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("동시에 401을 받은 요청 셋이 갱신을 한 번만 호출한다", async () => {
    // 갱신 전에는 401, 갱신 뒤에는 200을 주는 fetch. 재시도가 성공해야 단일 비행을 셀 수 있다
    let refreshed = false;
    const smartFetch = jest.fn(async (url: string) => {
      if (url.endsWith("/access-tokens")) {
        refreshed = true;
        return fakeResponse(201, SESSION);
      }
      return refreshed
        ? fakeResponse(200, { username: "hana" })
        : fakeResponse(401, { code: "UNAUTHORIZED", message: "인증이 필요합니다." });
    });
    const { store } = memoryTokenStore("stale", "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: smartFetch as unknown as typeof fetch,
    });

    const results = await Promise.all([
      api.get("/users/me"),
      api.get("/users/me"),
      api.get("/users/me"),
    ]);

    expect(results).toEqual([{ username: "hana" }, { username: "hana" }, { username: "hana" }]);
    const refreshCalls = smartFetch.mock.calls.filter(([url]) => String(url).endsWith("/access-tokens"));
    expect(refreshCalls).toHaveLength(1);
  });

  it("갱신 요청 자신은 401이어도 다시 갱신하지 않는다", async () => {
    const fetchFn = jest
      .fn()
      .mockResolvedValueOnce(
        fakeResponse(401, { code: "INVALID_REFRESH_TOKEN", message: "다시 로그인해 주세요." }),
      );
    const { store } = memoryTokenStore(null, "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(
      api.post("/access-tokens", { refreshToken: "refresh-1" }, { auth: false }),
    ).rejects.toMatchObject({ code: "INVALID_REFRESH_TOKEN" });

    expect(fetchFn).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
cd front && pnpm --filter @ieum/mobile test
```

Expected: FAIL — 401이 그대로 던져지고 `access-tokens` 호출이 없다

- [ ] **Step 3: `client.ts`에 갱신 로직을 넣는다**

`send` 함수를 `sendOnce`로 이름만 바꾸고(본문은 그대로), 그 아래에 다음을 추가한다. `return { get, post }` 부분은 `send`를 부르도록 유지한다.

```ts
  /**
   * 진행 중인 갱신. 동시에 401을 받은 요청들이 같은 Promise를 기다린다.
   * 요청 수만큼 갱신하면 Refresh Token 회전이 서로를 무효화한다.
   */
  let refreshInFlight: Promise<boolean> | null = null;

  async function doRefresh(): Promise<boolean> {
    const refreshToken = await config.tokens.getRefresh();
    if (refreshToken) {
      try {
        const session = await sendOnce<{ accessToken?: unknown; refreshToken?: unknown }>(
          "POST",
          "/access-tokens",
          { body: { refreshToken }, auth: false },
        );
        if (typeof session.accessToken === "string" && typeof session.refreshToken === "string") {
          await config.tokens.save({
            accessToken: session.accessToken,
            refreshToken: session.refreshToken,
          });
          return true;
        }
      } catch {
        // 아래 공통 실패 경로로 내려간다
      }
    }
    // single-flight라 이 정리는 갱신 1회당 한 번만 일어난다
    await config.tokens.clear();
    config.onSessionExpired();
    return false;
  }

  function refreshOnce(): Promise<boolean> {
    if (!refreshInFlight) {
      refreshInFlight = doRefresh().finally(() => {
        refreshInFlight = null;
      });
    }
    return refreshInFlight;
  }

  async function send<T>(method: string, path: string, options: RequestOptions): Promise<T> {
    try {
      return await sendOnce<T>(method, path, options);
    } catch (error) {
      const retriable =
        error instanceof ApiError && error.status === 401 && options.auth !== false;
      if (!retriable) throw error;

      const refreshed = await refreshOnce();
      if (!refreshed) throw error;

      // 재시도는 한 번뿐이다. 또 401이면 갱신으로 풀 수 있는 상황이 아니다
      return await sendOnce<T>(method, path, options);
    }
  }
```

- [ ] **Step 4: 테스트와 타입체크를 통과시킨다**

```bash
cd front && pnpm --filter @ieum/mobile test && pnpm -r typecheck
```

Expected: PASS (10개 테스트)

- [ ] **Step 5: 커밋한다**

```bash
git add front/packages/api-client/src/client.ts front/apps/mobile/__tests__/apiClient.test.ts
git commit -m "feat(user): 401 응답의 토큰 재발급과 단일 비행 처리 추가"
```

---

### Task 3: 응답 스키마와 앱 배선

**Files:**
- Modify: `front/packages/contracts/src/index.ts`
- Create: `front/apps/mobile/src/services/tokenStore.ts`
- Create: `front/apps/mobile/src/services/apiClient.ts`

**Interfaces:**
- Consumes: Task 1의 `createClient`, `TokenStore`, `AuthSessionTokens`
- Produces:
  - `userSummarySchema`, `authSessionSchema` / 타입 `UserSummary`, `AuthSessionResponse` (`@ieum/contracts`)
  - `tokenStore: TokenStore` (`@/services/tokenStore`)
  - `api: ApiClient`, `setSessionExpiredHandler(fn: () => void): void` (`@/services/apiClient`)

- [ ] **Step 1: contracts에 응답 스키마를 추가한다**

`front/packages/contracts/src/index.ts` 끝에 덧붙인다. 기존 요청 스키마는 건드리지 않는다.

```ts
/** users.level — 단계 번호가 아니라 학습 범주다. NULL이면 자가 진단 전 */
export const levelSchema = z.enum([
  "CONSONANT",
  "VOWEL",
  "WORD",
  "SENTENCE",
  "CONTRACTION",
  "CONTRACTION_WORD",
]);
export type Level = z.infer<typeof levelSchema>;

/** 가입·내 정보·로그인 응답이 공유하는 사용자 요약 (API 명세 3.2.1) */
export const userSummarySchema = z.object({
  /** BIGINT의 10진수 문자열. number로 바꾸지 않는다 */
  userId: z.string(),
  username: z.string(),
  email: z.string(),
  role: z.string(),
  emailVerified: z.boolean(),
  level: levelSchema.nullable(),
  createdAt: z.string(),
});
export type UserSummary = z.infer<typeof userSummarySchema>;

/** MOBILE 인증 응답 (API 명세 3.3.1). WEB은 refresh 필드가 없다 */
export const authSessionSchema = z.object({
  accessToken: z.string(),
  tokenType: z.string(),
  accessTokenExpiresAt: z.string(),
  refreshToken: z.string(),
  refreshTokenExpiresAt: z.string(),
  user: userSummarySchema,
});
export type AuthSessionResponse = z.infer<typeof authSessionSchema>;
```

- [ ] **Step 2: `tokenStore.ts`를 작성한다**

```ts
import * as SecureStore from "expo-secure-store";
import type { AuthSessionTokens, TokenStore } from "@ieum/api-client";

const REFRESH_KEY = "ieum.refreshToken";
/** 목 시절에 Access Token을 디스크에 두던 키. 남아 있으면 지운다 */
const LEGACY_ACCESS_KEY = "ieum.accessToken";

/**
 * Access Token은 메모리에만 둔다 — API 명세 3.3.1이 OS 보안 저장소 보관을
 * Refresh Token에만 요구하고, Access는 수명이 짧아 디스크에 남길 이유가 없다.
 */
let accessToken: string | null = null;

export const tokenStore: TokenStore = {
  getAccess: () => accessToken,

  getRefresh: () => SecureStore.getItemAsync(REFRESH_KEY),

  async save(tokens: AuthSessionTokens) {
    accessToken = tokens.accessToken;
    await SecureStore.setItemAsync(REFRESH_KEY, tokens.refreshToken);
  },

  async clear() {
    accessToken = null;
    await SecureStore.deleteItemAsync(REFRESH_KEY);
    await SecureStore.deleteItemAsync(LEGACY_ACCESS_KEY);
  },
};
```

- [ ] **Step 3: `apiClient.ts`를 작성한다**

```ts
import { createClient } from "@ieum/api-client";
import { tokenStore } from "./tokenStore";

/**
 * Android 에뮬레이터에서 host PC의 localhost는 10.0.2.2다.
 * iOS 시뮬레이터나 실기기는 .env의 EXPO_PUBLIC_API_URL로 덮는다.
 */
const DEFAULT_BASE_URL = "http://10.0.2.2:8080/api/v1";

/**
 * 만료 처리는 라우터를 아는 곳에서만 할 수 있다. 앱 루트가 여기에 핸들러를 꽂는다.
 * 꽂히기 전에 만료가 나도 조용히 무시된다 — 그 시점엔 보여 줄 화면이 없다.
 */
let sessionExpiredHandler: () => void = () => {};

export function setSessionExpiredHandler(handler: () => void): void {
  sessionExpiredHandler = handler;
}

export const api = createClient({
  baseUrl: process.env.EXPO_PUBLIC_API_URL ?? DEFAULT_BASE_URL,
  platform: "MOBILE",
  tokens: tokenStore,
  onSessionExpired: () => sessionExpiredHandler(),
});
```

- [ ] **Step 4: 타입체크를 통과시킨다**

```bash
cd front && pnpm -r typecheck && pnpm --filter @ieum/mobile test
```

Expected: PASS — 기존 10개 테스트 유지, 타입 오류 없음

- [ ] **Step 5: 커밋한다**

```bash
git add front/packages/contracts/src/index.ts front/apps/mobile/src/services/tokenStore.ts front/apps/mobile/src/services/apiClient.ts
git commit -m "feat(user): 인증 응답 스키마와 토큰 저장소 배선 추가"
```

---

### Task 4: `authService` 실제 호출 교체와 세션 복구

**Files:**
- Modify: `front/apps/mobile/src/services/authService.ts` (전체 교체)
- Modify: `front/apps/mobile/src/app/index.tsx:30-32` (스플래시 분기)
- Modify: `front/apps/mobile/src/app/_layout.tsx` (만료 핸들러 등록)

**Interfaces:**
- Consumes: Task 3의 `api`, `tokenStore`, `setSessionExpiredHandler`, `authSessionSchema`, `userSummarySchema`
- Produces:
  - `authService.signUp(input: SignUpInput): Promise<{ email: string }>` — 시그니처 유지
  - `authService.signIn(input: SignInInput): Promise<{ userName: string }>` — 시그니처 유지
  - `authService.signOut(): Promise<void>` — 시그니처 유지
  - `authService.restore(): Promise<boolean>` — **신규**, 기존 `getToken()`을 대체한다
  - `authService.getMe(): Promise<UserSummary>` — **신규**, Task 5의 홈 화면이 쓴다

- [ ] **Step 1: `authService.ts`를 통째로 교체한다**

`getToken()`은 삭제한다. 호출처는 스플래시 한 곳뿐이고 Step 3에서 `restore()`로 바꾼다.

```ts
import {
  authSessionSchema,
  userSummarySchema,
  type SignInInput,
  type SignUpInput,
  type UserSummary,
} from "@ieum/contracts";
import { api } from "./apiClient";
import { tokenStore } from "./tokenStore";

/** 로그인·갱신 성공 응답을 검증하고 토큰을 보관한다 */
async function persistSession(payload: unknown) {
  const session = authSessionSchema.parse(payload);
  await tokenStore.save({
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
  });
  return session;
}

export const authService = {
  /** 가입은 토큰을 발급하지 않는다 — 이메일 인증 전에는 로그인할 수 없다 */
  async signUp(input: SignUpInput): Promise<{ email: string }> {
    const user = userSummarySchema.parse(await api.post("/users", input, { auth: false }));
    return { email: user.email };
  },

  async signIn(input: SignInInput): Promise<{ userName: string }> {
    const session = await persistSession(await api.post("/auth-sessions", input, { auth: false }));
    return { userName: session.user.username };
  },

  /**
   * 앱 시작 시 Refresh Token으로 세션을 되살린다.
   *
   * @returns 복구에 성공해 탭 화면으로 보내도 되는지
   */
  async restore(): Promise<boolean> {
    const refreshToken = await tokenStore.getRefresh();
    if (!refreshToken) return false;
    try {
      await persistSession(await api.post("/access-tokens", { refreshToken }, { auth: false }));
      return true;
    } catch {
      // 만료·폐기·형식 위반을 구분하지 않는다. 어느 쪽이든 다시 로그인해야 한다
      await tokenStore.clear();
      return false;
    }
  },

  async signOut(): Promise<void> {
    const refreshToken = await tokenStore.getRefresh();
    if (refreshToken) {
      try {
        await api.post("/auth-session-revocations", { refreshToken }, { auth: false });
      } catch {
        // 서버 폐기에 실패해도 로컬 정리는 진행한다 — 이 기기에서는 로그아웃돼야 한다
      }
    }
    await tokenStore.clear();
  },

  getMe(): Promise<UserSummary> {
    return api.get<unknown>("/users/me").then((payload) => userSummarySchema.parse(payload));
  },
};
```

- [ ] **Step 2: 앱 루트에 만료 핸들러를 등록한다**

`front/apps/mobile/src/app/_layout.tsx`를 아래로 교체한다. 기존 파일은 `useState`·`Stack`·`QueryClientProvider`만 있는 20줄짜리다.

```tsx
import { useEffect, useState } from "react";
import { Stack, useRouter } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setSessionExpiredHandler } from "@/services/apiClient";
import { useTheme } from "@/theme";

export default function RootLayout() {
  const [queryClient] = useState(() => new QueryClient());
  const theme = useTheme();
  const router = useRouter();

  // 만료 처리는 라우터를 아는 이곳에서만 할 수 있다. 클라이언트는 콜백만 부른다
  useEffect(() => {
    setSessionExpiredHandler(() => router.replace("/auth/expired"));
  }, [router]);

  return (
    <QueryClientProvider client={queryClient}>
      <StatusBar style={theme.name === "dark" ? "light" : "dark"} />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: theme.color.bg },
        }}
      />
    </QueryClientProvider>
  );
}
```

- [ ] **Step 3: 스플래시를 `restore()`로 바꾼다**

`front/apps/mobile/src/app/index.tsx`의 다음 세 줄을

```tsx
      const token = await authService.getToken();
      if (cancelled) return;
      router.replace(token ? "/(tabs)" : "/auth/sign-in");
```

이렇게 바꾼다.

```tsx
      const restored = await authService.restore();
      if (cancelled) return;
      router.replace(restored ? "/(tabs)" : "/auth/sign-in");
```

- [ ] **Step 4: 타입체크와 테스트를 통과시킨다**

```bash
cd front && pnpm -r typecheck && pnpm --filter @ieum/mobile test
```

Expected: PASS. `getToken` 참조가 남아 있으면 타입 오류로 잡힌다

- [ ] **Step 5: 커밋한다**

```bash
git add front/apps/mobile/src/services/authService.ts front/apps/mobile/src/app/index.tsx front/apps/mobile/src/app/_layout.tsx
git commit -m "feat(user): 가입·로그인·로그아웃을 실제 API 호출로 교체"
```

---

### Task 5: 화면별 오류 안내와 계정 API 연결

**Files:**
- Modify: `front/apps/mobile/src/app/auth/sign-in.tsx:29-31`
- Modify: `front/apps/mobile/src/app/auth/sign-up.tsx:53-55` 및 `:112-116`
- Modify: `front/apps/mobile/src/app/auth/verify-email.tsx` (재발송 버튼)
- Modify: `front/apps/mobile/src/app/auth/reset-password.tsx` (제출)
- Modify: `front/apps/mobile/src/app/account/withdraw.tsx`
- Modify: `front/apps/mobile/src/app/(tabs)/index.tsx:20` 및 `:107`
- Create: `front/apps/mobile/src/services/authErrors.ts`

**Interfaces:**
- Consumes: Task 4의 `authService`, Task 1의 `ApiError`
- Produces:
  - `describeAuthError(error: unknown): string` (`@/services/authErrors`)
  - `authService.withdraw(currentPassword: string): Promise<void>`
  - `authService.requestEmailVerification(email: string): Promise<void>`
  - `authService.requestPasswordReset(email: string): Promise<void>`

- [ ] **Step 1: `authErrors.ts`를 작성한다**

백엔드 `message`가 이미 한국어 사용자 문구라 대부분 그대로 쓴다. 화면 맥락이 필요한 code만 덮어쓴다.

```ts
import { ApiError, NETWORK_UNAVAILABLE } from "@ieum/api-client";

/** 백엔드 문구보다 화면 맥락이 더 친절한 경우만 덮어쓴다 */
const OVERRIDES: Record<string, string> = {
  ACCOUNT_IDENTIFIER_UNAVAILABLE: "이미 쓰고 있는 아이디나 이메일이에요. 다른 값으로 해 주세요.",
  EMAIL_VERIFICATION_REQUIRED: "이메일 인증이 아직이에요. 메일함을 확인해 주세요.",
  [NETWORK_UNAVAILABLE]: "네트워크에 연결할 수 없어요. 연결 상태를 확인해 주세요.",
};

/**
 * 사용자에게 읽어 줄 한 문장을 만든다.
 *
 * 알 수 없는 오류까지 포함해 항상 문장을 돌려준다 — 시각장애인 사용자에게
 * 침묵은 실패를 알리지 않은 것과 같다.
 */
export function describeAuthError(error: unknown): string {
  if (error instanceof ApiError) {
    return OVERRIDES[error.code] ?? error.message;
  }
  return "문제가 생겼어요. 잠시 후 다시 시도해 주세요.";
}
```

- [ ] **Step 2: `authService`에 계정 API 세 개를 추가한다**

`front/apps/mobile/src/services/authService.ts`의 `getMe` 아래에 덧붙인다.

```ts
  /** 성공은 204다. 로컬 토큰까지 지워야 탈퇴가 끝난다 */
  async withdraw(currentPassword: string): Promise<void> {
    await api.post("/users/me/withdrawals", { currentPassword });
    await tokenStore.clear();
  },

  /** 202는 메일 작업의 접수만 뜻한다. 계정 존재 여부를 알려 주지 않는다 */
  async requestEmailVerification(email: string): Promise<void> {
    await api.post("/email-verification-requests", { email }, { auth: false });
  },

  async requestPasswordReset(email: string): Promise<void> {
    await api.post("/password-reset-requests", { email }, { auth: false });
  },
```

- [ ] **Step 3: 로그인 화면에 오류 처리를 넣는다**

`sign-in.tsx`의 `onSubmit` 첫 번째 인자를 다음으로 교체한다. `EMAIL_VERIFICATION_REQUIRED`는 안내로 끝내지 않고 인증 화면으로 보낸다.

```tsx
    async (values) => {
      try {
        const { userName } = await authService.signIn(values);
        router.replace({ pathname: "/auth/welcome", params: { userName } });
      } catch (error) {
        void hapticError();
        announce(describeAuthError(error));
        if (error instanceof ApiError && error.code === "EMAIL_VERIFICATION_REQUIRED") {
          router.push("/auth/verify-email");
        }
      }
    },
```

import 두 줄을 추가한다.

```tsx
import { ApiError } from "@ieum/api-client";
import { describeAuthError } from "@/services/authErrors";
```

- [ ] **Step 4: 가입 화면 두 곳에 오류 처리를 넣는다**

`sign-up.tsx`에는 단계형(`nextStep`)과 화면형(`ScreenSignUp`) 두 경로가 있다. **둘 다** 고친다.

`nextStep`의 마지막 두 줄을 교체한다.

```tsx
    try {
      await authService.signUp(values);
      router.replace({ pathname: "/auth/verify-email", params: { email: values.email } });
    } catch (error) {
      void hapticError();
      announce(describeAuthError(error));
    }
```

`ScreenSignUp`의 `onSubmit` 첫 번째 인자를 교체한다.

```tsx
    async ({ username, email, password }) => {
      try {
        await authService.signUp({ username, email, password });
        router.replace({ pathname: "/auth/verify-email", params: { email } });
      } catch (error) {
        void hapticError();
        announce(describeAuthError(error));
      }
    },
```

`describeAuthError` import를 파일 상단에 추가한다.

```tsx
import { describeAuthError } from "@/services/authErrors";
```

- [ ] **Step 5: 메일 재발송과 재설정 요청을 연결한다**

`verify-email.tsx`의 "메일 다시 보내기" 버튼 `onPress`를 교체한다. `target`이 `"가입한 이메일"` 기본값일 때는 보낼 주소가 없으므로 버튼을 비활성화한다.

```tsx
        <AppButton
          label="메일 다시 보내기"
          variant="secondary"
          disabled={!email}
          onPress={() => {
            if (!email) return;
            void authService
              .requestEmailVerification(email)
              .then(() => announce("인증 메일을 다시 보냈어요. 메일함을 확인해 주세요."))
              .catch((error) => {
                void hapticError();
                announce(describeAuthError(error));
              });
          }}
        />
```

import 세 줄을 추가한다. 기존 파일은 `announce`만 import하고 있다.

```tsx
import { authService } from "@/services/authService";
import { describeAuthError } from "@/services/authErrors";
import { hapticError } from "@/a11y";
```

`AppButton`은 이미 `disabled` prop을 받아 `accessibilityState={{ disabled }}`까지 설정한다. 컴포넌트를 고칠 필요가 없다.

`reset-password.tsx`의 `onSubmit` 첫 번째 인자를 교체한다.

```tsx
    async ({ email }) => {
      try {
        await authService.requestPasswordReset(email);
        announce(`${email}로 재설정 링크를 보냈어요. 메일함을 확인해 주세요.`);
        router.back();
      } catch (error) {
        void hapticError();
        announce(describeAuthError(error));
      }
    },
```

- [ ] **Step 6: 탈퇴를 연결한다**

`withdraw.tsx`에는 이미 `password` 상태와 `BigInput`이 있다. 입력을 새로 만들 필요는 없고, 지금 `authService.signOut()`을 부르는 `withdraw` 함수만 교체한다.

```tsx
  const withdraw = async () => {
    if (password.length < 8) {
      void hapticError();
      announce("비밀번호를 확인해 주세요. 8자 이상이에요.");
      return;
    }
    try {
      await authService.withdraw(password);
      announce("탈퇴가 완료됐어요. 그동안 함께해서 고마웠어요.");
      router.replace("/auth/sign-in");
    } catch (error) {
      // 비밀번호 불일치는 403 CURRENT_PASSWORD_MISMATCH다. 화면에 머물러 다시 입력받는다
      void hapticError();
      announce(describeAuthError(error));
    }
  };
```

`describeAuthError` import를 추가한다.

```tsx
import { describeAuthError } from "@/services/authErrors";
```

- [ ] **Step 7: 홈에 실제 사용자 정보를 연결한다**

`(tabs)/index.tsx`의 `useQuery` 두 곳(20행, 107행)을 다음으로 바꾼다.

```tsx
  const { data } = useQuery({ queryKey: ["me"], queryFn: authService.getMe });
```

그리고 화면이 쓰던 `data.userName`·`data.stepLabel`을 아래 헬퍼로 바꾼다. `level`은 단계 번호가 아니라 학습 범주이므로 `"3단계"` 같은 문구를 만들 수 없다.

```tsx
const LEVEL_LABEL: Record<string, string> = {
  CONSONANT: "자음",
  VOWEL: "모음",
  WORD: "단어",
  SENTENCE: "문장",
  CONTRACTION: "약자",
  CONTRACTION_WORD: "약어",
};

const userName = data?.username ?? "";
const stepLabel = data?.level ? LEVEL_LABEL[data.level] : "자가 진단 전";
```

`inProgress`와 `deviceConnected`는 각각 학습 세션 API와 BLE가 필요하므로 `homeSummary` 목을 그대로 둔다.

- [ ] **Step 8: 타입체크와 테스트를 통과시킨다**

```bash
cd front && pnpm -r typecheck && pnpm --filter @ieum/mobile test
```

Expected: PASS

- [ ] **Step 9: 커밋한다**

```bash
git add front/apps/mobile/src
git commit -m "feat(user): 계정 API 연결과 오류 음성 안내 추가"
```

---

### Task 6: 로컬 실기동 검증

**Files:** 없음 (검증 전용)

**Interfaces:**
- Consumes: Task 1~5 전부
- Produces: 스펙 8절 완료 기준의 충족 증거

- [ ] **Step 1: 백엔드를 띄운다**

먼저 `.env`에 다음 값이 있어야 한다. `application.yml`이 기본값을 두지 않아 **없으면 기동이 실패한다**: `DB_PASSWORD`, `AUTH_JWT_ISSUER`, `AUTH_ACCESS_ACTIVE_KID`, `AUTH_ACCESS_ACTIVE_KEY`. 값은 `.env.example`을 복사해 채운다.

```bash
cd /c/Users/SSAFY/Desktop/S15P21B201 && docker compose up -d backend db redis
```

`MAIL_DISPATCH_ENABLED`는 기본값 `false`를 유지한다. 메일 bean이 `@ConditionalOnProperty`로 묶여 있어 SMTP·링크 환경변수 없이 기동된다.

- [ ] **Step 2: 백엔드가 살아 있는지 확인한다**

```bash
curl -i http://127.0.0.1:8080/api/v1/health
```

Expected: `200 OK`

- [ ] **Step 3: 앱을 띄우고 가입한다**

```bash
cd front && pnpm dev:mobile
```

앱에서 회원가입을 완료한다. Expected: 이메일 인증 안내 화면으로 이동

- [ ] **Step 4: 로컬에서 이메일 인증 상태를 만든다**

발송이 꺼져 있으면 action token이 생성되지 않는다 — 토큰은 접수 시점이 아니라 `MailDispatchWorker`가 발송 시점에 만들고 `mail_outbox`에는 토큰 컬럼이 없다.

root 비밀번호는 `MARIADB_RANDOM_ROOT_PASSWORD`로 매번 무작위 생성되므로 쓸 수 없다. 컨테이너가 이미 가진 앱 계정 환경변수를 그대로 쓴다 — 비밀번호를 직접 타이핑하지 않는다.

```bash
docker compose exec db sh -c 'mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE" -e "UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE username = '"'"'<가입한아이디>'"'"';"'
```

- [ ] **Step 5: 로그인부터 탈퇴까지 한 바퀴 돈다**

순서대로 확인한다.

1. 로그인 → 환영 화면으로 이동
2. 홈 → 가입한 아이디가 표시된다
3. 앱을 완전히 종료했다 재시작 → 스플래시가 탭 화면으로 보낸다 (세션 복구)
4. 로그아웃 → 로그인 화면으로 돌아간다
5. 다시 로그인 후 탈퇴 → 로그인 화면으로 돌아가고, 같은 아이디로 재로그인이 거부된다

- [ ] **Step 6: 갱신 경로가 실제로 도는지 확인한다**

Access Token 유효기간 15분은 계약 값이라 **코드 상수로 고정돼 있다** (`application.yml` 주석). 설정으로 줄일 수 없으므로 두 가지로 나눠 확인한다.

먼저 백엔드 로그를 켜 둔다.

```bash
docker compose logs -f backend
```

1. **복구 경로** — Step 5의 3번(앱 재시작)을 하면서 로그에 `POST /api/v1/access-tokens`가 찍히는지 본다. `restore()`가 실제로 갱신 API를 부르는 증거다.
2. **401 재시도 경로** — 로그인한 채로 15분을 넘긴 뒤 홈을 새로고침한다. 로그인 화면으로 튕기지 않고 데이터가 갱신되면 통과다.

401 트리거 갱신의 세부 동작(단일 비행, 1회 재시도, 실패 시 만료 처리)은 Task 2의 단위 테스트가 이미 다룬다. 여기서는 실제 서버를 상대로 경로가 이어지는지만 본다.

- [ ] **Step 7: TalkBack으로 오류 안내를 확인한다**

TalkBack을 켜고 다음 두 가지를 확인한다.

1. 이미 있는 아이디로 가입 시도 → "이미 쓰고 있는 아이디나 이메일이에요"가 낭독된다
2. 틀린 비밀번호로 로그인 시도 → 백엔드 문구가 낭독되고 진동이 온다

- [ ] **Step 8: 검증 결과를 스펙 문서에 기록한다**

`docs/superpowers/specs/2026-09-23-auth-integration-design.md` 끝에 `## 10. 구현 결과와 설계 시점과의 차이` 절을 추가한다. 기존 `2026-09-09-backend-ddd-entities-design.md`의 7절이 같은 형식이다. 설계와 달라진 것, 구현 중 발견한 결함, 검증 결과를 적는다.

- [ ] **Step 9: 커밋한다**

```bash
git add docs/superpowers/specs/2026-09-23-auth-integration-design.md
git commit -m "docs(user): 인증 연동 구현 결과와 검증 기록 추가"
```

---

## 완료 후

`mr-draft` 스킬로 MR 초안을 만들고 GitLab 웹에서 등록한다. 대상 브랜치는 `dev`, squash merge, `Delete source branch` 선택.
