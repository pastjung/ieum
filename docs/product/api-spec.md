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

Backend는 외부 HTTP 요청 또는 백그라운드 작업 시작마다 UUID v4 `X-Request-Id`를 생성한다. HTTP 응답 헤더에 같은 값을 반환하고 AI 호출 헤더에도 전달해 Backend·AI 구조화 로그에서 같은 처리 흐름을 찾을 수 있게 한다. 클라이언트가 이 값을 필수로 만들거나 업무 body에 넣지 않으며, 도메인 DB와 응답 body에는 저장·포함하지 않는다. AI는 받은 값을 로그에 기록하고 응답 헤더에 돌려준다. 클라이언트가 보낸 `X-Request-Id`는 형식과 관계없이 신뢰하거나 로그에 기록하지 않는다. 유효한 UUID v4여도 그대로 쓰지 않고, 이 헤더 때문에 요청을 거부하지도 않는다. 외부 요청 하나에는 요청 식별자 하나만 생성하고, 응답 헤더와 로그에는 Backend가 생성한 값만 쓴다. 응답의 `X-Request-Id`는 공통 요청 추적 계층만 설정하며 개별 API가 덮어쓰지 않는다.

`X-Request-Id`는 관측용이며 재전송 중복을 막지 않는다. 답안·문제 등록의 `Idempotency-Key`, 수업 WebSocket 알림의 `eventId`, DB 기본 키와 서로 대체할 수 없다. 기본 요청 로그와 health check는 MVP 필수다. 메일 outbox의 최초 사건·정체 경고 조건은 3.8절에서 확정하며, 정체 경고는 새 수치 상수를 만들지 않고 ERD 3.5의 가시성 만료 기본값을 재사용한다. 그 밖의 비율·percentile·트래픽 기반 임계값과 로그 보존 기간·수집 시스템·샘플링·관측 제품·알림 채널, W3C `traceparent`·OpenTelemetry는 후속 운영 범위다.

역할은 LEARNER / INSTRUCTOR / ADMIN 중 하나다. roles 배열·역할 전환을 제공하지 않는다. 사용자 식별·권한을 요청 body의 주장만으로 신뢰하지 않으며, 타인의 학습 결과를 노출하지 않는다. ADMIN·INSTRUCTOR는 통제된 Seed/운영 절차로만 발급하고 MVP 관리자 공개 API·화면과 ADMIN의 강사/문제 자동 권한은 두지 않는다.

### 2.2 공통 HTTP 오류

