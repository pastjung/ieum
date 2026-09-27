# 운영 배포 재구축 — 프론트(강사 웹·학습자 앱) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 강사 웹에 로그인 없이 여는 앱 다운로드 페이지(`/app`)를 만들고, 학습자 앱은 Cartesia를 백엔드 대리 호출로 바꿔 APK에서 키를 없애고, 켤 때 새 버전을 알리며, 릴리스 APK를 우리 keystore로만 서명하게 한다.

**Architecture:** 웹은 `domains/app-release`에 `latest.json` 해석(순수)과 같은 출처 조회(`/app/latest.json`)를 두고, 페이지 `pages/AppDownloadPage`는 조합만 한다. QR은 빌드와 무관한 정적 SVG(`public/app-qr.svg`)로 커밋하고 만든 스크립트를 함께 둔다. 앱은 공유 `@ieum/api-client`에 **추가형** 옵션(FormData 본문 그대로 보내기, `responseType: "arrayBuffer"`, `signal`)을 더해 `services/cartesia.ts`가 `POST /voice/clones`·`/voice/speech`를 부르게 하고, 오류는 순수 모듈 `voiceSynthesisErrors`가 한국어 문구로 바꾼다. 새 버전 알림은 순수 모듈 `appRelease`(해석·날짜·판정·조회) + 작은 persist 스토어 + 의존성 주입 가능한 `appUpdateCheck`로 나누고, 탭 화면이 처음 열릴 때 한 번 돌려 알림 화면 `app-update`로 보낸다. 서명은 config plugin이 `android/app/build.gradle` 문자열을 바꾸는 순수 함수로 구현해 jest로 검증한다.

**Tech Stack:** React 19 · Vite 6.4 · vitest 3 (웹, node 환경) / Expo 57 (CNG) · React Native 0.86.3 · expo-router · zustand 5 persist + AsyncStorage · zod 3 · jest-expo (앱) / pnpm 10 워크스페이스(`node-linker=hoisted`)

**Spec:** `docs/superpowers/specs/2026-09-27-deploy-rebuild-design.md` §5.1·§5.2·§5.3·§5.5·§5.6·§6(앱 쪽)·§8 / 교차 계약(원문 그대로 사용, git에 넣지 않음): `C:/Users/SSAFY/Desktop/S15P21B201/.superpowers/sdd/deploy-contract.md`

## Global Constraints

- 브랜치: `chore/deploy-rebuild-front` (`chore/deploy-rebuild`에서 분기), 작업 폴더 `C:/Users/SSAFY/Desktop/wt-deploy-front`. 끝나면 `chore/deploy-rebuild`에 **로컬로** 병합한다(MR은 `chore/deploy-rebuild` → dev 한 번, 사람이 올린다). **push하지 않는다**
- 작업 범위: `front/apps/web`, `front/apps/mobile`, `front/packages/api-client`(추가형 변경만 — 웹도 쓰므로 기존 호출의 동작·타입을 바꾸지 않는다). backend·deploy·scripts·docs/infra·`docs/product`는 건드리지 않는다(다른 계획 담당). 요청과 무관한 파일을 고치지 않는다
- **의존성 추가 없음.** `front/pnpm-lock.yaml`·각 `package.json`의 dependencies 불변. `expo-application`은 설치돼 있지 않다 → 설치된 빌드 번호는 `expo-constants`(이미 의존성)의 `Constants.expoConfig?.android?.versionCode`로 읽는다(설계서 §5.6의 `expo-application` 대신 — 빌드 때 `app.json`이 그대로 임베드되고, CNG prebuild가 같은 값으로 `versionCode`를 쓰므로 같은 수다). QR 인코더는 웹 의존성으로 넣지 않고 워크스페이스에 이미 있는 `toqr@0.1.1`(`@expo/cli`의 의존성, hoisted라 `front/node_modules/toqr`)을 **일회성 생성 스크립트**에서만 쓴다
- 커밋: `type(scope): 한글 제목` + 빈 줄 + 정확히 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. scope는 `voice`(음성 대리 호출), `release`(다운로드 페이지·새 버전 알림·서명). `git add`는 **경로를 하나하나 지정**한다(`git add .`/`-A` 금지) — 추적되지 않는 `.superpowers/`·`seed_remaining.sql`·`total_init.sql`을 넣지 않는다. `--no-verify`, `push --force` 금지
- `.env*`·`*.pem` 파일은 읽지도, 고치지도 않는다. `front/apps/mobile/.env.example`의 키 변경(아래)은 **사람이** 한다 — 완료 확인과 MR 본문에 적는다
  - 지울 줄: `EXPO_PUBLIC_CARTESIA_API_KEY=` (와 그 설명 주석)
  - 더할 줄: `# 새 버전 확인·설치 페이지 주소. 비우면 확인하지 않는다 (운영: https://i-eum.site/app)` / `EXPO_PUBLIC_APP_RELEASE_URL=`
- 서버(EC2)는 건드리지 않는다. 프로세스를 끌 때는 **PID로만** 끈다(`netstat -ano | findstr :5173` → `taskkill //PID <pid> //F`). 이름 단위 종료 금지
- 계약(deploy-contract.md 원문):
  - `POST /api/v1/voice/clones` — LEARNER only (403 `LEARNER_ACCESS_REQUIRED`, 401 미인증). 요청 `multipart/form-data`, part `clip` = WAV(≤ 10 MB), 선택 part `name`(≤ 50자, 기본 "내 목소리"). 응답 201 `{"voiceId": "<string>"}`
  - `POST /api/v1/voice/speech` — LEARNER only. 요청 JSON `{"voiceId": "<string, 1..100>", "text": "<string, 1..300 after trim>"}` → 위반 시 400 `INVALID_REQUEST_PARAMETER`. 응답 200 본문 = MP3 바이트, `Content-Type: audio/mpeg`
  - 오류 body `{code, message}`: 503 `VOICE_SYNTHESIS_UNAVAILABLE`(서버에 키 없음), 429 `VOICE_RATE_LIMITED`(학습자별 1분 30회 초과, 두 API 공유), 502 `VOICE_SYNTHESIS_FAILED`(Cartesia 오류·시간 초과, 원문 비노출). 서버 시간 제한: clone 60초, tts 20초
  - 앱: `EXPO_PUBLIC_CARTESIA_API_KEY` 제거. `isCartesiaConfigured()`는 "백엔드로 시도 가능" 의미로 항상 true, 503은 "쓸 수 없어요" 안내로
  - `latest.json` (UTF-8): `{ "version": "1.0.0", "versionCode": 1, "releasedAt": "2026-09-27T12:00:00Z", "sha256": "<hex>", "url": "https://i-eum.site/app/ieum.apk", "notes": "변경 내용 한두 줄" }`
  - 호스트 nginx가 `location ^~ /app/ieum.apk`, `location = /app/latest.json`, `/app/ieum-*.apk`를 먼저 처리한다. `/app` 자체는 웹 SPA 라우트(공개, 인증 가드 밖), 같은 출처의 `/app/latest.json`을 읽는다
  - 앱 env: `EXPO_PUBLIC_API_URL=https://i-eum.site/api/v1`, `EXPO_PUBLIC_APP_RELEASE_URL=https://i-eum.site/app` — 새 버전 확인은 `${EXPO_PUBLIC_APP_RELEASE_URL}/latest.json`, 설치 페이지는 `${EXPO_PUBLIC_APP_RELEASE_URL}`
  - 서명 gradle 속성(PC `~/.gradle/gradle.properties`): `IEUM_UPLOAD_STORE_FILE`, `IEUM_UPLOAD_KEY_ALIAS`, `IEUM_UPLOAD_STORE_PASSWORD`, `IEUM_UPLOAD_KEY_PASSWORD`. 플러그인 `front/apps/mobile/plugins/withReleaseSigning.js`. 릴리스 스크립트(infra 계획)가 `front/apps/mobile/android/app/build/outputs/apk/release/app-release.apk`를 가져간다
  - `app.json`: `expo.version` semver, `expo.android.versionCode` 정수 — 이 계획은 `versionCode: 1`만 더하고 `version`(`0.1.0`)은 릴리스 때까지 그대로 둔다
- `ttsRequestBody`·모델 설정은 backend 계획이 `chore/deploy-rebuild`의 현재 `cartesia.ts`에서 옮겨 간다. 이 계획은 앱에서 그 함수를 지운다(백엔드 작업 폴더는 분기 시점 파일을 읽으므로 영향 없음)
- 문구(정확히 이대로):
  - 웹 버튼: `이음 앱 내려받기` / 정보 없음: `준비 중이에요` / 설치 안내 제목 3개: `알 수 없는 앱 설치 허용` → `Play 프로텍트 경고` → `첫 실행`
  - 앱 새 버전 낭독: `새 버전이 있어요. 설치 페이지를 열까요?`
  - 음성 오류: 503 `지금은 목소리 기능을 쓸 수 없어요. 기본 목소리로 안내할게요.` / 429 `요청이 너무 많아요. 1분 뒤에 다시 시도해 주세요.` / 502 `목소리 서버가 응답하지 않았어요. 잠시 뒤 다시 시도해 주세요.` / 413 `녹음 파일이 너무 커요. 조금 더 짧게 읽어 주세요.` / 네트워크 `네트워크에 연결할 수 없어요. 연결을 확인하고 다시 시도해 주세요.` / 403 `학습자 계정으로 로그인해야 쓸 수 있어요.` / 그 밖 `목소리 기능에서 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요.`
