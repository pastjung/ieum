# I:EUM API 명세서 V2

> 갱신일: 2026-09-09  
> 상태: 현행 MVP 기준 · 외부 연동·운영 세부는 후속 계약
> 기준: [기능 V2](./feature-spec.md), [ERD V2](./erd.md) · 설계 결정 기록은 로컬 별도 참고 자료로 보관한다.

## 1. 이 문서의 확정 범위

이 문서는 계정·개인 학습·문제·수업의 HTTP 요청·응답·권한·오류·멱등 계약을 정의한다. 공개 HTTP는 /api/v1, 수업 알림은 /ws/v1을 사용한다. AI의 두 내부 HTTP API도 /api/v1이지만 외부 Backend와 호스트·리스너를 분리한다. 문서 버전 V2는 URL 버전 변경을 뜻하지 않는다.

개인 학습 6개, 계정·인증 10개, 문제·기준 정보 5개, 수업 12개의 공개 HTTP와 2개의 AI 내부 HTTP 계약을 다룬다. A-01~A-22는 업무 추적 번호이므로 endpoint 개수와 다르다. OpenVidu의 버전 의존 접속 API와 BLE 통신 상수는 후속 계약이며 임의 경로·필드를 만들지 않는다.

## 2. 저장소 문서에 맞춘 공통 규칙

근거는 [API 컨벤션](../conventions/code/api.md)과 [아키텍처 문서](../infra/architecture.md)다. 공통 규칙의 원본은 API 컨벤션이며, 이 문서는 그 규칙을 따르는 개별 계약을 정의한다.

| 항목 | 적용 규칙 |
| --- | --- |
| URL | 명사·복수형 리소스와 HTTP method 사용. 동사형 URL을 새 확정 계약으로 만들지 않음 |
| JSON | 요청·응답 필드는 camelCase. DB의 snake_case 컬럼과 별개 |
| 오류 | 최상위 code, message. code는 UPPER_SNAKE_CASE, message는 한국어. 스택·내부 예외 비노출 |
| 목록 | items, page, size, totalElements, totalPages 형식의 페이지 기반 응답 |
| 시각 | ISO 8601 UTC 전송, 표시 시간대 변환은 클라이언트 책임 |
| 외부/내부 경계 | 외부 클라이언트는 주 Backend를 사용하고 AI를 직접 공개 호출하지 않음 |

형식 예시이며 아래 코드는 새 오류 종류의 승인이 아니다.

```json
{ "code": "ERROR_CODE_TBD", "message": "확인된 오류 상황에 대한 안내" }
```

```json
{ "items": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }
```

모든 BIGINT ID는 JSON과 경로에서 10진수 문자열이다. 답안·문제 등록의 멱등 키는 Idempotency-Key 헤더로 전달한다. 수업·문제은행·학습 요소의 페이지 목록은 기본 size 20·최대 100을 사용한다. 각 endpoint의 성공 응답과 필수 입력은 해당 절을 따른다.

### 2.1 공통 요청 추적

Backend는 외부 HTTP 요청 또는 백그라운드 작업 시작마다 UUID v4 `X-Request-Id`를 생성한다. HTTP 응답 헤더에 같은 값을 반환하고 AI 호출 헤더에도 전달해 Backend·AI 구조화 로그에서 같은 처리 흐름을 찾을 수 있게 한다. 클라이언트가 이 값을 필수로 만들거나 업무 body에 넣지 않으며, 도메인 DB와 응답 body에는 저장·포함하지 않는다. AI는 받은 값을 로그에 기록하고 응답 헤더에 돌려준다.

`X-Request-Id`는 관측용이며 재전송 중복을 막지 않는다. 답안·문제 등록의 `Idempotency-Key`, 수업 WebSocket 알림의 `eventId`, DB 기본 키와 서로 대체할 수 없다. 기본 요청 로그와 health check는 MVP 필수다. 로그 보존 기간·수집 시스템·샘플링·알림 임계값과 W3C `traceparent`·OpenTelemetry는 후속 운영 범위다.

역할은 LEARNER / INSTRUCTOR / ADMIN 중 하나다. roles 배열·역할 전환을 제공하지 않는다. 사용자 식별·권한을 요청 body의 주장만으로 신뢰하지 않으며, 타인의 학습 결과를 노출하지 않는다. ADMIN·INSTRUCTOR는 통제된 Seed/운영 절차로만 발급하고 MVP 관리자 공개 API·화면과 ADMIN의 강사/문제 자동 권한은 두지 않는다.

## 3. 계정·인증 업무 계약

| ID / 기능 | 입력 의미 | 처리·결과 | 저장·처리 규칙 |
| --- | --- | --- | --- |
| A-01 / F-01 가입·로그인·내 정보 | username·비밀번호와 가입 email | 공개 가입은 LEARNER만 생성. username 로그인 후 인증된 단일 역할로 접근하며 미인증·탈퇴·제한 계정은 거부 | users. username/email 정규화·UNIQUE, Argon2id, 실패 5회·10분 제한. 실제 HTTP·DTO·오류는 |
| A-02 / F-02 이메일 인증·재발송 | 계정 또는 이메일과 일회성 인증 토큰 | 유효한 토큰이면 email_verified_at 기록. 재발송은 기존 활성 인증 토큰을 무효화하고 새 토큰 발급 | Redis 계정 작업 토큰 상태, 인증 TTL 24시간·단일 사용·원문 미저장. 실제 HTTP·DTO·오류는 |
| A-03 / F-02 비밀번호 복구 | 이메일 기반 복구 요청·일회성 토큰·새 비밀번호 | 유효한 토큰으로 비밀번호를 변경하고 해당 사용자의 모든 Refresh Token 폐기 | Redis 계정 작업 토큰 상태, 복구 TTL 30분·단일 사용·계정 존재 비노출. 실제 HTTP·DTO·오류는 |
| A-04 / F-01 인증 상태 갱신·폐기 | Access/Refresh Token과 현재 인증 상태 | Access 15분, Refresh 14일. 갱신마다 회전하고 이전 토큰 재사용 시 같은 계열 폐기. 로그아웃은 현재 기기 계열 폐기 | Redis에 JWT 해시·jti·계열·회전·폐기 상태 저장. 보호 API에서 ACTIVE·이메일 인증 상태 재검증. 실제 HTTP·DTO·오류는 |
| A-05 / F-03 탈퇴 | 인증된 본인의 현재 비밀번호 확인 | MariaDB 트랜잭션으로 WITHDRAWN·탈퇴 시각·식별정보 가명화, Redis에서 전체 Refresh 폐기. 로그인·신규 학습 차단 | 사용자 행·가명 처리된 과거 이력 보존, identifier 즉시 재사용, 재가입은 새 ID. 실제 HTTP·DTO·오류는 |

접근성 설정(F-04)은 프론트엔드 로컬에서 계정별로 유지하므로 TTS·햅틱·고대비·글자 크기를 저장하는 백엔드 API는 두지 않는다. 내 정보 수정에 이러한 필드를 섞지 않는다. 일반 프로필 수정 API는 현행 계약에 포함하지 않는다.

### 3.1 계정·인증 공통 처리

가입 입력의 username은 앞뒤 공백 제거·NFC 정규화 후, email은 앞뒤 공백 제거·소문자 정규화 후 대소문자 비구분 UNIQUE를 검사한다. 공개 가입 요청의 role 값은 받거나 신뢰하지 않고 LEARNER로 생성한다. INSTRUCTOR·ADMIN은 관리자 통제 등록 또는 초기 데이터로만 생성한다. 미인증 계정은 별도 상태값 없이 email_verified_at=NULL로 판별하며 로그인·학습을 허용하지 않는다.

비밀번호는 15~64자, 유니코드·공백 허용, NFC 정규화와 흔하거나 유출된 값 차단을 적용한다. 대·소문자·숫자·특수문자 조합을 강제하거나 주기적 변경을 요구하지 않는다. Argon2id 최소 설정은 메모리 19MiB·반복 2·병렬도 1이며 원문·복구 토큰·Refresh Token을 로그에 남기지 않는다. 로그인 실패는 계정 없음·비밀번호 오류·잠금·탈퇴 여부를 외부 응답과 처리 시간으로 구분하지 않는다. 계정별 연속 실패 5회 시 10분 제한하고 정상 로그인 시 `failed_login_count`와 `locked_until`을 초기화한다. 프론트 검증은 안내용이며 동일 규칙을 백엔드가 다시 적용한다.

Access Token은 15분 유효 JWT이며 MariaDB·Redis에 원문을 저장하지 않는다. claim에는 sub·role·iat·exp·jti·iss·aud를 두고 username·email을 넣지 않는다. Refresh Token도 서명된 JWT로 발급하며 원문 대신 SHA-256 해시·jti·userId·familyId·만료·회전·폐기 상태를 Redis에 저장한다. 유효기간은 발급별 14일이다. 갱신 때 기존 토큰을 무효화하고 같은 familyId의 새 JWT를 발급한다. 폐기된 토큰 재사용은 해당 계열 전체를 폐기한다. 로그아웃은 현재 계열, 비밀번호 재설정과 탈퇴는 사용자 전체 계열을 폐기한다. 웹 Refresh Token은 Secure·HttpOnly 쿠키, 모바일은 운영체제 보안 저장소를 사용하고 일반 localStorage·AsyncStorage에는 저장하지 않는다.

이메일 인증·복구 토큰도 용도를 구분한 서명 JWT로 발급하고 Redis에는 SHA-256 해시·jti·userId·purpose·만료·사용 상태를 저장한다. EMAIL_VERIFICATION은 24시간, PASSWORD_RESET은 30분이다. 새 토큰 발급 시 같은 사용자·용도의 이전 활성 토큰을 무효화하고 완료 시 원자적으로 소비하여 단일 사용을 보장한다. 인증 재발송·비밀번호 복구 요청은 계정 존재 여부와 관계없이 같은 HTTP 상태와 일반 메시지를 사용한다. 미인증 계정은 MVP에서 자동 삭제하지 않고 재발송을 허용한다.

모든 JWT는 서버에서 허용한 서명 알고리즘·키·iss·aud·exp를 검증한다. Refresh에는 계열 식별, action에는 EMAIL_VERIFICATION/PASSWORD_RESET 용도 식별을 포함하고 jti는 안전한 난수로 생성한다. Access·Refresh·이메일 인증·비밀번호 복구 JWT의 audience/용도와 검증 경로를 분리하여 서로 대체 사용할 수 없게 한다. 정확한 issuer·audience 문자열·키 회전 설정은 구현 계획에서 확인받는다. Refresh/action은 Redis 활성 상태도 일치해야 하며 서명만으로 허용하지 않는다.

탈퇴는 물리 삭제가 아니다. 현재 비밀번호를 다시 확인한 뒤 status·`withdrawn_at`·`email_verified_at`, username·email, `password_hash`를 MariaDB 트랜잭션에서 변경하고 모든 Refresh Token은 Redis에서 폐기한다. API에는 가명 값을 노출하지 않고 ‘탈퇴 사용자’로 처리한다. 과거 학습·수업 기록은 사용자 내부 ID 관계를 유지하지만 신규 로그인·학습에는 사용할 수 없다. 운영 배포의 법적 보존 기간과 백업 제거 시점은 별도 정책이며 현재 API가 30일 물리 삭제를 보장한다고 명세하지 않는다.

### 3.2 계정·인증 공개 HTTP API

| 목적 | Method·path | 요청 | 성공 |
| --- | --- | --- | --- |
| 회원가입 | `POST /api/v1/users` | `{ username, email, password }` | `201`, 사용자 요약 |
| 내 정보 | `GET /api/v1/users/me` | Bearer Access Token | `200`, 사용자 요약 |
| 회원 탈퇴 | `POST /api/v1/users/me/withdrawals` | `{ currentPassword }` | `204` |
| 로그인 | `POST /api/v1/auth-sessions` | `X-Client-Platform`, `{ username, password }` | `201`, 인증 응답 |
| Access Token 갱신·Refresh 회전 | `POST /api/v1/access-tokens` | `X-Client-Platform`, 플랫폼별 Refresh 전달 | `201`, 인증 응답과 새 Refresh |
| 로그아웃·현재 Refresh 계열 폐기 | `POST /api/v1/auth-session-revocations` | `X-Client-Platform`, 플랫폼별 Refresh 전달 | `204` |
| 이메일 인증 요청·재발송 | `POST /api/v1/email-verification-requests` | `{ email }` | 항상 `202` |
| 이메일 인증 완료 | `POST /api/v1/email-verifications` | `{ token }` | `204` |
| 비밀번호 재설정 요청 | `POST /api/v1/password-reset-requests` | `{ email }` | 항상 `202` |
| 비밀번호 재설정 완료 | `POST /api/v1/password-resets` | `{ token, newPassword }` | `204` |

