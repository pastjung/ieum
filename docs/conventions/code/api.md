# API 컨벤션 (backend · ai 공통)

Spring Boot와 FastAPI가 한 서비스의 API를 나눠 제공하므로, 외부에서 보이는 규칙은 완전히 동일해야 한다. 상세 스펙은 노션 API 명세서가 원본이며, 이 문서는 명세서가 따르는 공통 규칙을 정의한다.

## URL

- 리소스는 명사·복수형, 동작은 HTTP method로: `GET /lessons`, `POST /quizzes/{quizId}/answers`
- 하위 리소스는 계층으로: `GET /learners/{learnerId}/wrong-answers`
- `POST /createLesson` 같은 동사 URL 금지.

## JSON 필드 — camelCase (팀 확정)

- 모든 요청/응답 JSON 필드는 camelCase: `quizId`, `isCorrect`, `createdAt`
- backend(Java)는 기본값 그대로, ai(Python)는 Pydantic alias로 경계 변환 (ai.md 참조).

## 에러 응답 (공통 포맷)

```json
{ "code": "LESSON_NOT_FOUND", "message": "수업을 찾을 수 없습니다." }
```

- `code`는 UPPER_SNAKE_CASE 문자열로 클라이언트 분기용, `message`는 사용자 표시용 한국어.
- 스택 트레이스·내부 예외명을 응답에 노출하지 않는다.

## 페이지네이션

```text
GET /lessons?page=0&size=20&sort=createdAt,desc
```

```json
{ "items": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }
```

## 날짜·시간

- ISO 8601, 서버 기준 UTC: `2026-09-07T10:30:00Z`. 표시 시간대 변환은 클라이언트 책임.