- 접근성(앱, `docs/conventions/code/mobile.md`): 버튼은 `AppButton`(라벨·역할 부여), 결과가 자명하지 않으면 힌트. 낭독·햅틱은 `@/a11y`로만(`expo-speech` 직접 호출 금지). 웹: 시맨틱 태그(`main`·`h1`·`h2`·`ol`·`figure`), 상태 문구는 `role="status"`, 이미지에 `alt`, 키보드로 버튼(링크) 도달
- 웹 lint: `@typescript-eslint/no-explicit-any: error`, 경고 0개(`react-hooks/exhaustive-deps`, `react-refresh/only-export-components` 포함). 스타일은 인라인 `style` + `shared/ui/theme`·`styles`. vitest는 `src/**/__tests__/**/*.test.ts`(node 환경)만 돈다 — 화면은 build·lint·수동 확인
- 검증: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`, `pnpm --filter @ieum/mobile typecheck && pnpm --filter @ieum/mobile exec jest`, `pnpm --filter @ieum/api-client typecheck`. MR 전 `docker build --target test front`. 실패 상태로 완료를 선언하지 않는다

## File Structure (`front/` 아래)

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `packages/api-client/src/types.ts`, `client.ts` | `RequestOptions.responseType`·`signal`, FormData 본문을 그대로 보내고 Content-Type을 붙이지 않음 | Modify |
| `apps/mobile/__tests__/apiClient.test.ts` | 위 세 동작 테스트 | Modify |
| `apps/mobile/src/services/voiceSynthesisErrors.ts` | 음성 API 오류 → 사용자 문구 (순수) | Create |
| `apps/mobile/src/services/cartesia.ts` | 백엔드 `/voice/clones`·`/voice/speech` 호출, 시간 제한, 로컬 캐시 유지, 키 제거 | Modify |
| `apps/mobile/src/app/voice-clone.tsx` | 키 안내 제거, 실패 문구를 그대로 보여 주고 낭독 | Modify |
| `apps/mobile/src/a11y/clonedVoice.ts` | 키 없음 분기(이제 닿지 않음) 제거 | Modify |
| `apps/mobile/__tests__/voiceSynthesisErrors.test.ts`, `cartesia.test.ts` | 테스트 | Create |
| `apps/mobile/src/services/appRelease.ts` | `latest.json` 해석, 주소 정규화, KST 날짜, 알림 판정, 조회 (RN import 없음) | Create |
| `apps/mobile/src/stores/appUpdateStore.ts` | 마지막으로 알린 `{versionCode, day}` persist | Create |
| `apps/mobile/src/services/appUpdateCheck.ts` | 확인 흐름(의존성 주입), 실행 1회 보장, 실패는 조용히 | Create |
| `apps/mobile/__tests__/appRelease.test.ts`, `appUpdateCheck.test.ts` | 테스트 | Create |
| `apps/mobile/src/app/app-update.tsx` | 새 버전 알림 화면(낭독, "설치 페이지 열기"·"나중에") | Create |
| `apps/mobile/src/app/(tabs)/_layout.tsx` | 탭이 처음 열릴 때 `checkForAppUpdateOnce()` | Modify |
| `apps/mobile/plugins/withReleaseSigning.js` | release `signingConfig` 주입, 속성 없으면 release 빌드 실패 | Create |
| `apps/mobile/__tests__/withReleaseSigning.test.ts` | 문자열 변환 테스트 | Create |
| `apps/mobile/app.json` | `android.versionCode: 1`, 플러그인 등록 | Modify |
| `apps/web/src/domains/app-release/model/releaseInfo.ts` | `ReleaseInfo`, `parseReleaseInfo`, `formatReleaseDate`, 경로 상수 (순수) | Create |
| `apps/web/src/domains/app-release/api/releaseApi.ts` | `fetchReleaseInfo(fetchFn)` — 실패는 null | Create |
| `apps/web/src/domains/app-release/**/__tests__/*.test.ts` | 테스트 | Create |
| `apps/web/src/pages/AppDownloadPage.tsx` | 다운로드 페이지 | Create |
| `apps/web/src/app/router.tsx` | `/app` 공개 라우트 | Modify |
| `apps/web/scripts/generate-app-qr.mjs`, `apps/web/public/app-qr.svg` | QR 생성 스크립트와 결과물 | Create |
| `apps/web/vite.config.ts` | 개발 서버에서 `/app/latest.json`·`/app/ieum*.apk`를 운영(또는 지정 대상)으로 프록시 | Modify |
| `apps/web/README.md` | 다운로드 페이지·QR 재생성·개발 프록시 안내 | Modify |

도메인 폴더 이름은 `app-release`(웹 README의 "새 도메인은 user/ 구조 복제" 규칙: `model/`·`api/`). 웹과 앱의 `latest.json` 해석은 각자 둔다 — 웹은 zod·`@ieum/contracts`에 의존하지 않고(의존성 추가 금지), 두 앱이 읽는 필드도 다르다(웹: 버전·날짜·메모, 앱: versionCode).

---

### Task 0: 작업 폴더 준비

**Files:** 없음

- [ ] **Step 1: 워크트리와 브랜치 만들기**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git status --short      # ?? seed_remaining.sql, ?? total_init.sql 만 보이면 정상 (건드리지 않는다)
git worktree add -b chore/deploy-rebuild-front C:/Users/SSAFY/Desktop/wt-deploy-front chore/deploy-rebuild
cd C:/Users/SSAFY/Desktop/wt-deploy-front
git log --oneline -1    # dea509a9 docs(infra): 운영 배포 재구축 설계를 정한다 (또는 chore/deploy-rebuild 최신)
```

- [ ] **Step 2: 의존성 설치와 기준선 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front/front
pnpm install --frozen-lockfile
pnpm --filter @ieum/web test && pnpm --filter @ieum/mobile exec jest
```

Expected: 둘 다 PASS (기준선). 실패하면 이 계획의 변경 전 문제이므로 멈추고 보고한다.

---

### Task 1: api-client — FormData 본문·바이트 응답·취소 신호

**Files:**
- Modify: `front/packages/api-client/src/types.ts` (`RequestOptions`)
- Modify: `front/packages/api-client/src/client.ts:11-65` (`buildHeaders`, `sendOnce`)
- Test: `front/apps/mobile/__tests__/apiClient.test.ts` (끝에 describe 추가)

**Interfaces:**
- Consumes: 없음
- Produces (Task 2가 쓴다):
  - `RequestOptions.responseType?: "json" | "arrayBuffer"` — `"arrayBuffer"`면 **성공 응답**을 `ArrayBuffer`로 돌려준다. 오류 응답은 지금처럼 JSON `{code,message}` → `ApiError`
  - `RequestOptions.signal?: AbortSignal` — fetch에 그대로 넘긴다. 취소되면 기존 규칙대로 `ApiError(0, NETWORK_UNAVAILABLE)`
  - `body`가 `FormData`면 문자열로 바꾸지 않고 그대로 보내며 `Content-Type`을 붙이지 않는다(fetch가 boundary를 붙인다)
  - 기존 호출(JSON 본문, 옵션 없음)의 요청·응답은 바이트 하나 바뀌지 않는다 — `signal`이 없으면 init에 `signal` 키도 넣지 않는다

- [ ] **Step 1: 실패하는 테스트 쓰기**

`front/apps/mobile/__tests__/apiClient.test.ts` 맨 끝에 추가:

```ts
describe("api-client 멀티파트·바이트 응답·취소", () => {
  function clientWith(fetchFn: jest.Mock) {
    const { store } = memoryTokenStore("access-1", "refresh-1");
    return createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });
  }

  it("FormData 본문은 그대로 보내고 Content-Type을 붙이지 않는다 (boundary는 fetch가 붙인다)", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(201, { voiceId: "v-1" }));
    const form = new FormData();
    form.append("name", "내 목소리");

    const result = await clientWith(fetchFn).post<{ voiceId: string }>("/voice/clones", form);

    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(init.body).toBe(form);
    expect(headers["Content-Type"]).toBeUndefined();
    expect(headers.Authorization).toBe("Bearer access-1");
    expect(result.voiceId).toBe("v-1");
  });

  it("responseType arrayBuffer면 성공 응답의 바이트를 그대로 돌려준다", async () => {
    const bytes = new Uint8Array([0x49, 0x44, 0x33]).buffer;
    const fetchFn = jest.fn(async () => ({
      ok: true,
      status: 200,
      arrayBuffer: async () => bytes,
      text: async () => {
        throw new Error("바이트 응답을 text로 읽으면 안 된다");
      },
    }));

    const result = await clientWith(fetchFn).post<ArrayBuffer>(
      "/voice/speech",
      { voiceId: "v-1", text: "안녕" },
      { responseType: "arrayBuffer" },
    );

    expect(new Uint8Array(result)).toEqual(new Uint8Array([0x49, 0x44, 0x33]));
    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("responseType arrayBuffer여도 오류 응답은 code를 ApiError로 옮긴다", async () => {
    const fetchFn = jest.fn(async () =>
      fakeResponse(503, { code: "VOICE_SYNTHESIS_UNAVAILABLE", message: "음성 합성을 사용할 수 없습니다." }),
    );

    await expect(
      clientWith(fetchFn).post("/voice/speech", { voiceId: "v-1", text: "안녕" }, { responseType: "arrayBuffer" }),
    ).rejects.toMatchObject({ status: 503, code: "VOICE_SYNTHESIS_UNAVAILABLE" });
  });

  it("signal을 fetch에 넘기고, 없으면 init에 signal 키를 넣지 않는다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(200, {}));
    const controller = new AbortController();

    await clientWith(fetchFn).get("/users/me", { signal: controller.signal });
    await clientWith(fetchFn).get("/users/me");

    const [, withSignal] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    const [, withoutSignal] = fetchFn.mock.calls[1] as unknown as [string, RequestInit];
    expect(withSignal.signal).toBe(controller.signal);
    expect("signal" in withoutSignal).toBe(false);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd C:/Users/SSAFY/Desktop/wt-deploy-front/front && pnpm --filter @ieum/mobile exec jest __tests__/apiClient.test.ts`
Expected: FAIL — 새 4건 중 FormData(`init.body`가 `"{}"`), arrayBuffer(`text`가 던짐), signal(`toBe` 불일치)이 실패. 기존 테스트는 PASS

- [ ] **Step 3: `types.ts`의 `RequestOptions` 교체**

```ts
export interface RequestOptions {
  /** JSON으로 보낼 값. `FormData`면 그대로 보내고 Content-Type은 fetch가 정한다(multipart boundary) */
  body?: unknown;
  /** 기본 true. false면 Authorization을 붙이지 않고 401 자동 갱신도 하지 않는다 */
  auth?: boolean;
  /** 요청별 헤더(예: Idempotency-Key). 플랫폼·Content-Type·Authorization은 덮지 못한다 */
  headers?: Readonly<Record<string, string>>;
  /**
   * 성공 응답 해석 방식. 기본 "json". "arrayBuffer"면 본문 바이트를 그대로 돌려준다(예: MP3).
   * 오류 응답은 어느 쪽이든 JSON `{code, message}`로 해석해 ApiError로 던진다
   */
  responseType?: "json" | "arrayBuffer";
  /** 요청 취소(시간 제한 등). 취소되면 NETWORK_UNAVAILABLE ApiError로 끝난다 */
  signal?: AbortSignal;
}
```

- [ ] **Step 4: `client.ts` 수정**

`createClient` 안, `RESERVED_HEADER_NAMES` 바로 아래에 두 함수를 더한다:

```ts
  // FormData는 문자열로 바꾸면 "{}"가 된다. 그대로 넘기고 boundary는 fetch에게 맡긴다
  function isFormData(body: unknown): body is FormData {
    return typeof FormData !== "undefined" && body instanceof FormData;
  }

  function encodeBody(body: unknown): BodyInit | undefined {
    if (body === undefined) return undefined;
    if (isFormData(body)) return body;
    return JSON.stringify(body);
  }
```

`buildHeaders`의 Content-Type 줄을 바꾼다:

```ts
    // body가 없는 요청에 Content-Type을 붙이지 않는다 — 보낼 표현이 없다.
    // FormData는 fetch가 multipart boundary를 담아 직접 붙여야 한다
    if (options.body !== undefined && !isFormData(options.body)) {
      headers["Content-Type"] = "application/json";
    }
