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

## 앱 다운로드 페이지 (`/app`)

- 로그인 없이 여는 학습자 앱 설치 페이지. `/app/latest.json`(같은 출처)을 읽어 버전·배포일을 보여 주고, 없으면 "준비 중이에요".
- 운영에서 `/app/latest.json`·`/app/ieum.apk`는 호스트 nginx가 `/home/ubuntu/app/downloads/`에서 먼저 제공한다(`scripts/release-app.sh`가 올린다). web 이미지에는 들어 있지 않다.
- 개발 서버는 두 경로를 `https://i-eum.site`로 프록시한다. 로컬 파일로 보려면:

```bash
mkdir -p /tmp/rel/app && echo '{"version":"1.0.0","versionCode":1,"releasedAt":"2026-09-27T12:00:00Z","notes":"테스트"}' > /tmp/rel/app/latest.json
(cd /tmp/rel && python -m http.server 8765)          # 다른 터미널
VITE_APP_RELEASE_TARGET=http://localhost:8765 pnpm dev:web
```

- QR(`public/app-qr.svg`)은 커밋된 정적 파일이다. 주소가 바뀌면 `node apps/web/scripts/generate-app-qr.mjs`(front에서)로 다시 만든다 — 워크스페이스의 `toqr`을 쓴다.
