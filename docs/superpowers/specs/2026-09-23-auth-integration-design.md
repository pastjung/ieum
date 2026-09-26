# 인증·계정 API 프론트 연동 설계

> 작성일: 2026-09-23 · 대상: `front/packages/api-client`, `front/apps/mobile` · 근거: [docs/product/api-spec.md](../../product/api-spec.md) 3절, [docs/conventions/code/mobile.md](../../conventions/code/mobile.md)

## 1. 배경과 범위

`front/apps/mobile`은 화면 30개가 완성돼 있지만 데이터는 전부 목이다. 서버 호출은 `services/{authService,learningService,classService}.ts` 세 곳에 격리돼 있고, 선언만 돼 있는 `front/packages/api-client`는 `package.json`조차 없는 빈 디렉터리다.

백엔드는 계정·인증 컨트롤러 6개가 완성돼 있다. 학습·수업 컨트롤러는 없거나 반쪽이다. 따라서 **프론트가 지금 끝까지 연결할 수 있는 유일한 영역이 계정·인증**이며, 이 문서는 그 연동과 그 과정에서 만들어지는 HTTP 경계를 다룬다.

이 경계는 이후 학습·수업 연동이 그대로 재사용한다. 그래서 인증 하나만 붙이는 작업이지만 인터페이스 결정을 먼저 문서로 고정한다.

### 범위에 포함

- `@ieum/api-client` 신설 — base URL, 플랫폼 헤더, 인증 헤더, 401 재발급, 오류 변환
- 토큰 저장·복구 (메모리 access + SecureStore refresh)
- `authService` 교체: 가입 · 로그인 · 로그아웃 · 토큰 복구
- 메일 **요청** API 2종 (`email-verification-requests`, `password-reset-requests`)
- `GET /users/me` → 홈 화면, 회원 탈퇴
- 스플래시 분기와 세션 만료 화면 연결
- `@ieum/contracts`에 응답 스키마 추가

### 범위 밖 (후속)

| 항목 | 이유 |
| --- | --- |
| 메일 인증·비밀번호 재설정 **완료** API | 메일 링크가 https만 허용돼 도메인·nginx 작업이 선행돼야 한다 (3.6) |
| `apps/web` 리다이렉트 페이지, `ieum://` 딥링크 수신, 새 비밀번호 입력 화면 | 위와 같은 이유 |
| `deploy/nginx/itda.conf` 도메인 정리 | 인증 연동과 직교하는 인프라 수정. 별도 MR |
| 루트 `apps/web`·`apps/mobile` 스캐폴드의 잘못된 `userApi` | 이번 작업 대상이 아니다. 건드리지 않는다 |
| 학습·수업 API 연동 | 백엔드 컨트롤러가 없거나 인증 체인 밖이다 |

## 2. 구성과 경계

```
front/
├── packages/api-client/         신규 — transport 전담
│   └── src/{client,errors,types}.ts
├── packages/contracts/          응답 스키마 추가
└── apps/mobile/src/
    ├── services/tokenStore.ts   신규 — SecureStore 어댑터
    ├── services/authService.ts  시그니처 유지, 내부만 교체
    └── app/                     스플래시·만료 분기만 수정
```

핵심 경계 규칙 하나: **`@ieum/api-client`는 `expo-secure-store`를 import하지 않는다.** 저장소는 `TokenStore` 인터페이스로 주입받는다. 그래야 패키지가 React Native 전용이 되지 않고, 네트워크·저장소 없이 순수하게 테스트할 수 있다.

```ts
export interface TokenStore {
  getAccess(): string | null;
  getRefresh(): Promise<string | null>;
  save(session: AuthSession): Promise<void>;
  clear(): Promise<void>;
}

export function createClient(config: {
  baseUrl: string;
  platform: "MOBILE" | "WEB";
  tokens: TokenStore;
  onSessionExpired: () => void;
  fetchFn?: typeof fetch;
}): ApiClient;
```

## 3. 결정 사항과 근거

### 3.1 transport만 공유하고 도메인 호출은 앱에 둔다

`authApi.signIn()` 같은 도메인 함수를 패키지에 넣지 않는다. 웹은 Refresh Token을 `__Host-refreshToken` 쿠키로 주고받아 갱신·로그아웃 경로가 모바일과 근본적으로 다르다. 지금 공유하면 얻는 것 없이 플랫폼 분기만 생긴다.

기존 `authService`의 시그니처를 그대로 두고 내부 구현만 교체한다. 화면은 목이 실제 호출로 바뀐 것을 모른다.

### 3.2 access는 메모리, refresh만 SecureStore

API 명세 3.3.1절이 "네이티브 앱은 refreshToken을 OS 보안 저장소에만 보관한다"고 정했다. Access Token은 수명이 짧아 모듈 스코프 변수로 충분하고, 디스크에 원문을 남기지 않는다.

앱 재시작 시 스플래시가 Refresh Token으로 `POST /access-tokens`를 호출해 세션을 복구한다. 기존 `ieum.accessToken` 키는 삭제하고 `ieum.refreshToken`만 남긴다.