```

`sendOnce`의 fetch 호출과 그 바로 뒤를 바꾼다(나머지 JSON 해석·오류 처리는 그대로):

```ts
  async function sendOnce<T>(method: string, path: string, options: RequestOptions): Promise<T> {
    let response: Response;
    try {
      response = await fetchFn(`${config.baseUrl}${path}`, {
        method,
        headers: buildHeaders(options),
        body: encodeBody(options.body),
        ...(options.signal ? { signal: options.signal } : {}),
      });
    } catch {
      // 원인 메시지를 그대로 노출하지 않는다 — 사용자에게 의미가 없다
      throw new ApiError(0, NETWORK_UNAVAILABLE, "네트워크에 연결할 수 없어요.");
    }

    // 바이트 응답(MP3 등)은 성공일 때만 그대로 넘긴다. 오류는 아래 JSON 경로로 해석한다
    if (response.ok && options.responseType === "arrayBuffer") {
      try {
        return (await response.arrayBuffer()) as T;
      } catch {
        throw new ApiError(response.status, INVALID_RESPONSE, "서버 응답을 해석하지 못했어요.");
      }
    }

    // 204와, 오류 상태에서 body를 생략한 응답을 같은 경로로 처리한다
    let payload: unknown;
```

(그 아래 `try { const text = await response.text(); ...` 부터 `return payload as T;`까지는 손대지 않는다.)

- [ ] **Step 5: 통과 확인 (앱·웹·패키지)**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front/front
pnpm --filter @ieum/mobile exec jest __tests__/apiClient.test.ts
pnpm --filter @ieum/api-client typecheck
pnpm --filter @ieum/web test && pnpm --filter @ieum/web build
```

Expected: 모두 PASS. 웹은 FormData·바이트 응답을 쓰지 않으므로 동작 변화 없음(빌드·기존 테스트 통과로 확인)

- [ ] **Step 6: 커밋**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front
git add front/packages/api-client/src/types.ts front/packages/api-client/src/client.ts front/apps/mobile/__tests__/apiClient.test.ts
git commit -m "feat(voice): api-client가 멀티파트 본문과 바이트 응답, 요청 취소를 지원한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 앱 음성 복제·합성을 백엔드 대리 호출로

**Files:**
- Create: `front/apps/mobile/src/services/voiceSynthesisErrors.ts`
- Modify: `front/apps/mobile/src/services/cartesia.ts` (전체 교체)
- Modify: `front/apps/mobile/src/app/voice-clone.tsx:8,20-37,73-77`
- Modify: `front/apps/mobile/src/a11y/clonedVoice.ts:40-46,110-115`
- Test: `front/apps/mobile/__tests__/voiceSynthesisErrors.test.ts`, `front/apps/mobile/__tests__/cartesia.test.ts`

**Interfaces:**
- Consumes: Task 1의 `RequestOptions.responseType`·`signal`, FormData 본문. `api` from `src/services/apiClient.ts`
- Produces:
  - `voiceFailureMessage(error: unknown): string` 와 문구 상수 `VOICE_UNAVAILABLE_MESSAGE`, `VOICE_RATE_LIMITED_MESSAGE`, `VOICE_FAILED_MESSAGE`, `VOICE_TOO_LARGE_MESSAGE`, `VOICE_NETWORK_MESSAGE`, `VOICE_LEARNER_ONLY_MESSAGE`, `VOICE_GENERIC_MESSAGE`
  - `cartesia.ts`(이름 유지 — 로컬 캐시 폴더 `cartesia-tts`와 호출부를 그대로 두기 위해): `cloneVoice(clipUri: string, name?: string): Promise<ClonedVoice>`(`ClonedVoice = {id, name}` — `language` 필드 제거), `synthesizeToFile(voiceId: string, text: string): Promise<string>`, `clearSynthesisCache(): void`, `isCartesiaConfigured(): boolean`(항상 true), `CartesiaError`(message = 사용자 문구). `ttsRequestBody`는 삭제(백엔드로 이동)

- [ ] **Step 1: 오류 문구 실패 테스트 쓰기**

`front/apps/mobile/__tests__/voiceSynthesisErrors.test.ts`:

```ts
import { ApiError, INVALID_RESPONSE, NETWORK_UNAVAILABLE } from "@ieum/api-client";
import {
  VOICE_FAILED_MESSAGE,
  VOICE_GENERIC_MESSAGE,
  VOICE_LEARNER_ONLY_MESSAGE,
  VOICE_NETWORK_MESSAGE,
  VOICE_RATE_LIMITED_MESSAGE,
  VOICE_TOO_LARGE_MESSAGE,
  VOICE_UNAVAILABLE_MESSAGE,
  voiceFailureMessage,
} from "@/services/voiceSynthesisErrors";

describe("voiceFailureMessage", () => {
  it.each([
    [new ApiError(503, "VOICE_SYNTHESIS_UNAVAILABLE", "x"), VOICE_UNAVAILABLE_MESSAGE],
    [new ApiError(429, "VOICE_RATE_LIMITED", "x"), VOICE_RATE_LIMITED_MESSAGE],
    [new ApiError(502, "VOICE_SYNTHESIS_FAILED", "x"), VOICE_FAILED_MESSAGE],
    [new ApiError(403, "LEARNER_ACCESS_REQUIRED", "x"), VOICE_LEARNER_ONLY_MESSAGE],
    [new ApiError(0, NETWORK_UNAVAILABLE, "x"), VOICE_NETWORK_MESSAGE],
  ])("계약 오류 코드를 사용자 문구로 바꾼다 (%#)", (error, expected) => {
    expect(voiceFailureMessage(error)).toBe(expected);
  });

  it("nginx가 HTML로 답한 상태 코드도 상태로 구분한다 (본문 해석 실패)", () => {
    expect(voiceFailureMessage(new ApiError(503, INVALID_RESPONSE, "x"))).toBe(VOICE_UNAVAILABLE_MESSAGE);
    expect(voiceFailureMessage(new ApiError(502, INVALID_RESPONSE, "x"))).toBe(VOICE_FAILED_MESSAGE);
    expect(voiceFailureMessage(new ApiError(504, INVALID_RESPONSE, "x"))).toBe(VOICE_FAILED_MESSAGE);
    expect(voiceFailureMessage(new ApiError(413, INVALID_RESPONSE, "x"))).toBe(VOICE_TOO_LARGE_MESSAGE);
  });

  it("그 밖의 오류는 일반 문구로, 서버 message 원문을 그대로 쓰지 않는다", () => {
    expect(voiceFailureMessage(new ApiError(400, "INVALID_REQUEST_PARAMETER", "text 길이 초과"))).toBe(VOICE_GENERIC_MESSAGE);
    expect(voiceFailureMessage(new Error("boom"))).toBe(VOICE_GENERIC_MESSAGE);
    expect(voiceFailureMessage("boom")).toBe(VOICE_GENERIC_MESSAGE);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd C:/Users/SSAFY/Desktop/wt-deploy-front/front && pnpm --filter @ieum/mobile exec jest __tests__/voiceSynthesisErrors.test.ts`
Expected: FAIL — `Cannot find module '@/services/voiceSynthesisErrors'`

- [ ] **Step 3: `voiceSynthesisErrors.ts` 작성**

```ts
import { ApiError, NETWORK_UNAVAILABLE } from "@ieum/api-client";

/**
 * 음성 복제·합성(백엔드 `/voice/*`) 실패를 사용자에게 읽어 줄 한 문장으로 바꾼다.
 *
 * 화면은 이 문장을 그대로 보여 주고 낭독한다(TalkBack이 한 번에 읽을 수 있게 짧게).
 * 서버 message나 Cartesia 원문은 쓰지 않는다 — 기술 용어가 섞이고, 계약상 502 원문은 오지도 않는다.
 * nginx가 HTML로 답한 경우(본문 해석 실패)도 상태 코드로 구분한다.
 */
export const VOICE_UNAVAILABLE_MESSAGE = "지금은 목소리 기능을 쓸 수 없어요. 기본 목소리로 안내할게요.";
export const VOICE_RATE_LIMITED_MESSAGE = "요청이 너무 많아요. 1분 뒤에 다시 시도해 주세요.";
export const VOICE_FAILED_MESSAGE = "목소리 서버가 응답하지 않았어요. 잠시 뒤 다시 시도해 주세요.";
export const VOICE_TOO_LARGE_MESSAGE = "녹음 파일이 너무 커요. 조금 더 짧게 읽어 주세요.";
export const VOICE_NETWORK_MESSAGE = "네트워크에 연결할 수 없어요. 연결을 확인하고 다시 시도해 주세요.";
export const VOICE_LEARNER_ONLY_MESSAGE = "학습자 계정으로 로그인해야 쓸 수 있어요.";
export const VOICE_GENERIC_MESSAGE = "목소리 기능에서 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요.";

export function voiceFailureMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return VOICE_GENERIC_MESSAGE;
  if (error.code === "VOICE_SYNTHESIS_UNAVAILABLE" || error.status === 503) return VOICE_UNAVAILABLE_MESSAGE;
  if (error.code === "VOICE_RATE_LIMITED" || error.status === 429) return VOICE_RATE_LIMITED_MESSAGE;
  if (error.code === "VOICE_SYNTHESIS_FAILED" || error.status === 502 || error.status === 504) {
    return VOICE_FAILED_MESSAGE;
  }
  if (error.status === 413) return VOICE_TOO_LARGE_MESSAGE;
  if (error.code === "LEARNER_ACCESS_REQUIRED") return VOICE_LEARNER_ONLY_MESSAGE;
  if (error.status === 0 || error.code === NETWORK_UNAVAILABLE) return VOICE_NETWORK_MESSAGE;
  return VOICE_GENERIC_MESSAGE;
}
```

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @ieum/mobile exec jest __tests__/voiceSynthesisErrors.test.ts`
Expected: PASS (3 describe 항목, it.each 5건 포함)

- [ ] **Step 5: `cartesia.ts` 실패 테스트 쓰기**

`front/apps/mobile/__tests__/cartesia.test.ts`:

```ts
import { ApiError, NETWORK_UNAVAILABLE } from "@ieum/api-client";
import { api } from "@/services/apiClient";
import {
  CartesiaError,
  clearSynthesisCache,
  cloneVoice,
  isCartesiaConfigured,
  synthesizeToFile,
} from "@/services/cartesia";
import { VOICE_RATE_LIMITED_MESSAGE, VOICE_UNAVAILABLE_MESSAGE } from "@/services/voiceSynthesisErrors";

/** 가짜 파일 시스템: uri → 내용. 이름이 mock으로 시작해야 jest.mock 팩토리에서 참조할 수 있다 */
const mockFiles = new Map<string, Uint8Array>();

jest.mock("@/services/apiClient", () => ({ api: { get: jest.fn(), post: jest.fn(), put: jest.fn() } }));
jest.mock("expo-file-system", () => {
  const uriOf = (part: string | { uri: string }) => (typeof part === "string" ? part : part.uri);
  class Directory {
    readonly uri: string;
    constructor(base: string | { uri: string }, name: string) {
      this.uri = `${uriOf(base)}/${name}`;
    }
    get exists() {
      return [...mockFiles.keys()].some((key) => key.startsWith(`${this.uri}/`));
    }
    delete() {
      for (const key of [...mockFiles.keys()]) if (key.startsWith(`${this.uri}/`)) mockFiles.delete(key);
    }
  }
  class File {
    readonly uri: string;
    constructor(...parts: Array<string | { uri: string }>) {
      this.uri = parts.map(uriOf).join("/");
    }
    get exists() {
      return mockFiles.has(this.uri);
    }
    get size() {
      return mockFiles.get(this.uri)?.byteLength ?? 0;
    }
    async bytes() {
      return mockFiles.get(this.uri) ?? new Uint8Array();
    }
    create() {
      mockFiles.set(this.uri, new Uint8Array());
    }
    write(data: Uint8Array) {
      mockFiles.set(this.uri, data);
    }
  }
  return { Directory, File, Paths: { cache: "file:///cache" } };
});

const mockedPost = (api as unknown as { post: jest.Mock }).post;
const CLIP = "file:///records/sample.wav";

beforeEach(() => {
  jest.resetAllMocks();
  mockFiles.clear();
});

describe("cloneVoice", () => {
  it("녹음 파일을 multipart(clip, name)로 백엔드에 올리고 voiceId를 받는다", async () => {
    mockFiles.set(CLIP, new Uint8Array([1, 2, 3]));
    mockedPost.mockResolvedValue({ voiceId: "voice-abc" });
    const append = jest.spyOn(FormData.prototype, "append");

    const voice = await cloneVoice(CLIP);

    expect(voice).toEqual({ id: "voice-abc", name: "내 목소리" });
    const [path, body, options] = mockedPost.mock.calls[0] as [string, unknown, { signal?: AbortSignal }];
    expect(path).toBe("/voice/clones");
    expect(body).toBeInstanceOf(FormData);
    expect(options.signal).toBeDefined();
    expect(append).toHaveBeenCalledWith(
      "clip",
      expect.objectContaining({ uri: CLIP, name: "voice-sample.wav", type: "audio/wav" }),
    );
    expect(append).toHaveBeenCalledWith("name", "내 목소리");
    // 언어·설명 같은 Cartesia 전용 필드는 이제 백엔드가 붙인다
    expect(append).toHaveBeenCalledTimes(2);
    append.mockRestore();
  });

  it("녹음 파일이 없으면 서버를 부르지 않는다", async () => {
    await expect(cloneVoice(CLIP)).rejects.toThrow("녹음 파일을 찾지 못했어요");
    expect(mockedPost).not.toHaveBeenCalled();
  });

  it("서버에 키가 없으면(503) 쓸 수 없다는 문구로 실패한다", async () => {
    mockFiles.set(CLIP, new Uint8Array([1]));
    mockedPost.mockRejectedValue(new ApiError(503, "VOICE_SYNTHESIS_UNAVAILABLE", "x"));

    const failure = cloneVoice(CLIP);

    await expect(failure).rejects.toBeInstanceOf(CartesiaError);
    await expect(failure).rejects.toThrow(VOICE_UNAVAILABLE_MESSAGE);
  });

  it("응답에 voiceId가 없으면 실패한다", async () => {
    mockFiles.set(CLIP, new Uint8Array([1]));
    mockedPost.mockResolvedValue({ id: "old-shape" });

    await expect(cloneVoice(CLIP)).rejects.toThrow("목소리를 만들지 못했어요");
  });
});

describe("synthesizeToFile", () => {
  it("백엔드에 {voiceId, text}를 보내 MP3 바이트를 받아 캐시에 쓰고, 같은 문장은 다시 받지 않는다", async () => {
    mockedPost.mockResolvedValue(new Uint8Array([9, 9]).buffer);

    const first = await synthesizeToFile("voice-abc", "네, 잇다예요");
    const second = await synthesizeToFile("voice-abc", "네, 잇다예요");

    expect(second).toBe(first);
    expect(first.startsWith("file:///cache/cartesia-tts/")).toBe(true);
    expect(mockFiles.get(first)).toEqual(new Uint8Array([9, 9]));
    expect(mockedPost).toHaveBeenCalledTimes(1);
    expect(mockedPost).toHaveBeenCalledWith(
      "/voice/speech",
      { voiceId: "voice-abc", text: "네, 잇다예요" },
      expect.objectContaining({ responseType: "arrayBuffer" }),
    );
  });

  it("캐시를 비우면 다시 받는다", async () => {
    mockedPost.mockResolvedValue(new Uint8Array([7]).buffer);

    await synthesizeToFile("voice-abc", "안녕");
    clearSynthesisCache();
    await synthesizeToFile("voice-abc", "안녕");

    expect(mockedPost).toHaveBeenCalledTimes(2);
  });

  it("빈 결과는 실패로 본다", async () => {
    mockedPost.mockResolvedValue(new ArrayBuffer(0));

    await expect(synthesizeToFile("voice-abc", "안녕")).rejects.toThrow("합성 결과가 비어 있어요");
  });

  it("사용량 제한(429)은 1분 뒤 다시 하라는 문구로 실패한다", async () => {
    mockedPost.mockRejectedValue(new ApiError(429, "VOICE_RATE_LIMITED", "x"));

    await expect(synthesizeToFile("voice-abc", "안녕")).rejects.toThrow(VOICE_RATE_LIMITED_MESSAGE);
  });

  it("빈 문장이나 300자를 넘는 문장은 서버를 부르지 않는다 (계약: trim 뒤 1~300자)", async () => {
    await expect(synthesizeToFile("voice-abc", "   ")).rejects.toThrow("1~300자");
    await expect(synthesizeToFile("voice-abc", "가".repeat(301))).rejects.toThrow("1~300자");
    expect(mockedPost).not.toHaveBeenCalled();
  });

  it("25초 안에 응답이 없으면 요청을 끊고 시간 초과를 알린다", async () => {
    jest.useFakeTimers();
    try {
      mockedPost.mockImplementation(
        (_path: string, _body: unknown, options: { signal: AbortSignal }) =>
          new Promise((_resolve, reject) => {
            options.signal.addEventListener("abort", () => reject(new ApiError(0, NETWORK_UNAVAILABLE, "x")));
          }),
      );

      const pending = synthesizeToFile("voice-abc", "안녕");
      jest.advanceTimersByTime(25_000);

      await expect(pending).rejects.toThrow("25초 안에 응답이 오지 않았어요");
    } finally {
      jest.useRealTimers();
    }
  });
});

it("키는 서버에 있으므로 앱은 항상 시도한다", () => {
  expect(isCartesiaConfigured()).toBe(true);
});
```

- [ ] **Step 6: 실패 확인**

Run: `pnpm --filter @ieum/mobile exec jest __tests__/cartesia.test.ts`
Expected: FAIL — `cloneVoice`가 아직 `requireKey()`에서 "Cartesia API 키가 없어요"로 던지고, `api.post`를 부르지 않는다

- [ ] **Step 7: `cartesia.ts` 전체 교체**

```ts
import { Directory, File, Paths } from "expo-file-system";
import { z } from "zod";
import { api } from "./apiClient";
import { voiceFailureMessage } from "./voiceSynthesisErrors";

/**
 * 내 목소리 복제·합성 — **백엔드 대리 호출**을 쓴다 (설계 2026-09-27-deploy-rebuild-design.md §6).
 *
 * - `POST /voice/clones`  WAV 샘플(multipart `clip`, `name`)을 올리고 `{voiceId}`를 받는다
 * - `POST /voice/speech`  `{voiceId, text}`로 문장을 읽어 MP3 바이트를 받는다
 *
 * Cartesia 키·모델·출력 형식은 backend에만 있다. APK에는 키가 들어가지 않는다.
 * 서버에 키가 없으면 503, 너무 자주 부르면 429, Cartesia가 실패하면 502 — 사용자 문구는
 * `voiceSynthesisErrors`가 정한다. 합성 결과는 기기 캐시(`cartesia-tts`)에 남겨 같은 문장은 다시 받지 않는다.
 */

/** 업로드 + 서버의 Cartesia 대기(60초). 서버가 먼저 끊고 502를 주도록 조금 길게 둔다 */
const CLONE_TIMEOUT_MS = 90_000;
/** 서버의 합성 대기(20초)보다 조금 길게. 이보다 길면 잇다 안내가 너무 늦게 기본 목소리로 넘어간다 */
const TTS_TIMEOUT_MS = 25_000;
/** 서버 계약: text는 앞뒤 공백을 뺀 뒤 1~300자 */
const MAX_TEXT_LENGTH = 300;
const DEFAULT_VOICE_NAME = "내 목소리";

const cloneResponseSchema = z.object({ voiceId: z.string().min(1) });

export interface ClonedVoice {
  readonly id: string;
  readonly name: string;
}

/** 키는 서버에 있으므로 앱은 항상 시도한다. 쓸 수 없으면 서버가 503으로 알린다 */
export function isCartesiaConfigured(): boolean {
  return true;
}

/** 사용자에게 그대로 보여 주고 읽어 줄 수 있는 문장을 message로 갖는다 */
export class CartesiaError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "CartesiaError";
  }
}

/**
 * 멀티파트에 실을 파일 한 조각.
 *
 * Expo가 전역 `fetch`를 자기 구현으로 덮어 두는데, **그쪽은 RN식 `{uri}` 파트를 읽지 않는다**
 * (`Unsupported FormDataPart implementation`). 받아 주는 건 `bytes()`를 가진 객체뿐이다.
 * RN 구현으로 되돌아가도 되도록 `uri`도 함께 남겨 둔다. api-client도 같은 전역 fetch를 쓴다.
 */
function uploadPart(file: File, name: string, type: string) {
  return {
    uri: file.uri,
    name,
    type,
    size: file.size,
    bytes: () => file.bytes(),
  };
}

/** 음성 샘플(WAV) 하나로 목소리를 복제한다. */
export async function cloneVoice(clipUri: string, name = DEFAULT_VOICE_NAME): Promise<ClonedVoice> {
  const clip = new File(clipUri);
  if (!clip.exists) throw new CartesiaError("녹음 파일을 찾지 못했어요. 다시 녹음해 주세요.");

  const form = new FormData();
  form.append("clip", uploadPart(clip, "voice-sample.wav", "audio/wav") as unknown as Blob);
  form.append("name", name);

  const response = await withTimeout(CLONE_TIMEOUT_MS, (signal) =>
    api.post<unknown>("/voice/clones", form, { signal }),
  );
  const parsed = cloneResponseSchema.safeParse(response);
  if (!parsed.success) throw new CartesiaError("목소리를 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.");

  return { id: parsed.data.voiceId, name };
}

/** 복제한 목소리로 문장을 읽어 캐시에 MP3로 저장하고 그 파일 URI를 돌려준다. */
export async function synthesizeToFile(voiceId: string, text: string): Promise<string> {
  const length = text.trim().length;
  if (length === 0 || length > MAX_TEXT_LENGTH) {
    throw new CartesiaError(`합성할 수 있는 문장은 1~${MAX_TEXT_LENGTH}자예요.`);
  }

  const cached = cacheFile(voiceId, text);
  if (cached.exists) return cached.uri;

  const buffer = await withTimeout(TTS_TIMEOUT_MS, (signal) =>
    api.post<ArrayBuffer>("/voice/speech", { voiceId, text }, { signal, responseType: "arrayBuffer" }),
  );
  const bytes = new Uint8Array(buffer);
  if (bytes.byteLength === 0) throw new CartesiaError("합성 결과가 비어 있어요.");

  cached.create({ overwrite: true, intermediates: true });
  cached.write(bytes);
  return cached.uri;
}

// --- 캐시 -------------------------------------------------------------------

const CACHE_DIR = "cartesia-tts";

/** 같은 문장은 한 번만 받는다. "네, 잇다예요" 같은 고정 응답이 매번 네트워크를 타지 않게. */
function cacheFile(voiceId: string, text: string): File {
  return new File(new Directory(Paths.cache, CACHE_DIR), `${voiceId.slice(0, 12)}_${hash(text)}.mp3`);
}

/** 파일 이름에 쓸 짧은 해시. 암호학적 용도가 아니라 충돌만 피하면 된다(djb2). */
function hash(text: string): string {
  let value = 5381;
  for (let i = 0; i < text.length; i += 1) {
    value = ((value << 5) + value + text.charCodeAt(i)) | 0;
  }
  return (value >>> 0).toString(16);
}

/** 복제한 목소리를 지울 때 캐시도 함께 비운다 — 옛 목소리가 남아 있으면 안 된다. */
export function clearSynthesisCache(): void {
  const directory = new Directory(Paths.cache, CACHE_DIR);
  if (directory.exists) directory.delete();
}

// --- 공통 -------------------------------------------------------------------

/**
 * 시간 제한을 걸어 부르고, 실패를 사용자 문장으로 바꾼다.
 * 취소된 요청은 api-client에서 NETWORK_UNAVAILABLE로 오므로 신호 상태로 시간 초과를 구분한다.
 */
async function withTimeout<T>(timeoutMs: number, run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await run(controller.signal);
  } catch (error) {
    if (controller.signal.aborted) {
      throw new CartesiaError(
        `${Math.round(timeoutMs / 1000)}초 안에 응답이 오지 않았어요. 네트워크가 느리면 와이파이에서 다시 시도해 주세요.`,
        { cause: error },
      );
    }
    throw new CartesiaError(voiceFailureMessage(error), { cause: error });
  } finally {
    clearTimeout(timer);
  }
}
```

- [ ] **Step 8: 통과 확인**

Run: `pnpm --filter @ieum/mobile exec jest __tests__/cartesia.test.ts __tests__/voiceSynthesisErrors.test.ts`
Expected: PASS

- [ ] **Step 9: `voice-clone.tsx` — 키 안내 제거, 실패 문구 그대로 보여 주고 읽기**

import 줄(8행)을 바꾼다:

```tsx
import { clearSynthesisCache, cloneVoice } from "@/services/cartesia";
```

주석(20~25행) 첫 줄을 바꾼다:

```tsx
/**
 * 내 목소리 복제 — 샘플을 녹음해 서버(백엔드가 Cartesia를 대신 부른다)에 올리고, 그 목소리로 잇다가 답하게 한다.
```

`status` 초기값(35~37행)을 바꾼다:

```tsx
  const [status, setStatus] = useState("");
```

`finish`의 catch(73~77행)를 바꾼다 — `reason(error)`는 이제 `CartesiaError`의 사용자 문장이다:

```tsx
    } catch (error) {
      const message = reason(error);
      setStatus(`복제하지 못했어요. ${message}`);
      void hapticError();
      announce(`목소리 복제에 실패했어요. ${message}`);
    } finally {
```

- [ ] **Step 10: `a11y/clonedVoice.ts` — 닿지 않는 키 분기 제거**

`isCartesiaConfigured()`가 항상 true이므로 두 분기는 죽은 코드이고, 문구가 사람을 `.env`로 잘못 보낸다. `speakWithClonedVoice`에서 이 블록을 지운다:

```ts
  if (!isCartesiaConfigured()) {
    outcome = { clonedVoice: false, detail: "Cartesia API 키가 없어요" };
    return false;
  }
```

`selfTest`의 두 줄을 이렇게 바꾼다(번호도 당긴다):

```ts
  if (!clonedVoice) return "실패 ① 복제한 목소리가 저장돼 있지 않아요";
```

그리고 catch의 `실패 ③`을 `실패 ②`로 바꾼다. `canUseClonedVoice`의 `isCartesiaConfigured() && clonedVoice !== null`와 import는 그대로 둔다(계약: 함수는 남고 항상 true).

- [ ] **Step 11: 전체 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front/front
pnpm --filter @ieum/mobile typecheck
pnpm --filter @ieum/mobile exec jest
cd .. && git grep -n "EXPO_PUBLIC_CARTESIA_API_KEY\|api.cartesia.ai\|ttsRequestBody" -- front/apps/mobile/src front/apps/mobile/__tests__
```

Expected: typecheck·jest PASS, 마지막 grep 출력 없음(키·Cartesia 직접 주소가 앱 소스에서 사라짐. `.env.example`은 사람이 고친다 — Global Constraints)

- [ ] **Step 12: 커밋**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front
git add front/apps/mobile/src/services/voiceSynthesisErrors.ts front/apps/mobile/src/services/cartesia.ts \
  front/apps/mobile/src/app/voice-clone.tsx front/apps/mobile/src/a11y/clonedVoice.ts \
  front/apps/mobile/__tests__/voiceSynthesisErrors.test.ts front/apps/mobile/__tests__/cartesia.test.ts
git commit -m "feat(voice): 목소리 복제·합성을 백엔드 대리 호출로 바꾸고 앱에서 Cartesia 키를 없앤다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 새 버전 확인 — 해석·판정·저장·흐름

**Files:**
- Create: `front/apps/mobile/src/services/appRelease.ts`
- Create: `front/apps/mobile/src/stores/appUpdateStore.ts`
- Create: `front/apps/mobile/src/services/appUpdateCheck.ts`
- Test: `front/apps/mobile/__tests__/appRelease.test.ts`, `front/apps/mobile/__tests__/appUpdateCheck.test.ts`

**Interfaces:**
- Consumes: 없음 (`expo-constants`, `expo-router`, `zod`, `zustand`, AsyncStorage — 모두 기존 의존성)
- Produces (Task 4가 쓴다):
  - `appRelease.ts`: `type ReleaseInfo = { version: string; versionCode: number; releasedAt: string; notes?: string }`, `parseReleaseInfo(raw: unknown): ReleaseInfo | null`, `releaseBaseUrl(raw: string | undefined): string | null`, `kstDay(now: Date): string`(`YYYY-MM-DD`), `interface UpdateNotice { versionCode: number; day: string }`, `shouldAnnounceUpdate(latest: number, installed: number, lastNotice: UpdateNotice | null, today: string): boolean`, `fetchLatestRelease(baseUrl: string, fetchFn?: typeof fetch, timeoutMs?: number): Promise<ReleaseInfo | null>`, 상수 `UPDATE_PROMPT_MESSAGE = "새 버전이 있어요. 설치 페이지를 열까요?"`, `UPDATE_OPEN_FAILED_MESSAGE`
  - `appUpdateStore.ts`: `useAppUpdateStore`(`lastNotice: UpdateNotice | null`, `setLastNotice(notice)`), `whenAppUpdateStoreHydrated(): Promise<void>`
  - `appUpdateCheck.ts`: `interface UpdateCheckDeps`, `defaultUpdateCheckDeps(): UpdateCheckDeps`, `checkForAppUpdate(deps): Promise<boolean>`(알렸으면 true, 절대 throw하지 않음), `checkForAppUpdateOnce(deps?): Promise<boolean>`(프로세스당 한 번), 기본 `showPrompt`는 `router.push({ pathname: "/app-update", params: { version } })`

- [ ] **Step 1: `appRelease` 실패 테스트 쓰기**

`front/apps/mobile/__tests__/appRelease.test.ts`:

```ts
import {
  fetchLatestRelease,
  kstDay,
  parseReleaseInfo,
  releaseBaseUrl,
  shouldAnnounceUpdate,
} from "@/services/appRelease";

const LATEST = {
  version: "1.0.0",
  versionCode: 2,
  releasedAt: "2026-09-27T12:00:00Z",
  sha256: "ab".repeat(32),
  url: "https://i-eum.site/app/ieum.apk",
  notes: "수업 입장 속도 개선",
};

function fakeResponse(status: number, text: string) {
  return { ok: status >= 200 && status < 300, status, text: async () => text } as unknown as Response;
}

describe("parseReleaseInfo", () => {
  it("계약 형식을 읽는다 (쓰지 않는 필드는 버린다)", () => {
    expect(parseReleaseInfo(LATEST)).toEqual({
      version: "1.0.0",
      versionCode: 2,
      releasedAt: "2026-09-27T12:00:00Z",
      notes: "수업 입장 속도 개선",
    });
  });

  it.each([
    ["versionCode가 문자열", { ...LATEST, versionCode: "2" }],
    ["versionCode가 0", { ...LATEST, versionCode: 0 }],
    ["versionCode가 소수", { ...LATEST, versionCode: 1.5 }],
    ["version이 semver가 아님", { ...LATEST, version: "1.0" }],
    ["releasedAt 없음", { ...LATEST, releasedAt: undefined }],
    ["null", null],
    ["배열", [LATEST]],
  ])("형식이 어긋나면 null (%s)", (_label, raw) => {
    expect(parseReleaseInfo(raw)).toBeNull();
  });
});

describe("releaseBaseUrl", () => {
  it("끝 슬래시와 공백을 지우고, 비어 있으면 null", () => {
    expect(releaseBaseUrl("https://i-eum.site/app/")).toBe("https://i-eum.site/app");
    expect(releaseBaseUrl("  https://i-eum.site/app ")).toBe("https://i-eum.site/app");
    expect(releaseBaseUrl("")).toBeNull();
    expect(releaseBaseUrl(undefined)).toBeNull();
  });
});

describe("kstDay", () => {
  it("한국 시간 기준 날짜를 돌려준다 (UTC 15시가 날짜 경계)", () => {
    expect(kstDay(new Date("2026-09-27T14:59:59Z"))).toBe("2026-09-27");
    expect(kstDay(new Date("2026-09-27T15:00:00Z"))).toBe("2026-09-28");
  });
});

describe("shouldAnnounceUpdate", () => {
  it("설치된 것보다 새 버전일 때만 알린다", () => {
    expect(shouldAnnounceUpdate(2, 1, null, "2026-09-27")).toBe(true);
    expect(shouldAnnounceUpdate(1, 1, null, "2026-09-27")).toBe(false);
    expect(shouldAnnounceUpdate(1, 2, null, "2026-09-27")).toBe(false);
  });

  it("같은 버전은 하루에 한 번만 알린다", () => {
    const notice = { versionCode: 2, day: "2026-09-27" };
    expect(shouldAnnounceUpdate(2, 1, notice, "2026-09-27")).toBe(false);
    expect(shouldAnnounceUpdate(2, 1, notice, "2026-09-28")).toBe(true);
  });

  it("오늘 알린 것보다 더 새 버전이 나오면 다시 알린다", () => {
    expect(shouldAnnounceUpdate(3, 1, { versionCode: 2, day: "2026-09-27" }, "2026-09-27")).toBe(true);
  });
});

describe("fetchLatestRelease", () => {
  it("${base}/latest.json을 읽어 해석한다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(200, JSON.stringify(LATEST)));

    const info = await fetchLatestRelease("https://i-eum.site/app", fetchFn as unknown as typeof fetch);

    expect(info?.versionCode).toBe(2);
    expect((fetchFn.mock.calls[0] as unknown as [string])[0]).toBe("https://i-eum.site/app/latest.json");
  });

  it("404·HTML·네트워크 실패는 모두 null (조용히 넘어간다)", async () => {
    const notFound = jest.fn(async () => fakeResponse(404, "<html>404</html>"));
    const html = jest.fn(async () => fakeResponse(200, "<!doctype html><html></html>"));
    const offline = jest.fn(async () => {
      throw new TypeError("Network request failed");
    });

    await expect(fetchLatestRelease("https://x/app", notFound as unknown as typeof fetch)).resolves.toBeNull();
    await expect(fetchLatestRelease("https://x/app", html as unknown as typeof fetch)).resolves.toBeNull();
    await expect(fetchLatestRelease("https://x/app", offline as unknown as typeof fetch)).resolves.toBeNull();
  });

  it("시간 안에 답이 없으면 끊고 null", async () => {
    jest.useFakeTimers();
    try {
      const hanging = jest.fn(
        (_url: string, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () => reject(new Error("aborted")));
          }),
      );

      const pending = fetchLatestRelease("https://x/app", hanging as unknown as typeof fetch, 5_000);
      jest.advanceTimersByTime(5_000);

      await expect(pending).resolves.toBeNull();
    } finally {
      jest.useRealTimers();
    }
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @ieum/mobile exec jest __tests__/appRelease.test.ts`
Expected: FAIL — `Cannot find module '@/services/appRelease'`

- [ ] **Step 3: `appRelease.ts` 작성**

```ts
import { z } from "zod";

/**
 * 앱 새 버전 확인에 쓰는 순수 함수들 (설계 §5.6). React Native에 의존하지 않는다.
 *
 * 서버 `${EXPO_PUBLIC_APP_RELEASE_URL}/latest.json` 형식(계약):
 * `{ version, versionCode, releasedAt, sha256, url, notes }` — 앱은 비교에 versionCode만,
 * 안내에 version만 쓴다. 형식이 어긋나거나 못 읽으면 null — 새 버전 알림은 **조용히** 실패한다.
 */
export const UPDATE_PROMPT_MESSAGE = "새 버전이 있어요. 설치 페이지를 열까요?";
export const UPDATE_OPEN_FAILED_MESSAGE = "설치 페이지를 열지 못했어요. 잠시 뒤 다시 눌러 주세요.";

const releaseInfoSchema = z.object({
  version: z.string().regex(/^\d+\.\d+\.\d+$/),
  versionCode: z.number().int().positive(),
  releasedAt: z.string().min(1),
  notes: z.string().optional(),
});

export type ReleaseInfo = z.infer<typeof releaseInfoSchema>;

export function parseReleaseInfo(raw: unknown): ReleaseInfo | null {
  const result = releaseInfoSchema.safeParse(raw);
  return result.success ? result.data : null;
}

/** 환경변수 값을 정리한다. 비어 있으면 확인 기능 자체를 끈다(개발 빌드 기본값) */
export function releaseBaseUrl(raw: string | undefined): string | null {
  const trimmed = (raw ?? "").trim().replace(/\/+$/, "");
  return trimmed.length > 0 ? trimmed : null;
}

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** "하루에 한 번"의 하루는 한국 날짜다 */
export function kstDay(now: Date): string {
  return new Date(now.getTime() + KST_OFFSET_MS).toISOString().slice(0, 10);
}

/** 마지막으로 알린 버전과 그 날짜 */
export interface UpdateNotice {
  versionCode: number;
  day: string;
}

export function shouldAnnounceUpdate(
  latest: number,
  installed: number,
  lastNotice: UpdateNotice | null,
  today: string,
): boolean {
  if (latest <= installed) return false;
  return !(lastNotice !== null && lastNotice.versionCode === latest && lastNotice.day === today);
}

/** 절대 던지지 않는다. 못 읽으면 null */
export async function fetchLatestRelease(
  baseUrl: string,
  fetchFn: typeof fetch = fetch,
  timeoutMs = 5_000,
): Promise<ReleaseInfo | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchFn(`${baseUrl}/latest.json`, {
      headers: { "Cache-Control": "no-cache" },
      signal: controller.signal,
    });
    if (!response.ok) return null;
    return parseReleaseInfo(JSON.parse(await response.text()));
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}
```

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @ieum/mobile exec jest __tests__/appRelease.test.ts`
Expected: PASS

- [ ] **Step 5: 흐름 실패 테스트 쓰기**

`front/apps/mobile/__tests__/appUpdateCheck.test.ts`:

```ts
import type { ReleaseInfo, UpdateNotice } from "@/services/appRelease";
import { checkForAppUpdate, checkForAppUpdateOnce, type UpdateCheckDeps } from "@/services/appUpdateCheck";
import { useAppUpdateStore, whenAppUpdateStoreHydrated } from "@/stores/appUpdateStore";

jest.mock("@react-native-async-storage/async-storage", () =>
  require("@react-native-async-storage/async-storage/jest/async-storage-mock"),
);
jest.mock("expo-router", () => ({ router: { push: jest.fn() } }));

const RELEASE: ReleaseInfo = { version: "1.1.0", versionCode: 2, releasedAt: "2026-09-27T12:00:00Z" };
const NOON_KST = new Date("2026-09-27T03:00:00Z");

function deps(overrides: Partial<UpdateCheckDeps> = {}) {
  let saved: UpdateNotice | null = null;
  const base: UpdateCheckDeps = {
    baseUrl: "https://i-eum.site/app",
    installedVersionCode: 1,
    fetchLatest: jest.fn(async () => RELEASE),
    now: () => NOON_KST,
    loadLastNotice: jest.fn(async () => saved),
    saveLastNotice: jest.fn((notice: UpdateNotice) => {
      saved = notice;
    }),
    showPrompt: jest.fn(),
  };
  return { ...base, ...overrides };
}

describe("checkForAppUpdate", () => {
  it("새 버전이면 알림 화면을 띄우고 오늘 알렸다고 적는다", async () => {
    const d = deps();

    await expect(checkForAppUpdate(d)).resolves.toBe(true);

    expect(d.fetchLatest).toHaveBeenCalledWith("https://i-eum.site/app");
    expect(d.showPrompt).toHaveBeenCalledWith(RELEASE);
    expect(d.saveLastNotice).toHaveBeenCalledWith({ versionCode: 2, day: "2026-09-27" });
  });

  it("같은 날 같은 버전은 다시 알리지 않고, 다음 날엔 다시 알린다", async () => {
    const d = deps();
    await checkForAppUpdate(d);

    await expect(checkForAppUpdate(d)).resolves.toBe(false);
    await expect(checkForAppUpdate({ ...d, now: () => new Date("2026-09-28T03:00:00Z") })).resolves.toBe(true);
    expect(d.showPrompt).toHaveBeenCalledTimes(2);
  });

  it("설치된 버전이 최신이면 알리지 않는다", async () => {
    const d = deps({ installedVersionCode: 2 });

    await expect(checkForAppUpdate(d)).resolves.toBe(false);
    expect(d.showPrompt).not.toHaveBeenCalled();
  });

  it("주소가 없거나 설치 버전을 모르면 서버를 부르지 않는다", async () => {
    const noUrl = deps({ baseUrl: null });
    const noVersion = deps({ installedVersionCode: null });

    await expect(checkForAppUpdate(noUrl)).resolves.toBe(false);
    await expect(checkForAppUpdate(noVersion)).resolves.toBe(false);
    expect(noUrl.fetchLatest).not.toHaveBeenCalled();
    expect(noVersion.fetchLatest).not.toHaveBeenCalled();
  });

  it("조회 실패·저장소 오류·화면 오류는 조용히 넘어간다", async () => {
    const noRelease = deps({ fetchLatest: jest.fn(async () => null) });
    const brokenStore = deps({
      loadLastNotice: jest.fn(async () => {
        throw new Error("storage");
      }),
    });
    const brokenPrompt = deps({
      showPrompt: jest.fn(() => {
        throw new Error("navigation");
      }),
    });

    await expect(checkForAppUpdate(noRelease)).resolves.toBe(false);
    await expect(checkForAppUpdate(brokenStore)).resolves.toBe(false);
    await expect(checkForAppUpdate(brokenPrompt)).resolves.toBe(false);
  });
});

describe("checkForAppUpdateOnce", () => {
  it("앱 실행마다 한 번만 확인한다", async () => {
    const d = deps();

    await checkForAppUpdateOnce(d);
    await checkForAppUpdateOnce(d);

    expect(d.fetchLatest).toHaveBeenCalledTimes(1);
  });
});

describe("appUpdateStore", () => {
  it("복원을 기다린 뒤 마지막 알림을 읽고 쓴다", async () => {
    await whenAppUpdateStoreHydrated();

    useAppUpdateStore.getState().setLastNotice({ versionCode: 2, day: "2026-09-27" });

    expect(useAppUpdateStore.getState().lastNotice).toEqual({ versionCode: 2, day: "2026-09-27" });
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `pnpm --filter @ieum/mobile exec jest __tests__/appUpdateCheck.test.ts`
Expected: FAIL — `Cannot find module '@/services/appUpdateCheck'`

- [ ] **Step 7: `stores/appUpdateStore.ts` 작성**

```ts
import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import AsyncStorage from "@react-native-async-storage/async-storage";
import type { UpdateNotice } from "@/services/appRelease";

/**
 * 새 버전 알림 기록. "같은 버전은 하루에 한 번"을 앱을 껐다 켜도 지키려고 기기에 남긴다.
 * 사용자 설정(settingsStore)과 수명이 달라 따로 둔다.
 */
interface AppUpdateState {
  lastNotice: UpdateNotice | null;
  setLastNotice: (notice: UpdateNotice) => void;
}

export const useAppUpdateStore = create<AppUpdateState>()(
  persist(
    (set) => ({
      lastNotice: null,
      setLastNotice: (lastNotice) => set({ lastNotice }),
    }),
    {
      name: "ieum-app-update",
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({ lastNotice: state.lastNotice }),
    },
  ),
);

/** AsyncStorage 복원은 비동기다. 복원 전에 읽으면 어제 알린 기록을 못 보고 또 알린다 */
export function whenAppUpdateStoreHydrated(): Promise<void> {
  if (useAppUpdateStore.persist.hasHydrated()) return Promise.resolve();
  return new Promise((resolve) => {
    const unsubscribe = useAppUpdateStore.persist.onFinishHydration(() => {
      unsubscribe();
      resolve();
    });
  });
}
```

- [ ] **Step 8: `services/appUpdateCheck.ts` 작성**

```ts
import Constants from "expo-constants";
import { router } from "expo-router";
import { useAppUpdateStore, whenAppUpdateStoreHydrated } from "@/stores/appUpdateStore";
import {
  fetchLatestRelease,
  kstDay,
  releaseBaseUrl,
  shouldAnnounceUpdate,
  type ReleaseInfo,
  type UpdateNotice,
} from "./appRelease";

/**
 * 앱을 켤 때 새 버전을 확인한다 (설계 §5.6).
 *
 * 설치된 빌드 번호는 `Constants.expoConfig.android.versionCode`로 읽는다 — 빌드 때 app.json이
 * 임베드되고 CNG prebuild가 같은 값으로 네이티브 versionCode를 쓰므로 같은 수다(expo-application을
 * 새로 들이지 않기 위해). iOS·Expo Go처럼 값이 없으면 확인하지 않는다.
 * 어떤 실패도 사용자에게 드러내지 않는다.
 */
export interface UpdateCheckDeps {
  baseUrl: string | null;
  installedVersionCode: number | null;
  fetchLatest: (baseUrl: string) => Promise<ReleaseInfo | null>;
  now: () => Date;
  loadLastNotice: () => Promise<UpdateNotice | null>;
  saveLastNotice: (notice: UpdateNotice) => void;
  showPrompt: (release: ReleaseInfo) => void;
}

function installedVersionCode(): number | null {
  const code = Constants.expoConfig?.android?.versionCode;
  return typeof code === "number" && Number.isInteger(code) ? code : null;
}

export function defaultUpdateCheckDeps(): UpdateCheckDeps {
  return {
    baseUrl: releaseBaseUrl(process.env.EXPO_PUBLIC_APP_RELEASE_URL),
    installedVersionCode: installedVersionCode(),
    fetchLatest: (baseUrl) => fetchLatestRelease(baseUrl),
    now: () => new Date(),
    loadLastNotice: async () => {
      await whenAppUpdateStoreHydrated();
      return useAppUpdateStore.getState().lastNotice;
    },
    saveLastNotice: (notice) => useAppUpdateStore.getState().setLastNotice(notice),
    showPrompt: (release) => router.push({ pathname: "/app-update", params: { version: release.version } }),
  };
}

/** @returns 알림 화면을 띄웠으면 true. 절대 던지지 않는다 */
export async function checkForAppUpdate(deps: UpdateCheckDeps): Promise<boolean> {
  try {
    if (deps.baseUrl === null || deps.installedVersionCode === null) return false;
    const latest = await deps.fetchLatest(deps.baseUrl);
    if (latest === null) return false;

    const today = kstDay(deps.now());
    const lastNotice = await deps.loadLastNotice();
    if (!shouldAnnounceUpdate(latest.versionCode, deps.installedVersionCode, lastNotice, today)) return false;

    // 화면을 띄우기 전에 적는다 — 사용자가 어느 버튼을 누르든(또는 앱을 꺼도) 오늘은 다시 묻지 않는다
    deps.saveLastNotice({ versionCode: latest.versionCode, day: today });
    deps.showPrompt(latest);
    return true;
  } catch {
    return false;
  }
}

let checkedThisLaunch = false;

/** 탭 화면이 다시 마운트돼도(로그아웃 → 로그인 등) 앱 실행당 한 번만 확인한다 */
export async function checkForAppUpdateOnce(deps?: UpdateCheckDeps): Promise<boolean> {
  if (checkedThisLaunch) return false;
  checkedThisLaunch = true;
  return checkForAppUpdate(deps ?? defaultUpdateCheckDeps());
}
```

- [ ] **Step 9: 통과 확인**

Run: `pnpm --filter @ieum/mobile exec jest __tests__/appRelease.test.ts __tests__/appUpdateCheck.test.ts && pnpm --filter @ieum/mobile typecheck`
Expected: PASS. typecheck가 `pathname: "/app-update"`에서 "not assignable" 오류를 내면 작업 폴더에 오래된 `.expo/types/router.d.ts`가 있는 것이다(새 워크트리에는 없다) — Task 4에서 화면 파일을 만든 뒤 다시 돌리면 된다. 그 경우에만 이 Step의 typecheck를 Task 4 Step 3으로 미룬다

- [ ] **Step 10: 커밋**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front
git add front/apps/mobile/src/services/appRelease.ts front/apps/mobile/src/stores/appUpdateStore.ts \
  front/apps/mobile/src/services/appUpdateCheck.ts \
  front/apps/mobile/__tests__/appRelease.test.ts front/apps/mobile/__tests__/appUpdateCheck.test.ts
git commit -m "feat(release): 앱을 켤 때 latest.json으로 새 버전을 확인한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 새 버전 알림 화면과 연결

**Files:**
- Create: `front/apps/mobile/src/app/app-update.tsx`
- Modify: `front/apps/mobile/src/app/(tabs)/_layout.tsx:1-12`

**Interfaces:**
- Consumes: Task 3의 `checkForAppUpdateOnce()`, `releaseBaseUrl`, `UPDATE_PROMPT_MESSAGE`, `UPDATE_OPEN_FAILED_MESSAGE`. 라우트 파라미터 `version`
- Produces: 라우트 `/app-update` (루트 Stack이 파일로 자동 등록)

탭 레이아웃에서 확인하는 이유: 루트 레이아웃에서 돌리면 스플래시(`app/index.tsx`)가 1.5초 뒤 `router.replace`로 알림 화면을 덮어 버린다. 탭은 로그인·온보딩을 마친 뒤의 첫 안정 화면이다. 로그인하지 않은 사용자는 로그인한 뒤에 알림을 받는다.

- [ ] **Step 1: 화면 작성**

`front/apps/mobile/src/app/app-update.tsx`:

```tsx
import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import * as Linking from "expo-linking";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { announce, hapticError } from "@/a11y";
import { releaseBaseUrl, UPDATE_OPEN_FAILED_MESSAGE, UPDATE_PROMPT_MESSAGE } from "@/services/appRelease";
import { useFontScale, useTheme } from "@/theme";

/**
 * 새 버전 알림 — 앱을 켠 뒤 탭 화면에서 한 번(같은 버전은 하루 한 번) 뜬다.
 * 설치는 브라우저의 다운로드 페이지(`EXPO_PUBLIC_APP_RELEASE_URL`)에서 한다. 앱이 APK를 직접 받지 않는다.
 */
export default function AppUpdate() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { version } = useLocalSearchParams<{ version?: string }>();
  const pageUrl = releaseBaseUrl(process.env.EXPO_PUBLIC_APP_RELEASE_URL);

  useEffect(() => {
    announce(UPDATE_PROMPT_MESSAGE);
  }, []);

  const close = () => {
    if (router.canGoBack()) router.back();
    else router.replace("/(tabs)");
  };

  const open = async () => {
    if (pageUrl === null) {
      close();
      return;
    }
    try {
      await Linking.openURL(pageUrl);
      close();
    } catch {
      void hapticError();
      announce(UPDATE_OPEN_FAILED_MESSAGE);
    }
  };

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={110} accessibilityLabel="이음 캐릭터" />
        <Text
          accessibilityRole="header"
          style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}
        >
          새 버전이 있어요
        </Text>
        <Text style={{ fontSize: 18 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
          {version ? `이음 ${version} 버전을 설치할 수 있어요.` : "새 이음 앱을 설치할 수 있어요."}
          {"\n"}설치 페이지를 열까요?
        </Text>
      </View>
      <View style={styles.actions}>
        <AppButton
          label="설치 페이지 열기"
          accessibilityHint="브라우저에서 이음 앱 설치 페이지를 열어요"
          onPress={() => void open()}
        />
        <AppButton label="나중에" variant="secondary" accessibilityHint="오늘은 다시 묻지 않아요" onPress={close} />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
  actions: { gap: 12 },
});
```

- [ ] **Step 2: 탭 레이아웃에서 한 번 확인**

`front/apps/mobile/src/app/(tabs)/_layout.tsx` 맨 위 import를 바꾸고, `TabsLayout` 첫 줄 다음에 effect를 더한다:

```tsx
import { useEffect } from "react";
import { Tabs } from "expo-router";
import { checkForAppUpdateOnce } from "@/services/appUpdateCheck";
import { useTheme } from "@/theme";
```

```tsx
export default function TabsLayout() {
  const theme = useTheme();

  // 로그인·온보딩을 마친 첫 안정 화면에서 새 버전을 확인한다(실행당 한 번, 실패는 조용히)
  useEffect(() => {
    void checkForAppUpdateOnce();
  }, []);

  return (
```

(나머지 `<Tabs …>` 반환부는 그대로.)

- [ ] **Step 3: 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front/front
pnpm --filter @ieum/mobile typecheck
pnpm --filter @ieum/mobile exec jest
```

Expected: PASS. (화면 동작·TalkBack 확인은 Task 8)

- [ ] **Step 4: 커밋**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front
git add "front/apps/mobile/src/app/app-update.tsx" "front/apps/mobile/src/app/(tabs)/_layout.tsx"
git commit -m "feat(release): 새 버전 알림 화면을 추가하고 탭 화면에서 한 번 확인한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 릴리스 서명 config plugin과 versionCode

**Files:**
- Create: `front/apps/mobile/plugins/withReleaseSigning.js`
- Modify: `front/apps/mobile/app.json` (`android.versionCode`, `plugins`)
- Test: `front/apps/mobile/__tests__/withReleaseSigning.test.ts`

**Interfaces:**
- Consumes: gradle 속성 `IEUM_UPLOAD_STORE_FILE`, `IEUM_UPLOAD_KEY_ALIAS`, `IEUM_UPLOAD_STORE_PASSWORD`, `IEUM_UPLOAD_KEY_PASSWORD` (PC `~/.gradle/gradle.properties`, 경로는 절대 경로 권장)
- Produces: `module.exports = withReleaseSigning`(config plugin), `module.exports.applyReleaseSigning(contents: string): string`(순수 변환). 결과: release buildType이 `signingConfigs.release`를 쓰고, 이름에 `release`가 든 작업(`assembleRelease`·`bundleRelease`·`installRelease` 등)을 요청했는데 속성이 하나라도 없으면 **설정 단계에서** `GradleException`. 디버그 빌드(`expo run:android`)는 속성 없이도 그대로 된다. infra 계획의 `scripts/release-app.sh`가 `assembleRelease` 산출물 `android/app/build/outputs/apk/release/app-release.apk`를 가져간다

설계 메모: 실패 판정을 `gradle.startParameter.taskNames`로 하는 이유 — 설정 캐시·taskGraph 콜백에 의존하지 않고, 작업을 고르기 전(설정 단계)에 멈춰 디버그 키로 서명된 APK가 만들어질 틈이 없다. 이름에 release가 없는 `./gradlew build`로 release가 끌려 들어와도 `signingConfigs.release`에 `storeFile`이 없으므로 AGP의 `validateSigningRelease`가 실패한다(디버그 키로 떨어지지 않는다).

- [ ] **Step 1: 실패 테스트 쓰기**

`front/apps/mobile/__tests__/withReleaseSigning.test.ts`:

```ts
// 플러그인은 prebuild(Node)에서 도는 CommonJS다. 순수 변환만 불러 검증한다
const { applyReleaseSigning } = require("../plugins/withReleaseSigning") as {
  applyReleaseSigning: (contents: string) => string;
};

/** expo prebuild(SDK 57)가 만드는 android/app/build.gradle의 해당 부분 그대로 */
const TEMPLATE = `apply plugin: "com.android.application"

def jscFlavor = 'io.github.react-native-community:jsc-android:2026004.+'

android {
    namespace 'com.ieum.mobile'
    defaultConfig {
        applicationId 'com.ieum.mobile'
        versionCode 1
        versionName "0.1.0"
    }
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }
    buildTypes {
        debug {
            signingConfig signingConfigs.debug
        }
        release {
            // Caution! In production, you need to generate your own keystore file.
            // see https://reactnative.dev/docs/signed-apk-android.
            signingConfig signingConfigs.debug
            def enableShrinkResources = findProperty('android.enableShrinkResourcesInReleaseBuilds') ?: 'false'
            shrinkResources enableShrinkResources.toBoolean()
        }
    }
}
`;

function buildTypeBlock(contents: string, name: "debug" | "release"): string {
  const buildTypes = contents.slice(contents.indexOf("    buildTypes {"));
  const start = buildTypes.indexOf(`        ${name} {`);
  return buildTypes.slice(start, buildTypes.indexOf("\n        }", start));
}

describe("applyReleaseSigning", () => {
  it("release buildType만 release 서명으로 바꾸고 debug는 그대로 둔다", () => {
    const result = applyReleaseSigning(TEMPLATE);

    expect(buildTypeBlock(result, "release")).toContain("signingConfig signingConfigs.release");
    expect(buildTypeBlock(result, "release")).not.toContain("signingConfigs.debug");
    expect(buildTypeBlock(result, "debug")).toContain("signingConfig signingConfigs.debug");
  });

  it("signingConfigs에 gradle 속성 네 개를 읽는 release 설정을 넣는다", () => {
    const result = applyReleaseSigning(TEMPLATE);
    const signing = result.slice(result.indexOf("    signingConfigs {"), result.indexOf("    buildTypes {"));

    expect(signing).toContain("        release {");
    expect(signing).toContain("storeFile file(findProperty('IEUM_UPLOAD_STORE_FILE'))");
    expect(signing).toContain("storePassword findProperty('IEUM_UPLOAD_STORE_PASSWORD')");
    expect(signing).toContain("keyAlias findProperty('IEUM_UPLOAD_KEY_ALIAS')");
    expect(signing).toContain("keyPassword findProperty('IEUM_UPLOAD_KEY_PASSWORD')");
    // 디버그 키 설정은 남아 있어야 개발 빌드가 된다
    expect(signing).toContain("storeFile file('debug.keystore')");
  });

  it("android 블록 앞에서 release 작업을 요청했는데 속성이 없으면 빌드를 멈춘다", () => {
    const result = applyReleaseSigning(TEMPLATE);
    const prelude = result.slice(0, result.indexOf("\nandroid {"));

    expect(prelude).toContain("gradle.startParameter.taskNames.any { it.toLowerCase().contains('release') }");
    expect(prelude).toContain("throw new GradleException(");
    for (const key of ["IEUM_UPLOAD_STORE_FILE", "IEUM_UPLOAD_KEY_ALIAS", "IEUM_UPLOAD_STORE_PASSWORD", "IEUM_UPLOAD_KEY_PASSWORD"]) {
      expect(prelude).toContain(`'${key}'`);
    }
  });

  it("두 번 적용해도 한 번 적용한 것과 같다 (prebuild 반복)", () => {
    const once = applyReleaseSigning(TEMPLATE);
    expect(applyReleaseSigning(once)).toBe(once);
  });

  it("템플릿이 바뀌어 기준 위치를 못 찾으면 조용히 넘어가지 않고 실패한다", () => {
    expect(() => applyReleaseSigning(TEMPLATE.replace("    signingConfigs {", "    signing {"))).toThrow(
      "withReleaseSigning",
    );
    expect(() =>
      applyReleaseSigning(TEMPLATE.replace(/(\n {8}release \{[^}]*?)signingConfig signingConfigs\.debug/, "$1")),
    ).toThrow("withReleaseSigning");
    expect(() => applyReleaseSigning(TEMPLATE.replace("\nandroid {", "\nandroidx {"))).toThrow("withReleaseSigning");
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @ieum/mobile exec jest __tests__/withReleaseSigning.test.ts`
Expected: FAIL — `Cannot find module '../plugins/withReleaseSigning'`

- [ ] **Step 3: 플러그인 작성**

`front/apps/mobile/plugins/withReleaseSigning.js`:

```js
const { withAppBuildGradle } = require("expo/config-plugins");

/**
 * 릴리스 APK를 우리 keystore로만 서명한다 (설계 2026-09-27-deploy-rebuild-design.md §5.2).
 *
 * expo prebuild가 만드는 app/build.gradle은 release도 디버그 키로 서명한다. 그 APK가 나가면
 * 나중에 진짜 키로 서명한 업데이트를 기존 설치본 위에 깔 수 없다. 그래서
 *   1) signingConfigs 에 release 를 넣어 ~/.gradle/gradle.properties 의 IEUM_UPLOAD_* 를 읽고
 *   2) release buildType 이 그것을 쓰게 하며
 *   3) 이름에 release 가 든 작업을 요청했는데 속성이 하나라도 없으면 설정 단계에서 멈춘다.
 * 디버그 빌드(expo run:android)는 속성 없이도 그대로 된다.
 *
 *   IEUM_UPLOAD_STORE_FILE=C:/Users/<me>/keys/ieum-upload.jks   (절대 경로 권장)
 *   IEUM_UPLOAD_KEY_ALIAS=ieum-upload
 *   IEUM_UPLOAD_STORE_PASSWORD=...
 *   IEUM_UPLOAD_KEY_PASSWORD=...
 *
 * keystore·비밀번호는 저장소에 두지 않는다. 생성물(android/)을 직접 고치면 prebuild 때 날아가므로 plugin 으로 둔다.
 */
const MARKER = "IEUM_UPLOAD_STORE_FILE";

const PRELUDE = `
// 릴리스 서명 — ~/.gradle/gradle.properties 의 IEUM_UPLOAD_* (plugins/withReleaseSigning.js)
def ieumSigningKeys = ['IEUM_UPLOAD_STORE_FILE', 'IEUM_UPLOAD_KEY_ALIAS', 'IEUM_UPLOAD_STORE_PASSWORD', 'IEUM_UPLOAD_KEY_PASSWORD']
def ieumSigningMissing = ieumSigningKeys.findAll { !findProperty(it) }
def ieumReleaseRequested = gradle.startParameter.taskNames.any { it.toLowerCase().contains('release') }
if (ieumReleaseRequested && !ieumSigningMissing.isEmpty()) {
    throw new GradleException("릴리스 서명 정보가 없습니다: \${ieumSigningMissing.join(', ')}. ~/.gradle/gradle.properties 에 넣어 주세요 (plugins/withReleaseSigning.js)")
}
`;

const RELEASE_SIGNING = `        release {
            if (ieumSigningMissing.isEmpty()) {
                storeFile file(findProperty('IEUM_UPLOAD_STORE_FILE'))
                storePassword findProperty('IEUM_UPLOAD_STORE_PASSWORD')
                keyAlias findProperty('IEUM_UPLOAD_KEY_ALIAS')
                keyPassword findProperty('IEUM_UPLOAD_KEY_PASSWORD')
            }
        }
`;

const ANDROID_ANCHOR = "\nandroid {";
const SIGNING_ANCHOR = "    signingConfigs {\n";
/** buildTypes 의 release 블록 안, 첫 '}' 전에 나오는 디버그 서명 줄 (주석에는 '}'가 없다) */
const RELEASE_DEBUG_SIGNING = /(\n {8}release \{[^}]*?)signingConfig signingConfigs\.debug/;

function fail(what) {
  throw new Error(`withReleaseSigning: app/build.gradle 에서 ${what} 을(를) 찾지 못했습니다. expo 템플릿이 바뀌었는지 확인하세요.`);
}

/** app/build.gradle 문자열 변환. 순수 함수라 jest 로 검증한다 (__tests__/withReleaseSigning.test.ts) */
function applyReleaseSigning(contents) {
  if (contents.includes(MARKER)) return contents; // 이미 들어가 있다

  if (!RELEASE_DEBUG_SIGNING.test(contents)) fail("release buildType 의 signingConfig");
  if (!contents.includes(SIGNING_ANCHOR)) fail("signingConfigs 블록");
  if (!contents.includes(ANDROID_ANCHOR)) fail("android 블록");

  return contents
    .replace(RELEASE_DEBUG_SIGNING, "$1signingConfig signingConfigs.release")
    .replace(SIGNING_ANCHOR, SIGNING_ANCHOR + RELEASE_SIGNING)
    .replace(ANDROID_ANCHOR, PRELUDE + ANDROID_ANCHOR);
}

function withReleaseSigning(config) {
  return withAppBuildGradle(config, (mod) => {
    if (mod.modResults.language !== "groovy") {
      throw new Error("withReleaseSigning: app/build.gradle 이 Groovy 가 아닙니다.");
    }
    mod.modResults.contents = applyReleaseSigning(mod.modResults.contents);
    return mod;
  });
}

module.exports = withReleaseSigning;
module.exports.applyReleaseSigning = applyReleaseSigning;
```

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @ieum/mobile exec jest __tests__/withReleaseSigning.test.ts`
Expected: PASS (5건)

- [ ] **Step 5: `app.json` 수정**

`expo.android`에 `"versionCode": 1`을 `"package"` 바로 다음에 넣고, `plugins`의 `"./plugins/withCmakeNinja.js"` 다음 줄에 `"./plugins/withReleaseSigning.js"`를 넣는다:

```json
    "android": {
      "package": "com.ieum.mobile",
      "versionCode": 1,
      "edgeToEdgeEnabled": true,
```

```json
      "./plugins/withCmakeNinja.js",
      "./plugins/withReleaseSigning.js",
```

- [ ] **Step 6: 실제 prebuild·gradle로 확인 (Android SDK·JDK가 있는 PC)**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front/front/apps/mobile
CI=1 npx expo prebuild -p android --clean --no-install
grep -n "IEUM_UPLOAD\|signingConfig signingConfigs\|GradleException" android/app/build.gradle
cd android
./gradlew :app:assembleDebug --dry-run
./gradlew :app:assembleRelease --dry-run
```

Expected:
- grep: 앞부분에 `def ieumSigningKeys …`·`throw new GradleException(`, `signingConfigs` 안에 `storeFile file(findProperty('IEUM_UPLOAD_STORE_FILE'))`, buildTypes에 `signingConfig signingConfigs.debug`(debug) 한 번과 `signingConfig signingConfigs.release`(release) 한 번
- `assembleDebug --dry-run`: `BUILD SUCCESSFUL`
- `assembleRelease --dry-run`: 이 PC의 `~/.gradle/gradle.properties`에 IEUM_UPLOAD_*가 없으면 `릴리스 서명 정보가 없습니다: IEUM_UPLOAD_STORE_FILE, …`로 실패, 있으면 `BUILD SUCCESSFUL`
- `android/`는 gitignore 대상이다(커밋되지 않음). Android 도구가 없어 이 Step을 못 돌리면 그 사실을 보고하고 Task 8에서 사람과 함께 한다

- [ ] **Step 7: 커밋**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front
git status --short front/apps/mobile   # android/ 가 보이지 않아야 한다
git add front/apps/mobile/plugins/withReleaseSigning.js front/apps/mobile/app.json front/apps/mobile/__tests__/withReleaseSigning.test.ts
git commit -m "build(release): 릴리스 APK를 업로드 키로만 서명하는 config plugin을 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 웹 — `latest.json` 해석과 조회

**Files:**
- Create: `front/apps/web/src/domains/app-release/model/releaseInfo.ts`
- Create: `front/apps/web/src/domains/app-release/api/releaseApi.ts`
- Test: `front/apps/web/src/domains/app-release/model/__tests__/releaseInfo.test.ts`, `front/apps/web/src/domains/app-release/api/__tests__/releaseApi.test.ts`

**Interfaces:**
- Consumes: 없음
- Produces (Task 7이 쓴다):
  - `interface ReleaseInfo { version: string; versionCode: number; releasedAt: string; notes: string | null }`
  - `parseReleaseInfo(raw: unknown): ReleaseInfo | null`
  - `formatReleaseDate(iso: string): string` — 한국 날짜 `2026년 9월 27일`
  - 상수 `RELEASE_INFO_PATH = '/app/latest.json'`, `APK_PATH = '/app/ieum.apk'`
  - `fetchReleaseInfo(fetchFn?: typeof fetch): Promise<ReleaseInfo | null>` — 절대 던지지 않는다

- [ ] **Step 1: 실패 테스트 쓰기**

`front/apps/web/src/domains/app-release/model/__tests__/releaseInfo.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { formatReleaseDate, parseReleaseInfo } from '../releaseInfo'

const LATEST = {
  version: '1.0.0',
  versionCode: 1,
  releasedAt: '2026-09-27T12:00:00Z',
  sha256: 'ab'.repeat(32),
  url: 'https://i-eum.site/app/ieum.apk',
  notes: '첫 배포',
}

describe('parseReleaseInfo', () => {
  it('계약 형식에서 화면에 쓰는 필드만 읽는다', () => {
    expect(parseReleaseInfo(LATEST)).toEqual({
      version: '1.0.0',
      versionCode: 1,
      releasedAt: '2026-09-27T12:00:00Z',
      notes: '첫 배포',
    })
  })

  it('notes가 없거나 비었거나 문자열이 아니면 null로 둔다 (정보 자체는 유효)', () => {
    expect(parseReleaseInfo({ ...LATEST, notes: undefined })?.notes).toBeNull()
    expect(parseReleaseInfo({ ...LATEST, notes: '   ' })?.notes).toBeNull()
    expect(parseReleaseInfo({ ...LATEST, notes: 3 })?.notes).toBeNull()
  })

  it.each([
    ['version이 semver가 아님', { ...LATEST, version: '1.0' }],
    ['version이 숫자', { ...LATEST, version: 1 }],
    ['versionCode가 0', { ...LATEST, versionCode: 0 }],
    ['versionCode가 문자열', { ...LATEST, versionCode: '1' }],
    ['releasedAt이 날짜가 아님', { ...LATEST, releasedAt: '어제' }],
    ['null', null],
    ['배열', [LATEST]],
    ['문자열', '<!doctype html>'],
  ])('형식이 어긋나면 null (%s)', (_label, raw) => {
    expect(parseReleaseInfo(raw)).toBeNull()
  })
})

describe('formatReleaseDate', () => {
  it('한국 날짜로 적는다 (UTC 15시가 날짜 경계)', () => {
    expect(formatReleaseDate('2026-09-27T12:00:00Z')).toBe('2026년 9월 27일')
    expect(formatReleaseDate('2026-09-27T15:00:00Z')).toBe('2026년 9월 28일')
  })
})
```

`front/apps/web/src/domains/app-release/api/__tests__/releaseApi.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { fetchReleaseInfo } from '../releaseApi'

const LATEST = { version: '1.0.0', versionCode: 1, releasedAt: '2026-09-27T12:00:00Z', notes: '첫 배포' }

describe('fetchReleaseInfo', () => {
  it('같은 출처의 /app/latest.json을 캐시 없이 읽는다', async () => {
    const fetchFn = vi.fn(async () => new Response(JSON.stringify(LATEST), { status: 200 }))

    const info = await fetchReleaseInfo(fetchFn as unknown as typeof fetch)

    expect(info?.version).toBe('1.0.0')
    expect(fetchFn).toHaveBeenCalledWith('/app/latest.json', { cache: 'no-store' })
  })

  it('파일이 없으면(404) null', async () => {
    const fetchFn = vi.fn(async () => new Response('not found', { status: 404 }))
    await expect(fetchReleaseInfo(fetchFn as unknown as typeof fetch)).resolves.toBeNull()
  })

  it('SPA 폴백 HTML(호스트 nginx 설정 전·개발 서버)이면 null', async () => {
    const fetchFn = vi.fn(async () => new Response('<!doctype html><html></html>', { status: 200 }))
    await expect(fetchReleaseInfo(fetchFn as unknown as typeof fetch)).resolves.toBeNull()
  })

  it('네트워크 실패도 던지지 않고 null', async () => {
    const fetchFn = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    await expect(fetchReleaseInfo(fetchFn as unknown as typeof fetch)).resolves.toBeNull()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd C:/Users/SSAFY/Desktop/wt-deploy-front/front && pnpm --filter @ieum/web test`
Expected: FAIL — `Failed to resolve import "../releaseInfo"` / `"../releaseApi"`

- [ ] **Step 3: `model/releaseInfo.ts` 작성**

```ts
/**
 * 앱 배포 정보 `latest.json` (설계 §5.4·§5.5, 계약 형식):
 * `{ version, versionCode, releasedAt, sha256, url, notes }` — 페이지는 버전·배포일·메모만 쓴다.
 * 파일은 호스트 nginx가 `/app/` 아래에서 web보다 먼저 제공한다(같은 출처).
 */
export interface ReleaseInfo {
  version: string
  versionCode: number
  releasedAt: string
  notes: string | null
}

export const RELEASE_INFO_PATH = '/app/latest.json'
export const APK_PATH = '/app/ieum.apk'

const SEMVER = /^\d+\.\d+\.\d+$/

export function parseReleaseInfo(raw: unknown): ReleaseInfo | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null
  const { version, versionCode, releasedAt, notes } = raw as Record<string, unknown>
  if (typeof version !== 'string' || !SEMVER.test(version)) return null
  if (typeof versionCode !== 'number' || !Number.isInteger(versionCode) || versionCode < 1) return null
  if (typeof releasedAt !== 'string' || Number.isNaN(Date.parse(releasedAt))) return null
  return {
    version,
    versionCode,
    releasedAt,
    notes: typeof notes === 'string' && notes.trim() !== '' ? notes.trim() : null,
  }
}

const KST_OFFSET_MS = 9 * 60 * 60 * 1000

/** 보는 사람의 시간대와 무관하게 한국 날짜로 적는다 */
export function formatReleaseDate(iso: string): string {
  const kst = new Date(Date.parse(iso) + KST_OFFSET_MS)
  return `${kst.getUTCFullYear()}년 ${kst.getUTCMonth() + 1}월 ${kst.getUTCDate()}일`
}
```

- [ ] **Step 4: `api/releaseApi.ts` 작성**

```ts
import { parseReleaseInfo, RELEASE_INFO_PATH, type ReleaseInfo } from '../model/releaseInfo'

/**
 * 배포 정보를 읽는다. `/api/v1`이 아니라 정적 파일이라 api-client를 쓰지 않는다(인증·JSON 오류 계약이 없다).
 * 파일이 없거나(첫 배포 전), SPA 폴백 HTML이 오거나, 네트워크가 끊겨도 던지지 않고 null — 페이지는 "준비 중이에요".
 */
export async function fetchReleaseInfo(fetchFn: typeof fetch = fetch): Promise<ReleaseInfo | null> {
  try {
    const response = await fetchFn(RELEASE_INFO_PATH, { cache: 'no-store' })
    if (!response.ok) return null
    return parseReleaseInfo(JSON.parse(await response.text()))
  } catch {
    return null
  }
}
```

- [ ] **Step 5: 통과 확인**

Run: `pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint`
Expected: PASS, lint 경고 0

- [ ] **Step 6: 커밋**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front
git add front/apps/web/src/domains/app-release
git commit -m "feat(release): 강사 웹이 앱 배포 정보 latest.json을 읽는다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 웹 — 다운로드 페이지·QR·라우트·개발 프록시

**Files:**
- Create: `front/apps/web/scripts/generate-app-qr.mjs`
- Create: `front/apps/web/public/app-qr.svg` (스크립트 산출물)
- Create: `front/apps/web/src/pages/AppDownloadPage.tsx`
- Modify: `front/apps/web/src/app/router.tsx` (import, `/app` 라우트)
- Modify: `front/apps/web/vite.config.ts` (`server.proxy`)
- Modify: `front/apps/web/README.md` (섹션 추가)

**Interfaces:**
- Consumes: Task 6의 `fetchReleaseInfo`, `formatReleaseDate`, `APK_PATH`, `ReleaseInfo`
- Produces: 공개 라우트 `/app`, 정적 파일 `/app-qr.svg`(웹 컨테이너 nginx가 `try_files $uri`로 제공, `/app/` 접두어가 아니므로 호스트 nginx의 앱 파일 location과 겹치지 않는다)

QR 결정: 웹에 QR 의존성이 없다. 빌드 때 만들면 운영 이미지 빌드(`web-deps`는 `--filter "@ieum/web..."`만 설치)에 `toqr`이 없다. 그래서 워크스페이스에 이미 있는 `toqr`로 **한 번** 만들어 SVG를 커밋하고, 주소가 바뀔 때만 스크립트를 다시 돌린다. 계획 작성 시 같은 로직으로 만든 행렬을 검증했다: 29×29(버전 3), 오류 정정 H, 바이트 모드, 두 블록 RS 신드롬 0, 해독 결과 `https://i-eum.site/app`. 결과는 결정적이라 다시 돌려도 파일이 같다.

- [ ] **Step 1: QR 생성 스크립트 작성·실행**

`front/apps/web/scripts/generate-app-qr.mjs`:

```js
/**
 * 다운로드 페이지(/app)에 거는 QR 이미지 public/app-qr.svg 를 만든다. 주소가 바뀔 때만 다시 실행한다.
 *
 *   node front/apps/web/scripts/generate-app-qr.mjs
 *
 * QR 인코더를 web 의존성으로 들이지 않고, 워크스페이스에 이미 있는 toqr(Expo CLI가 개발 서버 QR에 쓰는 것.
 * node-linker=hoisted 라 front/node_modules/toqr)을 빌려 쓴다. 빌드 때 실행하지 않으므로 운영 이미지와 무관하다.
 * 결과는 결정적이다 — 같은 주소면 같은 파일이 나온다.
 */
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'

const PAGE_URL = 'https://i-eum.site/app'
const EC_LEVEL_H = 2 // toqr ECLevel: M=0, L=1, H=2, Q=3. 수업 화면에 띄워 멀리서 찍으므로 가장 높은 H
const QUIET_ZONE = 4 // 표준 여백 4칸

const require = createRequire(new URL('../../../package.json', import.meta.url))
const { toQR } = require('toqr')

const modules = toQR(PAGE_URL, EC_LEVEL_H) // 1 = 검은 칸, 행 우선
const size = Math.sqrt(modules.length) | 0
if (size * size !== modules.length) throw new Error(`QR 행렬이 정사각형이 아니다: ${modules.length}`)

let path = ''
for (let y = 0; y < size; y += 1) {
  for (let x = 0; x < size; x += 1) {
    if (modules[y * size + x]) path += `M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z`
  }
}
const extent = size + QUIET_ZONE * 2
const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${extent} ${extent}" shape-rendering="crispEdges">` +
  `<!-- ${PAGE_URL} · scripts/generate-app-qr.mjs 로 생성 -->` +
  `<rect width="${extent}" height="${extent}" fill="#fff"/><path fill="#000" d="${path}"/></svg>\n`

writeFileSync(new URL('../public/app-qr.svg', import.meta.url), svg)
console.log(`${PAGE_URL} → public/app-qr.svg (${size}x${size}, 여백 ${QUIET_ZONE})`)
```

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front
node front/apps/web/scripts/generate-app-qr.mjs && sha256sum front/apps/web/public/app-qr.svg
node front/apps/web/scripts/generate-app-qr.mjs && sha256sum front/apps/web/public/app-qr.svg
git status --short front/apps/web
```

Expected: `https://i-eum.site/app → public/app-qr.svg (29x29, 여백 4)`, 파일 약 6KB, 두 해시가 같다(결정적), `?? front/apps/web/public/`·`?? front/apps/web/scripts/`

- [ ] **Step 2: 페이지 작성**

`front/apps/web/src/pages/AppDownloadPage.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { fetchReleaseInfo } from '../domains/app-release/api/releaseApi'
import { APK_PATH, formatReleaseDate, type ReleaseInfo } from '../domains/app-release/model/releaseInfo'
import { buttonStyle, cardStyle } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

type LoadState = { kind: 'loading' } | { kind: 'ready'; info: ReleaseInfo } | { kind: 'unavailable' }

const INSTALL_STEPS = [
  {
    title: '알 수 없는 앱 설치 허용',
    body: '내려받은 ieum.apk를 열면 설치를 허용할지 물어요. 설정으로 가서 지금 쓰는 브라우저의 "이 출처 허용"을 켜 주세요.',
  },
  {
    title: 'Play 프로텍트 경고',
    body: '"Play 프로텍트" 경고가 나오면 "세부정보 더보기"를 누르고 "무시하고 설치"를 눌러 주세요. Play 스토어 밖에서 받은 앱이라 나오는 경고예요.',
  },
  {
    title: '첫 실행',
    body: '설치가 끝나면 "열기"를 눌러 이음을 켜세요. 로그인한 뒤 선생님이 알려 준 참여 코드로 수업에 들어가요.',
  },
] as const

const sectionTitle = { margin: 0, fontSize: 18, fontWeight: 800 } as const

/**
 * 학습자 앱 다운로드 페이지 `/app` (설계 §5.5). 로그인 없이 연다 — 인증 가드 밖 라우트.
 * 강사가 수업 화면에 QR을 띄우면 학생이 폰으로 찍어 이 페이지를 연다.
 * APK·latest.json은 호스트 nginx가 `/app/` 아래에서 먼저 제공한다.
 */
export function AppDownloadPage() {
  const [state, setState] = useState<LoadState>({ kind: 'loading' })

  useEffect(() => {
    const previousTitle = document.title
    document.title = '이음 앱 내려받기'
    let cancelled = false
    void fetchReleaseInfo().then((info) => {
      if (!cancelled) setState(info ? { kind: 'ready', info } : { kind: 'unavailable' })
    })
    return () => {
      cancelled = true
      document.title = previousTitle
    }
  }, [])

  return (
    <main
      style={{
        minHeight: '100vh',
        background: theme.color.canvas,
        fontFamily: theme.font.sans,
        color: theme.color.text,
        padding: '32px 16px',
        boxSizing: 'border-box',
      }}
    >
      <div style={{ maxWidth: 560, margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
        <section style={{ ...cardStyle, padding: 28, display: 'flex', flexDirection: 'column', gap: 16 }}>
          <h1 style={{ margin: 0, fontSize: 28, fontWeight: 900, letterSpacing: '-.02em' }}>이음 학습자 앱</h1>
          <p style={{ margin: 0, fontSize: 15, lineHeight: 1.7, color: theme.color.textSub }}>
            점자를 배우는 안드로이드 앱이에요. 이 페이지를 휴대폰에서 열고 아래 버튼을 눌러 설치해요.
          </p>

          <div role="status" aria-live="polite" style={{ fontSize: 15, lineHeight: 1.7 }}>
            {state.kind === 'loading' && <p style={{ margin: 0 }}>버전 정보를 확인하고 있어요.</p>}
            {state.kind === 'unavailable' && (
              <>
                <p style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>준비 중이에요</p>
                <p style={{ margin: 0, color: theme.color.textSub }}>앱 파일을 올리는 중이에요. 잠시 뒤 다시 들어와 주세요.</p>
              </>
            )}
            {state.kind === 'ready' && (
              <>
                <p style={{ margin: 0, fontWeight: 700 }}>
                  버전 {state.info.version} · {formatReleaseDate(state.info.releasedAt)} 배포
                </p>
                {state.info.notes && (
                  <p style={{ margin: 0, color: theme.color.textSub }}>바뀐 점: {state.info.notes}</p>
                )}
              </>
            )}
          </div>

          {state.kind === 'ready' && (
            <a
              href={APK_PATH}
              download="ieum.apk"
              style={{
                ...buttonStyle('primary'),
                width: '100%',
                minHeight: 64,
                fontSize: 20,
                boxSizing: 'border-box',
              }}
            >
              이음 앱 내려받기
            </a>
          )}
        </section>

        <section style={{ ...cardStyle, padding: 28, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <h2 style={sectionTitle}>설치 방법</h2>
          <ol style={{ margin: 0, paddingLeft: 22, display: 'flex', flexDirection: 'column', gap: 12, lineHeight: 1.7 }}>
            {INSTALL_STEPS.map((step) => (
              <li key={step.title}>
                <strong>{step.title}</strong>
                <br />
                <span style={{ color: theme.color.textSub }}>{step.body}</span>
              </li>
            ))}
          </ol>
        </section>

        <section style={{ ...cardStyle, padding: 28, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <h2 style={sectionTitle}>휴대폰으로 열기</h2>
          <figure style={{ margin: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
            <img
              src="/app-qr.svg"
              width={240}
              height={240}
              alt="이 페이지 주소 i-eum.site/app 으로 가는 QR 코드"
              style={{ maxWidth: '100%', height: 'auto' }}
            />
            <figcaption style={{ fontSize: 14, color: theme.color.textSub, textAlign: 'center' }}>
              휴대폰 카메라로 찍으면 이 페이지가 열려요 · <span style={{ fontFamily: theme.font.mono }}>i-eum.site/app</span>
            </figcaption>
          </figure>
        </section>
      </div>
    </main>
  )
}
```

- [ ] **Step 3: 라우트 추가 (인증 가드 밖)**

`front/apps/web/src/app/router.tsx` — import 목록에 알파벳 순으로 더하고(맨 위), `/signup` 다음 줄에 라우트를 넣는다:

```tsx
import { AppActionRedirectPage } from '../pages/AppActionRedirectPage'
import { AppDownloadPage } from '../pages/AppDownloadPage'
```

```tsx
  { path: '/signup', element: <SignupPage /> },
  // 학습자 앱 다운로드 — 로그인 없이 연다. /app/latest.json·/app/ieum.apk 는 호스트 nginx가 먼저 처리한다
  { path: '/app', element: <AppDownloadPage /> },
```

- [ ] **Step 4: 개발 서버 프록시**

운영에서는 호스트 nginx가 `/app/latest.json`·`/app/ieum*.apk`를 web보다 먼저 가로챈다. Vite 개발 서버에는 그 파일이 없어, `fetch`(Accept `*/*`)에도 SPA 폴백으로 `index.html`을 200으로 준다(Vite 6.4 `htmlFallbackMiddleware`) — 페이지는 "준비 중이에요"가 된다. 개발 중에도 실제 모습을 보려고 두 경로만 넘긴다. `server.proxy`는 `vite dev`에만 적용되고 운영 빌드(nginx 정적 서빙)와 무관하다. `/app` 자체는 접두어가 달라 걸리지 않는다.

`front/apps/web/vite.config.ts`의 `proxy` 객체에 `/api` 항목 다음으로 추가:

```ts
      // 앱 배포 파일(운영에선 호스트 nginx가 web보다 먼저 제공). 개발 서버에는 없어 SPA 폴백 HTML이
      // 오므로 운영 서버의 공개 파일로 넘긴다. 로컬 파일로 보려면 VITE_APP_RELEASE_TARGET=http://localhost:8765
      // 처럼 정적 서버 주소를 준다(README). /app 페이지 자체는 접두어가 달라 여기 걸리지 않는다
      '/app/latest.json': {
        target: process.env.VITE_APP_RELEASE_TARGET ?? 'https://i-eum.site',
        changeOrigin: true,
      },
      '/app/ieum': {
        target: process.env.VITE_APP_RELEASE_TARGET ?? 'https://i-eum.site',
        changeOrigin: true,
      },
```

(`VITE_APP_RELEASE_TARGET`은 기존 `VITE_PROXY_TARGET`처럼 vite 설정만 읽는 개발용 셸 변수다 — 앱 번들에 들어가지 않고 `.env` 파일을 쓰지 않는다.)

- [ ] **Step 5: README 섹션 추가**

`front/apps/web/README.md` 끝에 추가:

````markdown
## 앱 다운로드 페이지 (`/app`)

- 로그인 없이 여는 학습자 앱 설치 페이지. `/app/latest.json`(같은 출처)을 읽어 버전·배포일을 보여 주고, 없으면 "준비 중이에요".
- 운영에서 `/app/latest.json`·`/app/ieum.apk`는 호스트 nginx가 `/home/ubuntu/app/downloads/`에서 먼저 제공한다(`scripts/release-app.sh`가 올린다). web 이미지에는 들어 있지 않다.
- 개발 서버는 두 경로를 `https://i-eum.site`로 프록시한다. 로컬 파일로 보려면:

```bash
mkdir -p /tmp/rel/app && echo '{"version":"1.0.0","versionCode":1,"releasedAt":"2026-09-27T12:00:00Z","notes":"테스트"}' > /tmp/rel/app/latest.json
(cd /tmp/rel && python -m http.server 8765)          # 다른 터미널
VITE_APP_RELEASE_TARGET=http://localhost:8765 pnpm dev:web
```

- QR(`public/app-qr.svg`)은 커밋된 정적 파일이다. 주소가 바뀌면 `node apps/web/scripts/generate-app-qr.mjs`(front에서)로 다시 만든다 — 워크스페이스의 `toqr`을 쓴다.
````

- [ ] **Step 6: 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front/front
pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build
ls apps/web/dist/app-qr.svg
```

Expected: 모두 통과, lint 경고 0, `dist/app-qr.svg` 존재(Vite가 `public/`을 복사)

- [ ] **Step 7: 커밋**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front
git add front/apps/web/scripts/generate-app-qr.mjs front/apps/web/public/app-qr.svg \
  front/apps/web/src/pages/AppDownloadPage.tsx front/apps/web/src/app/router.tsx \
  front/apps/web/vite.config.ts front/apps/web/README.md
git commit -m "feat(release): 로그인 없이 여는 학습자 앱 다운로드 페이지를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 통합 검증과 수동 확인 (사람과 함께)

**Files:** 없음 (확인 절차)

- [ ] **Step 1: CI와 같은 검증**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front
docker build --target test front
git diff --stat chore/deploy-rebuild...HEAD
git diff chore/deploy-rebuild...HEAD -- front/pnpm-lock.yaml front/apps/web/package.json front/apps/mobile/package.json front/packages/api-client/package.json
```

Expected: docker build 성공(`jest 통과: N/N`), 변경 파일이 File Structure 표의 파일뿐, 마지막 diff 출력 없음(의존성 불변)

- [ ] **Step 2: 웹 페이지 (개발 서버)**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-front/front
pnpm dev:web
```

1. 로그아웃 상태(시크릿 창)로 `http://localhost:5173/app` → 로그인 화면으로 가지 **않고** 페이지가 뜬다
2. 운영에 아직 `latest.json`이 없으면 "준비 중이에요"가 보이고 버튼이 없다
3. README의 로컬 정적 서버 절차로 `VITE_APP_RELEASE_TARGET`을 주고 다시 띄우면 "버전 1.0.0 · 2026년 9월 27일 배포", "바뀐 점: 테스트", 큰 "이음 앱 내려받기" 버튼이 보인다
4. Tab 키만으로 버튼에 도달하고 포커스 테두리가 보인다. 개발자 도구 375px 폭에서 가로 스크롤이 없다
5. **휴대폰 카메라로 화면의 QR을 찍으면 `https://i-eum.site/app`이 열린다**(QR 해독 확인)
6. 서버를 끌 때는 PID로만: `netstat -ano | findstr :5173` → `taskkill //PID <pid> //F`

- [ ] **Step 3: 앱 — 새 버전 알림 (개발 빌드, 사람)**

`app.json`에 플러그인이 추가됐으므로 개발 빌드를 다시 만든다(`npx expo run:android`, 디버그라 서명 속성 불필요). 사람이 `front/apps/mobile/.env`에 `EXPO_PUBLIC_APP_RELEASE_URL=http://<PC LAN IP>:8765/app`을 넣고(에이전트는 열지 않는다), README 절차의 정적 서버에 `versionCode: 2`인 `latest.json`을 둔다.

1. 앱을 켜고 로그인해 홈(탭)에 가면 "새 버전이 있어요. 설치 페이지를 열까요?"를 읽고 알림 화면이 뜬다(TalkBack 켠 상태에서도 제목·두 버튼이 순서대로 읽힌다)
2. "나중에" → 홈으로 돌아간다. 앱을 완전히 껐다 켜면 같은 날이라 다시 뜨지 않는다
3. `latest.json`의 `versionCode`를 3으로 바꾸고 다시 켜면 또 뜬다. "설치 페이지 열기" → 브라우저가 `EXPO_PUBLIC_APP_RELEASE_URL`을 연다
4. 정적 서버를 끄고 앱을 켜면 아무 안내 없이 홈이 된다(조용한 실패)

- [ ] **Step 4: 앱 — 음성 대리 호출 (backend 계획 병합 뒤, 사람)**

전제: `chore/deploy-rebuild-api`가 `chore/deploy-rebuild`에 병합되고 이 브랜치를 그 위로 로컬 병합(`git merge chore/deploy-rebuild`, push 금지). 로컬 backend를 학습자 계정으로 쓴다.

1. backend에 `CARTESIA_API_KEY`가 **비어 있을 때**: 설정 → 내 목소리 → 녹음·복제 → "목소리 복제에 실패했어요. 지금은 목소리 기능을 쓸 수 없어요. 기본 목소리로 안내할게요."를 읽는다
2. 키가 있을 때: 복제 성공 낭독 → "테스트 재생"이 복제한 목소리로 나온다. 같은 문장을 다시 재생하면 네트워크 없이(비행기 모드) 캐시로 나온다
3. 잇다 응답을 1분에 30번 넘게 부르면(연속 테스트 재생) 기본 목소리로 넘어가고 앱이 멈추지 않는다
4. `git grep -n EXPO_PUBLIC_CARTESIA_API_KEY -- front/apps/mobile/src`가 비어 있다. 릴리스 APK의 `strings` 확인(§8-6)은 infra 계획의 릴리스 절차에서 한다

- [ ] **Step 5: 서명 (Task 5 Step 6을 못 돌렸다면, 사람)**

keystore와 `~/.gradle/gradle.properties`가 준비된 PC에서 `CI=1 npx expo prebuild -p android --clean --no-install && cd android && ./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`가 생기고 `apksigner verify --print-certs`의 인증서가 업로드 키다(디버그 키 `CN=Android Debug`가 아님). 속성을 하나 지우면 "릴리스 서명 정보가 없습니다"로 멈춘다.

---

## 완료 확인 (병합 전)

- [ ] `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build` 통과(경고 0)
- [ ] `pnpm --filter @ieum/mobile typecheck && pnpm --filter @ieum/mobile exec jest` 통과, `pnpm --filter @ieum/api-client typecheck` 통과
- [ ] `docker build --target test front` 성공
- [ ] lockfile·package.json 불변, 변경 파일이 File Structure 표의 파일뿐, `.superpowers/`·`*.sql`·`android/`·`.env*`가 커밋되지 않았다
- [ ] **사람 작업**: `front/apps/mobile/.env.example`에서 `EXPO_PUBLIC_CARTESIA_API_KEY=` 줄을 지우고 `EXPO_PUBLIC_APP_RELEASE_URL=`(설명 주석 포함)을 더한다 — AGENTS.md "키를 추가하면 .env.example도 갱신" 규칙과 이 작업의 `.env*` 금지 규칙이 부딪혀 사람이 한다
- [ ] Task 8 수동 결과와 후속 항목을 MR 본문에 적는다
- [ ] `chore/deploy-rebuild`에 로컬 병합(`git checkout chore/deploy-rebuild && git merge --no-ff chore/deploy-rebuild-front`). push하지 않는다

## 알려진 한계·후속

- 설치 버전은 `expo-application` 대신 `Constants.expoConfig.android.versionCode`로 읽는다. 누군가 prebuild 없이 `android/`의 versionCode만 손으로 바꾸면 어긋나지만, CNG(`android/`는 gitignore, 릴리스 스크립트가 `prebuild --clean`)에서는 생기지 않는다
- 로그인하지 않은 사용자는 로그인해 탭에 도착한 뒤에 알림을 받는다(스플래시의 `router.replace`와 충돌을 피하려는 선택)
- 서버에 키가 없을 때(503)도 잇다가 말할 때마다 서버를 한 번 부른 뒤 기본 목소리로 넘어간다. 잦으면 "503이면 몇 분 쉬기"를 후속으로 검토
- 다운로드 페이지는 파일 크기·SHA-256을 보여 주지 않는다(계약 `latest.json`에 크기가 없고, 학습자에게 해시는 의미가 적다)

## Self-Review

- 설계서 대응: §5.1 versionCode(Task 5 app.json) / §5.2 서명 플러그인·속성 없으면 실패(Task 5) / §5.3 `EXPO_PUBLIC_CARTESIA_API_KEY` 제거(Task 2, `.env.example`은 사람), `EXPO_PUBLIC_APP_RELEASE_URL` 사용(Task 3·4) / §5.5 공개 `/app`·latest.json·큰 버튼 → `/app/ieum.apk`·버전·배포일·번호 목록 3단계·정적 QR·"준비 중이에요"·라우팅(Task 6·7, 개발 프록시 Task 7 Step 4) / §5.6 켤 때 확인·versionCode 비교·낭독 문구·확인 시 페이지 열기·하루 한 번·조용한 실패(Task 3·4) / §6 앱: 두 API 호출·로컬 캐시 유지·503 안내(Task 1·2) / §8 `docker build --target test front`(Task 8 Step 1), 앱 설치·새 버전 낭독·음성(Task 8 Step 3·4)
- 계약 대조: 경로 `/voice/clones`(multipart `clip`·`name`, 응답 `voiceId`)·`/voice/speech`(`{voiceId,text}`, 바이트) / 오류 503·429·502 코드명 / text 1~300 trim / `latest.json` 필드명 / gradle 속성 네 이름 / 플러그인 경로 / APK 산출 경로 / `/app` 공개 라우트 — Global Constraints의 원문과 Task 코드가 같다
- 타입 일관성: `ReleaseInfo`(앱: `notes?: string`, 웹: `notes: string | null` — 서로 다른 앱의 다른 타입, 교차 import 없음), `UpdateNotice`·`UpdateCheckDeps`·`checkForAppUpdate`·`checkForAppUpdateOnce`·`whenAppUpdateStoreHydrated`·`UPDATE_PROMPT_MESSAGE`가 Task 3·4에서 같은 이름. `RequestOptions.responseType`·`signal`이 Task 1·2에서 같다. `applyReleaseSigning`이 플러그인·테스트에서 같다
- 플레이스홀더 없음: 모든 코드 Step에 전체 코드, 모든 실행 Step에 명령과 기대 결과
