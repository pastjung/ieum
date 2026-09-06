# 코드 스타일 — web (React + Vite, 교육자 웹)

## 자동화 도구

- eslint + prettier (CI 강제 도입 예정), TypeScript strict — `npm run build`(tsc)가 타입 검증을 겸한다.

## 네이밍

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| 컴포넌트 (파일·함수) | PascalCase | `LessonBoard.tsx`, `QuizMonitor` |
| 훅 | use + camelCase | `useLiveSession`, `useQuizResults` |
| 변수·함수 | camelCase | `wrongAnswers`, `submitQuiz` |
| 상수 | UPPER_SNAKE_CASE | `MAX_STUDENTS` |
| 타입·인터페이스 | PascalCase | `QuizResultResponse` |
| boolean | 상태가 드러나게 | `isConnected`, `hasSubmission` |

## 코드 작성

- API 호출은 컴포넌트에 직접 쓰지 않고 api 계층(예: `src/api/`)으로 분리한다.
- API 응답 타입은 API 명세(camelCase)를 그대로 따른다 — api.md 참조.
- `any` 사용 금지. 불가피하면 이유를 주석으로 남긴다.
- 상태는 필요한 최소 범위에 둔다. 전역 상태는 팀 합의 후 도입한다.
- 교육자 웹도 접근성 기본을 지킨다: 시맨틱 태그, 폼 요소 라벨, 키보드 조작 가능.