트레이드오프: 스플래시에서 네트워크 왕복 한 번을 기다리고, 오프라인이면 로그인 화면으로 떨어진다. 학습 진행이 서버에 있는 앱이라 오프라인 진입의 가치가 낮다고 판단했다.

### 3.3 401 재발급은 single-flight, 재시도는 1회

동시에 여러 요청이 401을 받아도 갱신은 한 번만 수행하고 나머지는 같은 Promise를 기다린 뒤 재시도한다. 갱신이 요청 수만큼 발생하면 Refresh Token 회전이 서로를 무효화한다.

재시도는 한 번뿐이다. 재시도한 요청이 또 401이면 갱신으로 풀 수 있는 상황이 아니므로 세션 만료로 처리한다. `POST /auth-sessions`와 `POST /access-tokens` 자신은 인터셉터 대상에서 제외한다 — 갱신 실패가 다시 갱신을 부르는 무한 루프를 막는다.

갱신 실패 시 `tokens.clear()` 후 `onSessionExpired()`를 호출하고, 앱은 `auth/expired` 화면으로 보낸다.

### 3.4 503을 자동 재시도하지 않는다

`AUTH_STATE_UNAVAILABLE`, `PASSWORD_POLICY_UNAVAILABLE`은 재시도 버튼만 제공한다. API 명세 3.7절이 이 상황의 복구를 "사용자가 같은 입력을 다시 제출"로 규정했다. 자동 재시도는 사용자가 결과를 모르는 채 계정 생성·비밀번호 변경이 중복 시도되게 만든다.

### 3.5 응답을 경계에서 검증한다

`@ieum/contracts`에 `authSessionSchema`·`userSummarySchema`를 추가하고 응답을 zod로 parse한다. 계약 위반을 화면이 아니라 HTTP 경계에서 잡는다. 패키지에 이미 요청 스키마가 zod로 있어 도구가 늘지 않는다.

### 3.6 메일 완료 경로를 이번 범위에서 뺀 이유

[MailLinkProperties](../../../apps/backend/src/main/java/com/itda/user/service/MailLinkProperties.java)가 링크 base를 기동 시점에 검증한다: 절대 URI, `https`(루프백 host에 한해 `http`), host 존재, query·fragment 없음. 따라서 `ieum://auth/verify-email` 같은 커스텀 스킴은 **설정할 수 없다** — 백엔드가 기동에 실패한다.

토큰은 `<base>#token=<urlencoded>` 형태의 **fragment**로 붙는다. fragment는 서버로 전송되지 않으므로 무엇이 받든 클라이언트에서 `location.hash`를 읽어야 한다.

결론적으로 완료 경로는 https 페이지를 거쳐야 하고, 그러려면 도메인·nginx·인증서 정리가 선행된다. 그 작업은 인증 연동과 직교하므로 분리했다.

합의된 후속 설계는 다음과 같다: `https://i-eum.site/auth/{verify-email,reset-password}`의 얇은 페이지가 `location.hash`의 토큰을 읽어 `ieum://auth/...#token=`으로 넘기고, 완료 API 호출과 새 비밀번호 입력은 앱이 접근성 컴포넌트로 처리한다.

## 4. 확인된 백엔드 계약

전부 `/api/v1` 아래다 (`server.servlet.context-path`).

| Method · path | 성공 | 요청 | 비고 |
| --- | --- | --- | --- |
| `POST /users` | 201 | `{username, email, password}` | 응답 `UserSummaryResponse`. 토큰 없음 |
| `POST /auth-sessions` | 201 | `{username, password}` | `X-Client-Platform` 필수. 응답에 `refreshToken` 포함 |
| `POST /access-tokens` | 201 | `{refreshToken}` | 같은 응답 형태 |
| `POST /auth-session-revocations` | 204 | `{refreshToken}` | 빈 body |
| `GET /users/me` | 200 | — | Access Token 필요 |
| `POST /users/me/withdrawals` | 204 | `{currentPassword}` | |
| `POST /email-verification-requests` | 202 | `{email}` | 접수만 뜻한다 |
| `POST /password-reset-requests` | 202 | `{email}` | 접수만 뜻한다 |

`X-Client-Platform` 값은 `MOBILE` 또는 `WEB`과 **정확히** 같아야 하고, trim·대소문자 보정이 없다. `Origin` 헤더가 함께 오는 `MOBILE` 요청은 거부된다 ([ClientPlatform.java](../../../apps/backend/src/main/java/com/itda/user/controller/ClientPlatform.java)).

모든 BIGINT ID는 10진수 문자열이다. 오류 body는 `{code, message}`이며 `message`는 한국어다.

## 5. 화면 ↔ API 매핑

