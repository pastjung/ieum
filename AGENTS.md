# 이음(I:EUM) — AI 에이전트 공통 규약

시각장애인 점자 학습 플랫폼. 실시간 수업(교육자 웹 ↔ 학습자 앱), 커리큘럼 자기주도 학습,
AI 오답 분석 기반 맞춤 복습, Arduino 6점 촉각 출력을 제공한다.
점자 셀은 전 구간에서 6비트 정수(U+2800~U+283F)로 표현한다. 서비스 개요: README.md

## 저장소 구조 (요약 — 상세: docs/conventions/repository-structure.md)

- `apps/backend` Spring Boot API 서버
- `apps/ai` FastAPI (오답 분석·맞춤 학습)
- `apps/web` React + Vite (교육자 웹)
- `apps/mobile` React Native (학습자 앱)
- `deploy/`, `scripts/`, `Jenkinsfile*` 인프라 — 수정 전 docs/infra/README.md 필독

## 문서 라우팅 (해당 작업 전에 그 문서를 읽는다)

- 브랜치 생성·MR: docs/conventions/git/branch-strategy.md
- 커밋 작성: docs/conventions/git/commit-convention.md
- GitLab 설정·보호 규칙: docs/conventions/git/gitlab-settings.md
- 코드 작성: 작업하는 앱의 docs/conventions/code/<앱>.md (backend·ai·web·mobile·api)
- API 추가·수정: docs/conventions/code/api.md
- 테스트 작성: docs/conventions/quality/testing.md
- 비밀값·설정을 다룰 때: docs/conventions/quality/security.md
- 코드리뷰: docs/conventions/quality/review-checklist.md
- 인프라·배포·CI/CD: docs/infra/README.md (현관), 파이프라인 상세: docs/infra/pipeline.md

## 반드시 지킬 것

- main·dev·release/* 직접 push 금지 — 모든 변경은 MR로
- 커밋은 `type(scope): 한글 제목`, 이슈 연결은 footer `Refs #번호`
- `.env` 계열, `*.pem` 등 비밀 파일은 읽지도, 수정하지도, 커밋하지도 않는다.
  환경변수 키를 추가하면 `.env.example`도 함께 갱신한다
- `git push --force`, `--no-verify` 금지
- 서버(EC2)의 설정 파일을 직접 수정하지 않는다 — 원본은 repo이며 배포 때 덮인다
- 구조 변경·새 도메인 추가처럼 계획이 필요한 작업은 구현 전에 계획을 제시하고 승인을 받는다
- 결과를 바꿀 모호함은 질문하고, 안전한 가정은 명시한 뒤 진행한다
- 요청 목적과 무관한 파일을 함께 고치지 않는다 (직교 수정 금지)
- 구현 전에 자문한다: 시니어가 보기에 불필요하게 복잡한 구현은 아닌가
- 작업의 완료 기준을 검증 가능한 상태로 정의한다 — "X를 했다"가 아니라 "Y가 참임을 확인했다"로 보고한다

## 축적된 해결책 활용

- 비자명한 문제(환경, 빌드, 도구)에 막히면 먼저 docs/solutions/ 를 검색한다 — 이미 푼 문제일 수 있다.
- 비자명한 문제를 해결했으면 docs/solutions/ 에 기록을 제안한다 (형식은 그 폴더의 README).

## 검증 명령 (apps/ 코드를 수정했으면 커밋 전에 실행)

- backend: `cd apps/backend && ./gradlew test`
- ai: `cd apps/ai && pytest`
- web: `cd apps/web && npm run build` (tsc 타입체크 포함)
- mobile: `cd apps/mobile && npm test`
- CI와 동일한 통합 검증: `docker build --target test apps/<앱>` — MR verify 파이프라인이 이것을 실행한다
- 문서·설정만 변경한 경우 검증 명령 대신 diff 확인으로 대체한다
- 검증이 실패하면 통과할 때까지 수정한다. 실패 상태로 완료를 선언하지 않는다