가입 요청은 `role`, `emailVerified`, `status`, `level`을 받지 않고 서버가 항상 `LEARNER`를 부여한다. 가입 트랜잭션 뒤 최초 이메일 인증 토큰을 발급·발송하며 인증 완료 전에는 Access·Refresh Token을 발급하지 않는다. 가입·비밀번호 변경 시 정규화·비밀번호·Argon2id 규칙을 백엔드에서 적용한다.

사용자 요약은 `{ userId, username, email, role, emailVerified, level, createdAt }`이며 `userId`는 BIGINT의 10진수 문자열, `createdAt`은 ISO 8601 UTC다. 가입·내 정보 조회·인증 응답의 user는 같은 형태를 사용한다. level은 users.level에서 조회한 열린 최고 단계 코드다. LEARNER의 자가 진단 전과 INSTRUCTOR·ADMIN은 null, 진단 후에는 L1~L6이며 BKT 확률과 다르다. 재로그인 후 프론트는 이 값으로 자가 진단 필요 여부와 열린 단계 범위를 판단한다. 접근성 설정, 일반 프로필 변경, 역할 변경은 이 계약에 포함하지 않는다.

### 3.3 플랫폼별 Refresh Token 전달

로그인·갱신·로그아웃은 `X-Client-Platform: WEB` 또는 `X-Client-Platform: MOBILE`을 필수로 받는다. 이 헤더는 Refresh Token 전달 방식을 고르는 값일 뿐 역할·권한의 근거가 아니다. 허용 브라우저 Origin에서 `MOBILE`을 주장하는 요청은 거부하고 신뢰할 Origin·CORS 정책을 함께 검사한다.

공통 인증 응답은 `{ accessToken, tokenType: "Bearer", accessTokenExpiresAt, user }`다. WEB은 Refresh Token을 JSON에 포함하지 않고 `__Host-refreshToken` 이름의 `Secure; HttpOnly; SameSite=Strict; Path=/` 쿠키로 전달한다. MOBILE은 응답에 `refreshToken`, `refreshTokenExpiresAt`을 추가하며 Android Keystore·iOS Keychain과 동등한 운영체제 보안 저장소에만 보관한다. 웹 Access Token은 가능한 한 메모리에 유지하고 `localStorage`, 모바일의 `AsyncStorage` 등 일반 저장소에는 Refresh Token을 저장하지 않는다.

갱신·로그아웃 때 WEB은 쿠키, MOBILE은 `{ refreshToken }` body를 사용한다. 갱신은 엄격한 회전을 수행하고 기존 토큰을 폐기한 뒤 같은 `family_id`의 새 토큰을 발급한다. 폐기·만료·잘못된 Refresh Token은 모두 `INVALID_REFRESH_TOKEN`으로 응답하며 폐기 토큰 재사용이 감지되면 같은 계열 전체를 폐기한다. 프론트엔드는 동시 갱신을 single-flight로 합쳐야 한다. 회전 응답 유실 뒤 이미 폐기된 토큰을 다시 보내면 엄격한 재사용 정책상 재로그인이 필요하다. 로그아웃은 이미 폐기되었어도 `204`로 멱등 처리하고 WEB 쿠키와 MOBILE 로컬 토큰을 제거한다.

### 3.4 인증·복구·탈퇴 처리

이메일 인증 요청·재발송과 비밀번호 재설정 요청은 계정 존재 여부와 발송 여부에 상관없이 항상 `202`를 반환한다. 실제 이메일 인증 메일은 ACTIVE이면서 미인증인 계정에만 발송한다. 같은 사용자·용도의 새 인증 토큰을 만들 때 기존 활성 토큰을 무효화한다. 인증 토큰은 24시간, 비밀번호 재설정 토큰은 30분이며 단일 사용이다. 발송을 억제한 경우에도 외부 응답은 같고 실제 IP·계정별 제한 수치는 운영 부하·남용 데이터를 바탕으로 별도 승인한다.

원문 action token은 백엔드의 GET·query parameter로 받지 않고 POST body로만 전달한다. 이메일 링크는 프론트 화면 또는 앱 deep link·fragment를 열고, 프론트가 토큰을 POST body에 실어 완료 API를 호출한다. 비밀번호 재설정 성공 시 사용자의 모든 Refresh Token을 폐기하고 자동 로그인하지 않는다.

탈퇴 API는 현재 비밀번호를 다시 확인한다. 성공 시 가명화, `WITHDRAWN`·`withdrawn_at`·`email_verified_at=NULL`, 임의 Argon2id 비밀번호 해시 변경을 MariaDB 트랜잭션으로 수행하고 모든 Refresh Token은 Redis에서 폐기한다. 두 저장소의 일부 성공·실패 복구는 3.6절의 추가 승인 대상이다. Access Token 자체는 무상태지만 모든 보호 API가 `users.status=ACTIVE`를 재확인하므로 즉시 접근을 막는다. 물리 삭제, 30일 복구, 탈퇴 취소는 제공하지 않는다.

### 3.5 계정·인증 오류

오류 body는 공통 `{ code, message }` 형식이다.

| HTTP | code | 적용 조건 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | Access Token 없음·무효 또는 보호 자원 인증 실패 |
| 401 | `INVALID_CREDENTIALS` | 알 수 없는 계정·비밀번호 오류·로그인 제한·탈퇴 계정. 외부에서 원인을 구분하지 않음 |
| 401 | `INVALID_REFRESH_TOKEN` | Refresh Token 없음·무효·만료·폐기·재사용 |
| 403 | `EMAIL_VERIFICATION_REQUIRED` | 올바른 자격 증명이지만 이메일 미인증 |
| 403 | `CURRENT_PASSWORD_MISMATCH` | 탈퇴의 현재 비밀번호 불일치 |
| 409 | `ACCOUNT_IDENTIFIER_UNAVAILABLE` | username 또는 email 사용 불가 |
| 422 | `INVALID_ACCOUNT_INPUT` | username·email 형식 또는 필수 입력 오류 |
| 422 | `INVALID_PASSWORD` | 길이·정규화·흔한 값·유출 값 정책 위반 |
| 400 | `ACTION_TOKEN_INVALID` | action token 무효·만료·사용 완료·용도 불일치. 외부에서 원인을 구분하지 않음 |
| 400 | `INVALID_CLIENT_PLATFORM` | 플랫폼 헤더 누락·미지원 값 또는 신뢰하지 않는 브라우저의 MOBILE 주장 |

### 3.6 Redis 저장 계약과 구현 전 승인 경계

