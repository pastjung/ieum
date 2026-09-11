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

## 페이지네이션

```text
GET /lessons?page=0&size=20&sort=createdAt,desc
```

```json
{ "items": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }
```

## 날짜·시간

- ISO 8601, 서버 기준 UTC: `2026-09-07T10:30:00Z`. 표시 시간대 변환은 클라이언트 책임.
