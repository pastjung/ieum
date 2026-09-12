# API 컨벤션 (backend · ai 공통)

Spring Boot와 FastAPI가 한 서비스의 API를 나눠 제공하므로, 외부에서 보이는 규칙은 완전히 동일해야 한다. 상세 스펙은 [API 명세서](../../product/api-spec.md)가 원본이며, 이 문서는 명세서가 따르는 공통 규칙을 정의한다.

## URL

- 리소스는 명사·복수형, 동작은 HTTP method로: `GET /lessons`, `POST /quizzes/{quizId}/answers`
- 하위 리소스는 계층으로: `GET /learners/{learnerId}/wrong-answers`
- `POST /createLesson` 같은 동사 URL 금지.

## JSON 필드 — camelCase (팀 확정)

- 모든 요청/응답 JSON 필드는 camelCase: `quizId`, `isCorrect`, `createdAt`
- backend(Java)는 기본값 그대로, ai(Python)는 Pydantic alias로 경계 변환 (ai.md 참조).

## 성공 응답 (공통 포맷)

**성공 공통 wrapper를 쓰지 않는다.** body가 있으면 API별 명시적 `XxxResponse` DTO를 최상위에 그대로 반환한다.

```json
{ "lessonId": "12", "title": "점자 기초", "createdAt": "2026-09-07T10:30:00Z" }
```

- `{ "success": true, "data": ... }`처럼 실제 응답을 한 겹 더 감싸지 않는다.
- Entity·DB 모델을 HTTP 응답으로 직접 반환하지 않는다. 응답 DTO로 변환해서 내보낸다.
- 요청 DTO는 `XxxRequest`, 응답 DTO는 `XxxResponse`로 구분한다.

### body가 없는 성공

- `204` — 처리가 **완료된** 빈 body. 명세가 `204`로 정한 경우에 쓴다.
- `202` — 요청을 **접수했지만 완료를 즉시 확정하지 않는** API에만 쓰며 빈 body를 반환한다. 단순히 돌려줄 값이 없다는 이유로 쓰지 않는다.
- 어느 쪽을 쓸지는 각 API 명세를 따른다.
- 빈 성공에 `{}`나 `{ "success": true }`를 보내지 않는다.

## 에러 응답 (공통 포맷)

**실패만** 다음 형식으로 통일한다. 최상위에 `code`와 `message`를 두고 다른 객체로 감싸지 않는다.

```json
{ "code": "LESSON_NOT_FOUND", "message": "수업을 찾을 수 없습니다." }
```

- `code`는 UPPER_SNAKE_CASE 문자열로 클라이언트 분기용, `message`는 사용자 표시용 한국어.
- `code`는 업무 상황을 뜻하는 이름을 쓴다: `INVALID_ACCESS_TOKEN`, `LESSON_NOT_FOUND`.
- HTTP 상태를 숫자로 되풀이하는 `AUTH_4010` 같은 이름은 쓰지 않는다. 상태 코드는 이미 응답 라인에 있다.
- `{ "success": false, "error": { ... } }`처럼 중첩하지 않는다.
- 스택 트레이스·내부 예외명을 응답에 노출하지 않는다.
- ai는 FastAPI 기본 오류 응답 `{ "detail": ... }`을 그대로 내보내지 않고, 공통 exception handler에서 `{ code, message }`로 변환한다.

### 공통 HTTP 오류 code

공통 HTTP 오류는 업무 처리 전에 요청을 해석하지 못했거나, 예상하지 못한 서버 오류가 난 경우에만 쓴다. API가 같은 상황에 명시적인 업무 code를 정의했으면 업무 code가 우선한다.

| HTTP | code | message | 상황 |
| --- | --- | --- | --- |
| 400 | `MALFORMED_REQUEST_BODY` | 요청 본문 형식을 확인해 주세요. | JSON 문법 오류, 필수 body 누락, 필드 타입 불일치로 body를 요청 DTO로 읽을 수 없음 |
| 400 | `INVALID_REQUEST_PARAMETER` | 요청 값을 확인해 주세요. | path·query·header 값의 타입 변환 실패, 필수 값 누락, 파라미터 제약 위반, 요청 파라미터 파싱 실패(개수 초과·잘못된 인코딩) |
| 404 | `ENDPOINT_NOT_FOUND` | 요청한 API를 찾을 수 없습니다. | 요청 경로에 해당하는 API가 없음 |
| 405 | `UNSUPPORTED_HTTP_METHOD` | 지원하지 않는 요청 방식입니다. | 경로는 있으나 해당 HTTP method를 지원하지 않음 |
| 415 | `UNSUPPORTED_CONTENT_TYPE` | 지원하지 않는 요청 형식입니다. | 지원하지 않는 요청 `Content-Type` |
| 422 | `INVALID_REQUEST_INPUT` | 입력값을 확인해 주세요. | body를 DTO로 읽었으나 DTO 검증에 실패했고, 해당 API가 업무 code를 정의하지 않음 |
| 500 | `INTERNAL_SERVER_ERROR` | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요. | 예상하지 못한 서버 오류 |

- body를 DTO로 읽지 못하면 400, 읽은 뒤 DTO 검증에 실패하면 422다.
- 공통 code의 `message`는 위 고정 문구다. 필드명·거부된 입력값·검증기 원문 message·내부 예외 message를 넣지 않는다.
- 요청이 컨트롤러에 도달하기 전 인증 단계에서 거부된 `401`은 [API 명세서 3.5절](../../product/api-spec.md#35-계정인증-오류)의 `UNAUTHORIZED`를 쓴다.
- `405` 응답은 `Allow` 헤더로 지원하는 method를 알린다. `415` 응답은 `Accept` 헤더로 서버가 받는 요청 `Content-Type`을 알린다. 이 `Accept` 헤더는 오류 body의 형식(`Content-Type: application/json`)과 별개다.

### 오류 상태 보존과 body 생략

- 오류 응답은 원래 HTTP 상태를 유지한다. 공통 code가 없다는 이유로 다른 상태를 500으로 바꾸지 않는다.
- 업무 code 없이 인증·필터·컨테이너 단계에서 발생한 `403`·`413`·`429`·`503` 등 공통 code가 아직 정해지지 않은 상태는 원래 상태를 유지하고 body를 비운다. 이 상태의 code는 해당 기능을 구현하는 후속 이슈에서 계약 문서에 먼저 추가한다.
- 오류가 난 요청의 `Accept`가 없거나 JSON을 허용하면(`*/*`, `application/*`, `application/json` 등) 원래 오류 상태와 `{ code, message }`를 `Content-Type: application/json`으로 보낸다.
- 오류가 난 요청의 `Accept`가 JSON을 허용하지 않으면(`text/html`만, `application/xml`만, `application/json;q=0` 등) JSON을 보낼 수 없으므로 원래 오류 상태를 유지하고 body만 생략한다. 이 경우를 `406`으로 바꾸지 않는다.
- `406`은 정상 응답의 표현을 `Accept`에 맞춰 만들 수 없을 때만 쓰며 body 없이 보낸다.
- body가 없는 응답에도 `X-Request-Id` 헤더는 포함한다.

## 페이지네이션

```text
GET /lessons?page=0&size=20&sort=createdAt,desc
```

```json
{ "items": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }
```

## 날짜·시간

- ISO 8601, 서버 기준 UTC: `2026-09-07T10:30:00Z`. 표시 시간대 변환은 클라이언트 책임.