관계형 DB는 MariaDB 11.8.9, 인증 상태는 Redis Open Source 8.2.9를 사용한다. 토큰 테이블은 두지 않으며 저장 항목·TTL·재사용 탐지·사용자별 전체 폐기는 [ERD Redis 인증 상태](./erd.md#redis-auth)를 따른다. 기존 공개 endpoint·성공 DTO·유효기간·계정 존재 비노출 정책은 유지한다. 토큰 값의 형식만 서명 JWT로 변경하며 클라이언트가 payload를 권한 판단 근거로 사용하지 않는다.

Redis 원자적 소비·회전은 MariaDB 변경까지 원자적으로 만들지 않는다. 비밀번호 재설정·탈퇴, 이메일 인증 완료에서 일부 성공을 정상 완료로 숨기지 않아야 한다. 구체 처리 순서·보상/재시도·동시 로그인과 폐기의 직렬화, Redis 장애 HTTP/code·클라이언트 안내는 추가 승인 후 확정한다. 위 오류 표에 장애를 임의로 INVALID_REFRESH_TOKEN 등으로 끼워 넣지 않는다. 기존 요청/재발송의 일반 202는 실제 발송 성공을 보장하지 않는다.

승인 전에도 지켜야 할 기준은 만료·소비·폐기된 토큰 재허용 금지, Redis 검증 실패 시 서명만으로 통과시키는 우회 금지, 원문 토큰 로그 금지다. 일반 Access 보호 API에 새로운 Redis 블랙리스트 조회를 추가하는 승인은 아니며 users 상태 검증은 유지한다. 수업 WebSocket 60초 티켓의 기존 단일 인스턴스 메모리 보관과 Redis 전역 요청 제한 제외 범위도 변경하지 않는다.

## 4. 커리큘럼·자가 진단·세션 업무 계약

| ID / 기능 | 입력 의미 | 처리·결과 | 저장·처리 규칙 |
| --- | --- | --- | --- |
| A-06 / F-05·F-12 단계 조회·시작 경로 | L0~L6 단계 및 선지형 자가 진단 응답/선택 | 프론트가 선지 흐름을 진행하고 개별 응답은 API로 보내지 않음. 단계 목록은 기존 `GET /api/v1/curriculum-stages`, 시작은 기존 세션 생성 API의 curriculumStageId와 최초 initialUnlockedStageId 사용 | 별도 진단 endpoint 없음. users.level NULL인 최초 요청에서 최고 해금 단계와 이번 단계의 순서 관계·원자 초기화·충돌 재전송은 |
| A-07 / F-06 공통 안내 | 선택한 학습 경로·사용자의 안내 진행/건너뛰기 | L0 제공, 건너뛰어도 선택 단계 진입. 설정·도움말에서 언제든 재노출 | 안내 확인은 프론트 로컬만 사용하고 초기화 후 다시 표시될 수 있음. API·서버 풀이·BKT 기록 없음 |
| A-08 / F-07 학습 세션 준비 | `POST /api/v1/learning-sessions`, body의 curriculumStageId, 최초 선택적 initialUnlockedStageId, 선택적 sourceLectureId | 서버가 사용자·문제·mode를 결정하고 5개×2회 배정을 검증한 뒤 세션 생성. 최초 수준 초기화는 세션과 원자 처리하고 sourceLectureId가 있으면 CLOSED 수업의 본인 LIVE 오답을 우선 후보로 사용 | 개인 학습 API 계약. 같은 단계 미완료면 기존 세션 반환, 초기 수준 충돌·다른 단계 진행 중/분석 대기는 409, 유효 문제 5개 미확보는 503. 정량 시간 제한은 측정 후 확정 |
| A-09 / F-09 이어하기 | `GET /api/v1/learning-sessions/current` 또는 `GET /api/v1/learning-sessions/{sessionId}` | 저장된 배정·풀이를 조합해 최초 itemNo와 제출 결과를 유지하고 nextItemNo 계산 | current는 진행 중/분석 대기 세션, 없으면 204. 상세는 본인 세션만 제공하며 타인/없음은 모두 404 |
| A-10 / F-10 완료·분석·결과 조회 | 마지막 유효 답안, `POST .../{sessionId}/analysis`, `GET .../{sessionId}/result` | 마지막 답안으로 자동 완료·ANALYSIS_PENDING, 명시적 분석 API로 원자 반영·ANALYZED, 결과 조회는 읽기 | 분석 재호출은 기존 결과를 반환해 BKT를 중복 갱신하지 않음. sessionSkillSummary와 조회 시점 currentSkillStates를 구분 |

자가 진단의 정확한 여섯 질문은 [커리큘럼 V2 2절](./curriculum.md)을 따른다. 자기보고 응답·직접 단계 선택·L0 건너뛰기로 BKT 상태나 완료 실적을 만들지 않는다. 진단의 개별 응답은 서버에 저장하지 않고 최초 세션 생성에서 `initialUnlockedStageId`로 전달한 열린 최고 단계만 LEARNER의 users.level에 원자 기록한다.

세션 구성은 다음 의미를 반드시 보존한다.

1. 학습 내용 하나는 문제 하나(problem_id)다. 1~5번은 서로 다른 문제 5개이고, 6~10번은 바로 그 5개를 섞어 각 한 번씩 다시 배정한다. 각 문제는 정확히 두 번 등장하며 같은 문제를 연속 배정하지 않는다.
2. 문제 하나는 여러 learning_skills와 연결될 수 있으므로 문제 5개를 학습 요소 5개와 동일시하지 않는다.
3. 풀이 전에는 조작 방법·목표를 안내한다. 정상 독립 풀이 저장 후 정답 점형·차이를 공개하고 음성·햅틱·아두이노로 따라 하기를 제공한다. 따라 하기는 서버 풀이·10문항·BKT에서 제외한다.
4. 현재 풀이에 정답 안내 없이 스스로 답하는 시도만 독립 풀이로 구분한다. 이전에 그 내용을 배웠다는 이유로 제외하지 않는다.
5. 두 번째 풀이도 실제 새 시도이며 최초 오답을 이후 정답으로 덮어쓰지 않는다. 오답 때문에 문항 수를 자동 증가시키지 않는다.
6. 미완료 세션은 분석에 반영하지 않고 완료 후 한 번 반영한다. 결과 화면 조회 자체는 분석 실행 근거가 아니다.

학습 세션은 사용자·기준 단계·완성된 배정 참조 목록·생성/완료/분석 시각을 저장한다. 개인 학습과 복습은 FREE이며 실제 수업은 LIVE다. 같은 세션의 배정은 유지하고 실제 풀이를 연결해 이어하기와 완료·분석을 판정한다. 물리 컬럼·키·시각 기준은 [ERD 학습 세션](./erd.md#table-learning_sessions)을 따른다.

### 4.1 참조 중심 저장과 문제 내용 응답

- DB의 세션 내부 배정 목록은 문제 전문이 아닌 기존 문제 식별자와 배정 순서·반복 위치 등을 보관한다. 세션 JSON 컬럼 저장은 배정 JSON 규칙으로 승인했고, problem_id는 10진수 문자열이며 배정 객체는 두 승인 키만 저장한다.
- 백엔드는 세션 소유권·배정을 확인하고 참조 문제를 DB에서 조회해 필요한 문제 내용을 조합하여 응답한다.
- 프론트가 배정 목록을 먼저 조회한 뒤 문제 ID마다 별도 GET을 다시 호출하는 절차를 필수로 요구하지 않는다. DB 조회가 곧 HTTP GET 요청을 뜻하지는 않는다.
- 문제 ID만으로 자원 접근이나 정답 공개를 허용하지 않는다. 학습자는 미제출 문항의 정답을 받지 않고, 정상 독립 풀이가 저장된 뒤에만 그 문항의 정오답·점수·Cell별 차이와 정답 패턴을 받는다. 이미 제출한 문항은 같은 저장 결과와 정답을 다시 볼 수 있다. 수업의 담당 INSTRUCTOR·활성 LEARNER 현재 문제 권한과 실제 응답/오류는 실시간 수업 API 계약을 따른다.

세션 JSON 컬럼의 승인이 DB 저장 구조를 그대로 API 응답에 노출하는 승인은 아니다. 참조 목록을 백엔드에서 검증하고 필요한 문제 내용을 조합해 제공한다. 세션 생명주기·갱신/동시성은 세션 생명주기 규칙, 실제 GET 경로·응답 DTO는 개인 학습 API 계약을 따른다.

### 4.2 배정 문항과 풀이의 식별·저장 연결

개인 학습·복습에서 세션 ID+배정 순번으로 배정 문항을 식별한다. 별도의 배정 문항 UUID는 만들지 않으며 처음 배정한 순번을 이어하기·제출·결과 조회에 유지한다. 백엔드는 본인 세션·배정 순번·배정 문제·실제 풀이의 일치를 확인한다. 배정 순번 규칙으로 배정 순번은 1부터 시작하도록 확정했다. 현재 10문항에는 1~10을 부여하며 같은 문제를 반복 배정해도 각 배정 위치는 다른 순번을 가진다. 이어하기·제출·결과 조회에서도 최초 순번을 유지한다.

같은 문제를 반복한 새 풀이는 배정 위치별로 구분하고 최초 오답을 이후 정답으로 덮어쓰지 않는다. 문제 참조·유형·학습 요소와 실제 평가를 연결해 완료 후 BKT 분석에 활용한다. 결과 조회 자체로 다시 갱신하지 않으며, 요소별 명시 오류 귀속과 배정 순번별 이진 관측 처리를 적용한다.

이 조합은 멱등 키나 기존 Attempt ID를 대신하지 않는다. problem_attempts.learning_session_id BIGINT NULL과 session_item_no INT NULL을 사용한다. 두 값은 개인 학습·복습에서 함께 필수, LIVE에서 함께 NULL이며, 둘 다 NULL 또는 둘 다 값이어야 한다. learning_session_id는 learning_sessions.id를 DELETE/UPDATE RESTRICT로 참조하고 두 컬럼 조합은 UNIQUE다. session_item_no는 DB에서 1 이상, 현재 정확한 1~10 범위와 JSON 항목 일치는 백엔드에서 검증한다. API 전달 형식·오류 코드는 4.5절을 따른다.

### 4.3 배정 JSON 저장 계약·검증

| 내부 항목 | 정의 |
| --- | --- |
| 최상위 값 | 배정 문항 객체의 JSON 배열 |
| item_no | 필수 정수. 배정 순번이며 현재 1~10 |
| problem_id | 필수 10진수 문자열. 기존 problems.id(BIGINT) 참조 |
| 저장 순서 | item_no 오름차순 |
| 순번 검증 | 현재 정확히 10항목, 1~10 누락·중복·범위 초과 금지 |
| 문제 검증 | 백엔드가 problems 존재 및 curriculum_problems의 기준 단계 소속 확인 |
| 반복 허용 | 서로 다른 item_no에서 같은 problem_id 참조 가능 |
| 복제 제외 | 문제 전문·정답·제출 답안·채점 결과는 배정 JSON에 저장하지 않음 |

백엔드가 구성한 목록이 배열인지, 각 항목이 객체인지, 두 필수값이 누락되거나 NULL이 아닌지 검사한다. item_no는 정수로 검증하며 빈 배열·JSON null·누락/중복/범위 초과 순번·잘못된 문제 참조·다른 단계의 문제로는 세션을 저장하지 않는다. 저장 전 item_no 오름차순으로 구성한다. 이는 클라이언트가 배정 목록을 직접 제출한다거나 정렬되지 않은 API 요청을 특정 오류 코드로 거절한다는 승인이 아니다.

같은 problem_id의 반복은 서로 다른 item_no에서 허용한다. 한 세션은 서로 다른 문제 ID 5개를 선정하고 각 ID를 정확히 두 번 사용한다. DB JSON의 snake_case 키를 API DTO로 그대로 노출하지 않으며, 공통 API camelCase 규칙은 유지한다.

이는 DB JSON 내부 계약이다. problem_id는 10진수 문자열이고 MVP 배정 객체는 item_no/problem_id만 저장한다. API는 camelCase DTO와 필요한 문제 내용을 조합하되 저장 JSON을 그대로 노출하지 않는다. 승인되지 않은 추가 키·스키마 버전·문제 전문 스냅샷은 넣지 않는다. 트랜잭션/동시성은 세션 생명주기 규칙, 오류 응답은 개인 학습 API 계약을 따른다. JSON 키 2개는 물리 컬럼 추가로 계산하지 않는다.

### 4.4 세션 생성·이어하기·완료·분석 계약

세션 상태는 새 status 컬럼 없이 시각으로 판정한다. completed_at이 NULL이면 진행 중, completed_at만 존재하면 완료·분석 대기, completed_at과 analysis_applied_at이 모두 존재하면 분석 반영 완료다. status·current_item_no·paused_at·updated_at 컬럼은 추가하지 않는다.

사용자당 미완료 개인 학습 세션은 최대 한 건이다. 생성 요청은 사용자 행을 잠근 트랜잭션에서 기존 미완료 세션을 확인한다. 같은 단계의 미완료 세션이 있으면 기존 세션을 반환하고, 다른 단계면 진행 중 세션이 있음을 알리며 새로 만들거나 덮어쓰지 않는다. 직전 세션이 완료됐어도 분석 반영 전이면 다음 맞춤 세션을 만들지 않는다. 배정 10문항을 구성한 뒤 존재·단계 소속·JSON 구조를 최종 검증해 세션을 저장하며 실패하면 전부 롤백한다.

이어하기 위치는 별도 커서 없이 assigned_problems의 item_no와 저장된 problem_attempts.session_item_no를 대조해 아직 풀이가 없는 가장 작은 순번으로 계산한다. 제출 완료된 풀이와 결과만 복원하며 미제출 입력은 서버에 저장하지 않는다. 최초 배정 순서와 문제는 유지한다. MVP에서는 자동 만료·자동 삭제·포기·초기화 기능을 두지 않고 완료할 때까지 같은 세션을 이어간다.

한 문항은 problem_attempts와 필요한 attempt_cells·attempt_skill_results 및 최종 채점 결과가 모두 저장되어야 완료 문항으로 센다. 오답도 정상 제출이면 포함하고 검증·채점 실패 요청은 포함하지 않는다. 안내 연습은 배정 문항과 완료 계산에서 제외한다. 배정 JSON의 모든 위치에 저장 성공한 최종 독립 풀이가 한 건씩 있을 때 완료하며 현재는 10문항이다. 마지막 유효 풀이 저장 트랜잭션에서 전체를 확인해 completed_at을 최초 한 번 기록한다. 완료 후 새 풀이는 금지하고 동일 멱등 재전송만 기존 결과를 반환한다.

완료 처리와 분석 반영은 별도 트랜잭션이다. 분석은 세션 행을 잠그고 completed_at 존재·analysis_applied_at NULL을 재확인한 뒤 독립 풀이와 요소 평가로 BKT·취약점을 계산해 user_skill_memory 갱신과 analysis_applied_at 기록을 한 번에 커밋한다. 일부만 반영하거나 필요한 attempt_skill_results가 누락된 경우 성공으로 처리하지 않는다. 실패하면 전체 롤백하여 completed_at IS NOT NULL AND analysis_applied_at IS NULL 상태로 재시도한다. 동시 분석은 한 번만 반영하고 스킬 행은 skill_id 순서로 잠근다.

여러 기기의 조회는 허용하지만 쓰기는 서버 상태를 기준으로 직렬화한다. 동일 순번 동시 제출은 복합 UNIQUE와 트랜잭션으로 한 건만 성공하며, 매 제출 후 서버가 최신 풀이 목록으로 다음 순번과 완료 여부를 계산한다. 분석 대기 중에도 완료 결과는 조회할 수 있지만 다음 맞춤 세션은 분석 성공 후 생성한다.

created_at은 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), completed_at과 analysis_applied_at은 DATETIME(6) NULL DEFAULT NULL로 구체화한다. DB 연결 시간대는 UTC로 통일하고 세 값 모두 DB 시각을 사용하며 자동 ON UPDATE는 두지 않는다. 이어하기·재조회·재분석으로 기존 시각을 변경하지 않는다.

트랜잭션은 ① 배정 최종 검증+세션 생성, ② Attempt+Cell+요소 평가+필요 시 완료 처리, ③ BKT·취약점 갱신+분석 반영 시각 기록의 세 경계로 분리한다. 별도 세션 생성 멱등 키·분석 작업 테이블은 추가하지 않는다.

개인 학습 세션 API 경로·DTO·오류 코드는 개인 학습 API 계약으로 확정했다. 서버 상태 판정과 트랜잭션 규칙은 특정 화면의 완료 주장이나 결과 조회에 의존하지 않는다.

### 4.5 개인 학습 세션 공개 API

개인 학습 세션은 다음 여섯 endpoint만 MVP 공개 계약으로 사용한다.

| method·path | 성공 | 핵심 계약 |
| --- | --- | --- |
| `POST /api/v1/learning-sessions` | 201 새 세션 / 200 같은 단계 기존 미완료 세션 | 요청은 `curriculumStageId`, 최초 수준 초기화용 선택적 `initialUnlockedStageId`, 수업 복습용 선택적 `sourceLectureId`만 받는다. userId·문제 목록·problemId·attemptMode는 받지 않는다. |
| `GET /api/v1/learning-sessions/current` | 200 / 현재 세션 없음 204 | 본인의 IN_PROGRESS 또는 ANALYSIS_PENDING 한 건을 반환한다. |
| `GET /api/v1/learning-sessions/{sessionId}` | 200 | 상세·이어하기용. 존재하지 않거나 타인 소유면 모두 404다. |
| `POST /api/v1/learning-sessions/{sessionId}/items/{itemNo}/answers` | 201 새 풀이 / 동일 재전송 200 | `Idempotency-Key: <UUID v4>` 필수. 마지막 유효 답안이면 자동 완료한다. |
| `POST /api/v1/learning-sessions/{sessionId}/analysis` | 200 | 완료 세션을 분석·반영한다. 실패 시 원자 롤백 후 재시도할 수 있고, 이미 반영됐으면 기존 결과를 반환한다. |
| `GET /api/v1/learning-sessions/{sessionId}/result` | 200 | 분석 완료 결과만 조회하며 상태를 갱신하지 않는다. |

별도 resume·complete·delete·abandon·reset endpoint와 완료 세션 목록·기간별 통계 endpoint는 두지 않는다. 프론트엔드는 마지막 답안 응답의 ANALYSIS_PENDING을 확인한 뒤 analysis endpoint를 호출한다. 그 사이 중단되면 current 조회로 대기 상태를 복구해 분석을 재시도한다. 미완료 세션에는 자동 만료가 없고 같은 단계 생성 요청은 기존 세션을 반환하며, 다른 단계 시작은 기존 세션 완료 전까지 거부한다.

최초 세션, 이후 일반 세션과 수업 후 복습 요청은 다음과 같다. 모든 BIGINT 식별자는 JSON과 경로에서 10진수 문자열로 표현한다.

```json
{
  "curriculumStageId": "12",
  "initialUnlockedStageId": "17"
}
```

```json
{ "curriculumStageId": "12" }
```

```json
{
  "curriculumStageId": "12",
  "sourceLectureId": "42"
}
```

`users.level`이 NULL이면 `initialUnlockedStageId`가 필수다. 두 단계는 L1~L6에 존재해야 하고 이번 `curriculumStageId.orderNo`는 최고 해금 `initialUnlockedStageId.orderNo`보다 클 수 없다. 첫 모르는 단계에서 시작하면 두 값이 같고, 모두 안다고 답한 뒤 낮은 단계 복습을 선택하면 최고 해금은 L6, 이번 단계는 선택한 L1~L5다. Backend는 유효 문제 5개·배정 10항목을 확정한 뒤 users.level 초기화와 세션 생성을 한 트랜잭션으로 처리한다. 실패하면 둘 다 반영하지 않는다.

최초 성공 요청의 동일 재전송이 현재 활성 세션·선택 단계·기존 users.level과 일치하면 기존 세션과 200을 반환한다. 최초 이후 일반 요청은 `initialUnlockedStageId`를 생략한다. 이미 수준이 정해진 상태에서 새 초기화 값을 보내거나 기존 값과 다른 값이면 409 `INITIAL_LEVEL_CONFLICT`다. 하위 복습은 users.level을 낮추지 않는다.

`sourceLectureId`가 있으면 서버는 요청자가 해당 수업의 participants에 존재하는 LEARNER인지, 수업이 CLOSED인지, 본인의 저장된 LIVE 오답이 선택한 단계의 `curriculum_problems`에 연결되는지를 확인한다. 클라이언트가 문제 ID나 정오답을 지정하지 않는다. 연결되는 LIVE 오답을 우선 후보로 쓰고 5개보다 부족하면 기존 BKT 추천과 SYSTEM 기본 문제로 보충한다. 선택 단계의 유효 문제 5개를 끝내 확보하지 못하면 기존 `LEARNING_CONTENT_NOT_READY`를 사용한다. LIVE 오답이 0개인 경우에도 선택 단계의 일반 개인화 후보와 기본 문제로 세션을 구성하며 이를 별도 오류로 처리하지 않는다.

수업 후 복습도 `FREE`이며 별도 REVIEW mode·세션 유형·자동 생성·기존 진행 세션 덮어쓰기를 사용하지 않는다. `sourceLectureId`는 생성 시 후보 조회 문맥일 뿐 응답·assigned_problems·learning_sessions 컬럼에 저장하지 않는다. 어느 수업에서 파생됐는지 이력·통계를 제공할 때만 별도 저장 컬럼을 확장 검토한다. 기존 `contentMode`는 LIVE 출처를 새 ENUM으로 추가하지 않고, 실제 배정이 일반 후보만이면 PERSONALIZED, 기본 문제가 섞이면 MIXED, 전부 기본 문제면 DEFAULT로 계산한다.

선택 단계의 서로 다른 LIVE 오답이 5개를 초과하면 각 문제에서 실제 오답으로 판정된 요소만 사용해 순위를 계산하고 여러 오답 요소 중 가장 우선인 요소가 문제 순위를 대표한다. 순서는 ① 관측된 취약 요소의 낮은 masteryProbability, ② 미관측 요소, ③ 학습 중 요소, ④ 숙련 요소다. 이 특별 후보군에서는 미관측 문제에 기본 문제 여부를 요구하지 않는다. 동률은 개인 FREE 풀이 없음, 마지막 개인 FREE 풀이가 오래됨, problemId 오름차순으로 고정한다. errorCount·LIVE attemptedAt·수업 출제 순서·무작위 값은 가중치가 아니다. 상위 서로 다른 5개를 선정한 뒤 각 문제를 두 번 배정하고 같은 문제를 연속 배정하지 않는다.

세션 생성·current·상세의 기본 응답 필드는 다음과 같다. 단계 요약의 title은 curriculum_stages.name을 조합한 표시 이름이며 별도 DB title 컬럼을 뜻하지 않는다. 단계 목록 API의 name도 같은 원본을 사용한다.

| 필드 | 타입·의미 |
| --- | --- |
| sessionId | decimal string |
| curriculumStage | `{stageId, level, title}`; stageId는 decimal string |
| status | `IN_PROGRESS`, `ANALYSIS_PENDING`, `ANALYZED`; completedAt·analysisAppliedAt에서 파생 |
| contentMode | `PERSONALIZED`, `MIXED`, `DEFAULT`; 실제 배정 구성에서 파생, DB 저장값 아님 |
| answeredCount / totalCount / nextItemNo | 제출 완료 수, 전체 수, 다음 미풀이 순번. 다음이 없으면 nextItemNo는 null |
| createdAt / completedAt / analysisAppliedAt | ISO 8601 UTC, 미기록 시 null |
| items | itemNo 순서의 조합 문항 목록 |

각 item은 `itemNo`, `problemId`, `problemType`, `targetText`, `cellCount`, `submitted`를 가진다. `result`는 제출된 item에만 포함하고 미제출 item에는 expectedPattern·diffMask·isCorrect·score를 포함하지 않는다. 같은 problemId가 반복돼도 itemNo가 다르면 서로 다른 배정 위치다. DB assigned_problems를 그대로 응답하지 않고 권한 확인 뒤 필요한 문제 내용을 조합한다.

답안 요청은 다음 필드만 허용한다.

```json
{
  "cells": [
    { "cellIndex": 1, "inputPattern": 9 }
  ],
  "durationMs": 3240
}
```

durationMs는 null일 수 있다. userId, problemId, curriculumStageId, lectureId, attemptMode, isCorrect, score, expectedPattern, diffMask, status는 받지 않는다. 서버가 세션·itemNo의 배정 문제, 사용자, 단계와 `FREE` mode를 결정한다. `Idempotency-Key`는 UUID v4이며 DB에는 `CHAR(36) NOT NULL UNIQUE`로 저장한다. 같은 사용자·같은 키·같은 정규화 요청은 기존 결과와 200, 새 저장은 201, 같은 키·다른 내용은 409다.

세션 결과는 해당 세션의 `sessionSkillSummary`와 조회 시점의 `currentSkillStates`를 분리한다. sessionSkillSummary는 세션 관측·정오답 요약이고 currentSkillStates는 현재 user_skill_memory를 읽은 값이므로 이후 학습에 따라 바뀔 수 있다. 세션별 과거 숙련도 Snapshot을 뜻하지 않는다. 완료 기록은 DB에 유지하고 소유자가 이미 알고 있는 sessionId의 상세·결과 조회는 허용하지만, 완료 세션을 탐색하는 목록·통계 API와 완료 당시 숙련도 Snapshot은 MVP에서 제공하지 않는다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 인증되지 않음 |
| 403 | LEARNER_ACCESS_REQUIRED | 학습자 역할이 아님 |
| 403 | CURRICULUM_STAGE_LOCKED | 열리지 않은 단계 |
| 404 | LECTURE_NOT_FOUND | sourceLectureId 수업 없음 또는 요청 학습자의 참여 이력 없음 |
| 404 | LEARNING_SESSION_NOT_FOUND | 세션 없음 또는 타인 소유 |
| 409 | SELF_ASSESSMENT_REQUIRED | 최초 단계가 정해지지 않음 |
| 409 | INITIAL_LEVEL_CONFLICT | 이미 정한 최고 해금 단계와 다른 초기화 값 또는 최초가 아닌 초기화 요청 |
| 409 | LEARNING_SESSION_IN_PROGRESS | 다른 단계의 미완료 세션 존재 |
| 409 | LEARNING_ANALYSIS_PENDING | 직전 완료 세션 분석 대기 |
| 409 | LEARNING_SESSION_ALREADY_COMPLETED | 완료 세션에 새 답안 제출 |
| 409 | SESSION_ITEM_ALREADY_ANSWERED | 다른 멱등 키로 이미 제출한 item 재제출 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키에 다른 요청 내용 |
| 409 | INVALID_LECTURE_STATE | sourceLectureId의 본인 참여 수업이 CLOSED가 아님 |
| 422 | INVALID_LEARNING_SESSION_INPUT | 필수값 누락·BIGINT ID 10진수 문자열 형식 등 세션 생성 입력 오류 |
| 422 | INVALID_ANSWER_CELLS | Cell 수·순번·범위 검증 실패 |
| 503 | LEARNING_CONTENT_NOT_READY | 유효 문제 5개를 확보하지 못함 |
| 503 | LEARNING_ANALYSIS_UNAVAILABLE | 분석 트랜잭션 실패·일시 처리 불가 |

오류 body는 `{"code":"...","message":"..."}`다. sourceLectureId 검증은 타인 수업 존재를 노출하지 않는 원칙을 적용해 수업 없음과 참여 이력 없음을 모두 404 `LECTURE_NOT_FOUND`로 처리한다. 참여 이력이 있는 본인 수업의 상태가 CLOSED가 아니면 409 `INVALID_LECTURE_STATE`다. 연결되는 LIVE 오답이 0개인 것은 오류가 아니며 일반 후보·기본 문제로 구성한다. 세션 삭제·포기·초기화·자동 만료와 완료 세션 목록·통계·과거 숙련도 Snapshot은 MVP 제외로 확정했다. 분석 실패는 완료 기록을 유지한 채 기존 `LEARNING_ANALYSIS_UNAVAILABLE`로 응답하고 같은 분석 endpoint를 재시도한다. 시간 제한은 측정 뒤 별도 승인한다.

## 5. 문제 등록·조회 업무 계약

| ID / 기능 | 입력 의미 | 처리·결과 | 저장·처리 규칙 |
| --- | --- | --- | --- |
| A-11 / F-11 문제 작성·등록 | targetText·문제 유형과 1~6개 정답 Cell·학습 요소/단계 문맥 | 비어 있지 않은 목표 설명, 허용 유형, 1부터 연속된 고유 Cell 순번, 패턴 0~63, DOT 유형 1 Cell, 최소 1개 학습 요소·단계 및 각 요소의 독립 평가 가능성을 모두 검증한 뒤 관련 행을 한 번에 저장. 실패 사유 전달 | 공개 등록은 서버가 source_type=INSTRUCTOR·creator_user_id=현재 강사로 결정. 공개 DTO·멱등, SYSTEM Seed·AI 내부 경계 적용 |
| A-12 / F-11·F-12 문제 조회·선정 | 문제·단계·학습 요소 식별 문맥 | 검증된 저장소 문제 조회. INSTRUCTOR 문제은행은 요약 목록과 정답 포함 상세를 구분. 개인 학습은 배정 확인, LIVE는 담당 강사/활성 참여자·현재 문제 확인 후 필요한 내용을 조합 | 문제은행 필터·응답·오류. 학습자는 문제은행 직접 접근 불가, 세션·LIVE의 정답 공개는 적용 |
| A-13 / F-13 기본 문제 준비 | 제공할 L1~L6의 기본 콘텐츠 | 단계별 5개씩 총 최소 30개의 서로 다른 검증 SYSTEM 문제를 준비하고 각 문제를 하나의 기본 단계와 default_order_no 1~5에 연결. 버전 Seed 전체를 사전 검증하고 하나라도 실패하면 롤백 | L0 제외. 작성자/공개 멱등 키 NULL. Seed HTTP 없이 일회성 마이그레이션 이력으로 중복 실행 방지. 문서 승인은 실제 Seed 생성 완료가 아님 |

검증 전·실패 문제, validation_status와 검증 이력은 저장하지 않는다. 등록 관련 행 일부만 남기지 않는다. 등록된 문제·정답은 불변이며 수정·삭제 API는 제공하지 않는다. 변경 내용은 새 문제로 등록한다. 수업 출제 여부·출제 이력은 등록의 선행 조건이 아니다.

`learning_skills.skill_code`는 유형과 무관하게 전체 고유다. 같은 요소를 여러 문제에 연결하되 주·보조 구분은 없다. 학습 요소 유형은 JAMO / RULE / DOT이고 문제 형태는 DOT_POSITION / DOT_PATTERN / JAMO / SYLLABLE / WORD / SENTENCE다. DOT_POSITION은 L1 한 점 위치·번호, DOT_PATTERN은 L2 여러 점 조합·입력에 사용한다. 문제 형태와 학습 요소 유형은 서로 대체하지 않으며 SENTENCE 값 존재가 L8 학습의 MVP 승인은 아니다.

### 5.1 문제 등록·조회 공개 HTTP API

| 목적 | Method·path | 권한 | 성공 |
| --- | --- | --- | --- |
| 문제 등록 | `POST /api/v1/problems` | INSTRUCTOR | 신규 `201`, 동일 멱등 재요청 `200` |
| 문제은행 목록 | `GET /api/v1/problems` | INSTRUCTOR | `200` 페이지 응답 |
| 문제 상세·정답 확인 | `GET /api/v1/problems/{problemId}` | INSTRUCTOR | `200` |
| 학습 요소 선택 목록 | `GET /api/v1/learning-skills` | INSTRUCTOR | `200` 페이지 응답 |
| 커리큘럼 단계 목록 | `GET /api/v1/curriculum-stages` | LEARNER·INSTRUCTOR | `200` |

ACTIVE·이메일 인증을 마친 사용자만 Access Token으로 접근한다. 공개 문제 등록 body는 `sourceType`, `creatorUserId`, `problemId`, `defaultOrderNo`, `createdAt`을 받지 않는다. 서버가 `sourceType=INSTRUCTOR`, `creatorUserId=현재 INSTRUCTOR`로 결정한다. LEARNER는 문제은행·학습 요소 선택 목록에 직접 접근하지 않으며 개인 세션 또는 참여 수업의 문맥으로만 문제를 받는다. ADMIN에게 문제 관리 권한을 자동 부여하지 않는다. SYSTEM 기본 문제는 Seed 작업, AI 문제는 후속 내부 서비스 계약으로 등록한다.

등록 요청은 UUID v4 `Idempotency-Key` 헤더와 다음 body를 사용한다.

```json
{
  "problemType": "JAMO",
  "targetText": "ㄱ",
  "cells": [
    { "cellIndex": 1, "expectedPattern": 9 }
  ],
  "skillIds": ["31"],
  "curriculumStageIds": ["3"]
}
```

BIGINT ID는 10진수 문자열이다. `targetText`는 앞뒤 공백 제거·NFC 정규화 후 비어 있으면 거부한다. cells는 1~6개·1부터 연속된 고유 순번·0~63 패턴을 만족하고 DOT_POSITION·DOT_PATTERN은 정확히 1 Cell이어야 한다. skillIds는 중복 없는 1개 이상이며 존재하고 규칙 엔진이 각각 독립 평가할 수 있어야 한다. curriculumStageIds도 중복 없는 1개 이상이며 MVP L1~L6만 허용하고 L0·L7·L8은 거부한다. 모든 검증에 성공한 뒤 problems·problem_cells·problem_skills·curriculum_problems를 한 트랜잭션으로 저장한다. 실패·장애 시 일부 행을 남기지 않는다.

### 5.2 응답·정답 공개

등록 성공과 문제 상세는 `problemId`, `problemType`, `targetText`, `sourceType`, `cells`, `skills`, `curriculumStages`, `createdAt`을 반환한다. cells는 `{cellIndex, expectedPattern}`, skills는 `{skillId, skillType, skillCode, name}`, curriculumStages는 `{stageId, level, name}` 배열이다. 신규 등록 `201`은 `Location: /api/v1/problems/{problemId}`도 반환한다.

문제은행 목록의 각 item은 `problemId`, `problemType`, `targetText`, `sourceType`, `cellCount`, `skills`, `curriculumStages`, `createdAt`만 포함하고 cells·expectedPattern을 포함하지 않는다. 문제은행 목록에는 정답을 넣지 않고 INSTRUCTOR의 상세·등록 성공 응답에 포함한다. LEARNER에게는 문제 ID만으로 정답을 공개하지 않고 개인 세션·LIVE 제출 전후 규칙을 적용한다.

문제은행 목록은 선택적으로 curriculumStageId, skillId, problemType, sourceType을 받고 여러 필터는 AND로 적용한다. page 기본 0, size 기본 20·최대 100이다. sort는 `createdAt,asc` 또는 `createdAt,desc`만 허용하고 기본은 내림차순이며 problemId를 같은 방향의 보조 정렬로 사용한다. 자유 텍스트 검색·다중 단계/요소 OR 필터는 MVP 계약에 포함하지 않는다. 결과가 없으면 빈 items의 200 페이지 응답이다.

학습 요소 목록은 skillType 선택 필터와 page/size를 사용하며 기본 20·최대 100이다. 각 item은 `skillId`, `skillType`, `skillCode`, `name`, `description`이다. 공개 API로 학습 요소를 생성·수정·삭제하지 않는다. 커리큘럼 단계 목록은 orderNo 오름차순의 `stageId`, `level`, `name`, `description`, `orderNo`를 반환하며 L0~L6을 제공한다. 사용자 요약의 level과 단계 목록의 level·orderNo를 대조해 열린 단계를 표시한다. 최초 수준 저장과 진급은 개인 학습 세션 생성·완료 분석에서만 처리한다.

### 5.3 등록 멱등·물리 저장 계약

`problems.creation_idempotency_key CHAR(36) NULL`을 추가하고 `(creator_user_id, creation_idempotency_key)`를 UNIQUE로 둔다. 공개 INSTRUCTOR 등록에서는 UUID v4 값을 필수로 저장하며 기존 행과 SYSTEM·AI 등록은 NULL을 허용한다. 정규화한 targetText, cellIndex 순 cells, 중복 제거·정렬한 skillIds와 curriculumStageIds를 기준으로 요청을 비교한다. 같은 강사·같은 키·같은 요청은 기존 상세와 200, 같은 키·다른 요청은 409이며 신규 저장은 201이다. 의미가 유사하거나 같은 문제의 의도적 중복을 이 키로 판정하지 않는다.

`problems.id`와 `problem_cells.id`는 BIGINT·PK·AUTO_INCREMENT로 생성한다. `problems.created_at`은 DATETIME(6)·NOT NULL·DEFAULT CURRENT_TIMESTAMP(6)의 UTC DB 시각이다. problems를 참조하는 problem_cells·problem_skills·curriculum_problems의 삭제·갱신 FK는 RESTRICT다. 문제 수정·삭제가 없으므로 등록 결과와 연결을 불변으로 유지한다.

### 5.4 문제 API 오류

오류 body는 `{code,message}`다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | 인증되지 않았거나 유효하지 않은 Access Token |
| 403 | `INSTRUCTOR_ACCESS_REQUIRED` | 문제 등록·은행·상세를 INSTRUCTOR가 아닌 역할이 요청 |
| 404 | `PROBLEM_NOT_FOUND` | 문제 상세 대상이 없음 |
| 409 | `PROBLEM_CREATION_KEY_REUSED` | 같은 강사의 등록 멱등 키를 다른 정규화 요청에 재사용 |
| 422 | `INVALID_PROBLEM_INPUT` | 허용하지 않은 필드·유형 또는 비어 있는 targetText 등 기본 입력 오류 |
| 422 | `INVALID_PROBLEM_CELLS` | Cell 수·순번·패턴·DOT 유형 규칙 위반 |
| 422 | `INVALID_PROBLEM_SKILLS` | 요소 없음·중복·미존재·독립 평가 불가 |
| 422 | `INVALID_PROBLEM_STAGES` | 단계 없음·중복·미존재 또는 L0/L7/L8 연결 |
| 400 | `INVALID_PROBLEM_FILTER` | 허용하지 않은 필터·정렬·페이지 범위 |
| 503 | `PROBLEM_VALIDATION_UNAVAILABLE` | 검증 시스템 장애로 원자 등록 불가 |

규칙 위반 message에는 호출자가 수정할 수 있는 실패 사유를 표시하되 스택·내부 예외·다른 사용자의 정보를 노출하지 않는다. 의미상 중복 문제 자동 판정과 관리자 콘텐츠 운영은 현행 계약에 포함하지 않는다. SYSTEM Seed와 AI 내부 생성/검증 경계는 문제 사전 준비 규칙을 따른다.

### 5.5 SYSTEM Seed·AI 내부 후보 생성/검증

SYSTEM 기본 문제는 공개·내부 HTTP API 없이 버전 관리 Seed 파일과 일회성 마이그레이션 작업으로 등록한다. L1~L6 단계별 서로 다른 기본 문제 5개씩·전체 최소 30개, defaultOrderNo 1~5, 문제·Cell 채점 정답 Cell, 독립 평가 가능한 학습 요소와 기준 단계 하나의 연결을 Seed 버전 전체에 대해 먼저 검증한다. 하나라도 실패하면 해당 버전의 problems·problem_cells·problem_skills·curriculum_problems 저장 전체를 롤백한다. 마이그레이션 실행 이력으로 재실행 중복을 방지하고 SYSTEM 행은 sourceType=SYSTEM, creatorUserId·creationIdempotencyKey=NULL이다.

| 목적 | Method·path | 호출 주체 | 저장 권한 |
| --- | --- | --- | --- |
| AI 문제 후보 생성 | `POST /api/v1/problem-candidates` | Backend만 | AI 서비스 저장 없음 |
| 문제 내용 규칙 검증 | `POST /api/v1/problem-validations` | Backend만 | 검증 결과 저장 없음 |

두 endpoint는 AI 서비스의 내부 `/api/v1`에 있으며 외부 호스트 Nginx의 사용자 `/api/` 라우팅에 노출하지 않는다. 내부 리스너·네트워크 제한과 필수 `X-Internal-Api-Key`를 함께 적용하고 사용자 Access Token으로 직접 호출하지 않는다. 키는 환경변수 또는 배포 Secret으로 주입하며 로그·응답·도메인 DB에 남기지 않는다. 누락·불일치는 401 `INTERNAL_UNAUTHORIZED`다.

후보 생성 요청 body는 Backend가 확인한 L1~L6 curriculumStage, 하나 이상의 learning skill 코드·유형, 허용 problemType, 1~5의 count를 전달한다. body requestId는 제거하고 `X-Request-Id` 헤더를 사용한다. 응답 후보는 검증 가능한 problemType·targetText·cells 내용을 제공한다. AI가 단계·요소 식별값을 반환하더라도 연결 권한으로 신뢰하지 않으며 Backend가 요청 전에 확인한 단계·요소를 사용한다. 검증 endpoint는 공개 강사 문제 또는 AI 후보의 문제 내용 규칙을 검사하고 `valid`와 `violations`를 반환한다.

AI 서비스는 문제 ID·sourceType·creatorUserId·createdAt을 정하거나 DB에 접근하지 않는다. Backend가 관계 대상 존재·독립 평가 가능성·문제·Cell 채점 정답 Cell 조건을 최종 확인한 후보만 sourceType=AI, creatorUserId=NULL, creationIdempotencyKey=NULL, defaultOrderNo=NULL로 네 관련 테이블에 원자 저장한다. AI 문제는 일반 추천 후보이며 SYSTEM 기본 문제를 대체하지 않는다. 실패·미검증 후보와 검증 중간 결과는 저장하지 않는다.

학습 세션 생성 중 AI를 동기 호출하지 않는다. 별도 사전 생성 작업이 문제은행을 보충하며 장애·시간 초과·유효 후보 부족이면 저장 없이 기존 SYSTEM 문제를 사용한다. 생성 처리가 정상 종료됐지만 유효 후보가 없으면 200과 빈 `candidates` 배열, 검증 처리가 정상 종료됐지만 내용이 부적합하면 200과 `valid=false`, `violations`를 반환한다. `NO_VALID_PROBLEM_CANDIDATE`는 top-level 오류로 사용하지 않고, `INVALID_PROBLEM_CONTENT`는 violations 내부 사유 code로 사용한다.

오류 body는 `{code,message}`이며 Backend는 내부 오류를 외부에 그대로 전달하지 않는다.

| HTTP | code | 적용 조건 |
| --- | --- | --- |
| 401 | `INTERNAL_UNAUTHORIZED` | 내부 API 키 누락·불일치 |
| 422 | `INVALID_GENERATION_REQUEST` | 후보 생성 요청 형식·허용 범위 위반 |
| 422 | `INVALID_VALIDATION_REQUEST` | 문제 검증 요청 형식 위반 |
| 503 | `AI_GENERATION_UNAVAILABLE` | 후보 생성 서비스·의존 기능 사용 불가 |
| 503 | `PROBLEM_VALIDATION_UNAVAILABLE` | 검증 서비스·의존 기능 사용 불가 |
| 504 | `AI_GENERATION_TIMEOUT` | 후보 생성 처리 시간 초과 |
| 504 | `PROBLEM_VALIDATION_TIMEOUT` | 문제 검증 처리 시간 초과 |
| 500 | `INTERNAL_SERVER_ERROR` | 예상하지 못한 내부 오류 |

AI 사전 생성 실패·빈 후보는 SYSTEM 기본 문제로 보충하고 이후에도 서로 다른 유효 문제 5개가 부족하면 공개 503 `LEARNING_CONTENT_NOT_READY`를 사용한다. 강사 문제의 `valid=false`는 violations를 Backend 규칙에 대조해 기존 422 문제 오류로 변환하며, 검증 서비스 자체가 중단된 경우에만 공개 503 `PROBLEM_VALIDATION_UNAVAILABLE`을 사용한다. AI는 Should이며 모델·제공자·프롬프트·자동 생성 주기·재고·시간/재시도 제한은 AI 구현 시 정한다.

AI 모델·프롬프트·생성/검증 규칙 버전의 추가 DB 저장은 MVP 범위에서 제외한다. BKT 설정 버전은 기존 user_skill_memory.algorithm_version에 기록한다.

## 6. 답안·채점·멱등 계약

### A-14 / F-08·F-16·F-17·F-19 답안 제출과 최종 채점

개인 학습 답안은 경로의 sessionId·itemNo, `Idempotency-Key` UUID v4 헤더, body의 cells와 NULL 가능한 durationMs로 제출한다. 문제·사용자·단계·FREE 문맥은 서버가 결정한다. 개인 학습·복습에서는 세션 ID+배정 순번으로 본인 세션의 배정 문항과 제출의 연결을 백엔드에서 확인하며, 동일 문제의 반복 배정을 문제 ID만으로 혼동하지 않는다. 서버는 저장된 검증 정답과 원본 답안을 기준으로 최종 채점하고 expectedPattern·diffMask·score·isCorrect의 클라이언트 값을 신뢰하지 않는다.

| 데이터 | 결과 의미 |
| --- | --- |
| problem_attempts | 사용자·문제·문맥·최종 정오답·풀이 요약·멱등 키·learning_session_id/session_item_no, 개인 세션 연결·복합 UNIQUE |
| attempt_cells | Cell 위치별 정답·입력·차이 |
| attempt_skill_results | 학습 요소별 정오답·오류 평가 |

문제·Cell·자동 채점 계약은 다음과 같다.

- problemType은 DOT_POSITION / DOT_PATTERN / JAMO / SYLLABLE / WORD / SENTENCE다. targetText는 문자 원문뿐 아니라 ‘1번 점’, ‘1·4번 점’ 같은 사람이 읽을 수 있는 목표 설명이며 비어 있을 수 없다. promptText 컬럼 없이 유형별 고정 안내 템플릿과 조합한다.
- 문제와 답안의 Cell 순번은 1부터 시작한다. 문제는 1~6 Cell을 1부터 누락 없이 가지며 DOT_POSITION·DOT_PATTERN은 정확히 1 Cell이다. 각 문제/풀이 안의 순번은 중복될 수 없다.
- 6점은 왼쪽 1·2·3, 오른쪽 4·5·6이고 bit 0~5의 값 1·2·4·8·16·32에 대응한다. expectedPattern·inputPattern·diffMask는 0~63이며 0은 명시적인 빈 점형이다.
- 최종 제출은 정답과 같은 Cell 수 및 정확한 순번을 모두 포함한다. 빈 Cell도 inputPattern=0으로 명시한다. 누락·초과·중복·잘못된 순번·범위 밖 패턴을 거부하고 누락을 0으로 보정하지 않으며 관련 행을 일부 저장하지 않는다.
- 서버는 Cell마다 diffMask=expectedPattern XOR inputPattern을 계산한다. 모든 차이가 0일 때만 전체 정답이다. 오답도 정상 저장되면 완료 문항으로 센다.
- score는 전체 정답 Cell의 6개 점 위치를 분모로 하는 점 단위 정확도다. 모든 diffMask의 1비트 수를 오류 점 수로 하여 (전체 점 위치 수-오류 점 수)/전체 점 위치 수를 소수점 넷째 자리로 반올림한다. 0~1 범위이며 정답이면 1이다. 부분 점수는 BKT 관측으로 사용하지 않는다.
- attemptedAt·respondedAt은 DB 최종 수신 시점의 UTC DATETIME(6)이고 자동 갱신하지 않는다. durationMs는 클라이언트 monotonic clock 기반 경과 시간으로 NULL 가능·0 이상이며 직접 BKT 관측값이 아니고 임의 최댓값을 두지 않는다.
- 제출 전에는 학습자에게 정답 패턴을 제공하지 않는다. 정상 독립 풀이 저장 뒤 정오답·점수·Cell별 diffMask·expectedPattern을 제공하고, 이미 제출한 문항은 같은 저장 결과와 정답을 다시 볼 수 있다. 미제출 문항은 공개하지 않는다. INSTRUCTOR만 문제은행 상세에서 정답을 조회한다. ADMIN에게 문제 관리 권한을 자동 부여하지 않는다.
- 정답 따라 하기는 독립 풀이를 먼저 저장한 뒤 프론트가 공개된 정답으로 음성·햅틱·아두이노 안내를 제공한다. 별도 problem_attempts로 저장하거나 10문항·BKT에 포함하지 않고 최초 결과를 덮어쓰지 않는다.

문제 등록도 targetText·허용 유형·1~6개 정답 Cell·연속/고유 순번·0~63 패턴·DOT 유형 1 Cell·최소 1개 학습 요소·요소별 독립 평가 규칙을 저장 전에 모두 검증한다. 실패하면 문제·Cell·요소·단계 연결을 전부 저장하지 않는다. 요소별 오답 귀속과 BKT는 요소 평가·BKT 규칙, 개인 학습 API는 개인 학습 API 계약, AI 내부 전송은 내부 서비스 계약을 따른다. BLE 범위는 접근성·장치 범위이며 실제 패킷 상수는 IoT 구현 계약이다. 전체 오답을 모든 요소 오답으로 일괄 복사하지 않는다.

#### 요소별 평가·BKT API 계약

- 등록·출제 가능한 문제는 최소 한 개의 학습 요소를 가져야 하고, 각 요소는 규칙 엔진이 독립적으로 판정할 수 있어야 한다.
- 유효한 독립 풀이 한 건을 저장할 때 연결된 모든 요소에 대해 정확히 한 건의 요소 평가 결과를 함께 저장한다. 결과의 요소 집합은 문제의 연결 요소 집합과 같아야 하고 `(attemptId, skillId)`는 중복될 수 없다. 누락·초과·중복·판정 실패가 있으면 Attempt·Cell·요소 결과를 전부 롤백한다.
- `isCorrect=true`이면 `errorCount=0`, `isCorrect=false`이면 `errorCount>=1`이다. 오류는 명시된 요소 규칙에 따라 귀속하고 전체 문제의 오답을 모든 요소에 복사하지 않는다.
- BKT 입력은 요소 결과의 이진 `isCorrect`뿐이다. `score`, `errorCount`, `durationMs`는 직접 입력하지 않는다. 완료한 개인 학습 세션의 독립 풀이만 관측하며 자가 진단·안내·미완료·재전송·조회·LIVE는 제외한다. 한 세션의 동일 요소 관측은 `sessionItemNo` 순서로 모두 적용한다.
- BKT_V1은 P(L0)=0.20, P(T)=0.10, P(G)=0.20, P(S)=0.10이다. 정답 posterior는 `p*(1-S)/(p*(1-S)+(1-p)*G)`, 오답 posterior는 `p*S/(p*S+(1-p)*(1-G))`이며, 이후 `pNext=posterior+(1-posterior)*T`를 적용한다.
- 첫 유효 관측에서 사용자·요소 상태를 생성한다. 행 없음은 미관측을 뜻한다. `masteryProbability`는 0~1의 소수 여섯 자리, `algorithmVersion`은 백엔드가 `BKT_V1`을 기록한다. 파라미터는 요청으로 받지 않는다.
- 상태는 저장 필드가 아니라 확률에서 파생한다: 0.70 미만 취약, 0.70 이상 0.95 미만 학습 중, 0.95 이상 숙련. 처음 두 번 연속 정답의 약 0.874는 숙련이 아니다.
- 관측 한 건마다 totalSeen과 totalCorrect 또는 totalIncorrect 중 하나만 1 증가시키고 합계 불변식을 유지한다. `lastPracticedAt`은 마지막 유효 풀이 시각, `updatedAt`은 분석 트랜잭션 성공 시각이다. `estimatedHalfLife`와 `nextReviewAt`은 MVP에서 NULL이며 HLR·SRS 계산은 하지 않는다.
- 상태·카운터·시각·세션의 분석 반영 시각은 분석 트랜잭션에서 함께 반영하거나 전부 롤백한다. API가 부분 요소 결과나 중간 BKT 상태를 성공 응답으로 노출해서는 안 된다.

| 요청 상황 | 반드시 보장할 처리 |
| --- | --- |
| 같은 사용자·같은 키·같은 내용 | 기존 결과 반환, 새 Attempt·평가·집계·BKT 없음 |
| 같은 키·다른 내용 | 충돌 거부, 기존 결과 변경 없음 |
| 타인이 기존 키 사용 | 기존 사용자 결과 비노출 |
| 같은 문제를 실제로 다시 풀이 | FREE의 다른 배정 위치·새 세션에서만 새 키 사용. LIVE 동일 수업·사용자·문제는 기존 결과 유지 |
| 수업 종료 후 저장된 동일 제출 재요청 | 기존 결과 반환 |
| 수업 종료 후 신규 LIVE 제출 | 거부, 신규 기록 없음 |

`problem_attempts.idempotency_key`는 UUID v4를 저장하는 CHAR(36)·NN·UNIQUE다. 응답 캐시가 만료되어도 DB 고유 제약은 유지한다. 개인 세션의 동일/충돌 재요청 상태는 개인 학습 API 계약, LIVE 종료 동시성·HTTP/WS 매핑은 수업 운영 규칙을 적용한다.

### A-15 / F-10·F-18 학습 결과·숙련도 조회

자신의 원본 풀이 결과와 사용자·학습 요소별 현재 상태를 구분해 제공한다. 조회를 새 관측이나 새 갱신으로 처리하지 않는다. 정답 따라 하기는 프론트에서만 수행하고 서버 풀이·BKT에 포함하지 않는다. FREE / LIVE는 서버가 결정하는 학습 문맥이며 안내 연습용 저장 구분 값이 아니다. REVIEW는 MVP에서 사용하지 않는다.

BKT 갱신에는 완료한 개인 학습 세션의 독립 풀이에서 생성된 요소별 이진 관측과 이전 `user_skill_memory` 상태를 사용한다. BKT_V1 수식·순번 처리·파생 상태·카운터 불변식·시각 규칙을 적용하며 조회는 상태를 바꾸지 않는다. `mastery_probability`는 숙련 확률, `algorithm_version`은 백엔드 설정 식별자다. `estimated_half_life`와 `next_review_at`은 MVP에서 NULL이고 HLR·SRS 계산은 하지 않는다. LIVE는 직접 관측에서 제외하고 저장된 본인 오답은 후속 FREE 세션 후보에만 사용한다.

개인 세션의 결과와 조회 시점 현재 숙련도는 `GET /api/v1/learning-sessions/{sessionId}/result`로 제공한다. 별도 숙련도 endpoint·완료 목록·과거 숙련도 Snapshot은 제공하지 않는다.

## 7. 추천·AI 내부 업무 계약

| ID / 기능 | 입력 의미 | 처리·결과 | 적용 범위 |
| --- | --- | --- | --- |
| A-16 / F-14 다음 학습 추천 | 완료 후 BKT 숙련도·취약점과 현재 단계, 검증된 문제 후보 | 각 문제의 연결 요소 중 가장 긴급한 상태로 정렬: 취약(<0.70, 낮은 확률 우선) → 미관측 기본 문제(default_order_no) → 학습 중(0.70~0.95) → 숙련. 같은 우선순위는 개인 FREE 풀이 없음·마지막 풀이가 오래된 문제, problem_id 순 | 주·보조 요소·추천 가중치 없음. 5개 후보 선정과 기본 문제 보충/장애 fallback은 기본 문제·추천·진급 규칙. Backend 내부 모듈이며 별도 HTTP endpoint 없음 |
| A-17 / F-15 AI 후보 생성 | `X-Request-Id`, 확인된 L1~L6 단계, 하나 이상 요소 코드·유형, 허용 문제 유형, count 1~5 | Backend 전용 AI 내부 API로 후보 생성·규칙 검증 후 Backend가 관계를 최종 확인하고 통과 문제만 AI 출처·기본 순번 NULL로 원자 저장 | 문제 사전 준비 규칙. 보조 Should·세션 비동기·SYSTEM fallback. 모델·제공자·프롬프트·정량 제한은 AI 구현 시 확정 |

현재 단계의 유효한 서로 다른 개인화 후보를 최대 5개 선정하고 부족한 자리는 아직 선택하지 않은 단계 기본 문제로 채운다. 후보가 없거나 추천 엔진이 오류·시간 초과이면 기본 문제 5개로 구성하고 응답에서 기본 학습임을 알린다. 규칙 채점·BKT·추천·SYSTEM fallback은 MVP 필수지만 AI 생성은 보조 Should이며 동기 선행 조건·완료 게이트가 아니다. 다른 단계·미검증 문제·세 번째 중복을 사용하거나 1~9문항의 부분 세션을 만들지 않는다. DB 오류 또는 기본 문제까지 사용해도 현재 단계의 유효한 서로 다른 문제 5개를 얻지 못하면 세션을 만들지 않고 콘텐츠 준비 부족으로 처리한다. 내부 경계·오류는 내부 서비스 계약, 정량 시간 제한은 측정 후 확정한다.

assigned_problems에는 item_no와 problem_id만 저장한다. 기본/개인화 여부·추천 사유·후보 점수·벡터·알고리즘 Snapshot을 새 JSON 키나 별도 이력으로 추가하지 않는다. 기본 문제 여부는 curriculum_problems.default_order_no로 판정하며 추천과 문제 생성을 구분한다.

### 7.1 단계 기본 문제·사용자 단계·진급 계약

- curriculum_stages는 L0~L6을 각각 한 행으로 유지하고 level과 order_no를 각각 고유하게 관리한다. L0은 안내 전용이며 기본 문제 최소 수·개인 학습 세션·users.level 대상에서 제외한다.
- curriculum_problems.default_order_no는 NULL 가능이다. 값이 있으면 해당 단계 기본 문제와 기본 순서를 뜻하며 1 이상이어야 하고 `(curriculum_stage_id, default_order_no)`는 고유하다. 별도 is_default·기본 문제 테이블·단계-요소 테이블은 추가하지 않는다.
- L1~L6은 단계별 서로 다른 SYSTEM 기본 문제를 5개씩 가지며 default_order_no는 1~5다. 전체 최소 30개이고 각 문제는 기준 단계 하나, 작성자 NULL, 검증 및 Cell·요소 연결을 갖는다.
- 열린 최고 단계 세션 분석 뒤 해당 단계 기본 문제 전체에 연결된 모든 요소의 mastery_probability가 0.95 이상일 때 users.level을 order_no상 다음 단계로 한 칸만 올린다. 요소 상태 행이 하나라도 없으면 진급하지 않는다. 하위 단계 복습과 L6 완료는 level을 올리지 않는다.
- 진급 판정과 users.level·BKT 상태/카운터/시각·analysis_applied_at은 분석 트랜잭션에서 함께 커밋하거나 전부 롤백한다.

## 8. 실제 수업 업무 계약

| ID / 기능 | 입력 의미 | 처리·결과 | 저장·제약 |
| --- | --- | --- | --- |
| A-18 / F-20 수업 생성·시작·종료 | 담당 강사의 제목·설명·선택 예정 시각과 상태 전환 요청 | 담당 INSTRUCTOR만 SCHEDULED → OPEN → CLOSED 단방향 전이. SCHEDULED에서만 메타데이터 수정 | 저장·상태 불변식과 `/api/v1/lectures` 공개 계약. 취소·삭제·재개 없음 |
| A-19 / F-21 코드 입장·대기·퇴장 | 인증·이메일 인증된 LEARNER와 숫자 6자리 코드 | SCHEDULED/OPEN 직접 참여, 최초 입장 시각 유지, 퇴장 기록·재입장 시 left_at 초기화, CLOSED 입장 거부 | participants 한 행 유지·종료 시 left_at 처리, 참여 API·오류 계약 |
| A-20 / F-22 문제 연결·전달·상태 동기화 | 담당 강사·수업·검증 문제, 참여 학습자의 현재 문제 답안 | HTTP로 수업·문제·답안을 저장하고 WebSocket으로 메타데이터·상태·현재 문제 변경만 알림 | UNIQUE/동시성과 HTTP·WS·재접속 Snapshot 계약 |
| A-21 / F-23 음성 접속 | 담당 강사·해당 참여 학습자의 수업 문맥 | OpenVidu 강사 송출·학습자 수신 단방향 음성. 학습자 마이크·영상·화면 공유·녹음/저장/전사/다시보기 제외 | 제품 범위 승인. 버전·SDK·토큰 endpoint·미디어 식별·배포·복구는 다음 스프린트 |

수강 등록·강사 승인 대기·출결 판정·강사 별도 피드백 저장·문제별 수동 제출 마감·해설 상태 API는 두지 않는다. 사전 입장은 수업 시작이 아니다. 개인 학습 세션의 10문항·완료 분석 단위를 실제 수업에 자동 적용하지 않는다. 수업 후 복습은 별도 수업 endpoint가 아니라 기존 개인 세션 생성의 선택 문맥으로 처리한다.

### 8.1 실시간 수업 운영·참여·LIVE 풀이 계약

- 수업 생성 시 백엔드가 앞자리 0을 허용하는 숫자 6자리 participation_code를 생성한다. 전체에서 UNIQUE이며 MVP에서는 재발급·재사용하지 않는다. SCHEDULED와 OPEN에서만 참여할 수 있고 CLOSED에서는 거부한다.
- 담당 INSTRUCTOR만 자신의 수업 제목·설명·scheduled_at을 SCHEDULED에서 수정하고 시작·종료할 수 있다. ADMIN에게 수업 운영 권한을 자동 부여하지 않는다. 취소·삭제·CANCELLED·CLOSED 재개 기능은 두지 않는다.
- 상태 전이는 SCHEDULED → OPEN → CLOSED뿐이다. 시작·종료 시각은 UTC DB 시각으로 최초 한 번 기록하며 상태와 started_at/ended_at의 조합을 검증한다. 종료 시 current_lecture_problem_id를 NULL로 바꾼다.
- 인증·이메일 인증된 LEARNER만 참여한다. 최초 입장 때 participants 행과 joined_at을 만들고 직접 퇴장 시 left_at을 기록한다. 재입장 시 joined_at은 유지하고 left_at만 NULL로 되돌리며 별도 출입 이력·체류 시간·출석 판정은 만들지 않는다.
- 같은 수업·문제 연결은 한 건이다. SCHEDULED에서는 연결·해제, OPEN에서는 새 연결·전달만 가능하고 기존 연결 삭제는 금지한다. CLOSED에서는 모두 금지한다. current_lecture_problem_id는 같은 수업의 lecture_problems 행만 가리킨다.
- LIVE 제출은 OPEN 수업의 현재 문제와 left_at이 NULL인 참여자만 허용한다. 서버가 user_id·problem_id·lecture_id·attempt_mode=LIVE를 결정하며 learning_session_id·session_item_no·curriculum_stage_id는 NULL이다. (lecture_id, user_id, problem_id)는 UNIQUE다.
- 같은 문제를 다시 보여 줘도 학습자의 새 LIVE Attempt를 만들지 않고 기존 결과를 제공한다. LIVE는 BKT와 user_skill_memory 갱신에서 제외하며, 저장된 본인 오답·요소 결과는 CLOSED 뒤 사용자가 요청한 FREE 세션의 문제 후보로만 사용한다.
- 종료와 답안 저장은 같은 lectures 행 잠금으로 직렬화한다. 답안 트랜잭션이 OPEN을 먼저 검증하면 커밋 후 종료하고, 종료가 먼저 CLOSED를 커밋하면 신규 제출을 거부한다. 종료 전에 저장된 동일 멱등 제출은 이후에도 기존 결과를 반환한다.

이 절은 업무·저장·권한·동시성 계약이다. 실제 HTTP method/path·DTO·오류 코드와 WebSocket 현재 상태 Snapshot·재연결 이벤트는 실시간 수업 API 계약을 따른다. OpenVidu의 MVP 미디어 경계는 실시간 음성 범위, 버전 의존 계약은 다음 스프린트이며 LIVE BKT 제외·수업 후 개인 복습 연결은 수업 후 복습 규칙을 따른다.

### 8.2 실시간 수업 공개 HTTP API

수업의 영속 상태를 바꾸는 명령은 다음 HTTP endpoint만 사용한다. WebSocket으로 같은 명령이나 답안을 중복 수신하지 않는다. 모든 BIGINT ID는 경로와 JSON에서 10진수 문자열이다.

| method·path | 성공 | 권한·핵심 계약 |
| --- | --- | --- |
| `POST /api/v1/lectures` | 201 | INSTRUCTOR. `title` 필수, `description`·`scheduledAt` 선택. 서버가 SCHEDULED·담당자·6자리 코드를 결정한다. |
| `GET /api/v1/lectures` | 200 | INSTRUCTOR 본인 수업 목록. 선택 `status`, `page`, `size`; 기본 size 20, 최대 100. |
| `GET /api/v1/lectures/{lectureId}` | 200 | 담당 INSTRUCTOR 또는 해당 participants 행의 LEARNER. 재접속의 권위 있는 Snapshot. |
| `PATCH /api/v1/lectures/{lectureId}` | 200 | 담당 INSTRUCTOR, SCHEDULED 전용. `title`·`description`·`scheduledAt` 중 하나 이상을 보낸다. status·코드·현재 문제는 받지 않는다. |
| `PUT /api/v1/lectures/{lectureId}/status` | 200 | 담당 INSTRUCTOR. body `status`는 OPEN 또는 CLOSED. 동일 상태 재요청은 현재 자원, 역행·건너뛰기는 409. |
| `POST /api/v1/lecture-participations` | 201 최초 / 200 재입장 | LEARNER. body는 `participationCode`만 받는다. 기존 joinedAt을 유지하고 leftAt을 NULL로 만든다. |
| `DELETE /api/v1/lectures/{lectureId}/participants/me` | 204 | 해당 LEARNER. 참여한 적이 있고 이미 퇴장했어도 204, 참여 관계가 없으면 404. |
| `POST /api/v1/lectures/{lectureId}/problems` | 201 신규 / 200 기존 연결 | 담당 INSTRUCTOR. body는 `problemId`만 받으며 검증된 저장 문제를 연결한다. |
| `DELETE /api/v1/lectures/{lectureId}/problems/{problemId}` | 204 | 담당 INSTRUCTOR, SCHEDULED 전용. OPEN/CLOSED는 409. |
| `PUT /api/v1/lectures/{lectureId}/current-problem` | 200 | 담당 INSTRUCTOR, OPEN 전용. body의 `problemId`를 같은 수업에 연결된 문제에서 찾아 현재 문제로 교체한다. 동일 값 재요청은 새 이벤트를 만들지 않는다. |
| `POST /api/v1/lectures/{lectureId}/problems/{problemId}/answers` | 201 신규 / 동일 멱등 재전송 200 | 활성 LEARNER·OPEN·현재 문제 전용. `Idempotency-Key` UUID v4 필수. |
| `POST /api/v1/lectures/{lectureId}/realtime-tickets` | 201 | 담당 INSTRUCTOR 또는 SCHEDULED/OPEN의 활성 LEARNER. 60초 유효 일회용 WebSocket 티켓 발급. |

강의 생성 요청은 다음과 같다. `title`은 비어 있지 않고 DB VARCHAR(150) 범위이며 `description`·`scheduledAt`은 생략하거나 null일 수 있다. `PATCH`에서도 description·scheduledAt을 null로 보내 값을 지울 수 있다.

```json
{
  "title": "한글 점자 기초 수업",
  "description": "자음과 모음 점형 연습",
  "scheduledAt": "2026-09-09T10:00:00Z"
}
```

강의 상세 Snapshot의 공통 필드는 `lectureId`, `title`, `description`, `status`, `scheduledAt`, `startedAt`, `endedAt`, `currentProblem`, `createdAt`이다. 담당 강사 응답에만 `participationCode`와 `linkedProblems`를 포함한다. 학습자 응답에는 `myParticipation`, 제출했다면 현재 문제의 `myResult`, 항상 배열인 `reviewableCurriculumStages`를 포함한다. 학습자에게 제출 전 expectedPattern·diffMask·정답 여부를 제공하거나 다른 참여자·답안·결과를 노출하지 않는다. 현행 계약에는 참여자 상세 목록·정답률·대시보드 응답이 없다.

`reviewableCurriculumStages`는 수업 상태가 CLOSED이고 요청 학습자의 participants 이력이 있을 때 본인의 서로 다른 LIVE 오답을 단계별로 집계한 파생 응답이다. 각 항목은 `{stageId, level, title, incorrectProblemCount}`이며 stageId는 10진수 문자열, incorrectProblemCount는 해당 단계에 연결된 서로 다른 본인 오답 problemId 수다. `users.level` 범위에서 열린 L1~L6만 `curriculum_stages.order_no` 오름차순으로 제공한다. 같은 문제가 여러 열린 단계에 연결되면 각 단계에서 한 번씩 집계한다. CLOSED가 아니거나 대상 단계가 없으면 빈 배열이며 INSTRUCTOR 응답에는 이 필드를 넣지 않는다. 문제 ID·정답·요소 상세는 포함하지 않는다. 하나의 항목은 프론트가 기본 선택할 수 있고, 여러 항목은 사용자가 하나를 선택하며, 빈 배열은 일반 개인 학습 흐름으로 이동해 sourceLectureId를 생략한다.

학습자 HTTP 상세의 `currentProblem`은 null이거나 `problemId`, `problemType`, `targetText`, `cellCount`, `submitted`를 가진다. submitted와 myResult는 요청 학습자 본인의 제출 여부·결과다. myResult는 제출한 현재 문제에만 포함한다. 담당 강사의 문제 관리 응답은 정답 Cell을 포함할 수 있다. 공통 WebSocket 알림에는 정답과 특정 학습자의 제출 여부·결과를 넣지 않는다.

참여 요청과 현재 문제 변경 요청은 각각 다음과 같다.

```json
{ "participationCode": "012345" }
```

```json
{ "problemId": "81" }
```

LIVE 답안 body는 개인 답안과 같은 `cells`, `durationMs`만 받는다. userId·lectureId·problemId·attemptMode·curriculumStageId·isCorrect·score·expectedPattern은 body에서 받지 않는다. 경로와 인증·현재 수업 상태로 서버가 문맥을 결정한다. 응답은 `attemptId`, `lectureId`, `problemId`, `isCorrect`, `score`, `attemptedAt`, Cell별 `cellIndex`·`inputPattern`·`expectedPattern`·`diffMask`·`isCorrect`를 제공한다. LIVE BKT 요약은 포함하지 않으며 이 응답·수업 CLOSED만으로 user_skill_memory를 갱신하지 않는다.

### 8.3 WebSocket 인증·이벤트·재연결

브라우저가 장기 Access JWT를 WebSocket URL에 노출하지 않도록 먼저 Access JWT로 realtime-tickets HTTP API를 호출한다. 응답은 `{ticket, expiresAt}`이며 티켓은 해당 lectureId·userId·역할에 묶인 60초 유효 일회용 값이다. 서버는 MariaDB에 저장하지 않고 단기 메모리 캐시에 보관하며 사용 즉시 폐기한다. query의 티켓 값은 접근 로그에서 가린다. 다중 인스턴스 공유 캐시는 후속 운영 범위다.

연결 주소는 `/ws/v1/lectures/{lectureId}?ticket={ticket}`이다. 담당 INSTRUCTOR와 SCHEDULED/OPEN의 활성 LEARNER만 연결한다. 퇴장한 학습자는 코드로 재입장하고 새 티켓을 발급받아야 한다. CLOSED에서는 새 연결을 허용하지 않으며 종료 이벤트를 보낸 뒤 기존 연결을 닫는다.

WebSocket은 서버→클라이언트 알림 전용이다. 클라이언트는 수업 변경·상태 전이·문제 연결/전달·답안 제출을 HTTP로만 요청한다. 이벤트 envelope는 다음과 같다.

```json
{
  "eventId": "0beec7b5-ea3f-4f7b-a0a8-728e2d74b001",
  "type": "CURRENT_PROBLEM_CHANGED",
  "lectureId": "42",
  "occurredAt": "2026-09-09T10:05:00Z",
  "data": {}
}
```

| type | 수신 대상 | data와 처리 |
| --- | --- | --- |
| `LECTURE_METADATA_CHANGED` | 담당 강사·활성 참여자 | title·description·scheduledAt 최신값. SCHEDULED에서만 발생 |
| `LECTURE_STATE_CHANGED` | 담당 강사·활성 참여자 | status·startedAt·endedAt. CLOSED 알림 뒤 연결 종료 |
| `CURRENT_PROBLEM_CHANGED` | 담당 강사·활성 참여자 | currentProblem의 공통 문제 정보(problemId·problemType·targetText·cellCount). 정답·개인 submitted/myResult 제외. 같은 값의 멱등 PUT에서는 재발행하지 않음 |

`eventId`는 UUID v4이며 연결 내 중복 방지에만 사용한다. DB·Idempotency-Key에 저장하지 않고 sequence·이벤트 이력·replay cursor를 두지 않는다. 한 연결 안의 WebSocket 순서를 사용하되 연결이 끊기거나 이벤트 유실이 의심되면 새 티켓으로 재연결하고 `GET /api/v1/lectures/{lectureId}`를 조회해 전체 화면을 교체한다. 답안과 채점 결과는 제출 HTTP 응답으로만 전달하고 다른 참여자에게 방송하지 않는다.

### 8.4 권한·멱등·오류

담당 강사가 아닌 사용자가 ID 기반 수업 자원을 요청하면 존재 여부를 드러내지 않도록 404 `LECTURE_NOT_FOUND`를 사용한다. 참여 코드가 존재하지 않거나 CLOSED이면 404 `LECTURE_NOT_JOINABLE`과 일반화된 메시지를 사용한다. 상세 조회는 담당 강사 또는 기존 participants 행의 본인만 허용하고 학습자는 본인 결과만 본다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 인증되지 않음·유효하지 않은 Access JWT |
| 403 | INSTRUCTOR_ACCESS_REQUIRED | 강사 전용 collection 요청에 INSTRUCTOR가 아님 |
| 403 | LEARNER_ACCESS_REQUIRED | 참여·LIVE 답안 요청자가 LEARNER가 아님 |
| 403 | LECTURE_PARTICIPATION_REQUIRED | 학습자에게 활성 참여 권한이 없음 |
| 404 | LECTURE_NOT_FOUND | 수업 없음 또는 ID 기반 자원 조회/변경 권한 없음 |
| 404 | LECTURE_NOT_JOINABLE | 참여 코드가 없거나 CLOSED라 새 참여 불가 |
| 404 | PROBLEM_NOT_FOUND | 연결하려는 검증 문제 없음 |
| 409 | INVALID_LECTURE_STATE | 허용되지 않은 상태 전이·상태별 작업 |
| 409 | LECTURE_PROBLEM_NOT_CURRENT | 제출 경로의 문제가 현재 문제가 아님 |
| 409 | LECTURE_PROBLEM_ALREADY_ANSWERED | 다른 멱등 키로 같은 수업·학습자·문제 재제출 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키에 다른 정규화 답안 내용 |
| 422 | INVALID_ANSWER_CELLS | Cell 수·순번·범위 검증 실패 |

오류 body는 `{"code":"...","message":"..."}`다. 수업 목록의 page는 0 이상, size는 1~100으로 검증한다. HTTP 5xx·시간 제한·재시도 수치는 기준선 측정 뒤 정하며, 오류가 발생한 신규 LIVE 풀이를 일부 저장하지 않는다.

OpenVidu 강사 단방향 음성은 제품 MVP에 포함하지만 버전 선정과 이에 의존하는 SDK·토큰 endpoint·배포·복구 계약은 다음 스프린트에서 검토한다. 강사 publish·학습자 subscribe만 허용하고 녹음·저장·전사·다시 듣기·학습자 마이크·영상·화면 공유는 추가하지 않는다. 미디어 접속 상태·토큰은 participants 행과 서비스 WebSocket 티켓이 아니며 이전 WEBRTC_SIGNAL 직접 시그널링 계약도 현행 확정 API가 아니다.

## 9. 장치·실시간 전송 경계

### A-22 / F-25·F-26 촉지 출력·상태

Android 학습자 모바일과 아두이노는 BLE로 장치 검색·연결·해제, 상태, 1~6개 Cell의 0~63 점형 출력과 전체 해제를 주고받는다. 명령 식별/순번·성공/실패 ACK·중복 처리·연결/시간 초과/장치 오류·안전 해제를 계약에 포함한다. 실제 UUID·Frame·오류 code·시간/재시도 상수는 IoT 구현 계약에서 확정한다. 강사 웹 원격 제어와 서버 BLE 중계는 MVP에 없다.

장치 미연결·출력 실패는 촉지만 제한하며 모바일 입력·채점·복습을 막지 않는다. 실패를 성공으로 표시하거나 장치 ACK를 정답 관측으로 집계하지 않는다. Android TalkBack·200% 확대·색상 비의존·TTS/햅틱 검수도 MVP 인수 기준이며 최소 Android 버전과 BLE 실제 상수는 실기기 계약에 남긴다.

| 구간 | 전달 책임 | 계약 상태·적용 범위 |
| --- | --- | --- |
| HTTPS | 계정·문제·단계·학습 상태 등의 조회/처리 | 계정·개인 학습·문제·수업 HTTP는 이 문서 3~8절의 요청·응답·오류 계약 적용 |
| 서비스 WebSocket | 수업 메타데이터·상태·현재 문제 변경 알림 | 60초 일회용 ticket·eventId·3개 이벤트·HTTP Snapshot 재연결. 답안/결과/장치 중계·sequence/replay 없음 |
| OpenVidu | 강사 publish·학습자 subscribe 단방향 음성 | 다음 스프린트에 버전·SDK·토큰 endpoint·접속·복구 확정 |
| BLE | Android 모바일↔장치 연결·1~6 Cell 출력·해제·상태·ACK | 실제 UUID·Frame·오류 code·시간/재시도 상수는 IoT 계약에서 확정 |

같은 논리적 답안은 HTTP와 UUID v4 `Idempotency-Key`로만 저장한다. WebSocket eventId는 알림 중복 방지용이며 답안 멱등 키와 동일시하거나 DB에 저장하지 않는다. 새 학습 풀이와 전송 재시도는 구분한다.

## 10. 제공 범위와 후속 계약

현행 endpoint 목록 외에 강좌·차시·수강, 일반 프로필 수정, 문제 수정·삭제·수업 후 재등록, 관리자·리포트·CSV, 서버 장치 제어, 완료 세션 목록·통계·세션 포기·초기화·자동 만료 API는 제공하지 않는다.

OpenVidu 토큰 endpoint·SDK·배포·복구와 BLE UUID·프레임·오류 코드·시간/재시도 값은 실제 연동 계약에서 확정한다. 기본 health check·요청 로그와 한 수업 30명 검증을 수행하고 지연 목표·운영 보존·복구 수치는 측정 뒤 결정한다. 응답의 미확정 세부는 정리 검수 보고서(로컬 별도 참고 자료)에서 추적한다.

### 10.1 구현 전에 보완할 세부 명세

현재 문서는 핵심 필드와 처리 규칙을 정의한 Markdown 계약이다. sessionSkillSummary·currentSkillStates의 요소별 전체 필드·배열/객체 구조, 개인 답안 성공 응답에서 상태·진행 정보를 배치할 정확한 경로, 분석 전 result/미완료 analysis/잘못된 itemNo의 상세 오류 대응은 완전한 DTO 표로 확정되어 있지 않다. 새 필드·오류 코드를 임의로 추가하지 않고 해당 구현 착수 전에 보완한다.

contentMode는 배정 구성에서 파생하는 기존 응답이다. 기본 문제가 추천 과정에서 선택된 경우와 fallback으로 선택된 경우를 구분하는 별도 저장값은 없다. 재조회 시 일관되게 재계산할 분류 기준을 API 연동 전에 확인하며 추천 선택 경로를 DB에 저장한다고 가정하지 않는다.

## 11. 계약 검수 기준

각 endpoint의 정상·실패·타인 권한·멱등 재전송·상태 경쟁을 검증한다. 개인 세션 생성/최초 수준 설정, 답안/Cell/요소 평가, 완료 분석은 정해진 트랜잭션 경계를 지킨다. 수업 답안·변경은 HTTP, WebSocket은 알림 전용이다. 분석 대기·수업 연결 유실·기기 오류에서 확정 데이터를 복원하고 부분 성공을 정상으로 표시하지 않는다.

모든 BIGINT ID는 10진수 문자열, API 필드는 camelCase, DB 컬럼·배정 JSON은 snake_case로 구분한다. 상세 컬럼 대응은 [ERD](./erd.md), 기능 식별자는 [기능 명세](./feature-spec.md), 사용자 복구 흐름은 [Flow](./user-flow.md)를 함께 검수한다.
