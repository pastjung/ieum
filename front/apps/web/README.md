# web — React (Vite + TypeScript)

강사용 웹.

## 실행

```bash
cd front && pnpm install
pnpm dev:web                          # http://localhost:5173  (/api → localhost:8080 프록시)
pnpm --filter @ieum/web build         # 타입체크 + 프로덕션 빌드
```

전체 스택 통합 실행(컨테이너): 루트에서 `docker compose up --build --watch` — [docs/infra/docker.md](../../docs/infra/docker.md)

## DDD 지향 구조

```
src/
├── app/           # 앱 셸 — 라우터, 전역 프로바이더
├── pages/         # 라우트 단위 화면 (조립만 담당)
├── domains/       # 도메인별 모듈 — 새 도메인은 user/ 구조 복제
│   └── user/
│       ├── model/ # 타입·도메인 로직
│       └── api/   # 해당 도메인의 API 호출
└── shared/        # 공용 인프라 (api 클라이언트, 유틸, UI 킷)
```

- 의존 방향: `pages → domains → shared`. domains끼리 직접 import하지 않는다.
- 서버 상태 라이브러리(TanStack Query)와 UI 킷은 팀 결정 후 `shared/`에 추가.