| 화면 | API |
| --- | --- |
| `app/index.tsx` 스플래시 | `POST /access-tokens` (복구) |
| `app/auth/sign-up.tsx` | `POST /users` |
| `app/auth/sign-in.tsx` | `POST /auth-sessions` |
| `app/auth/verify-email.tsx` 재발송 | `POST /email-verification-requests` |
| `app/auth/reset-password.tsx` | `POST /password-reset-requests` |
| `app/(tabs)/index.tsx` 홈 | `GET /users/me` |
| `app/(tabs)/settings.tsx` 로그아웃 | `POST /auth-session-revocations` |
| `app/account/withdraw.tsx` | `POST /users/me/withdrawals` |
| `app/auth/expired.tsx` | 갱신 실패 시 전역 이동 대상 |

홈의 `homeSummary`는 `userName`·`stepLabel`만 `GET /users/me`로 채운다. `inProgress`·`deviceConnected`는 각각 학습 세션 API와 BLE가 있어야 해서 목을 유지한다.

## 6. 오류 → 화면 매핑

모든 오류는 `announce()`와 `hapticError()`를 거친다. 시각장애인 사용자에게 화면 텍스트만 바꾸는 것은 오류를 알리지 않은 것과 같다 (mobile.md 접근성 절).

| code | 상태 | 화면 처리 |
| --- | --- | --- |
| `INVALID_CREDENTIALS` | 401 | 로그인 폼에 안내 |
| `EMAIL_VERIFICATION_REQUIRED` | 403 | `auth/verify-email`로 이동 |
| `ACCOUNT_IDENTIFIER_UNAVAILABLE` | 409 | 가입: 아이디 입력 단계로 되돌림 |
| `INVALID_ACCOUNT_INPUT` / `INVALID_PASSWORD` | 422 | 해당 필드 오류 |
| `CURRENT_PASSWORD_MISMATCH` | 403 | 탈퇴 화면 비밀번호 오류 |
| `INVALID_REFRESH_TOKEN` | 401 | 세션 만료 처리 |
| `MALFORMED_REQUEST_BODY` | 400 | 개발 오류. 일반 안내로 처리 |
| `AUTH_STATE_UNAVAILABLE` / `PASSWORD_POLICY_UNAVAILABLE` | 503 | 재시도 버튼. 자동 재시도 금지 (3.4) |

## 7. 로컬 실행 조건

- `docker compose up backend db redis` — 백엔드는 `127.0.0.1:8080`, 프로필 `local`
- `EXPO_PUBLIC_API_URL`: Android 에뮬레이터 `http://10.0.2.2:8080/api/v1`, iOS 시뮬레이터 `http://localhost:8080/api/v1`
- `MAIL_DISPATCH_ENABLED`는 기본값 `false`를 유지한다. 메일 관련 bean이 `@ConditionalOnProperty`로 묶여 있어 SMTP·링크 환경변수 없이 기동된다.

**주의**: 발송이 꺼져 있으면 action token이 아예 생성되지 않는다. 토큰은 접수 시점이 아니라 [MailDispatchWorker](../../../apps/backend/src/main/java/com/itda/user/service/MailDispatchWorker.java)가 발송 시점에 만들며, `mail_outbox`에는 토큰 컬럼이 없다.

로그인은 `status=ACTIVE`와 `email_verified_at != null`을 모두 요구하므로([User.java](../../../apps/backend/src/main/java/com/itda/user/domain/User.java)), 로컬에서는 가입 직후 DB로 인증 상태를 만든다.

```sql
UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE username = '<가입한아이디>';
```

## 8. 완료 기준

"연동했다"가 아니라 아래가 **참임을 확인**한 상태를 완료로 본다.

1. `packages/api-client` 단위 테스트 통과: 401 → 갱신 → 재시도 성공 / 갱신 실패 → `onSessionExpired` 호출 / 동시 3요청에 갱신 1회 / 오류 body가 `ApiError.code`로 변환
2. `cd front && pnpm -r typecheck`와 `pnpm -r test` 통과
3. 로컬 실기동 1회: 가입 → (DB 인증) → 로그인 → `GET /users/me` 200 → Access Token 만료 후 같은 호출이 자동 갱신을 거쳐 성공 → 로그아웃 → 탈퇴
4. TalkBack을 켠 상태로 가입·로그인 1회 조작 — 오류 상황의 안내가 낭독되는 것까지 확인

## 9. 후속 작업

| 항목 | 메모 |
| --- | --- |
| 메일 완료 경로 + 도메인·nginx 정리 | 3.6. 서버 `itda.conf`가 repo와 어긋나 있어 다음 배포에 `i-eum.site`가 끊긴다 |
| `deploy/.env.config.example` 부재 | infra README 3.5절이 지목하는 파일이 저장소에 없다. 운영 메일 설정을 적을 자리가 없다 |
| `ProblemController`가 인증 체인 밖 | `SecurityConfig`가 `GET /users/me`와 `POST /users/me/withdrawals`만 보호한다. 학습 API 연동의 선행 조건 |
| 학습 세션 계약 불일치 | 요청 필드명·응답 형태·정답 선공개가 API 명세와 다르다 |