모든 공개 endpoint에 [API 컨벤션의 공통 HTTP 오류 code](../conventions/code/api.md#공통-http-오류-code)와 [오류 상태 보존·body 생략 규칙](../conventions/code/api.md#오류-상태-보존과-body-생략)을 적용한다. 공통 HTTP 오류는 각 절의 업무 오류 표에서 반복하지 않으며, 같은 상황에 업무 code가 정의되어 있으면 업무 code가 우선한다.

요청 body를 DTO로 읽지 못한 요청은 공통 `400 MALFORMED_REQUEST_BODY`다. body를 읽은 뒤의 업무 입력 검증은 해당 API의 `422` 계약(예: `INVALID_ACCOUNT_INPUT`, `INVALID_PROBLEM_INPUT`)을 따르고, API가 업무 code를 정의하지 않은 경우에만 공통 `422 INVALID_REQUEST_INPUT`을 쓴다.

오류 응답은 원래 HTTP 상태를 유지한다. 오류가 난 요청의 `Accept`가 JSON을 허용하지 않으면 원래 오류 상태를 유지하고 body만 생략하며, 이를 `406`으로 바꾸지 않는다. `406`은 정상 응답의 표현을 `Accept`에 맞춰 만들 수 없을 때만 body 없이 쓴다. 업무 code 없이 인증·필터·컨테이너 단계에서 발생한 `403`·`413`·`429`·`503` 등 공통 code가 아직 없는 상태는 원래 상태를 유지하고 body를 비우며, 그 code는 해당 기능의 후속 계약에서 이 문서에 먼저 추가한다. body가 없는 응답에도 `X-Request-Id`를 포함한다.

## 3. 계정·인증 업무 계약

| ID / 기능 | 입력 의미 | 처리·결과 | 저장·처리 규칙 |
| --- | --- | --- | --- |
| A-01 / F-01 가입·로그인·내 정보 | username·비밀번호와 가입 email | 공개 가입은 LEARNER만 생성. username 로그인 후 인증된 단일 역할로 접근하며 미인증·탈퇴·제한 계정은 거부 | users. username/email 정규화·UNIQUE, Argon2id, 실패 5회·10분 제한. 실제 HTTP·DTO·오류는 |
| A-02 / F-02 이메일 인증·재발송 | 계정 또는 이메일과 일회성 인증 토큰 | 유효한 토큰이면 email_verified_at 기록. 재발송은 기존 활성 인증 토큰을 무효화하고 새 토큰 발급 | Redis 계정 작업 토큰 상태, 인증 TTL 24시간·단일 사용·원문 미저장. 실제 HTTP·DTO·오류는 |
| A-03 / F-02 비밀번호 복구 | 이메일 기반 복구 요청·일회성 토큰·새 비밀번호 | 유효한 토큰으로 비밀번호를 변경하고 같은 트랜잭션의 auth_version +1로 기존 Access·Refresh 인증 세션 무효화 | Redis 계정 작업 토큰 상태, 복구 TTL 30분·단일 사용·계정 존재 비노출. 실제 HTTP·DTO·오류는 |
| A-04 / F-01 인증 상태 갱신·폐기 | Access/Refresh Token과 현재 인증 상태 | Access 15분, Refresh 14일. 갱신마다 회전하고 이전 토큰 재사용 시 같은 계열 폐기. 로그아웃은 현재 기기 계열 폐기 | Redis에 JWT 해시·jti·계열·회전·폐기 상태 저장. 보호 API에서 status·이메일 인증 상태·authVersion 재검증. 실제 HTTP·DTO·오류는 |
| A-05 / F-03 탈퇴 | 인증된 본인의 현재 비밀번호 확인 | MariaDB 트랜잭션에서 WITHDRAWN·탈퇴 시각·식별정보 가명화와 auth_version +1을 함께 커밋해 기존 Access·Refresh 인증 세션 무효화. Redis family 폐기는 사후 정리. 로그인·신규 학습 차단 | 사용자 행·가명 처리된 과거 이력 보존, identifier 즉시 재사용, 재가입은 새 ID. 실제 HTTP·DTO·오류는 |

접근성 설정(F-04)은 프론트엔드 로컬에서 계정별로 유지하므로 TTS·햅틱·고대비·글자 크기를 저장하는 백엔드 API는 두지 않는다. 내 정보 수정에 이러한 필드를 섞지 않는다. 일반 프로필 수정 API는 현행 계약에 포함하지 않는다.

### 3.1 계정·인증 공통 처리

가입 입력의 username은 앞뒤 공백 제거·NFC 정규화 후, email은 앞뒤 공백 제거·소문자 정규화 후 대소문자 비구분 UNIQUE를 검사한다. 공개 가입 요청의 role 값은 받거나 신뢰하지 않고 LEARNER로 생성한다. INSTRUCTOR·ADMIN은 관리자 통제 등록 또는 초기 데이터로만 생성한다. 미인증 계정은 별도 상태값 없이 email_verified_at=NULL로 판별하며 로그인·학습을 허용하지 않는다.

비밀번호는 15~64자, 유니코드·공백 허용, NFC 정규화와 흔하거나 유출된 값 차단을 적용한다. 대·소문자·숫자·특수문자 조합을 강제하거나 주기적 변경을 요구하지 않는다. Argon2id 최소 설정은 메모리 19MiB·반복 2·병렬도 1이며 원문·복구 토큰·Refresh Token을 로그에 남기지 않는다. 로그인 실패는 계정 없음·비밀번호 오류·잠금·탈퇴 여부를 외부 응답과 처리 시간으로 구분하지 않는다. 계정별 연속 실패 5회 시 10분 제한하고 정상 로그인 시 `failed_login_count`와 `locked_until`을 초기화한다. 프론트 검증은 안내용이며 동일 규칙을 백엔드가 다시 적용한다.

Access Token은 15분 유효 JWT이며 MariaDB·Redis에 원문을 저장하지 않는다. claim에는 sub·role·authVersion·iat·exp·jti·iss·aud를 두고 username·email을 넣지 않는다. Refresh Token도 서명된 JWT로 발급하며 원문 대신 SHA-256 해시·jti·userId·familyId·만료·회전·폐기 상태를 Redis에 저장한다. 유효기간은 발급별 14일이다. 갱신 때 기존 토큰을 무효화하고 같은 familyId의 새 JWT를 발급한다. 폐기된 토큰 재사용은 해당 계열 전체를 폐기한다. Refresh도 authVersion을 필수 claim으로 싣는다. 로그아웃은 Redis에서 현재 계열을 폐기하고, 비밀번호 재설정과 탈퇴는 users.auth_version을 같은 MariaDB 트랜잭션에서 증가시켜 기존 Access·Refresh 인증 세션을 무효화하며 Redis 계열 정리는 사후 정리다. action token은 이 무효화의 대상이 아니다. 웹 Refresh Token은 Secure·HttpOnly 쿠키, 모바일은 운영체제 보안 저장소를 사용하고 일반 localStorage·AsyncStorage에는 저장하지 않는다.

이메일 인증·복구 토큰도 용도를 구분한 서명 JWT로 발급하고 Redis에는 SHA-256 해시·jti·userId·purpose·만료·사용 상태를 저장한다. EMAIL_VERIFICATION은 24시간, PASSWORD_RESET은 30분이다. 새 토큰 발급 시 같은 사용자·용도의 이전 활성 토큰을 무효화하고 완료 시 원자적으로 소비하여 단일 사용을 보장한다. 인증 재발송·비밀번호 복구 요청은 계정 존재 여부와 관계없이 같은 HTTP 상태와 일반 메시지를 사용한다. 미인증 계정은 MVP에서 자동 삭제하지 않고 재발송을 허용한다.

모든 JWT는 서버에서 허용한 서명 알고리즘·키·iss·aud·exp를 검증한다. Refresh에는 계열 식별, action에는 EMAIL_VERIFICATION/PASSWORD_RESET 용도 식별을 포함하고 jti는 안전한 난수로 생성한다. Access·Refresh·이메일 인증·비밀번호 복구 JWT의 audience/용도와 검증 경로를 분리하여 서로 대체 사용할 수 없게 한다. 서명 알고리즘·key 구성·key 회전·검증 순서와 정확한 issuer·audience 문자열, Redis 키·상태·원자 연산은 3.6.1절을 따른다. Refresh/action은 Redis 활성 상태도 일치해야 하며 서명만으로 허용하지 않는다.

탈퇴는 물리 삭제가 아니다. 현재 비밀번호를 다시 확인한 뒤 status·`withdrawn_at`·`email_verified_at`, username·email, `password_hash`를 MariaDB 트랜잭션에서 변경하고 같은 트랜잭션에서 `auth_version`을 +1 해 기존 Access·Refresh 인증 세션을 무효화한다. Redis family 폐기는 커밋 뒤의 사후 정리다. API에는 가명 값을 노출하지 않고 ‘탈퇴 사용자’로 처리한다. 과거 학습·수업 기록은 사용자 내부 ID 관계를 유지하지만 신규 로그인·학습에는 사용할 수 없다. 운영 배포의 법적 보존 기간과 백업 제거 시점은 별도 정책이며 현재 API가 30일 물리 삭제를 보장한다고 명세하지 않는다.

### 3.2 계정·인증 공개 HTTP API

| 목적 | Method·path | 요청 | 성공 |
| --- | --- | --- | --- |
| 회원가입 | `POST /api/v1/users` | `{ username, email, password }` | `201`, 사용자 요약 |
| 아이디 사용 가능 확인 | `GET /api/v1/username-availability` | `?username=<값>` | `200`, `{ username, available }` |
| 내 정보 | `GET /api/v1/users/me` | Bearer Access Token | `200`, 사용자 요약 |
| 회원 탈퇴 | `POST /api/v1/users/me/withdrawals` | `{ currentPassword }` | `204` |
| 로그인 | `POST /api/v1/auth-sessions` | `X-Client-Platform`, `{ username, password }` | `201`, 인증 응답 |
| Access Token 갱신·Refresh 회전 | `POST /api/v1/access-tokens` | `X-Client-Platform`, 플랫폼별 Refresh 전달 | `201`, 인증 응답과 새 Refresh |
| 로그아웃·현재 Refresh 계열 폐기 | `POST /api/v1/auth-session-revocations` | `X-Client-Platform`, 플랫폼별 Refresh 전달 | `204` |
| 이메일 인증 요청·재발송 | `POST /api/v1/email-verification-requests` | `{ email }` | `202`, 빈 body |
| 이메일 인증 완료 | `POST /api/v1/email-verifications` | `{ token }` | `204` |
| 비밀번호 재설정 요청 | `POST /api/v1/password-reset-requests` | `{ email }` | `202`, 빈 body |
| 비밀번호 재설정 완료 | `POST /api/v1/password-resets` | `{ token, newPassword }` | `204` |

가입 요청은 `role`, `emailVerified`, `status`, `level`을 받지 않고 서버가 항상 `LEARNER`를 부여한다. 가입 트랜잭션 뒤 최초 이메일 인증 토큰을 발급·발송하며 인증 완료 전에는 Access·Refresh Token을 발급하지 않는다. 가입·비밀번호 변경 시 정규화·비밀번호·Argon2id 규칙을 백엔드에서 적용한다.

사용자 요약은 `{ userId, username, email, role, emailVerified, level, createdAt }`이며 `userId`는 BIGINT의 10진수 문자열, `createdAt`은 ISO 8601 UTC다. 가입·내 정보 조회·인증 응답의 user는 같은 형태를 사용한다. level은 users.level에서 조회한 열린 최고 단계 코드다. LEARNER의 자가 진단 전과 INSTRUCTOR·ADMIN은 null, 진단 후에는 L1~L6이며 BKT 확률과 다르다. 재로그인 후 프론트는 이 값으로 자가 진단 필요 여부와 열린 단계 범위를 판단한다. 접근성 설정, 일반 프로필 변경, 역할 변경은 이 계약에 포함하지 않는다.

### 3.2.1 계정 리소스 API 계약 (가입·내 정보·탈퇴)

username은 trim·NFC 정규화 후 3~30자이며 ASCII 영문 대소문자(`A-Z`, `a-z`)·숫자·`.`·`_`·`-`만 허용하고 처음과 끝은 ASCII 영문 또는 숫자다. 대소문자 비구분 UNIQUE이며 위반은 `422 INVALID_ACCOUNT_INPUT`이다.

email은 trim 후 소문자로 정규화하며 유효한 이메일 형식이어야 하고 정규화 후 254자를 넘지 않는다. 위반은 `422 INVALID_ACCOUNT_INPUT`이다.

사용자 요약은 가입·내 정보 조회·인증 응답이 같은 형태를 쓴다. 필드 의미는 3.2절을 따른다.

| 필드 | 타입 | required | nullable |
| --- | --- | :---: | :---: |
| `userId` | string | O | X |
| `username` | string | O | X |
| `email` | string | O | X |
| `role` | string | O | X |
| `emailVerified` | boolean | O | X |
| `level` | string | O | **O** |
| `createdAt` | string | O | X |

`userId`는 BIGINT의 10진수 문자열, `createdAt`은 ISO 8601 UTC다. `level`만 조건부 nullable이며 필드를 생략하지 않고 값으로 `null`을 보낸다.

`POST /api/v1/users` 요청 body는 `username`·`email`·`password` 세 개이며 모두 required·non-null 문자열이다. `role`·`status`·`emailVerified`·`level`은 받지 않는다. 성공은 `201`과 사용자 요약이며, 생성 리소스 전용 조회 URI가 없으므로 `Location` 헤더를 두지 않는다.

가입은 `users` INSERT와 이메일 인증 메일 작업의 `mail_outbox` INSERT를 하나의 MariaDB 트랜잭션으로 커밋한다. 메일 작업의 purpose는 `EMAIL_VERIFICATION`이다. `201`의 저장소 성공 조건은 두 INSERT를 포함한 트랜잭션 commit 성공이 확정되는 것이며, `201`은 사용자 생성과 메일 작업의 내구적 접수만 뜻하고 action token 발급, 제공자 접수, 사용자 편지함 도달은 뜻하지 않는다.

실패 판정은 결정적 결과를 장애보다 우선한다. 입력 형식의 결정적 위반은 기존 `422 INVALID_ACCOUNT_INPUT`, 비밀번호가 정책을 결정적으로 위반하면 기존 `422 INVALID_PASSWORD`를 유지한다. 유출 비밀번호 조회 자체가 실패하면 기존 `503 PASSWORD_POLICY_UNAVAILABLE`이며 이를 `AUTH_STATE_UNAVAILABLE`로 바꾸지 않는다. MariaDB가 username 또는 email의 UNIQUE 위반을 결정적으로 확인하면 동시 가입 경쟁 때문에 commit 단계에서 드러난 경우를 포함해 기존 `409 ACCOUNT_IDENTIFIER_UNAVAILABLE`을 유지한다. `503 AUTH_STATE_UNAVAILABLE`은 그 밖의 MariaDB 명령 실패, 저장소 장애, commit 실패, commit 결과 불확실에만 쓴다. 저장소 오류를 UNIQUE 충돌로 추측하지 않고 UNIQUE 충돌을 `503`으로 바꾸지도 않는다.

`GET /api/v1/users/me`는 요청 body가 없고 성공은 `200`과 사용자 요약이다.

`POST /api/v1/users/me/withdrawals` 요청 body는 required·non-null 문자열 `currentPassword` 하나이며 성공은 `204`와 빈 body다. `currentPassword`가 누락·null·빈 문자열이면 `422 INVALID_ACCOUNT_INPUT`이고, non-empty지만 실제 비밀번호와 다르면 `403 CURRENT_PASSWORD_MISMATCH`다.

`GET /api/v1/username-availability`는 회원가입 화면이 아이디를 미리 확인하기 위한 조회다. 인증을 요구하지 않는다. required query parameter `username` 하나를 받고 성공은 `200`과 다음 body다.

| 필드 | 타입 | required | nullable |
| --- | --- | :---: | :---: |
| `username` | string | O | X |
| `available` | boolean | O | X |

응답의 `username`은 이 절의 규칙으로 **정규화한 값**이다. trim·NFC 정규화로 요청 값과 달라질 수 있고, 클라이언트는 무엇에 대한 판정인지 알아야 사용자에게 정확히 안내할 수 있다.

`available`은 조회 시점에 그 값을 가입에 쓸 수 있는지만 뜻한다. 조회와 실제 가입 사이에 다른 요청이 같은 값을 선점할 수 있으므로 **`200`은 이후 가입의 `201`을 보장하지 않는다.** 최종 판정은 `POST /api/v1/users`의 `409 ACCOUNT_IDENTIFIER_UNAVAILABLE`이며, 클라이언트는 이 조회를 통과했다는 이유로 가입 실패를 예외로 다루지 않는다.

`username`이 누락되거나 이 절의 형식·길이 규칙을 위반하면 `422 INVALID_ACCOUNT_INPUT`이고 `available`을 돌려주지 않는다. **형식 위반을 `available: false`로 답하지 않는다** — 쓸 수 없는 이유가 중복인지 형식인지 구분되지 않으면 클라이언트가 사용자에게 무엇을 고치라고 안내할 수 없다.

탈퇴한 계정의 username은 탈퇴 시점에 가명으로 바뀌어 더 이상 그 값을 점유하지 않으므로 `available: true`다.

다음은 이 절에서 확정하는 계정 업무 오류다. 공통 HTTP 파싱·전송 오류는 제외하고, 저장소 장애 code의 상세 판정 절차는 3.6.2절을 따르며 code 정의는 3.5절을 따른다.

| API | 오류 |
| --- | --- |
| `POST /api/v1/users` | `409 ACCOUNT_IDENTIFIER_UNAVAILABLE`, `422 INVALID_ACCOUNT_INPUT`, `422 INVALID_PASSWORD`, `503 PASSWORD_POLICY_UNAVAILABLE`, `503 AUTH_STATE_UNAVAILABLE` |
| `GET /api/v1/username-availability` | `422 INVALID_ACCOUNT_INPUT` |
| `GET /api/v1/users/me` | `401 UNAUTHORIZED` |
| `POST /api/v1/users/me/withdrawals` | `401 UNAUTHORIZED`, `403 CURRENT_PASSWORD_MISMATCH`, `422 INVALID_ACCOUNT_INPUT` |

`409`는 username과 email 중 어느 값이 중복됐는지 구분하지 않는다. 이로 인한 계정 열거 가능성은 MVP에서 의도적으로 수용한다. 가입 API와 `GET /api/v1/username-availability`는 계정 열거 완화를 위한 요청 제한 대상이며 구체 제한 수치는 후속 보안 계약에서 정한다.

조회 API는 가입 API보다 열거 비용이 낮다. 가입은 body 세 개를 갖춰 보내야 하고 성공하면 행과 메일 작업을 만들지만, 조회는 요청 하나로 username 하나의 사용 여부를 그대로 알려 준다. 제한 수치를 정할 때 이 차이를 전제로 하며, 두 API에 같은 수치를 쓰지 않는다. email에 대응하는 조회 API는 두지 않는다 — 이메일은 아이디보다 추측 대상이 되기 쉽고, 가입 화면이 미리 확인해야 할 이유도 없다.

### 3.3 플랫폼별 Refresh Token 전달

로그인·갱신·로그아웃은 `X-Client-Platform: WEB` 또는 `X-Client-Platform: MOBILE`을 필수로 받는다. 이 헤더는 Refresh Token 전달 방식을 고르는 값일 뿐 역할·권한의 근거가 아니다. 요청에 `Origin` 헤더가 있으면 값이 허용 목록에 있는지와 무관하게 `MOBILE` 주장을 `400 INVALID_CLIENT_PLATFORM`으로 거부하며 `Origin: null`도 존재로 취급한다. 허용 Origin 검사는 CORS에서 별도로 수행한다. `Origin`과 `X-Client-Platform`은 인증·신원·권한의 근거가 아니다.

공통 인증 응답은 `{ accessToken, tokenType: "Bearer", accessTokenExpiresAt, user }`다. WEB은 Refresh Token을 JSON에 포함하지 않고 `__Host-refreshToken` 이름의 `Secure; HttpOnly; SameSite=Strict; Path=/` 쿠키로 전달한다. MOBILE은 응답에 `refreshToken`, `refreshTokenExpiresAt`을 추가하며 Android Keystore·iOS Keychain과 동등한 운영체제 보안 저장소에만 보관한다. 웹 Access Token은 가능한 한 메모리에 유지하고 `localStorage`, 모바일의 `AsyncStorage` 등 일반 저장소에는 Refresh Token을 저장하지 않는다.

갱신·로그아웃 때 WEB은 쿠키, MOBILE은 `{ refreshToken }` body를 사용한다. 갱신은 엄격한 회전을 수행하고 기존 토큰을 폐기한 뒤 같은 `family_id`의 새 토큰을 발급한다. 폐기·만료·잘못된 Refresh Token은 모두 `INVALID_REFRESH_TOKEN`으로 응답하며 폐기 토큰 재사용이 감지되면 같은 계열 전체를 폐기한다. 프론트엔드는 동시 갱신을 single-flight로 합쳐야 한다. 회전 응답 유실 뒤 이미 폐기된 토큰을 다시 보내면 엄격한 재사용 정책상 재로그인이 필요하다. 위 `INVALID_REFRESH_TOKEN` 규칙은 갱신 기준이다. 로그아웃은 서명·용도·만료 검증을 통과하고 Redis 정상 조회로 토큰과 family 상태가 식별되면 family가 폐기된 상태가 되도록 보장하고 `204`를 반환한다. 활성 토큰이면 family를 폐기하고, 회전된 이전 토큰이면 이미 폐기된 것으로 무시하지 않고 재사용으로 판정해 family 전체를 폐기하며, 이미 폐기된 family면 멱등하게 `204`다. 토큰 누락·위조·용도 불일치·만료와 Redis가 정상 응답했지만 토큰 상태가 조회되지 않는 경우는 `401 INVALID_REFRESH_TOKEN`이다. Redis 조회나 폐기 명령 자체가 실패했거나 결과를 알 수 없는 장애는 이 규칙과 구분하며 `503 AUTH_STATE_UNAVAILABLE`이다. 멱등 `204`는 Redis가 정상 응답해 상태를 확인한 경우에만 성립하며 상세는 3.6.2절을 따른다. `X-Client-Platform: WEB`이고 전달 경로 충돌이 없는 로그아웃 요청에는 서버 판정이 `204`·`401`·`503` 중 무엇이든 발급 때와 같은 속성으로 refresh 쿠키 삭제 응답을 보내고, MOBILE 로컬 보안 저장소 삭제는 클라이언트 책임이다. `400 INVALID_CLIENT_PLATFORM`처럼 WEB 여부와 전달 경로가 확정되지 않은 요청에는 적용하지 않는다.

### 3.3.1 인증 세션 API 계약 (로그인·갱신·로그아웃)

Origin에 따른 MOBILE 주장 거부와 로그아웃 토큰 상태 판정은 3.3절을 따른다. 이 절은 요청·응답 스키마와 쿠키 속성을 정의한다.

`POST /api/v1/auth-sessions` 요청 body는 `username`·`password`이며 둘 다 required·non-null 문자열이다. username은 trim·NFC 정규화와 3.2.1절의 길이·허용 문자 검증을 적용한 뒤 대소문자 비구분으로 조회한다. `username` 또는 `password`가 누락·null·빈 문자열이거나 정규화한 username이 3.2.1절 형식에 맞지 않으면 `422 INVALID_ACCOUNT_INPUT`이다. 형식은 유효하지만 계정·비밀번호·잠금·탈퇴 조건으로 실패하면 원인을 구분하지 않고 `401 INVALID_CREDENTIALS`이며, 자격 증명이 올바르지만 이메일 미인증이면 `403 EMAIL_VERIFICATION_REQUIRED`다. 성공은 `201`과 아래 인증 응답이다.

`POST /api/v1/access-tokens`(성공 `201`과 인증 응답)와 `POST /api/v1/auth-session-revocations`(성공 `204`와 빈 body)는 Refresh Token을 플랫폼별 위치에서만 받는다.

| `X-Client-Platform` | 허용 위치 | 금지 위치 |
| --- | --- | --- |
| `WEB` | `__Host-refreshToken` 쿠키 | body `refreshToken` |
| `MOBILE` | body `refreshToken` (required·non-null) | `__Host-refreshToken` 쿠키 |

금지 위치에 토큰이 하나라도 있으면 `400 INVALID_CLIENT_PLATFORM`, 허용 위치에 없으면 `401 INVALID_REFRESH_TOKEN`, 양쪽에 모두 있으면 우선순위 없이 `400 INVALID_CLIENT_PLATFORM`이다.

갱신은 플랫폼과 전달 위치 → Refresh Token의 서명·용도·만료와 Redis 활성 상태 → 연결 사용자 조회 순으로 검사한다. 사용자 검사에서는 `status != ACTIVE`를 먼저 판정해 `401 INVALID_REFRESH_TOKEN`으로 응답하고, 이어서 Refresh claim의 `authVersion`이 `users.auth_version`과 다르면 `401 INVALID_REFRESH_TOKEN`으로 응답한다. 두 조건을 통과한 ACTIVE 계정에 한해 `emailVerified`를 검사해 미인증이면 `403 EMAIL_VERIFICATION_REQUIRED`로 응답한다.

Redis 회전에 성공한 뒤 응답 전에 `users.auth_version`과 `status`를 다시 확인한다. 이 재확인은 선택이 아니라 필수다. `auth_version`이 Snapshot과 달라졌거나 `status`가 ACTIVE가 아니면 해당 family를 폐기하고 신규 Access·Refresh를 반환하지 않으며 `401 INVALID_REFRESH_TOKEN`으로 응답한다. DB 재확인 자체가 실패하면 family 폐기를 가능한 범위에서 시도하되 신규 토큰을 반환하지 않으며, 그때는 `503 AUTH_STATE_UNAVAILABLE`이다. 회전 전 사용자 조회가 실패한 경우를 포함한 저장소 장애 처리는 3.6.2절을 따른다. 어떤 경우에도 기존 Snapshot의 `authVersion`을 최신 값으로 바꿔 발급하지 않는다. 재확인 이후에 발생하는 경쟁의 최종 차단은 보호 API의 DB `authVersion` 비교가 담당한다.

인증 응답의 공통 필드는 `accessToken`·`tokenType`·`accessTokenExpiresAt`·`user`이며 모두 required·non-null이다. `tokenType`은 고정 문자열 `Bearer`, `accessTokenExpiresAt`은 ISO 8601 UTC, `user`는 3.2.1절의 사용자 요약이다. MOBILE 응답은 `refreshToken`과 `refreshTokenExpiresAt`을 추가하며, 두 필드 모두 required·non-null이다. `refreshTokenExpiresAt`은 ISO 8601 UTC다. **WEB body에는 이 두 필드를 어떤 경우에도 포함하지 않는다.** 구현은 WEB과 MOBILE의 응답 타입을 분리하고, Service는 플랫폼 중립 결과를 반환하며 Controller가 플랫폼별 HTTP 응답으로 변환한다.

WEB 쿠키는 로그인과 정상 갱신에서 같은 규칙으로 발급한다.

| 속성 | 발급 | 삭제 |
| --- | --- | --- |
| 이름 | `__Host-refreshToken` | 발급과 동일 |
| `Secure`·`HttpOnly`·`SameSite=Strict`·`Path=/` | 적용 | 발급과 동일 |
| `Domain` | 생략 | 생략 |
| `Max-Age` | `1209600` | `0` |
| `Expires` | `refreshTokenExpiresAt`과 같은 시각 | 과거 시각 |

WEB으로 판정되고 전달 경로 충돌이 없는 로그아웃 요청에는 서버 처리 결과가 `204`·`401`·`503` 중 무엇이든 삭제 `Set-Cookie`를 반환한다. MOBILE 로컬 보안 저장소 삭제는 클라이언트 책임이다.

`SameSite=Strict`를 쓰려면 WEB 프론트와 API를 HTTPS same-site로 배포해야 한다. cross-site 배포가 필요하면 `SameSite`를 임의로 `None`으로 바꾸지 않고 CORS·CSRF·쿠키 정책을 재검토해 승인받는다.

세 API의 계정 업무 오류는 다음과 같다. 공통 HTTP 파싱·전송 오류는 제외하고, 저장소 장애 code의 상세 판정 절차는 3.6.2절을 따르며 code 정의는 3.5절을 따른다.

| API | 오류 |
| --- | --- |
| `POST /api/v1/auth-sessions` | `400 INVALID_CLIENT_PLATFORM`, `401 INVALID_CREDENTIALS`, `403 EMAIL_VERIFICATION_REQUIRED`, `422 INVALID_ACCOUNT_INPUT`, `503 AUTH_STATE_UNAVAILABLE` |
| `POST /api/v1/access-tokens` | `400 INVALID_CLIENT_PLATFORM`, `401 INVALID_REFRESH_TOKEN`, `403 EMAIL_VERIFICATION_REQUIRED`, `503 AUTH_STATE_UNAVAILABLE` |
| `POST /api/v1/auth-session-revocations` | `400 INVALID_CLIENT_PLATFORM`, `401 INVALID_REFRESH_TOKEN`, `503 AUTH_STATE_UNAVAILABLE` |

### 3.4 인증·복구·탈퇴 처리

이메일 인증 요청·재발송과 비밀번호 재설정 요청은 요청 형식이 유효하고 메일 작업의 `mail_outbox` INSERT commit 성공이 확정되면 계정 존재 여부와 발송 여부에 관계없이 `202`와 빈 body를 반환한다. INSERT 또는 commit이 실패하거나 commit 결과가 불확실하면 `503 AUTH_STATE_UNAVAILABLE`이다. 입력 형식 오류는 `422 INVALID_ACCOUNT_INPUT`이며 `202`로 감추지 않는다. 이 규칙은 두 요청 API에 공통 적용된다. 실제 이메일 인증 메일은 ACTIVE이면서 미인증인 계정에만 발송하며 이 판정은 동기 요청 경로가 아니라 비동기 worker가 수행한다. 같은 사용자·용도의 새 인증 토큰을 만들 때 기존 활성 토큰을 무효화한다. 인증 토큰은 24시간, 비밀번호 재설정 토큰은 30분이며 단일 사용이다. 발송을 억제한 경우에도 외부 응답은 같고 실제 IP·계정별 제한 수치는 운영 부하·남용 데이터를 바탕으로 별도 승인한다.

원문 action token은 백엔드의 GET·query parameter로 받지 않고 POST body로만 전달한다. 이메일 링크는 프론트 화면 또는 앱 deep link·fragment를 열고, 프론트가 토큰을 POST body에 실어 완료 API를 호출한다. 비밀번호 재설정 성공 시 `password_hash` 변경과 같은 트랜잭션에서 `users.auth_version`을 +1 해 기존 Access와 Refresh를 모두 무효화하고 자동 로그인하지 않는다. Redis family 폐기는 사후 정리다.

탈퇴 API는 현재 비밀번호를 다시 확인한다. 성공 시 가명화, `WITHDRAWN`·`withdrawn_at`·`email_verified_at=NULL`, 임의 Argon2id 비밀번호 해시 변경과 `auth_version` +1을 하나의 MariaDB 트랜잭션으로 커밋해 기존 Access·Refresh 인증 세션을 무효화한다. Redis family 폐기는 커밋 뒤의 사후 정리다. Access Token 자체는 무상태지만 모든 보호 API가 `users.status`와 `auth_version`을 재확인하므로 즉시 접근을 막는다. 물리 삭제, 30일 복구, 탈퇴 취소는 제공하지 않는다.

### 3.4.1 이메일 인증 API 계약 (요청·완료)

두 API는 `X-Client-Platform`을 읽지 않는다. WEB과 MOBILE이 같은 요청 body·상태 코드·오류를 쓰며 쿠키를 설정하지도 읽지도 않는다. 플랫폼 분기는 3.3.1절의 인증 세션 API에만 있다.

**`POST /api/v1/email-verification-requests`**

| 요청 필드 | 타입 | required | nullable |
| --- | --- | :---: | :---: |
| `email` | string | O | X |

`email`이 누락·`null`·문자열이 아니거나 빈 문자열이거나 공백만 있으면 required 위반이다. 값이 있으면 trim 후 소문자로 정규화하고 유효한 이메일 형식과 254자 이하를 검사한다. required 위반과 형식 위반은 모두 `422 INVALID_ACCOUNT_INPUT`이다. 형식 오류는 어떤 계정에 대해서도 같게 거부되므로 계정 존재 단서가 되지 않는다.

동기 요청 경로는 email 검증·정규화와 `mail_outbox` INSERT·commit까지 수행한다. 형식이 유효하면 계정을 조회하지 않고 purpose가 `EMAIL_VERIFICATION`인 작업을 INSERT하며, INSERT commit 성공이 확정되면 `202`와 빈 body를, INSERT 또는 commit이 실패하거나 commit 결과가 불확실하면 `503 AUTH_STATE_UNAVAILABLE`을 반환한다. 이 `202`는 메일 작업이 내구적으로 접수되었다는 뜻이며 계정 존재, 발송 적격 여부, action token 발급, 제공자 접수, 사용자 편지함 도달 중 어느 것도 뜻하지 않는다. 계정 조회와 `status`·`email_verified_at`·발송 적격 여부 판정은 동기 요청 경로에서 수행하지 않고 비동기 worker가 담당하며, 실제 인증 메일은 `status=ACTIVE`이면서 `email_verified_at`이 NULL인 계정에만 보낸다. 형식이 유효한 모든 요청은 계정 존재 여부와 무관하게 같은 종류의 outbox INSERT·commit 경로를 거치고, 계정 존재·상태·발송 적격 여부에 따른 동기 분기나 의도적인 차등 지연을 두지 않는다. `202`와 `503`의 차이는 오직 outbox 저장 결과에 따르며 계정 존재 여부에 따르지 않는다. 다만 실제 처리 시간을 정확히 같은 값으로 고정하거나 새로운 지연 SLA를 만들지는 않는다. 계정이 없거나 `ACTIVE`가 아니거나 이미 인증된 경우에도 동기 응답은 달라지지 않으며 HTTP 상태·body·명세된 응답 헤더도 달라지지 않는다.

worker가 발송 대상으로 판정하면 같은 사용자·용도의 이전 활성 토큰을 무효화하고 새 토큰을 발급한다. 무효화된 토큰은 이후 제출되어도 `400 ACTION_TOKEN_INVALID`이며 가장 최근에 발급된 action token만 유효하다.

오류: `422 INVALID_ACCOUNT_INPUT`, `503 AUTH_STATE_UNAVAILABLE`.

**`POST /api/v1/email-verifications`**

| 요청 필드 | 타입 | required | nullable |
| --- | --- | :---: | :---: |
| `token` | string | O | X |

`token`은 opaque 문자열이므로 trim·정규화·대소문자 변환을 적용하지 않는다. 누락·`null`·문자열이 아니거나 빈 문자열이거나 공백만 있으면 `422 INVALID_ACCOUNT_INPUT`이다. 이는 요청이 계약 형식을 갖추지 못한 경우이며 토큰이나 계정의 상태와 무관하다. 그 외 문자열은 입력값 그대로 JWT 검증에 넘긴다. 앞뒤 공백이 포함된 non-empty 문자열도 trim하지 않으며 그 때문에 검증에 실패하면 `400 ACTION_TOKEN_INVALID`다.

non-empty token은 다음 사전 판정을 모두 통과해야 한다.

1. `token`의 누락·`null`·빈 문자열·공백 여부 검사
2. 3.6.1절에 따른 JWT 서명·`alg`·`kid`·`typ`·`iss`·`aud`·`exp` 검증
3. `purpose`가 `EMAIL_VERIFICATION`인지 검증
4. `sub`가 가리키는 사용자를 조회하고 `status=ACTIVE`인지 확인

Redis action token의 적격 조건은 `action-current`가 요청 `jti`를 가리키고, 상태가 `ACTIVE`이며, `tokenHash`가 일치하는 것이다. 이 조건은 별도의 선행 조회 호출을 요구하지 않는다. 실제 토큰 소비 시에는 3.6.1절의 `consumeActionToken`이 세 조건을 한 원자 연산에서 다시 검사하면서 `CONSUMED`로 전이해야 한다. Redis 조건 불일치 결과는 모두 `400 ACTION_TOKEN_INVALID`로 일반화한다.

1의 실패는 `422 INVALID_ACCOUNT_INPUT`이다. 2~4의 실패와 위 Redis 조건 불일치는 외부에서 원인을 구분하지 않고 모두 `400 ACTION_TOKEN_INVALID`다. 위조·서명 불일치·만료·소비 완료·재발급으로 인한 무효화·용도 불일치·`aud`나 `typ` 불일치가 여기에 해당한다. 사용자를 찾을 수 없는 경우와 `status`가 `ACTIVE`가 아닌 경우도 같은 code로 일반화한다. 탈퇴는 `email_verified_at`을 NULL로 되돌리므로 재인증을 허용하지 않으며, 계정 상태를 드러내는 별도 code를 쓰지 않는다.

성공은 `204`와 빈 body이며 `users.email_verified_at`이 값을 가진 상태로 확정되었음을 뜻한다. 위 사전 판정과 Redis 적격 조건을 모두 만족한 계정의 `email_verified_at`에 이미 값이 있으면 멱등하게 `204`를 반환하고 기존 값을 덮어쓰지 않으며, 그 토큰도 단일 사용 대상이다. 이 조건들을 만족하지 못한 토큰을 계정 상태만 보고 `204`로 처리하지 않는다.

오류: `422 INVALID_ACCOUNT_INPUT`, `400 ACTION_TOKEN_INVALID`, `503 AUTH_STATE_UNAVAILABLE`.

`consumeActionToken`의 `CONSUMED` 전이와 MariaDB `email_verified_at` 기록의 선후, 한쪽만 성공한 경우의 처리, `row_version` 경합 재시도, Redis·DB 장애의 HTTP 상태와 오류 code는 3.6.2절을 따른다. `mail_outbox` 스키마와 worker의 선점·재시도·종결 처리는 [ERD 3.5](./erd.md#table-mail_outbox)를 따른다. API별 클라이언트 복구는 3.7절을, 운영 경고·관측 기준은 3.8절을 따른다.

### 3.4.2 비밀번호 재설정 API 계약 (요청·완료)

두 API는 `X-Client-Platform`을 읽지 않는다. WEB과 MOBILE이 같은 요청 body·상태 코드·오류를 쓰며 쿠키를 설정하지도 읽지도 않는다. Controller에 플랫폼 분기를 두지 않는다.

**`POST /api/v1/password-reset-requests`**

| 요청 필드 | 타입 | required | nullable |
| --- | --- | :---: | :---: |
| `email` | string | O | X |

`email`이 누락·`null`·문자열이 아니거나 빈 문자열이거나 공백만 있으면 required 위반이다. 값이 있으면 trim 후 소문자로 정규화하고 유효한 이메일 형식과 254자 이하를 검사한다. required 위반과 형식 위반은 모두 `422 INVALID_ACCOUNT_INPUT`이다.

동기 요청 경로는 email 검증·정규화와 `mail_outbox` INSERT·commit까지 수행한다. 형식이 유효하면 계정을 조회하지 않고 purpose가 `PASSWORD_RESET`인 작업을 INSERT하며, INSERT commit 성공이 확정되면 `202`와 빈 body를, INSERT 또는 commit이 실패하거나 commit 결과가 불확실하면 `503 AUTH_STATE_UNAVAILABLE`을 반환한다. 이 `202`는 메일 작업이 내구적으로 접수되었다는 뜻이며 계정 존재, 발송 적격 여부, action token 발급, 제공자 접수, 사용자 편지함 도달 중 어느 것도 뜻하지 않는다. 계정 조회와 `status`·발송 적격 여부 판정은 동기 요청 경로에서 수행하지 않고 비동기 worker가 담당하며, 실제 재설정 메일은 `status=ACTIVE`인 계정에만 보내고 `email_verified_at`은 조건에 넣지 않는다. 미인증 계정도 재설정 대상이기 때문이며, 이메일 인증 요청이 `email_verified_at`이 NULL인 계정만 대상으로 삼는 것과 의도적으로 다르다. 형식이 유효한 모든 요청은 계정 존재 여부와 무관하게 같은 종류의 outbox INSERT·commit 경로를 거치고, 계정 존재·상태·발송 적격 여부에 따른 동기 분기나 의도적인 차등 지연을 두지 않는다. `202`와 `503`의 차이는 오직 outbox 저장 결과에 따르며 계정 존재 여부에 따르지 않는다. 다만 실제 처리 시간을 정확히 같은 값으로 고정하거나 새로운 지연 SLA를 만들지는 않는다. 발송하지 않는 경우에도 동기 응답은 달라지지 않고 계정 상태에 따라 HTTP 상태·body·명세된 응답 헤더가 달라지지 않는다.

worker가 발송 대상으로 판정하면 같은 사용자·`PASSWORD_RESET` 용도의 이전 활성 토큰을 무효화하고 새 토큰을 발급한다. 무효화된 토큰은 이후 제출되어도 `400 ACTION_TOKEN_INVALID`이며 가장 최근에 발급된 action token만 유효하다.

오류: `422 INVALID_ACCOUNT_INPUT`, `503 AUTH_STATE_UNAVAILABLE`.

**`POST /api/v1/password-resets`**

| 요청 필드 | 타입 | required | nullable |
| --- | --- | :---: | :---: |
| `token` | string | O | X |
| `newPassword` | string | O | X |

`token`은 opaque 문자열이므로 trim·정규화·대소문자 변환을 적용하지 않는다. 누락·`null`·문자열이 아니거나 빈 문자열이거나 공백만 있으면 `422 INVALID_ACCOUNT_INPUT`이다. 그 외 문자열은 입력값 그대로 JWT 검증에 넘기며, 앞뒤 공백이 포함된 non-empty 문자열도 trim하지 않고 그 때문에 검증에 실패하면 `400 ACTION_TOKEN_INVALID`다.

`newPassword`는 누락·`null`·문자열이 아닌 값이면 `422 INVALID_ACCOUNT_INPUT`이다. 문자열로 전달되었으면 trim하지 않는다. 비밀번호는 공백을 허용하므로 앞뒤 공백이 값의 일부이며, 공백만 있다는 이유로 required 위반 처리하지 않고 아래 정책으로 판정한다.

`newPassword` 정책 검증은 다음 순서로 한다. 먼저 NFC 정규화한다. 정규화된 문자열의 Unicode code point 수를 세어 15~64 code points여야 한다. Java 구현에서 UTF-16 code unit 수인 `String.length()`만 사용해서는 안 되며, 결합 문자와 supplementary character가 포함되어도 같은 규칙을 적용한다. 흔한 값·유출 값 검사도 정규화된 값을 대상으로 한다. 빈 문자열은 필드 누락이 아니라 0 code points이므로 길이 정책 위반이다. 위 정책을 만족하지 못하면 `422 INVALID_PASSWORD`다.

처리는 다음 순서를 따른다.

1. `token`과 `newPassword`의 필드 존재·`null`·타입 검사
2. 3.6.1절의 검증 순서에 따라 JOSE Header, 서명과 모든 필수 claim 검증
3. `purpose`가 `PASSWORD_RESET`인지 검증
4. `sub`가 가리키는 사용자를 조회하고 `status=ACTIVE`인지 확인
5. Redis action token 적격 조건을 비변경 조회로 확인
6. `newPassword` 정책 검증
7. Argon2id 해시 계산
8. 상태 변경 처리

5단계의 적격 조건은 `action-current`가 요청 `jti`를 가리키고, 상태가 `ACTIVE`이며, `tokenHash`가 일치하는 것이다. 이 조회는 값비싼 비밀번호 검사와 Argon2id를 반복 유발하지 못하게 하는 자원 보호용 사전 확인이며 정확성의 최종 근거가 아니다. 실제 소비 시에는 3.6.1절의 `consumeActionToken`이 같은 조건을 원자적으로 다시 검사하면서 `CONSUMED`로 전이해야 하고, 사전 확인과 소비 사이에 상태가 바뀌면 최종 소비가 실패해야 한다.

5단계를 두는 이유는 이 endpoint가 인증 없이 호출되기 때문이다. 서명이 정상이지만 이미 `CONSUMED`이거나 `INVALIDATED`인 토큰을 반복 제출하면, 5단계가 없을 때 매번 유출 값 조회와 Argon2id 연산에 도달한다. 5단계는 위조 토큰뿐 아니라 이미 소비·무효화된 정상 서명 토큰도 7단계에 이르지 못하게 한다. 동시 요청으로 사전 확인 직후 상태가 바뀌는 경쟁은 `consumeActionToken`의 원자적 재검증이 차단한다.

1의 실패는 `422 INVALID_ACCOUNT_INPUT`이다. 2~5의 실패는 외부에서 원인을 구분하지 않고 모두 `400 ACTION_TOKEN_INVALID`다. 위조·서명 불일치·만료·소비 완료·재발급으로 인한 무효화·용도 불일치·`aud`나 `typ` 불일치가 여기에 해당하며, 사용자를 찾을 수 없는 경우와 `status`가 `ACTIVE`가 아닌 경우, Redis 사전 확인 실패도 같은 code로 일반화한다. 6의 실패는 `422 INVALID_PASSWORD`다.

정책 위반으로 실패한 요청은 action token을 소비하지 않는다. 6은 `CONSUMED` 전이보다 먼저 완료되어야 하며, 잘못된 새 비밀번호 때문에 30분짜리 정상 토큰이 소진되면 사용자가 메일을 다시 받아야 한다.

7은 MariaDB 트랜잭션을 열거나 행 잠금을 잡기 전에 계산한다. Argon2id는 수백 밀리초가 걸리므로 잠금 안에서 계산하면 같은 계정의 로그인·탈퇴가 그동안 직렬화된다.

MariaDB 상태 변경은 하나의 트랜잭션에서 `password_hash` 교체, `auth_version` +1, `failed_login_count`를 0으로, `locked_until`을 NULL로 확정한다. `row_version`은 기존 낙관적 잠금 계약대로 함께 증가한다. 잠금 상태를 함께 푸는 이유는 누적된 실패가 이미 교체된 비밀번호에 대한 것이어서 새 비밀번호를 막을 근거가 되지 않기 때문이다.

이 갱신은 `status='ACTIVE'`와 4단계에서 조회한 `row_version`을 조건으로 갖는 조건부 갱신으로 수행한다. 갱신된 행이 없으면 `204`를 반환하지 않는다. 0행의 원인은 두 가지로 나뉜다. `status`가 더 이상 `ACTIVE`가 아니면 비밀번호를 변경하지 않고 `400 ACTION_TOKEN_INVALID`로 일반화한다. 탈퇴는 `password_hash`를 폐기된 무작위 값으로 교체하므로 탈퇴 뒤 재설정이 성립하면 로그인 가능한 계정이 되살아나기 때문이다. `status`는 `ACTIVE`인데 `row_version`만 달라진 경우는 다른 갱신과의 경합이며 이를 탈퇴로 단정하지 않는다. `ACTIVE` 상태에서의 `row_version` 경합 재시도 횟수와 최종 오류 응답, 경합 시 이미 계산한 Argon2id 해시를 다시 계산하지 않아도 되는 조건은 3.6.2절을 따른다. 어떤 경우에도 조건부 갱신 0행을 `204`로 숨기지 않는다.

`email_verified_at`은 변경하지 않는다. 재설정 토큰은 그 주소로 메일이 도달했음을 보이지만 가입 절차 완료를 뜻하지 않으며, 재설정이 이메일 인증을 우회하는 경로가 되어서는 안 된다. 미인증 계정은 재설정 후에도 로그인 전에 이메일 인증을 마쳐야 한다.

새 비밀번호가 기존 비밀번호와 같은지는 검사하지 않는다. 현재 정책을 통과하면 허용한다. 별도 password history를 두지 않는 구조에서 직전 값 하나만 막는 효과가 제한적이고, 비교를 위해 Argon2id 검증을 한 번 더 수행하는 비용이 크기 때문이다.

성공은 `204`와 빈 body이며 다음을 모두 만족한다. MariaDB 변경 확정 시점에도 `status=ACTIVE`가 보장되었고, `password_hash`가 교체되었으며, `auth_version`이 +1 되어 기존 Access·Refresh 인증 세션이 무효화되었고, `failed_login_count`가 0이고 `locked_until`이 NULL이며, `row_version`이 증가했고, `email_verified_at`은 바뀌지 않았으며, 그 action token을 다시 쓸 수 없고, 응답으로 Access·Refresh를 발급하지 않아 사용자가 새 비밀번호로 다시 로그인해야 한다.

오류: `422 INVALID_ACCOUNT_INPUT`, `422 INVALID_PASSWORD`, `400 ACTION_TOKEN_INVALID`, `503 AUTH_STATE_UNAVAILABLE`, `503 PASSWORD_POLICY_UNAVAILABLE`.

`consumeActionToken`의 `CONSUMED` 전이와 MariaDB 트랜잭션의 선후, 한쪽만 성공한 경우의 처리, Redis·DB·유출 비밀번호 조회 장애의 HTTP 상태와 오류 code, Redis Refresh family 사후 정리 실패 처리는 3.6.2절을 따른다. `mail_outbox` 스키마와 worker의 선점·재시도·종결 처리는 [ERD 3.5](./erd.md#table-mail_outbox)를 따른다. API별 클라이언트 복구는 3.7절을, 운영 경고·관측 기준은 3.8절을 따른다.

### 3.5 계정·인증 오류

오류 body는 공통 `{ code, message }` 형식이다.

| HTTP | code | 적용 조건 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | Access Token 없음·무효·`authVersion` 불일치 또는 보호 자원 인증 실패 |
| 401 | `INVALID_CREDENTIALS` | 알 수 없는 계정·비밀번호 오류·로그인 제한·탈퇴 계정. 외부에서 원인을 구분하지 않음 |
| 401 | `INVALID_REFRESH_TOKEN` | Refresh Token 없음·무효·만료·폐기·재사용 또는 `authVersion` 불일치 |
| 403 | `EMAIL_VERIFICATION_REQUIRED` | 올바른 자격 증명이지만 이메일 미인증 |
| 403 | `CURRENT_PASSWORD_MISMATCH` | 탈퇴의 현재 비밀번호 불일치 |
| 409 | `ACCOUNT_IDENTIFIER_UNAVAILABLE` | username 또는 email 사용 불가 |
| 422 | `INVALID_ACCOUNT_INPUT` | username·email 형식 또는 필수 입력 오류 |
| 422 | `INVALID_PASSWORD` | 길이·정규화·흔한 값·유출 값 정책 위반 |
| 400 | `ACTION_TOKEN_INVALID` | action token 무효·만료·사용 완료·용도 불일치. 외부에서 원인을 구분하지 않음 |
| 400 | `INVALID_CLIENT_PLATFORM` | 플랫폼 헤더 누락·미지원 값, 금지 위치 전달·양쪽 동시 전달 또는 `Origin` 헤더가 있는 요청의 `MOBILE` 주장 |
| 503 | `AUTH_STATE_UNAVAILABLE` | MariaDB 또는 Redis의 인증 상태를 읽거나 바꾸지 못했거나 결과가 불확실함. 저장소 종류를 외부에서 구분하지 않음 |
| 503 | `PASSWORD_POLICY_UNAVAILABLE` | 유출 비밀번호 조회 실패로 비밀번호 정책을 완성할 수 없음 |

`authVersion` 불일치는 Access 보호 API에서는 `401 UNAUTHORIZED`, Refresh 검증에서는 `401 INVALID_REFRESH_TOKEN`으로 일반화한다. 실제 세대 값이나 내부 비교 결과를 응답 body·헤더·로그에 기록하지 않으며 같은 code와 message를 쓴다.

### 3.6 Redis 저장 계약과 구현 전 승인 경계

관계형 DB는 MariaDB 11.8.9, 인증 상태는 Redis Open Source 8.2.9를 사용한다. 토큰 테이블은 두지 않으며 저장 항목·TTL·재사용 탐지는 [ERD Redis 인증 상태](./erd.md#redis-auth)를 따른다. 사용자별 families 인덱스는 조회·정리를 위한 보조 구조이며, 기존 Access·Refresh 인증 세션 무효화의 권위는 `users.auth_version`과 `users.status`다. 기존 공개 endpoint·성공 DTO·유효기간·계정 존재 비노출 정책은 유지한다. 토큰 값의 형식만 서명 JWT로 변경하며 클라이언트가 payload를 권한 판단 근거로 사용하지 않는다.

Redis 원자적 소비·회전은 MariaDB 변경까지 원자적으로 만들지 않는다. action token 소비와 MariaDB 변경처럼 서로 다른 저장소에 걸친 복합 처리에서 일부 성공을 정상 완료로 숨기지 않는다. 반대로 MariaDB에 커밋된 `auth_version`·`status`의 무효화 효력은 Redis 사후 정리가 실패해도 되돌아가지 않는다. 각 API의 최종 응답과 복구 방식은 3.6.2절을 따른다. 이 절에서 확정된 것은 무효화 권위(users.auth_version과 users.status), 로그인 Snapshot 처리, Refresh 회전 경쟁의 불변식, Access·Refresh의 authVersion 비교, Redis 복원 정책이며 상세는 3.6.1절을 따른다. action token 소비와 MariaDB 변경의 순서, 한쪽만 성공한 경우의 재시도·복구, Redis·DB 장애의 HTTP 상태·오류 code는 3.6.2절에서 확정한다. `mail_outbox` 스키마와 worker의 선점·재시도·종결 처리는 [ERD 3.5](./erd.md#table-mail_outbox)에서 확정했다. API별 클라이언트 복구는 3.7절에서, 운영 경고·관측 기준은 3.8절에서 확정했다. 위 오류 표에 장애를 임의로 INVALID_REFRESH_TOKEN 등으로 끼워 넣지 않는다. 요청 API의 `202`는 메일 작업의 내구적 접수를 뜻하며 실제 발송 성공을 보장하지 않는다.

승인 전에도 지켜야 할 기준은 만료·소비·폐기된 토큰 재허용 금지, Redis 검증 실패 시 서명만으로 통과시키는 우회 금지, 원문 토큰 로그 금지다. 일반 Access 보호 API에 새로운 Redis 블랙리스트 조회를 추가하는 승인은 아니며 users 상태 검증은 유지한다. 수업 WebSocket 60초 티켓의 기존 단일 인스턴스 메모리 보관과 Redis 전역 요청 제한 제외 범위도 변경하지 않는다.

### 3.6.1 JWT와 Redis 인증 상태 계약

**서명과 key ring.** 서명은 `HS256`으로 고정한다. key는 Access·Refresh·action의 독립된 세 ring으로 나누고 각 ring은 자기 종류의 토큰만 서명·검증한다. Access는 사용 빈도가 높고 수명이 짧아 회전 주기가 다르며, Refresh는 14일 수명 때문에 retired key를 오래 유지해야 하므로 두 종류가 key를 공유하면 한쪽의 유출 대응 회전이 다른 쪽 전체를 끌고 들어간다. action 두 종류는 수명이 짧고 성격이 같아 한 ring으로 묶고 `purpose`로 구분한다. 각 key는 CSPRNG로 생성한 256비트 이상 값을 Base64URL로 인코딩해 환경변수로 주입하고 소스·문서에 실제 값을 기록하지 않는다.

**검증 순서.** 기대하는 토큰 종류와 key ring, `typ`, `aud`는 요청 endpoint와 유스케이스가 먼저 결정한다. 토큰에 실린 값으로 검증기나 ring을 고르지 않는다. 순서는 다음과 같다.

1. endpoint와 유스케이스가 기대 토큰 종류, key ring, `typ`, `aud`를 정한다
2. JOSE Header를 미검증 값으로 제한적으로만 파싱한다
3. 헤더에서 `alg`가 정확히 `HS256`인지, `typ`이 1에서 정한 기대값과 같은지, `kid`가 있는지, `jku`·`x5u`가 없는지 확인한다. 하나라도 어긋나면 서명 검증에 들어가지 않고 거부한다
4. 1에서 선택한 ring 안에서만 `kid`로 key를 조회한다. 그 ring에 없으면 거부한다
5. 토큰의 `alg`가 아니라 서버가 고정한 `HS256`으로 서명을 검증한다
6. 서명 검증에 성공한 뒤 payload의 `iss`·`aud`·`sub`·`iat`·`exp`·`jti`와 종류별 claim을 검증한다

`kid`가 전역 유일하더라도 4단계에서 ring을 넓히지 않으며 이 순서를 생략하지 않는다. 미검증 `typ`·`kid`·`alg`는 검증기나 ring을 선택하는 근거가 아니라 거부 판단에만 사용한다. `jku`·`x5u` 등 외부 key 조회를 유발하는 헤더는 지원하지 않으며, 목록에 없는 `kid`는 즉시 거부한다.

**key rotation.** 종류별로 active signing key 1개와 retired verification key 0개 이상을 둔다. 서명은 항상 active key로만 하고 검증은 해당 ring의 active와 retired 전체에서 조회한다. retired key는 그 key로 마지막에 발급한 토큰이 모두 만료될 때까지 유지한 뒤 제거하며 보유 개수를 미리 제한하지 않는다. key 유출은 정상 회전과 구분해 해당 key를 retired로 옮기지 않고 즉시 제거하며, 그 key로 서명된 토큰은 수명이 남아 있어도 무효가 된다. `kid`는 세 ring을 통틀어 유일해야 한다.

**JOSE Header.** `typ`은 payload claim이 아니라 JOSE Header 파라미터다. `typ`은 endpoint가 기대하는 값과 같은지 검사하는 대상이며, `typ` 값을 읽어 검증기나 ring을 고르는 데 쓰지 않는다. 아래 값은 이 서비스 전용이며 RFC 9068 전체 프로필을 채택하지 않는다.

| 헤더 | Access | Refresh | Email verification | Password reset |
| --- | --- | --- | --- | --- |
| `alg` | `HS256` | `HS256` | `HS256` | `HS256` |
| `kid` | Access ring | Refresh ring | action ring | action ring |
| `typ` | `itda-access+jwt` | `itda-refresh+jwt` | `itda-email-verification+jwt` | `itda-password-reset+jwt` |

**Payload claim.** 필수 claim이 없으면 거부한다.

| claim | Access | Refresh | Email verification | Password reset |
| --- | --- | --- | --- | --- |
| `iss` | O | O | O | O |
| `aud` | `itda-api` | `itda-refresh` | `itda-email-verification` | `itda-password-reset` |
| `sub` | O | O | O | O |
| `iat`·`exp` | O | O | O | O |
| `jti` | O | O | O | O |
| `authVersion` | O | O | — | — |
| `role` | O | — | — | — |
| `familyId` | — | O | — | — |
| `purpose` | — | — | `EMAIL_VERIFICATION` | `PASSWORD_RESET` |
| 유효기간 | 15분 | 14일 | 24시간 | 30분 |

`iss`는 배포 환경별 논리 이름을 환경변수로 주입한다: `itda-backend-local`, `itda-backend-test`, `itda-backend-prod`. 운영 도메인을 `iss`나 `aud`에 넣지 않으며 `aud`를 WEB·MOBILE로 나누지 않는다.

값 형식은 다음과 같다. `sub`는 `users.id`의 BIGINT 10진수 문자열, `jti`와 `familyId`는 CSPRNG 기반 UUID v4, `iat`와 `exp`는 UTC NumericDate 정수 초다. `authVersion`은 `users.auth_version`의 0 이상 정수이며 claim 누락·음수·DB 현재 값 불일치는 모두 거부한다.

**시각 검증.** `now >= exp`이면 즉시 만료이며 `exp` 허용 오차는 0초다. `iat`는 현재보다 최대 30초 미래인 것까지만 허용한다. `iat <= exp`를 필수로 검사한다. `nbf`는 사용하지 않는다. Redis 만료는 기존 계약대로 `exp`와 일치시키며, Refresh와 action의 유효기간을 시각 오차 허용으로 연장하지 않는다.

**종류 분리.** ring 분리에 더해 종류별로 다른 `aud`, 헤더 `typ`, 전용 발급기와 전용 검증기, 서로 다른 필수 claim 집합을 모두 적용한다. action token은 `purpose`까지 추가 검증한다. 범용 검증기 하나에 종류를 파라미터로 넘기지 않으며, 종류가 다르면 다른 코드 경로를 타게 한다. Access를 갱신·action endpoint에, Refresh를 보호 API·action endpoint에, 서로 다른 용도의 action token을 상대 endpoint에 넣는 모든 조합이 실패하는 테스트를 계약으로 요구한다.

**Redis prefix.** 환경변수 `AUTH_REDIS_KEY_PREFIX`로 환경을 분리한다. 값은 `local` 환경에서 `local`, `test` 환경에서 `test`, `prod` 환경에서 `prod`다. `prod`에서 값이 누락되거나 허용 값이 아니면 기동에 실패한다. Redis DB index로 환경을 분리하지 않는다. DB index는 Cluster에서 쓸 수 없고 잘못된 설정이 조용히 다른 환경 데이터를 읽게 만든다.

**Redis 키 표기.** 중괄호는 Redis hash tag 문법 그대로이며 `userId` 값에만 적용한다. prefix와 나머지 자리표시자는 꺾쇠로 표기한다. 예를 들어 `<prefix>:auth:{<userId>}:refresh:<jti>`는 실제로 `local:auth:{42}:refresh:3f2a9c1e-...`처럼 만들어지며 slot 계산에 쓰이는 값은 `42`뿐이다. 현재 배포는 단일 인스턴스 전제이지만 hash tag를 두어 키 설계가 Cluster의 same-slot 제약을 깨지 않게 한다.

| 논리 키 | 자료구조 | 값 | 만료 시각 |
| --- | --- | --- | --- |
| `<prefix>:auth:{<userId>}:refresh:<jti>` | Hash | `userId`, `familyId`, `tokenHash`, `expiresAt`, `status` | 그 Refresh JWT의 `exp` |
| `<prefix>:auth:{<userId>}:family:<familyId>` | Hash | `userId`, `currentJti`, `status` | 계열에 남은 Refresh 중 가장 늦은 `exp` |
| `<prefix>:auth:{<userId>}:families` | ZSET | member `familyId`, score 계열 만료 epoch | 사용자의 계열 중 가장 늦은 계열 만료 시각 |
| `<prefix>:auth:{<userId>}:action:<purpose>:<jti>` | Hash | `userId`, `purpose`, `tokenHash`, `expiresAt`, `status` | 그 action JWT의 `exp` |
| `<prefix>:auth:{<userId>}:action-current:<purpose>` | String | 현재 활성 `jti` | 그 action JWT의 `exp` |

위 열은 키가 사라지는 절대 시각을 뜻하며 값 자리에 epoch 숫자를 넣는다는 의미가 아니다. 구현은 Redis의 만료 설정으로 이 시각을 지정하고, `expiresAt` 필드는 조회용으로 같은 시각을 담는다.

Refresh 회전 시 만료는 다음과 같이 움직인다. 기존 토큰 키는 `ROTATED`로 바뀌되 원래 `exp`를 그대로 유지하고 만료를 앞당기지 않는다. 그래야 아직 유효 기간이 남은 이전 토큰의 재사용을 판별할 수 있다. 신규 토큰 키는 신규 토큰의 `exp`에 만료한다. family 키와 `families` 인덱스의 만료는 신규 토큰의 `exp`까지 연장한다. 계열이나 인덱스가 그 안의 유효한 토큰보다 먼저 사라지면 회전과 재사용 탐지에 필요한 연결이 끊기기 때문이다.

action token 키와 `action-current` 키는 모두 해당 action token의 `exp`에 만료한다. `CONSUMED`·`INVALIDATED`로 바뀐 뒤에도 만료를 앞당기지 않는다.

상태 값은 Refresh가 `ACTIVE`·`ROTATED`·`REVOKED`, action이 `ACTIVE`·`CONSUMED`·`INVALIDATED`, family가 `ACTIVE`·`REVOKED`다. `tokenHash`는 compact JWT 원문의 ASCII 바이트에 대한 SHA-256을 Base64URL(padding 없음)로 인코딩한 값이며 토큰 원문·비밀번호·이메일을 Redis 값이나 키, 로그에 넣지 않는다.

action의 `CONSUMED`·`INVALIDATED`와 Refresh의 `ROTATED` 상태는 원래 `exp`까지 유지한다. 조기에 삭제하면 재사용 시도를 존재하지 않는 토큰과 구분할 수 없기 때문이다.

**원자 연산.** 상태 확인과 전이 사이에 다른 요청이 끼어들지 않도록 Lua 스크립트로 처리한다.

| 스크립트 | 처리 | 내부 반환 사유 |
| --- | --- | --- |
| `issueRefreshFamily` | 새 `familyId`의 family를 `ACTIVE`로 만들고 첫 Refresh를 `ACTIVE`로 등록하며 `families`에 추가. 같은 `familyId`나 `jti`가 이미 있으면 덮어쓰지 않는다 | `OK` / `ALREADY_EXISTS` |
| `rotateRefresh` | 토큰 존재·`ACTIVE`·`tokenHash` 일치·family `ACTIVE`를 모두 확인한 뒤 기존 토큰을 `ROTATED`, 신규를 `ACTIVE`로 바꾸고 `currentJti`와 만료를 갱신. 동시 요청 중 하나만 성공 | `OK` / `NOT_FOUND` / `HASH_MISMATCH` / `REUSED` / `FAMILY_REVOKED` |
| `revokeFamily` | family `status`를 `REVOKED`로 변경 | `OK` / `NOT_FOUND` |
| `issueActionToken` | 같은 사용자·용도의 이전 활성 토큰을 `INVALIDATED`로 바꾸고 새 토큰을 `ACTIVE`로 등록하며 `action-current`를 새 `jti`로 갱신. 같은 `jti`가 이미 있으면 덮어쓰지 않는다 | `OK` / `ALREADY_EXISTS` |
| `consumeActionToken` | `action-current`가 가리키는 `jti`와 요청 `jti`의 일치, 토큰 `ACTIVE`, `tokenHash` 일치를 한 연산에서 확인한 뒤 토큰을 `CONSUMED`로 바꾸고 `action-current`가 더 이상 활성 토큰을 가리키지 않도록 함께 정리한다 | `OK` / `NOT_FOUND` / `NOT_CURRENT` / `HASH_MISMATCH` / `ALREADY_CONSUMED` / `INVALIDATED` |

`ALREADY_EXISTS`는 새 `jti` 또는 `familyId`가 이미 존재해 덮어쓰기를 거부한 경우다. Service는 새 UUID를 만들어 유한 횟수로 다시 시도하며, 이 내부 사유를 HTTP 응답에 노출하지 않는다.

접근하는 모든 키를 `KEYS` 인자로 명시해 전달하고 스크립트 안에서 키 이름을 조립하지 않는다. 한 스크립트가 접근하는 키는 같은 `userId` hash tag에 둔다. `rotateRefresh`가 기존 토큰의 `ROTATED`·`REVOKED`를 감지하면 같은 스크립트 안에서 해당 family를 `REVOKED`로 바꿔 탐지와 폐기 사이에 다른 요청이 끼어들지 못하게 한다. 어떤 스크립트도 `families` ZSET 전체를 무제한 순회하지 않으며, 순회가 필요하면 배치 단위로 끊는다. Redis는 단일 스레드이므로 긴 순회가 전체 서비스를 멈추기 때문이다. `EVALSHA`는 `NOSCRIPT` 오류가 났을 때만 `EVAL`로 재시도한다.

**사용자 인증 세션 전체 무효화.** 비밀번호 재설정은 `password_hash` 변경과 `users.auth_version` +1을, 탈퇴는 `status=WITHDRAWN`·가명화·폐기 `password_hash`와 `users.auth_version` +1을 각각 같은 MariaDB 트랜잭션에서 커밋한다. Access와 Refresh는 `authVersion`을 필수 claim으로 실으며, 보호 API와 갱신 API가 DB의 현재 값과 비교해 다르면 거부한다. 비밀번호 재설정 후 기존 Access를 즉시 거부하는 것은 기존 계약의 충돌 해소가 아니라 새로 채택하는 보안 강화 정책이다. 탈퇴는 `status`와 `authVersion` 두 조건으로 차단하며 Access 블랙리스트는 추가하지 않는다.

Redis token·family 값에는 `authVersion`을 복제하지 않는다. 권위가 DB에 있어 복제본은 검증에 쓰이지 않고 어긋날 지점만 늘리기 때문이다. Redis 계열 정리는 사후 정리이며 무효화의 최종 권위는 `users.auth_version`과 `users.status`다.

갱신은 DB에서 확인한 Snapshot의 `auth_version`을 신규 토큰 claim에 그대로 쓴다. 회전 도중 `auth_version`이 증가해도 신규 토큰이 새 세대로 승격되지 않게 하기 위해서다. 회전 후 DB 재확인은 필수이며 상세는 3.3.1절을 따른다. 재확인 직후에도 다시 경쟁이 생길 수 있으므로 저장소 간 완전한 원자성은 주장하지 않는다. 어떤 경쟁에서도 새로 발급된 Access의 보호 API 사용이 DB `auth_version` 비교로 거부된다는 것이 불변식이며, 최종 차단 권위는 보호 API의 DB 비교다.

**내부 사유의 비노출.** 위 반환 사유는 Service 내부 판정용이다. `HASH_MISMATCH`·`REUSED`·`NOT_CURRENT`·`ALREADY_EXISTS` 같은 내부 원인을 HTTP 응답 body나 헤더에 노출하지 않으며, 외부 응답은 3.5절의 일반화된 code만 사용한다.

**계층 경계.** Service는 공통 JWT 발급·검증 계약과 Redis 저장소 계약을 사용하고 WEB과 MOBILE은 동일한 JWT·Redis 구현을 사용한다. 플랫폼 분기는 Controller의 쿠키·body 변환에만 둔다.

**동시성 검증.** 후속 구현은 실제 Redis를 사용해 동일 Refresh Token 동시 갱신에서 하나만 성공하는 것, 재사용 감지 후 family가 폐기되는 것, 동일 action token 동시 소비에서 하나만 성공하는 것을 검증한다. Redis mock만으로 원자성 검증이 완료되었다고 판단하지 않는다.

### 3.6.2 인증 저장소 장애와 부분 성공 계약

MariaDB와 Redis를 하나의 트랜잭션으로 묶지 않으므로 두 저장소 사이에 부분 성공이 생길 수 있다. 모든 처리는 fail-closed다. 상태를 확인하거나 바꿀 수 없으면 인증·인가를 진행하지 않고 토큰을 발급하지 않으며, 결과가 불확실한 처리를 성공으로 단정하지 않는다.

저장소 장애는 `503 AUTH_STATE_UNAVAILABLE`, 유출 비밀번호 조회 실패는 `503 PASSWORD_POLICY_UNAVAILABLE`이다. 어느 경우에도 장애를 `INVALID_CREDENTIALS`·`INVALID_REFRESH_TOKEN`·`ACTION_TOKEN_INVALID`로 위장하지 않는다.

반대로 저장소가 정상 응답해 얻은 결정적 결과를 장애로 바꾸지도 않는다. `AUTH_STATE_UNAVAILABLE`은 Redis 연결·명령 실행 실패, 타임아웃, 명령 적용 여부를 알 수 없는 결과, MariaDB 읽기·commit 결과 불확실, 허용된 재시도 뒤에도 지속되는 DB 경합에만 쓴다. 다음 결정적 결과는 기존 오류를 그대로 유지한다. Refresh Token 없음·만료·폐기·재사용·상태 불일치는 `401 INVALID_REFRESH_TOKEN`과 기존 family 폐기 규칙을 따른다. action token의 `NOT_FOUND`·`NOT_CURRENT`·`HASH_MISMATCH`·`ALREADY_CONSUMED`·`INVALIDATED`는 `400 ACTION_TOKEN_INVALID`다. 로그아웃에서 Redis 조회가 정상인데 토큰이 식별되지 않으면 `401 INVALID_REFRESH_TOKEN`이고, 이미 폐기된 family임을 확인하면 `204`다.

`AUTH_STATE_UNAVAILABLE`을 받은 클라이언트는 작업이 성공했다고도, 현재 인증 상태가 어떠하다고도 가정하지 않는다. 같은 요청을 그대로 재시도해도 되는지는 API와 실패 시점에 따라 다르다. action token은 소비 전 장애라면 다시 쓸 수 있고, 소비 결과가 불확실하거나 소비 뒤 장애라면 다시 쓸 수 없을 수 있다. 이 code가 링크를 항상 폐기하라거나 흐름을 항상 처음부터 다시 시작하라는 뜻은 아니며, 클라이언트는 현재 화면에서의 재시도, 새 링크 요청, 재로그인 같은 API별 복구 경로를 제공한다. `PASSWORD_POLICY_UNAVAILABLE`은 인증 상태를 바꾸기 전에, 비밀번호 재설정에서는 action token을 소비하기 전에 실패한다. 따라서 회원가입은 같은 입력으로, 비밀번호 재설정은 같은 입력과 같은 token으로 서비스 복구 후 다시 시도할 수 있다는 점이 다르다. 이 code는 사용자가 비밀번호를 바꿔야 한다는 뜻이 아니며, 유출 여부를 검사할 수 없는 동안 다른 비밀번호로 우회하도록 안내하지 않는다.

**action 완료 API의 저장소 순서.** 이메일 인증 완료와 비밀번호 재설정 완료는 모든 요청 형식·JWT·`purpose`·사용자 상태 검증과 비밀번호 정책 검증을 먼저 끝낸다. 비밀번호 재설정은 Argon2id 해시도 트랜잭션 밖에서 먼저 계산한다. 그다음 Redis `consumeActionToken`을 원자적으로 성공시켜 단일 사용을 확정하고, 마지막으로 MariaDB 조건부 갱신을 수행한다.

MariaDB를 먼저 바꾸고 Redis 소비를 나중에 하면, DB 성공 후 소비가 실패했을 때 같은 재설정 토큰으로 비밀번호를 한 번 더 바꿀 수 있다. Redis를 먼저 소비하면 이후 DB가 실패해도 토큰만 소진되고 사용자는 새 메일을 요청해 복구한다. 재사용 가능한 토큰을 남기는 것보다 fail-closed로 소진하는 쪽이 안전하다. action token 완료 처리의 Redis와 MariaDB 사이 원자성을 만들기 위한 `PROCESSING`·`RESERVED` 중간 상태, DB token 테이블, action 처리용 outbox는 도입하지 않는다. 이 제한은 action token 완료 처리에만 적용된다. 메일 전달 신뢰성에는 이미 [ERD 3.5](./erd.md#table-mail_outbox)의 MariaDB `mail_outbox`를 사용하며, action token 완료 처리용 outbox와 메일 발송용 `mail_outbox`는 서로 다른 목적이므로 혼동하지 않는다.

**Redis 소비 결과가 불확실한 경우.** `consumeActionToken` 호출 결과를 알 수 없으면 실제로 `CONSUMED`가 되었을 수도, 되지 않았을 수도 있다. 이때 소비 성공을 확정할 수 없으므로 MariaDB 변경을 시작하지 않고 `503 AUTH_STATE_UNAVAILABLE`을 반환한다. 토큰을 `ACTIVE`로 되돌리는 보상은 하지 않는다. 같은 토큰으로 다시 시도하면 실제 Redis 결과에 따라 처리가 다시 진행되거나 `400 ACTION_TOKEN_INVALID`가 된다. 어느 쪽인지 추측해 DB 변경을 진행하지 않는다.

**Redis 소비가 확정적으로 성공한 뒤 MariaDB가 실패한 경우.** `204`를 반환하지 않고 `503 AUTH_STATE_UNAVAILABLE`을 반환한다. `CONSUMED` 토큰을 `ACTIVE`로 되돌리지 않으므로 같은 토큰을 다시 제출하면 `400 ACTION_TOKEN_INVALID`가 된다.

이 경우 MariaDB 결과를 두 가지로 나눈다. rollback되었거나 commit 전에 실패한 것이 확정되었다면 본 요청에 의한 MariaDB 변경은 없다. 이메일 인증에서는 기존 `email_verified_at` 값을 그대로 유지하고, 비밀번호 재설정에서는 기존 `password_hash`와 `auth_version`을 그대로 유지한다. 이는 본 요청이 아무것도 바꾸지 않았다는 뜻이며 다른 동시 요청이 그 사이 상태를 바꿨을 가능성까지 부정하지 않는다. 반면 commit 성공 여부 자체가 불확실하다면 목적 상태가 변경되었을 수도 있고 아닐 수도 있다. 이때 외부에 어느 쪽이라고 단정하지 않으며, action token은 복원하지 않는다. 사용자는 로그인을 시도해 현재 상태를 확인하거나 새 인증·재설정 메일 요청 흐름을 시작할 수 있다.

**`row_version` 경합 재시도.** 아래 재시도는 action token을 이미 소비한 뒤이며, 실패해도 토큰은 복원되지 않는다. 재시도는 각 API에서 최대 1회이고 무제한 재시도를 하지 않는다.

이메일 인증 완료는 조건부 갱신이 0행이면 사용자를 다시 읽는다. `status`가 `ACTIVE`가 아니면 `400 ACTION_TOKEN_INVALID`다. 재조회에 성공했고 결과가 불확실하지 않으며 `status`가 `ACTIVE`이고 `email_verified_at`에 이미 값이 있으면 목적 상태가 달성된 것이므로 `204`를 반환하고 덮어쓰지 않는다. DB 조회 장애나 결과 불확실을 목적 상태 달성으로 추측해 `204`로 처리하지 않으며 그때는 `503 AUTH_STATE_UNAVAILABLE`이다. `ACTIVE`이고 미인증이며 `row_version`만 달라졌으면 최신 `row_version`으로 한 번만 재시도한다. 재시도가 성공하면 `204`, 다시 0행이면 `503 AUTH_STATE_UNAVAILABLE`이다.

비밀번호 재설정 완료는 조건부 갱신이 0행이면 사용자를 다시 읽는다. `status`가 `ACTIVE`가 아니면 `400 ACTION_TOKEN_INVALID`다. `password_hash` 또는 `auth_version`이 최초 Snapshot과 달라졌으면 그 사이 다른 인증 변경이 확정된 것이므로 기존 계산 결과로 덮어쓰지 않고 `503 AUTH_STATE_UNAVAILABLE`을 반환한다. `status`·`password_hash`·`auth_version`이 모두 같고 `row_version`만 달라졌으면 이미 계산한 Argon2id 해시를 재사용해 최신 `row_version`으로 한 번만 재시도한다. 이미 계산된 encoded Argon2id 해시는 새 비밀번호와 salt, 알고리즘 파라미터를 모두 포함한 저장 가능한 결과이므로, 관련 인증 필드가 그대로인 한 DB 상태와 무관하게 같은 새 비밀번호를 나타낸다. 재시도가 성공하면 `204`, 다시 0행이면 `503 AUTH_STATE_UNAVAILABLE`이다. 어떤 경합도 `204`로 숨기지 않는다.

**API별 fail-closed 처리.**

| API | MariaDB 장애 | Redis 장애 |
| --- | --- | --- |
| 회원가입 | `users`와 `mail_outbox` 등록은 같은 MariaDB 트랜잭션이며 `201`은 두 INSERT의 commit 성공이 확정될 때만 반환한다. username·email UNIQUE 위반이 결정적으로 확인되면 commit 단계에서 드러나도 `409 ACCOUNT_IDENTIFIER_UNAVAILABLE`이고, 그 밖의 MariaDB 명령 실패·commit 실패·commit 결과 불확실은 `503 AUTH_STATE_UNAVAILABLE` | 회원가입 동기 트랜잭션은 Redis action token 발급이나 메일 제공자 호출을 `201`의 성공 조건으로 삼지 않는다. commit 성공 뒤 worker의 action token 발급 또는 메일 발송이 실패해도 회원가입을 롤백하지 않고 이미 반환한 `201`도 바꾸지 않으며, 사용자는 이메일 인증 요청 API로 새 작업을 접수한다 |
| 내 정보 | `503 AUTH_STATE_UNAVAILABLE` | 해당 없음 |
| 탈퇴 | `503 AUTH_STATE_UNAVAILABLE` | family 정리 실패는 `204` 유지 |
| 로그인 | 첫 조건부 갱신 0행은 아래 절차를 따르고, 두 번째 갱신도 실패하거나 DB 결과가 불확실하면 `503`. 토큰을 발급하지 않음 | 세션 상태 저장 실패나 불확실은 `503`. Access·Refresh를 응답하지 않음 |
| Access 갱신 (초기 검증) | 사용자 조회 자체가 실패하면 회전하지 않고 `503`. 조회는 성공했으나 `status`·`authVersion`·`emailVerified`가 계약과 다르면 기존 `401`·`403` | Token·family 상태 조회가 정상인데 상태가 유효하지 않으면 기존 `401`. 조회 명령 실패·타임아웃·결과 불확실은 `503` |
| Access 갱신 (회전) | 해당 없음 | 결정적 재사용·상태 불일치는 기존 `401`과 family 폐기 규칙. 회전 명령 실패나 결과 불확실은 `503`이며 신규 토큰을 반환하지 않음 |
| Access 갱신 (회전 후 재확인) | 재확인 자체가 실패하면 `503`. 값이 달라졌으면 `401 INVALID_REFRESH_TOKEN` | 재확인 실패 시 family 폐기를 가능한 범위에서 시도 |
| 로그아웃 | 해당 없음 | 폐기 실패나 불확실은 `503`. `204`로 성공 처리하지 않음 |
| 이메일 인증 완료 | 소비 확정 뒤 실패는 `503` | `consumeActionToken`의 결정적 적격 조건 실패는 `400`. Redis 명령 실패나 결과 불확실은 `503` |
| 비밀번호 재설정 완료 | 소비 확정 뒤 실패는 `503` | `consumeActionToken`의 결정적 적격 조건 실패는 `400`. Redis 명령 실패나 결과 불확실은 `503` |

회원가입에서 commit 결과가 불확실하면 사용자와 메일 작업이 생성되지 않았다고 단정하지 않는다. 두 요청 API도 같다. `mail_outbox` INSERT commit 결과가 불확실해 `503`을 반환했더라도 작업이 저장되었을 수 있으므로 `503`은 메일 작업 미접수를 보장하지 않으며 이후 메일이 발송될 수 있다. 클라이언트가 재시도하면 작업과 메일이 중복될 수 있고 중복 요청은 허용하며, 가장 최근에 발급된 action token만 유효하다. 이 동작은 계정 존재 여부와 무관하게 두 요청 API에 동일하다.

로그인의 조건부 갱신이 0행이면 즉시 `503`이 아니라 [ERD 3.1](./erd.md#table-users)의 기존 계약대로 사용자 상태를 다시 읽는다. `password_hash` 또는 `auth_version`이 달라졌거나 현재 조건이 로그인 불가라면 기존 일반화된 `401 INVALID_CREDENTIALS` 계약을 유지한다. `password_hash`·`auth_version`과 인증 조건이 같고 `row_version`만 바뀌었으면 최신 `row_version`으로 최대 1회 재시도한다. 두 번째 조건부 갱신도 실패하거나 DB 결과가 불확실할 때만 `503 AUTH_STATE_UNAVAILABLE`이며, 이때 토큰을 발급하지 않는다.

Access 갱신은 초기 검증, 회전, 회전 후 DB 재확인 세 구간이며 DB 검사는 두 번으로 유지하고 합치지 않는다. 초기 검증에서는 Redis의 Token·family 활성 상태를 조회하고 MariaDB에서 사용자를 조회한다. Redis 조회가 정상인데 상태가 유효하지 않거나 MariaDB 조회가 성공했는데 값이 계약과 다르면 기존 `401`·`403`이고, 두 저장소의 조회 명령 자체가 실패하거나 결과가 불확실하면 `503`이다. 회전 구간에서는 결정적 재사용·상태 불일치를 기존 `401`과 family 폐기 규칙으로 처리하고, Redis 명령 실패나 결과 불확실만 `503`으로 다루며 어느 쪽이든 신규 토큰을 반환하지 않는다. 회전 후 재확인은 응답 전에 `auth_version`과 `status`를 다시 본다. 회전 후 재확인에서 값이 달라졌으면 family를 폐기하고 `401 INVALID_REFRESH_TOKEN`, 재확인 자체가 실패하면 가능한 범위에서 family 폐기를 시도하고 `503`이다. family 폐기 시도가 실패하더라도 DB에서 `authVersion` 불일치나 비활성 상태가 확정되었다면 신규 Access·Refresh를 반환하지 않는다.

로그아웃의 `503`은 3.3절 멱등 `204` 규칙의 예외다. 멱등 `204`는 Redis가 정상 응답해 이미 폐기된 상태를 확인한 경우에만 성립하며, 폐기 명령 자체가 실패했거나 결과를 알 수 없으면 성공으로 보고하지 않는다. `X-Client-Platform: WEB`이고 전달 경로 충돌이 없는 요청에는 서버 판정이 `503`이어도 발급 때와 같은 속성의 쿠키 삭제 응답을 함께 보낸다. 서버 상태를 정리하지 못했더라도 브라우저에 토큰을 남길 이유가 없기 때문이다. MOBILE의 로컬 보안 저장소 삭제는 계속 클라이언트 책임이다.

**Refresh 회전 결과의 불확실성.** 기존 엄격한 회전과 재사용 탐지를 그대로 유지하며 idempotency grace나 회전 결과 캐시를 추가하지 않는다. 회전 응답이 유실된 뒤 구 Refresh Token을 다시 보내면 재사용으로 판정되어 계열이 폐기되고 재로그인이 필요하다. grace를 두면 그만큼 재사용 탐지 창이 열리고, 프론트엔드가 동시 갱신을 single-flight로 합치므로 응답 유실 빈도도 낮다. Redis 명령 결과가 불확실하면 내부 상태를 추측해 `INVALID_REFRESH_TOKEN`으로 응답하지 않고 `503 AUTH_STATE_UNAVAILABLE`을 반환한다.

**Refresh family 사후 정리 실패.** 비밀번호 재설정과 탈퇴의 MariaDB 트랜잭션이 확정적으로 성공했고 `auth_version` 또는 `status`로 무효화 효력이 달성되었다면, Redis family 정리 실패를 이유로 DB를 롤백하지 않고 응답도 바꾸지 않는다. 두 API는 그대로 `204`다. 무효화 권위가 `users.auth_version`과 `users.status`이므로 남은 family의 Refresh Token은 DB 재검증에서 거부되며 보안 효과는 이미 달성되어 있다. 여기서 `503`을 반환하면 이미 성공한 작업을 실패로 보고하게 되고, 사용자는 이미 바뀐 비밀번호로 재시도하다 혼란에 빠진다. Redis family 정리 성공을 `204`의 필수 조건으로 만들지 않는다. 남은 family는 best-effort 재시도 대상이며 TTL로 최종 제거하고, 정리 실패는 내부 metric과 운영 경고로 남긴다. 오래된 family가 서명과 Redis 상태만으로 통과하는 우회는 허용하지 않는다. 이 규칙은 DB 성공이 확정된 경우에만 적용하며, DB 성공 여부가 불확실한 경우에는 적용하지 않는다.

**유출 비밀번호 조회 장애.** 가입과 비밀번호 재설정 모두 fail-closed다. 유출 값 검사를 생략하고 진행하지 않으며 `503 PASSWORD_POLICY_UNAVAILABLE`을 반환한다. 비밀번호가 실제로 정책을 위반한 `422 INVALID_PASSWORD`와 구분한다. 회원가입은 인증 상태를 바꾸기 전에 실패하므로 같은 입력으로 서비스 복구 후 다시 시도할 수 있다. 비밀번호 재설정은 이 검사가 끝나기 전에 action token을 소비하지 않으므로 토큰이 소진되지 않고 같은 입력과 같은 token으로 서비스 복구 후 다시 시도할 수 있다. 어느 경우에도 다른 비밀번호로 우회하도록 안내하지 않는다. 입력 비밀번호 원문과 전체 해시를 외부 서비스나 로그에 노출하지 않는다. 구체적인 연동 방식은 구현 이슈의 범위이며 이 절은 장애의 의미만 정한다.

**`204` 성공 조건.** 이메일 인증 완료와 비밀번호 재설정 완료는 action token을 다시 쓸 수 없고, MariaDB 목적 상태가 커밋되었으며, 각 절의 무효화 불변식을 충족하고, 결과가 불확실하지 않을 때만 `204`다. Redis Refresh family 사후 정리는 `auth_version`·`status`로 효력이 대체되는 cleanup이므로 `204`의 필수 조건에 포함하지 않는다.

**응답과 로그.** 실패 body는 최상위 `{code, message}`다. 저장소 종류·호스트·명령·SQL·스택 트레이스를 외부에 노출하지 않는다. 같은 장애 code의 `message`를 API별로 다르게 만들어 어떤 저장소가 실패했는지 추측하게 하지 않는다. 토큰 원문·`tokenHash`·비밀번호·JWT claim 전체를 로그에 남기지 않는다. 내부 관측에는 request·correlation ID, API, 실패 단계, 저장소 종류, 재시도 횟수만 기록한다.

### 3.7 메일 요청·완료 API 클라이언트 복구

이 절은 계정 메일과 관련된 다섯 API에서 클라이언트가 무엇을 확정해도 되고 무엇을 확정하면 안 되는지, 사용자에게 어떤 복구 경로를 제공할지를 정한다. 저장소 장애의 판정 규칙 자체는 3.6.2절을 따르며 여기서 다시 정의하지 않는다. 새 endpoint·오류 code·DB 컬럼·Redis key를 추가하지 않는다.

공통 원칙은 셋이다. 첫째, 성공 응답이 보장하는 범위를 넘어 화면에 적지 않는다. `201`은 계정 생성과 이메일 인증 `mail_outbox` 작업의 내구적 접수를 보장하고, `202`는 `mail_outbox` 작업의 내구적 접수만 보장한다. 어느 응답도 action token 발급·제공자 접수·편지함 도달을 보장하지 않는다. 둘째, `503 AUTH_STATE_UNAVAILABLE`은 실패 확정이 아니라 결과를 알 수 없다는 뜻일 수 있으므로 성공으로도 미접수로도 단정하지 않는다. 셋째, 계정 존재·상태·발송 적격 여부를 화면 문구나 오류 종류로 구분해 노출하지 않는다.

메일 도착을 확인하기 위한 조회 API·polling·WebSocket·push 알림을 추가하지 않는다. `mail_outbox` 상태를 외부에 노출하는 endpoint도 두지 않는다. 메일이 오지 않으면 사용자는 해당 요청 API로 새 작업을 접수해 복구한다. 요청 제한 시간과 화면 재요청 대기 시간의 구체 수치는 이 절에서 정하지 않는다.

이 절의 POST 요청은 클라이언트나 네트워크 계층이 투명하게 자동 재시도하지 않는다. 특히 `AUTH_STATE_UNAVAILABLE`은 commit 결과가 불확실할 수 있어 자동 재시도가 중복 `mail_outbox` 작업과 중복 메일을 만들 수 있다. 같은 입력·같은 email·같은 token으로 다시 보내는 것이 계약상 허용되는 경우에도 사용자가 화면에서 명시적으로 선택할 때만 실행한다. `PASSWORD_POLICY_UNAVAILABLE` 뒤의 재시도 역시 비밀번호 원문을 보관해 자동 실행하지 않고 사용자가 다시 제출한다. 상태 확인용 로그인도 자동·반복 호출하지 않는다.

| API | 응답 | 클라이언트가 확정해도 되는 것 | 확정하면 안 되는 것 | 다음 행동 |
| --- | --- | --- | --- | --- |
| `POST /api/v1/users` | `201` | 계정 생성과 이메일 인증 메일 작업의 내구적 접수 | 메일 발송·도달, action token 발급 | 이메일 인증 안내 화면으로 이동. 메일이 오지 않으면 이메일 인증 요청 API로 새 작업을 접수 |
| `POST /api/v1/users` | `409` | username 또는 email을 쓸 수 없음 | 어느 값 때문인지, 직전 요청이 그 계정을 만들었는지 | 입력을 바꿔 다시 시도. 직전 응답이 `503`이었다면 사용자가 명시적으로 같은 자격 증명으로 로그인해 현재 접근 가능한지 확인 |
| `POST /api/v1/users` | `422` | 입력 형식 또는 비밀번호 정책 위반 | 계정 존재 여부 | 해당 입력만 수정 |
| `POST /api/v1/users` | `503 PASSWORD_POLICY_UNAVAILABLE` | 계정과 메일 작업이 생성되지 않음 | — | 서비스 복구 후 사용자가 같은 입력을 다시 제출. 다른 비밀번호로 우회하도록 안내하지 않음 |
| `POST /api/v1/users` | `503 AUTH_STATE_UNAVAILABLE` | 없음 | 계정과 메일 작업이 생성되지 않았다는 것 | 사용자가 같은 입력으로 다시 시도. `409`가 나오면 사용자가 명시적으로 로그인해 현재 접근 가능한지 확인하되 직전 요청의 성공 여부로 해석하지 않음 |
| 두 요청 API | `202` | 메일 작업의 내구적 접수 | 계정 존재, 발송 적격, action token 발급, 제공자 접수, 편지함 도달 | 계정을 구분하지 않는 동일 안내. 메일이 오지 않으면 사용자가 같은 email로 다시 요청 |
| 두 요청 API | `422` | email 형식 위반 | 계정 존재 여부 | email을 고쳐 다시 요청 |
| 두 요청 API | `503 AUTH_STATE_UNAVAILABLE` | 없음 | 작업이 접수되지 않았다는 것 | 사용자가 같은 email로 다시 요청할 수 있음. 중복 작업과 중복 메일이 생길 수 있음 |
| `POST /api/v1/email-verifications` | `204` | `email_verified_at`이 값을 가진 상태로 확정 | — | 로그인 화면으로 이동 |
| `POST /api/v1/email-verifications` | `400` (일반) | 그 token을 쓸 수 없음 | 만료·사용 완료·재발급 교체 중 무엇인지 | 원인을 구분하지 않고 새 인증 메일 요청 안내 |
| `POST /api/v1/email-verifications` | `400` (직전 `503` 뒤 같은 token 재시도) | 그 token을 더 쓸 수 없음 | 인증이 실패했다는 것, 아직 미인증이라는 것 | 실패 화면으로 끝내지 않음. 사용자가 명시적으로 로그인해 상태를 확인하거나 새 인증 메일을 요청 |
| `POST /api/v1/email-verifications` | `422` | 요청 형식 위반 | — | 형식을 고쳐 다시 제출 |
| `POST /api/v1/email-verifications` | `503 AUTH_STATE_UNAVAILABLE` | 없음 | 인증 성공·실패와 token 소비 여부 | 같은 화면에서 사용자가 같은 token으로 다시 시도. `400`이 나와도 실패로 단정하지 않고 로그인 확인 또는 새 인증 메일 요청으로 이어감 |
| `POST /api/v1/password-resets` | `204` | 비밀번호 교체, `auth_version` 증가, 그 token의 소진 | — | 새 비밀번호로 직접 로그인. 자동 로그인하지 않음 |
| `POST /api/v1/password-resets` | `400` | 그 token을 쓸 수 없음 | 만료·사용 완료·재발급 교체 중 무엇인지 | 원인을 구분하지 않고 새 재설정 메일 요청 안내 |
| `POST /api/v1/password-resets` | `422 INVALID_ACCOUNT_INPUT` | 요청 형식 위반 | — | 형식을 고쳐 다시 제출 |
| `POST /api/v1/password-resets` | `422 INVALID_PASSWORD` | 새 비밀번호가 정책 위반 | — | 다른 새 비밀번호를 입력해 같은 token으로 다시 제출 |
| `POST /api/v1/password-resets` | `503 PASSWORD_POLICY_UNAVAILABLE` | action token이 소비되지 않음 | — | 서비스 복구 후 사용자가 같은 token과 같은 입력을 다시 제출 |
| `POST /api/v1/password-resets` | `503 AUTH_STATE_UNAVAILABLE` | 없음 | 비밀번호 변경, `auth_version` 증가, token 소비 여부 | 성공 화면을 표시하거나 자동 로그인하지 않음. 사용자가 명시적으로 그 비밀번호로 로그인해 다음 경로로 이동하거나 새 재설정 메일을 요청 |

**회원가입의 불확실.** `503 AUTH_STATE_UNAVAILABLE`을 받으면 사용자와 메일 작업이 생성되지 않았다고 단정하지 않는다. 사용자가 같은 입력으로 다시 가입을 시도할 수 있고 그 결과는 `201`·`409`·`503` 중 하나일 수 있다. 앞선 요청이 실제로 커밋되었다면 같은 값이 이미 쓰이고 있으므로 `409`가 된다. 이때도 username과 email 중 어느 값 때문인지 노출하지 않는다. 사용자는 명시적으로 같은 자격 증명으로 로그인해 다음 경로를 고를 수 있으나, 로그인 결과는 현재 그 자격 증명이 받아들여지는지만 알려 주고 직전 회원가입 요청이 그 계정을 만들었는지는 증명하지 않는다. 로그인이 성공하면 제출한 username과 password가 현재 받아들여지고 접근 가능한 상태이며, `403 EMAIL_VERIFICATION_REQUIRED`면 받아들여지지만 미인증이므로 이메일 인증 요청 흐름으로 이동할 수 있다. `401 INVALID_CREDENTIALS`는 계정 없음·비밀번호 오류·로그인 제한·탈퇴를 구분하지 못해 상태를 확정할 수 없고, `503 AUTH_STATE_UNAVAILABLE`이면 로그인으로도 상태를 확인하지 못한 것이므로 어느 쪽으로도 추측하지 않고 나중에 다시 시도한다. 클라이언트는 비밀번호 원문을 자동 재시도를 위해 보관하지 않으며 사용자가 다시 입력하게 한다.

**두 요청 API의 불확실.** `503 AUTH_STATE_UNAVAILABLE`을 접수 실패로 단정하지 않는다. 작업이 이미 저장되어 메일이 나중에 도착할 수 있고, 사용자가 같은 email로 다시 요청하면 작업과 메일이 중복될 수 있다. 중복 요청은 허용하며 가장 최근에 발급된 action token만 유효하다. 메일 도착 순서는 토큰 유효성의 근거가 아니므로 나중에 도착한 메일이 항상 유효하다고 안내하지 않는다. 무효한 링크를 연 사용자에게는 유효하지 않다는 안내와 함께 다시 요청할 경로를 제공한다.

**이메일 인증 완료의 불확실.** `503 AUTH_STATE_UNAVAILABLE`은 인증이 됐는지도, token이 소비됐는지도 알려 주지 않는다. 3.6.2절에 따라 같은 token을 다시 제출하면 처리가 다시 진행되거나 `400 ACTION_TOKEN_INVALID`가 되므로, 같은 화면에서 사용자가 명시적으로 다시 시도할 수 있게 한다. 클라이언트가 이 재시도를 대신 수행하지 않는다.

직전에 `503`을 받고 같은 token을 다시 제출해 받은 `400`은 일반적인 `400`과 문맥이 다르다. 앞선 요청에서 token이 이미 소비되었을 가능성이 있고, 그때 MariaDB 변경이 성공했는지 실패했는지는 `400`만으로 알 수 없다. 따라서 이 `400`을 인증 실패나 미인증으로 단정하지 않으며 바로 실패 화면으로 끝내지 않는다. 사용자가 명시적으로 로그인해 상태를 확인하거나 새 인증 메일을 요청할 수 있는 두 경로를 함께 제공한다. 확인 로그인이 `503`이면 상태를 확인하지 못한 것이므로 인증 여부를 추측하지 않고 새 인증 메일 요청 경로를 그대로 둔다.

**비밀번호 재설정 완료의 불확실.** `503 AUTH_STATE_UNAVAILABLE`에서는 비밀번호가 바뀌었는지, `auth_version`이 올랐는지, token이 소비됐는지를 모두 단정하지 않는다. 성공 화면을 표시하거나 자동으로 로그인하지 않는다. 성공 화면과 자동 이동은 `204`를 받았을 때만 허용한다.

사용자 복구의 목표는 이번 재설정 트랜잭션이 실행됐음을 사후에 증명하는 것이 아니라 다음에 쓸 수 있는 경로로 이동하는 것이다. 이 명세는 새 비밀번호가 기존 비밀번호와 같은지 검사하지 않으므로, 그 비밀번호로 로그인에 성공하더라도 이번 요청이 실행되었는지 `auth_version`이 증가했는지는 알 수 없다. 사용자가 명시적으로 그 비밀번호로 로그인해 접근하거나, `403`이면 이메일 인증 흐름으로 가거나, `401`이면 새 재설정 메일을 요청한다. 확인 로그인이 `503`이면 상태를 확인하지 못한 것이므로 변경 여부를 추측하지 않고 나중에 다시 시도하거나 새 재설정 메일을 요청한다.

**상태 확인용 로그인.** 위 세 흐름의 확인은 별도 조회 API를 만들지 않기 위해 기존 로그인 응답을 그대로 쓰는 것이며, 언제나 사용자의 명시적 행동으로만 수행한다. 로그인 응답의 의미는 확인하려는 대상에 따라 다르다.

| 로그인 응답 | 회원가입 불확실 확인에서 | 이메일 인증 확인에서 | 비밀번호 재설정 확인에서 |
| --- | --- | --- | --- |
| 성공 | 제출한 username과 password가 현재 받아들여지고 접근 가능한 상태임. 직전 회원가입 요청이 그 계정을 생성했다고 증명하지는 않음 | 이메일 인증이 완료된 것이 확인됨 | 현재 계정이 입력한 비밀번호를 받아들이고 접근 가능하다는 것만 확인됨 |
| `403 EMAIL_VERIFICATION_REQUIRED` | 제출한 username과 password가 현재 받아들여지지만 미인증 상태임. 직전 회원가입 commit이 실행됐다고 증명하지는 않으며 이메일 인증 요청 흐름으로 이동 가능 | 아직 미인증인 것이 확인되므로 새 인증 메일을 요청 | 입력한 비밀번호가 현재 비밀번호와 일치하지만 이메일이 미인증임. 재설정 트랜잭션 실행이나 `auth_version` 증가는 증명하지 못함 |
| `401 INVALID_CREDENTIALS` | 계정 없음·비밀번호 오류·로그인 제한·탈퇴를 구분할 수 없어 상태를 확정할 수 없음 | 같은 이유로 인증 상태를 확정할 수 없음. 인증 실패로 단정하지 않고 새 인증 메일 요청 경로를 제공 | 같은 이유로 상태를 확정할 수 없고 재설정 실패도 증명하지 못함. 새 재설정 메일 요청 경로를 제공 |
| `503 AUTH_STATE_UNAVAILABLE` | 로그인으로도 현재 상태를 확인하지 못함. 직전 회원가입 결과를 추측하지 않음 | 로그인으로도 현재 상태를 확인하지 못함. 인증 여부를 추측하지 않음 | 로그인으로도 현재 상태를 확인하지 못함. 비밀번호 변경 여부를 추측하지 않음 |

`503`을 받은 경우 자동 또는 반복 로그인을 하지 않고, 사용자가 나중에 다시 시도하거나 해당 메일 요청 흐름을 선택할 수 있게 한다. `422 INVALID_ACCOUNT_INPUT`과 `400 INVALID_CLIENT_PLATFORM`은 먼저 고쳐야 하는 요청 오류이며 계정 상태 판정의 근거로 쓰지 않는다. `401 INVALID_CREDENTIALS`는 원인을 구분하지 않아 상태를 좁히지 못하고 로그인 실패는 로그인 제한을 소모하므로, 상태 확인을 위한 로그인은 반복하지 않는다.

### 3.8 메일 outbox 운영 경고·관측 기준

이 절은 `mail_outbox` 처리의 상태를 운영자가 어떻게 관측하고 무엇을 경고로 올릴지를 정한다. 저장 모델과 worker 동작은 [ERD 3.5](./erd.md#table-mail_outbox)를, 클라이언트 복구는 3.7절을 따르며 여기서 다시 정의하지 않는다.

이 절은 계정·인증 공개 API를 추가하지 않고, `mail_outbox` 상태를 사용자나 관리자에게 노출하는 API도 두지 않으며, 외부에 공개되는 metrics endpoint를 금지한다. 내부 metrics를 어떤 방식으로 내보낼지와 그 수집 수단은 후속 구현에서 정한다. 이 문서 변경 자체로는 endpoint·DB 컬럼·Redis key·의존성·배포 설정을 추가하지 않는다. 지표의 수집 시스템·저장 위치·보존 기간·샘플링과 알림 채널·대시보드는 2.1절이 후속 운영 범위로 남긴 것을 그대로 유지한다.

**상태의 의미.** `PROVIDER_ACCEPTED`는 제공자가 요청을 접수했다는 뜻이며 사용자 편지함 도달이 아니므로 전달 성공 지표로 쓰지 않는다. `SKIPPED`는 계정 부재·비활성·이미 인증됨처럼 발송 대상이 아니어서 끝난 정상 결과다. 두 상태의 개별 사건은 초기 경고 대상이 아니며, 이후 비율·추세 경고를 도입하는 것을 금지하지는 않는다. `FAILED`는 재시도를 종료했다는 뜻이지 메일이 절대 전달되지 않았다는 증명이 아니다. `PENDING`은 최초 대기, backoff 대기, 가시성 만료 뒤 재처리 대기를 모두 포함하므로 그 자체가 이상은 아니다. 클라이언트에 반환한 `201`·`202`는 작업 접수를 뜻하므로 메일 발송 성공 지표로 계산하지 않는다.

**지표의 출처.** 아래 세 지표는 `mail_outbox`의 현재 행을 읽어 계산할 수 있다.

| 논리 지표 | 종류 | 단위 | label | 의미 |
| --- | --- | --- | --- | --- |
| `mail_outbox.pending.count` | gauge | 건 | `purpose` | purpose별 `PENDING` 작업 수 |
| `mail_outbox.pending.ready.count` | gauge | 건 | `purpose` | `next_attempt_at`이 지나 지금 처리 가능한 `PENDING` 수 |
| `mail_outbox.pending.ready.oldest_wait` | gauge | 초 | `purpose` | 처리 가능해진 뒤 가장 오래 기다린 시간 |

나머지 지표는 현재 행으로 복원할 수 없고 API와 worker가 런타임에 직접 계측해 외부로 내보내야 한다. 종결 행은 보존 기간이 지나면 삭제되고 재시도·재선점 과정에서 같은 행의 상태가 덮어써지므로, 사건 발생 횟수와 지연 시간과 장기 추세를 `mail_outbox`만으로 되살릴 수 없다.

| 논리 지표 | 종류 | 단위 | label | 의미 |
| --- | --- | --- | --- | --- |
| `mail_outbox.worker.last_success_at` | gauge | UTC Unix epoch 초 | 없음 | 그 worker 프로세스가 마지막으로 `QUERY` 단계를 정상 완료한 시각 |
| `mail_outbox.terminal.count` | counter | 누적 건 | `purpose`, `status` | purpose·종결 상태별 처리 건수 |
| `mail_outbox.attempt_count.distribution` | histogram | 시도 횟수 | `purpose` | 종결 시점의 `attempt_count` 분포 |
| `mail_outbox.retry.count` | counter | 누적 건 | `purpose` | `PENDING`으로 되돌린 재시도 건수 |
| `mail_outbox.reclaimed.count` | counter | 누적 건 | `purpose` | 가시성 만료 뒤 다시 선점된 건수 |
| `mail_outbox.terminal.max_attempts_exceeded` | counter | 누적 건 | `purpose` | 최대 시도 초과로 종결한 건수 |
| `mail_outbox.terminal.delivery_deadline_exceeded` | counter | 누적 건 | `purpose` | 발송 기한 초과로 종결한 건수 |
| `mail_provider.request` | counter | 누적 건 | `purpose`, `result` | 제공자 호출 결과별 건수 |
| `mail_provider.latency` | histogram | 초 | `purpose` | 제공자 호출 지연 시간 |
| `mail_outbox.enqueue` | counter | 누적 건 | `api`, `result` | 접수 트랜잭션 결과별 건수 |
| `mail_outbox.worker.db_operation` | counter | 누적 건 | `stage`, `result` | worker의 MariaDB 처리 단계별 결과 |

`mail_outbox.terminal.count`는 모든 종결 전이를 상태별로 세고, 최대 시도 초과와 발송 기한 초과 전용 counter는 그중 운영 경고가 필요한 `FAILED` 원인의 부분집합을 센다. 해당 종결 전이는 `mail_outbox.terminal.count`와 적용되는 전용 counter 하나에 함께 기록되므로 이들을 서로 배타적인 건수처럼 합산하지 않는다. 최대 시도와 발송 기한 조건이 동시에 성립하면 ERD 3.5의 판정 순서대로 `DELIVERY_DEADLINE_EXCEEDED`만 전용 counter에 기록한다. 제공자 인증·설정 오류는 별도 counter를 두지 않고 `mail_provider.request`의 `result=AUTH_CONFIG_ERROR`로만 기록한다.

논리 이름은 제품에 독립적이므로 구현에서 제품별 naming convention에 맞게 바꿀 수 있지만 단위와 의미는 바꾸지 않는다. 이 계약을 구현하려면 새 DB 컬럼이 아니라 API와 worker의 런타임 계측과 외부 집계가 필요하다. 현재 저장소에는 그 수단이 없으므로 도입 방식은 후속 구현에서 정한다.

**label의 닫힌 값.** 아래 값만 label로 쓴다. `result`는 지표마다 집합이 다르므로 지표 이름과 함께 구분한다. 새 값이 필요하면 이 표를 먼저 고친다.

| label | 허용 값 |
| --- | --- |
| `purpose` | `EMAIL_VERIFICATION`, `PASSWORD_RESET` |
| `status` | `PROVIDER_ACCEPTED`, `SKIPPED`, `FAILED` |
| `stage` | `QUERY`, `CLAIM`, `UPDATE` |
| `api` | `SIGN_UP`, `EMAIL_VERIFICATION_REQUEST`, `PASSWORD_RESET_REQUEST` |
| `mail_outbox.worker.db_operation`의 `result` | `SUCCESS`, `FAILURE` |
| `mail_outbox.enqueue`의 `result` | `SUCCESS`, `UNAVAILABLE` |
| `mail_provider.request`의 `result` | `ACCEPTED`, `REJECTED`, `AUTH_CONFIG_ERROR`, `RETRYABLE`, `UNCERTAIN` |

**제공자 호출 결과의 구분.** 다섯 값은 서로 배타적이며 판정 기준은 제공자의 접수 여부다. `ACCEPTED`는 제공자가 요청 접수를 명시적으로 확인한 경우다. `REJECTED`는 결정적 영구 거부이며 재시도하지 않는다. `AUTH_CONFIG_ERROR`는 인증·권한·필수 설정 오류다. `RETRYABLE`은 제공자가 접수하지 않았음이 확정된 일시적 실패이며 다시 시도할 수 있다. `UNCERTAIN`은 요청을 보낸 뒤 시간 초과나 연결 단절 등으로 제공자의 접수 여부를 확정할 수 없는 경우다. 단순한 시간 초과를 무조건 `RETRYABLE`로 분류하지 않는다. 제공자가 접수하지 않았다는 사실이 확인되지 않으면 `UNCERTAIN`이다. 각 제공자 어댑터가 기술 오류를 이 의미로 분류하며 제공자 원문 오류를 label 값으로 쓰지 않는다.

**DB 처리 결과의 구분.** `mail_outbox.worker.db_operation`의 `SUCCESS`는 MariaDB 명령 실행 결과가 확정적으로 정상 반환된 경우다. `FAILURE`는 연결·명령 실행 실패, 시간 초과, 적용 결과 불확실이다. `QUERY` 결과가 0건이어도 `SUCCESS`이며 처리 후보 없음은 실패가 아니다. `CLAIM`과 `UPDATE`가 낙관적 조건 불일치나 선점 경쟁 때문에 0행을 변경했더라도 DB 명령 자체가 정상 완료됐다면 `SUCCESS`다. 이런 0행 결과는 업무 경쟁의 정상 결과이지 저장소 장애가 아니다. `mail_outbox.worker.last_success_at`은 `QUERY`가 `SUCCESS`일 때 갱신하는 진단 지표이며 worker 비가동 경고에 사용하지 않는다. 인스턴스 식별자를 애플리케이션 지표 label에 넣지 않는다.

**접수 결과의 구분.** `mail_outbox.enqueue`의 `SUCCESS`는 해당 API에서 계약상 필요한 MariaDB commit 성공이 확정된 경우다. 회원가입은 `users`와 `mail_outbox`를 함께 커밋하는 트랜잭션의 성공이 확정됐을 때만 `SUCCESS`이고, 이메일 인증 요청과 비밀번호 재설정 요청은 `mail_outbox` 접수 commit 성공이 확정됐을 때 `SUCCESS`다. `UNAVAILABLE`은 INSERT·commit 실패 또는 commit 결과 불확실로 `AUTH_STATE_UNAVAILABLE`을 반환한 경우다. `PASSWORD_POLICY_UNAVAILABLE`처럼 outbox 접수 이전에 끝난 실패는 이 지표의 `UNAVAILABLE`에 넣지 않는다.

**금지 label.** `recipient_email`·`userId`·`username`·token·`tokenHash`·`jti`·JWT claim 전체·`mail_outbox` 행 id·요청 식별자·인스턴스 식별자·`last_error_kind`·제공자 원문 오류·예외 message는 어떤 지표의 label로도 쓰지 않는다. `last_error_kind`를 label로 쓰지 않는 이유는 [ERD 3.5](./erd.md#table-mail_outbox)가 그 값을 닫힌 열거로 정의하지 않아 cardinality를 보장할 수 없기 때문이며, 이 값은 접근이 통제된 구조화 로그 필드로만 유지한다. 제공자 원문 오류·예외 message·SQL은 지표 label로도 로그 값으로도 쓰지 않는다.

**빈 집합의 의미.** 처리 가능한 `PENDING`이 하나도 없으면 `mail_outbox.pending.ready.count`는 0이고 `mail_outbox.pending.ready.oldest_wait`는 값을 내보내지 않고 미관측으로 둔다. 후자를 0으로 채우지 않는다. 0은 방금 처리 가능해진 작업과 구분되지 않기 때문이다. 경고 평가는 값이 관측된 경우에만 수행하고 미관측을 정체로 판정하지 않는다.

**로그의 요청 식별자.** 원래 HTTP 요청의 `X-Request-Id`를 `mail_outbox`에 저장하지 않는다. worker는 각 처리 시도를 시작할 때 2.1절과 같은 방식으로 새 UUID v4 요청 식별자를 만들어 그 시도의 구조화 로그에 쓴다. 한 작업의 여러 시도를 잇는 것은 지표 label이 아니라 접근이 통제된 로그의 `mail_outbox` 작업 id다. 이를 위해 새 DB 컬럼을 추가하지 않는다. 로그에 남길 필드와 금지 항목은 [ERD 3.5](./erd.md#table-mail_outbox)를 따른다.

**경고.** 새로운 수치 상수는 추가하지 않는다. worker 정체에는 [ERD 3.5](./erd.md#table-mail_outbox)에서 작업 처리 안전값으로 확정한 가시성 만료 기본값 5분을 최초 운영 경고 임계값으로 재사용한다. 신규 수치 상수는 0개이고, 새로 적용하는 알림 임계값은 이 하나뿐이다.

| 경고 | 심각도 | 발생 조건 | 해제 조건 | 중복 억제 |
| --- | --- | --- | --- | --- |
| 제공자 인증·설정 오류 | 높음 | 한 `purpose`에서 `mail_provider.request`의 `result`가 `AUTH_CONFIG_ERROR`로 처음 기록됨 | 같은 `purpose`가 동일하게 구성된 제공자 경로에서 `result`가 `ACCEPTED`로 관측됨. 제공자 경로 복구를 뜻할 뿐 편지함 도달의 증명은 아님 | `purpose` 단위로 한 건 |
| 발송 포기 종결 | 보통 | 한 `purpose`에서 `mail_outbox.terminal.max_attempts_exceeded` 또는 `mail_outbox.terminal.delivery_deadline_exceeded`가 증가 | 자동 회복 신호 없음. 확인·종료 절차는 후속 알림 시스템에서 정함 | `purpose` 단위로 집계하며 개별 수신자 단위로 보내지 않음 |
| worker 정체 | 보통 | 값이 관측된 `mail_outbox.pending.ready.oldest_wait`가 가시성 만료를 넘김 | 관측된 최장 대기가 가시성 만료 미만으로 회복 | `purpose` 단위로 한 건 |
| 저장소 장애 | 보통 | 한 `stage`에서 `mail_outbox.worker.db_operation`의 `result`가 `FAILURE`로 기록됨 | 같은 `stage`에서 `result`가 `SUCCESS`로 관측됨 | `stage` 단위로 집계 |
| 접수 불가 | 보통 | 한 `api`에서 `mail_outbox.enqueue`의 `result`가 `UNAVAILABLE`로 기록됨 | 같은 `api`에서 `result`가 `SUCCESS`로 관측됨 | `api` 단위로 집계 |

다섯 경고는 발생·해제·중복 억제가 모두 같은 label 값을 기준으로 판정된다.

worker 정체는 개별 인스턴스가 아니라 `mail_outbox` 전체의 서비스 수준 상태를 뜻한다. 가시성 만료는 한 작업의 처리 제한이지 worker의 poll 주기가 아니므로, 처리할 작업이 없는 상태에서 프로세스나 인스턴스가 살아 있는지는 이 경고로 판정하지 않는다. 인스턴스 생존 감시는 health check와 배포·런타임 관측의 범위이며, 이를 위해 poll 주기·기동 유예 시간·인스턴스별 heartbeat 계약을 이 절에서 만들지 않는다.

집계 시간창과 알림 반복 정책은 후속 수집·알림 시스템 설정에서 정한다. 제공자 실패율, API `503` 비율, 대기 건수, 처리 지연 percentile 같은 비율·분위 기준은 실제 트래픽 기준선과 수집 인프라가 있어야 의미가 있으므로 이 절에서 수치를 정하지 않는다. 도입하려면 기준선을 먼저 측정하고 별도로 승인한다.

**관측과 복구의 경계.** 경고는 관측 결과일 뿐 이미 반환된 API 응답을 사후에 바꾸지 않는다. worker의 token 발급·메일 발송 실패가 이미 반환한 `201`·`202`를 바꾸지 않는다. 운영자는 `mail_outbox` 행을 임의로 `PENDING`으로 되돌리거나 `attempt_count`를 초기화하지 않으며 원문 action token을 복원·재사용하지 않는다. 재처리는 ERD 3.5의 원자적 선점·낙관적 잠금·최대 시도·발송 기한 계약 안에서만 일어난다. 이를 위한 수동 DB 수정 절차나 관리자 API를 새로 만들지 않는다. 종결되어 수신 주소가 지워진 작업의 복구는 사용자가 요청 API로 새 작업을 접수하는 3.7절의 경로를 따른다.

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
| 404 | USER_NOT_FOUND | 인증 주체에 해당하는 사용자 계정이 없음 |
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
| A-21 / F-23 음성 접속 | 담당 강사·해당 참여 학습자의 수업 문맥 | OpenVidu 강사 송출·학습자 수신 단방향 음성. 학습자 마이크·영상·화면 공유·녹음/저장/전사/다시보기 제외 | 제품 범위 승인. 토큰·방 규칙은 8.5절 |

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
| `POST /api/v1/lectures/{lectureId}/media-tokens` | 201 | 담당 INSTRUCTOR(OPEN) 또는 SCHEDULED/OPEN의 활성 LEARNER. OpenVidu 음성 접속 토큰 발급 (8.5절). |

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
| 503 | MEDIA_SERVER_UNAVAILABLE | OpenVidu 토큰 서명 설정 누락 또는 방 생성·삭제 호출 실패 |

오류 body는 `{"code":"...","message":"..."}`다. 수업 목록의 page는 0 이상, size는 1~100으로 검증한다. HTTP 5xx·시간 제한·재시도 수치는 기준선 측정 뒤 정하며, 오류가 발생한 신규 LIVE 풀이를 일부 저장하지 않는다.

OpenVidu 강사 단방향 음성은 제품 MVP에 포함하며 버전·토큰 endpoint·복구는 8.5절을 따른다. 강사 publish·학습자 subscribe만 허용하고 녹음·저장·전사·다시 듣기·학습자 마이크·영상·화면 공유는 추가하지 않는다. 미디어 접속 상태·토큰은 participants 행과 서비스 WebSocket 티켓이 아니며 이전 WEBRTC_SIGNAL 직접 시그널링 계약도 현행 확정 API가 아니다.

### 8.5 OpenVidu 음성 접속

OpenVidu 3.x(LiveKit 기반)를 사용한다. 음성 토큰은 participants 행·WebSocket 티켓과 별개이며 서버에 저장하지 않는다.

`POST /api/v1/lectures/{lectureId}/media-tokens` 는 body가 없고 응답은 다음과 같다.

```json
{ "serverUrl": "wss://openvidu.i-eum.site", "token": "<LiveKit JWT>", "expiresAt": "2026-09-26T10:10:00.000Z" }
```

| 요청자 | 허용 상태 | 권한 |
| --- | --- | --- |
| 담당 INSTRUCTOR | OPEN | 입장, 마이크 음성 publish, subscribe |
| 활성 참여 LEARNER (left_at NULL) | SCHEDULED, OPEN | 입장, subscribe만. publish·데이터 전송 불가 |

- 방 이름은 `lecture-{lectureId}`, 참가자 identity는 `user-{userId}`, 표시 이름은 username이다.
- 토큰 유효기간은 10분이다. 접속 중 갱신은 OpenVidu가 처리하며, 클라이언트는 재접속할 때만 새 토큰을 받는다.
- 강사가 OPEN으로 바꾸면 서버가 방을 만들고 방 metadata를 `{"status":"OPEN"}` 으로 설정한다. 학습자는 SCHEDULED에서 미리 접속해 대기할 수 있으므로 클라이언트는 접속 시 metadata와 metadata 변경 이벤트로 시작을 안다.
- 강사가 CLOSED로 바꾸면 서버가 방을 삭제한다. 클라이언트는 연결 해제 사유 `ROOM_DELETED` 를 수업 종료로, 그 밖의 해제를 음성 장애로 구분한다. 음성 장애가 곧 수업 종료는 아니며 `GET /api/v1/lectures/{lectureId}` 로 상태를 확인한다.
- 상태 저장이 끝난 뒤 방 생성·삭제가 실패하면 상태는 유지하고 `503 MEDIA_SERVER_UNAVAILABLE` 로 응답한다. 강사는 같은 상태로 다시 요청해 방 작업만 재시도한다(동일 상태 재요청은 현재 자원 — 8.2절).

## 9. 장치·실시간 전송 경계

### A-22 / F-25·F-26 촉지 출력·상태

Android 학습자 모바일과 아두이노는 BLE로 장치 검색·연결·해제, 상태, 1~6개 Cell의 0~63 점형 출력과 전체 해제를 주고받는다. 명령 식별/순번·성공/실패 ACK·중복 처리·연결/시간 초과/장치 오류·안전 해제를 계약에 포함한다. 실제 UUID·Frame·오류 code·시간/재시도 상수는 IoT 구현 계약에서 확정한다. 강사 웹 원격 제어와 서버 BLE 중계는 MVP에 없다.

장치 미연결·출력 실패는 촉지만 제한하며 모바일 입력·채점·복습을 막지 않는다. 실패를 성공으로 표시하거나 장치 ACK를 정답 관측으로 집계하지 않는다. Android TalkBack·200% 확대·색상 비의존·TTS/햅틱 검수도 MVP 인수 기준이며 최소 Android 버전과 BLE 실제 상수는 실기기 계약에 남긴다.

| 구간 | 전달 책임 | 계약 상태·적용 범위 |
| --- | --- | --- |
| HTTPS | 계정·문제·단계·학습 상태 등의 조회/처리 | 계정·개인 학습·문제·수업 HTTP는 이 문서 3~8절의 요청·응답·오류 계약 적용 |
| 서비스 WebSocket | 수업 메타데이터·상태·현재 문제 변경 알림 | 60초 일회용 ticket·eventId·3개 이벤트·HTTP Snapshot 재연결. 답안/결과/장치 중계·sequence/replay 없음 |
| OpenVidu | 강사 publish·학습자 subscribe 단방향 음성 | 8.5절 (OpenVidu 3.x, 토큰 endpoint·방 규칙 확정) |
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
