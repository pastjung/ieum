# 코드 스타일 — ai (FastAPI)

## 자동화 도구

- 포맷·린트: Ruff / 타입체크: pyright (팀 확정) / 테스트: pytest — CI 강제 도입 예정
- PEP 8 기준. Java처럼 camelCase를 쓰지 않는다.

## 네이밍

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| 변수·함수 | snake_case | `wrong_answer_ids`, `grade_answer` |
| 클래스 | PascalCase | `ReviewPlanner`, `QuizSubmitRequest` |
| 상수 | UPPER_SNAKE_CASE | `MAX_DOT_COUNT` |
| 모듈·파일 | snake_case | `review_service.py` |
| boolean | 상태가 드러나게 | `is_correct`, `has_pending_review` |
| 테스트 | `test_동작_조건_기대결과` | `test_grade_answer_when_dot_missing_marks_wrong` |

## 코드 작성

- 공개 함수는 인자·반환 타입 힌트를 필수로 작성한다 (pyright가 검사).
- 요청/응답은 Pydantic 모델로 분리: `XxxRequest` / `XxxResponse`. DB 모델을 API 응답에 그대로 노출하지 않는다.
- API JSON은 camelCase로 나간다(팀 확정) — 코드 내부는 snake_case를 유지하고 경계에서 변환한다:

```python
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

class QuizResultResponse(ApiModel):
    quiz_id: int          # JSON에서는 "quizId"
    is_correct: bool      # JSON에서는 "isCorrect"
```

## 구조와 예외 처리

- 폴더는 도메인 중심: `app/lessons/`, `app/reviews/`(router.py, service.py, models.py, schemas.py) + 공통 `app/core/`, `app/common/`.
- SQLAlchemy 모델은 PascalCase, 필드·테이블·컬럼은 snake_case.
- 라우터에서 예외 응답을 반복하지 않고 공통 exception handler를 등록한다.
- 에러 응답 포맷은 backend와 동일하게 api.md를 따른다.
