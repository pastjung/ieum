# 강사 웹 모노레포 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `apps/web` 을 `front/apps/web`(`@ieum/web`)으로 옮겨 pnpm 워크스페이스에 합류시키고, `front/Dockerfile` 이 웹 운영 이미지·검증을 맡게 한다. 동작 변화는 없다.

**Architecture:** 소스는 `git mv` 로 이력째 옮긴다. npm lockfile을 버리고 `front/pnpm-lock.yaml` 에 합류한다. `front/Dockerfile` 에 `web-deps`(설치) → `web-build`(빌드) → `web`(nginx 런타임) 스테이지를 추가하고, `test` 스테이지가 웹 lint·build까지 검사한다. Jenkins 배포는 이미 `docker build --target web front` 를 부르므로 Jenkinsfile은 고치지 않는다.

**Tech Stack:** pnpm 10.34.5 workspace, Vite 6, React 19, TypeScript 5.8, Docker multi-stage, nginx:stable-alpine

**Spec:** `docs/superpowers/specs/2026-09-26-live-lecture-mvp-design.md` §4

## Global Constraints

- 브랜치: `chore/web-monorepo` (dev에서 분기), MR 대상 `dev`
- 커밋: `type(scope): 한글 제목` — scope는 `web`(소스 이동), `infra`(Dockerfile·compose), `docs`
- 동작 변화 금지 — 기존 라우트 `/`, `/signup`, `/auth/verify-email`, `/auth/reset-password` 가 전과 같이 떠야 한다
- `front/apps/mobile` 파일은 건드리지 않는다
- 비밀 파일(`.env*`)은 읽거나 고치지 않는다
- 완료 기준: `docker build --target test front` 와 `docker build --target web front` 가 모두 성공

---

### Task 1: 소스를 워크스페이스로 옮기고 pnpm으로 설치

**Files:**
- Move: `apps/web/**` → `front/apps/web/**` (`git mv`)
- Delete: `front/apps/web/package-lock.json`, `front/apps/web/Dockerfile`, `front/apps/web/.dockerignore`
- Modify: `front/apps/web/package.json`
- Modify: `front/pnpm-lock.yaml` (pnpm이 갱신)
- Modify: `front/package.json`

**Interfaces:**
- Produces: 워크스페이스 패키지 `@ieum/web` — 스크립트 `dev`, `build`, `lint`, `typecheck`. 산출물 `front/apps/web/dist`. Task 2의 Dockerfile이 이 이름과 경로를 쓴다.

- [ ] **Step 1: 추적되지 않는 산출물을 지우고 이동**

`apps/web/dist`, `apps/web/node_modules`, `apps/web/tsconfig.tsbuildinfo` 는 추적 파일이 아니다(`.gitignore`). 옮기기 전에 지운다.

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
rm -rf apps/web/dist apps/web/node_modules apps/web/tsconfig.tsbuildinfo
git mv apps/web front/apps/web
git rm -q front/apps/web/package-lock.json front/apps/web/Dockerfile front/apps/web/.dockerignore
ls apps/web 2>/dev/null || echo "apps/web 없음"
```

Expected: `apps/web 없음`

- [ ] **Step 2: package.json 이름·스크립트 변경**

`front/apps/web/package.json` 의 `name` 과 `scripts` 를 다음으로 바꾼다. 의존성 목록은 그대로 둔다.

```json
  "name": "@ieum/web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "lint": "eslint .",
    "typecheck": "tsc --noEmit",
    "format": "prettier --write src"
  },
```

`typecheck` 를 추가하는 이유: `front/Dockerfile` test 스테이지가 `pnpm -r typecheck` 로 모든 패키지를 검사한다. 이름이 없으면 웹이 조용히 빠진다.

- [ ] **Step 3: 루트 스크립트에 웹 개발 명령 추가**

`front/package.json` 의 `scripts` 에 한 줄 추가한다.

```json
    "dev:mobile": "pnpm --filter @ieum/mobile start",
    "dev:web": "pnpm --filter @ieum/web dev",
```

- [ ] **Step 4: pnpm 설치로 lockfile 갱신**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201/front
corepack enable
pnpm install
git status --short pnpm-lock.yaml
```

Expected: `M pnpm-lock.yaml` (importers에 `apps/web` 가 추가됨)

- [ ] **Step 5: 로컬 검증 — 타입체크·린트·빌드**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201/front
pnpm --filter @ieum/web typecheck && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build
ls apps/web/dist/index.html
```

Expected: 세 명령 모두 exit 0, `apps/web/dist/index.html` 존재.
pnpm의 엄격한 의존성 해석으로 eslint 플러그인을 못 찾으면, 해당 패키지가 `front/apps/web/package.json` devDependencies에 직접 있는지 확인한다(현재 전부 직접 선언돼 있다). 누락이면 `pnpm --filter @ieum/web add -D <pkg>` 로 추가한다.

- [ ] **Step 6: 개발 서버 동작 확인**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201/front
pnpm dev:web
```

브라우저에서 `http://localhost:5173/` 와 `http://localhost:5173/signup` 이 이전과 같이 뜨는지 확인하고 서버를 끈다.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git add -A apps/web front/apps/web front/package.json front/pnpm-lock.yaml
git commit -m "refactor(web): 강사 웹을 front 워크스페이스로 옮긴다"
```

---

### Task 2: front/Dockerfile 이 웹 이미지와 검증을 맡는다

**Files:**
- Modify: `front/Dockerfile`
- Modify: `docker-compose.yml:12-39` (web 서비스)

**Interfaces:**
- Consumes: Task 1의 `@ieum/web` 패키지, `front/apps/web/nginx.conf`, `front/apps/web/dist`
- Produces: Docker 스테이지 `web-deps`, `web-build`, `web`. Jenkins 배포가 `--target web front` 로, 로컬 compose가 `web-deps` 로 쓴다.

- [ ] **Step 1: 헤더 주석 갱신**

`front/Dockerfile` 1~8행의 헤더 주석을 다음으로 바꾼다(웹이 없다는 설명과 "어긋난 부분" 언급을 지운다).

```dockerfile
# 프론트 모노레포(pnpm workspace) 이미지.
#
# - test     : MR 검증 파이프라인이 `docker build --target test front`로 쓴다 (Jenkinsfile.mr).
#              이 파일이 없으면 front/ 를 건드린 모든 MR이 코드 품질과 무관하게 빨간불이 된다.
# - web      : 강사 웹 운영 이미지. 배포 단계가 `docker build --target web front`로 쓴다 (Jenkinsfile).
# - web-deps : 로컬 개발 compose가 vite dev 서버를 띄우는 스테이지 (루트 docker-compose.yml).
#
# 학습자 앱(Expo)의 운영 산출물은 여기서 만들지 않는다.
```

- [ ] **Step 2: test 스테이지에 웹 lint·build 추가**

`RUN pnpm -r typecheck` 바로 다음에 추가한다. 빌드는 tsc -b와 vite 번들을 모두 검사한다(예전 `apps/web/Dockerfile` test 스테이지와 같은 범위).

```dockerfile
RUN pnpm -r typecheck

# 웹은 예전 apps/web 검증과 같은 범위(lint + tsc -b + vite build)를 유지한다.
RUN pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build
```

- [ ] **Step 3: 파일 끝에 웹 스테이지 추가**

`front/Dockerfile` 맨 끝(test 스테이지의 마지막 `RUN` 뒤)에 추가한다.

```dockerfile

# ---------- web-deps: 웹 의존성 설치 (로컬 개발 compose가 여기서 vite dev를 띄운다) ----------
FROM base AS web-deps

COPY . .

# 웹과 그 워크스페이스 의존성만 설치한다. 앱(Expo) 의존성까지 받으면 이미지가 수 배로 커진다.
RUN --mount=type=cache,target=/pnpm-store \
    pnpm config set store-dir /pnpm-store \
    && pnpm install --frozen-lockfile --filter "@ieum/web..."

# ---------- web-build ----------
FROM web-deps AS web-build

RUN pnpm --filter @ieum/web build

# ---------- web: 운영 런타임 ----------
FROM nginx:stable-alpine AS web

COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=web-build /workspace/apps/web/dist /usr/share/nginx/html

EXPOSE 80
```

- [ ] **Step 4: 로컬 compose web 서비스를 새 위치로**

`docker-compose.yml` 의 `web:` 서비스 블록(12~39행)을 다음으로 바꾼다.

```yaml
  web:
    build:
      context: ./front
      target: web-deps         # 의존성 설치 스테이지에서 멈추고 vite dev 서버 실행
    command: ["pnpm", "--filter", "@ieum/web", "dev", "--host", "0.0.0.0"]
    env_file:
      - path: .env            # 개인 변수 자동 주입 (없어도 기동 — .env.example 참고)
        required: false
    environment:
      VITE_PROXY_TARGET: http://backend:8080   # /api 요청을 backend 컨테이너로 프록시
    ports:
      - "127.0.0.1:5173:5173"
    develop:
      watch:
        - action: sync                          # 변경 파일을 컨테이너로 복사 → HMR
          path: ./front/apps/web/src
          target: /workspace/apps/web/src
          initial_sync: true
        - action: sync
          path: ./front/apps/web/index.html
          target: /workspace/apps/web/index.html
          initial_sync: true
        - action: rebuild                       # 의존성 변경은 이미지 재빌드
          path: ./front/apps/web/package.json
        - action: rebuild
          path: ./front/pnpm-lock.yaml
    depends_on:
      - backend
```

- [ ] **Step 5: 이미지 빌드 검증**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
docker build --target test front
docker build --target web -t itda-web:local-check front
docker run --rm -d -p 127.0.0.1:3999:80 --name web-check itda-web:local-check
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3999/
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3999/signup
docker rm -f web-check
```

Expected: 두 build 성공, 두 curl 모두 `200`(SPA 폴백).

- [ ] **Step 6: compose 설정 문법 확인**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
docker compose config --quiet && echo OK
```

Expected: `OK`

- [ ] **Step 7: Commit**

```bash
git add front/Dockerfile docker-compose.yml
git commit -m "chore(infra): front 이미지가 강사 웹 빌드와 검증을 맡게 한다"
```

---

### Task 3: 경로를 참조하는 문서·훅 갱신

**Files:**
- Modify: `AGENTS.md:11`, `AGENTS.md:51`
- Modify: `.claude/hooks/post-edit-lint.sh:29-30`
- Modify: `apps/README.md:134`, `apps/README.md:240`
- Modify: `docs/conventions/onboarding.md:29`
- Modify: `docs/infra/deploy.md:284`
- Modify: `docs/infra/docker.md:130`, `docs/infra/docker.md:168`
- Modify: `docs/conventions/repository-structure.md` (web 경로가 나오는 곳)

**Interfaces:**
- Consumes: Task 1·2의 새 경로와 스테이지 이름

- [ ] **Step 1: 남은 참조 목록 뽑기**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git grep -n "apps/web" -- ':!docs/superpowers' ':!Claude outputs' ':!.superpowers'
```

아래 Step 2~6의 대상이 전부 나오는지 확인한다. 목록에 더 있으면 같은 규칙으로 고친다.

- [ ] **Step 2: AGENTS.md**

11행과 51행을 바꾼다.

```markdown
- `front/apps/web` React + Vite (교육자 웹) — pnpm 워크스페이스 `@ieum/web`
```

```markdown
- web: `cd front && pnpm --filter @ieum/web build` (tsc 타입체크 포함)
```

- [ ] **Step 3: 편집 후 린트 훅**

`.claude/hooks/post-edit-lint.sh` 29~30행을 바꾼다.

```bash
  front/apps/web/*.ts|front/apps/web/*.tsx)
    out=$(cd "$ROOT/front/apps/web" && pnpm exec eslint --fix "$FILE" 2>&1) || feedback "$out"
```

- [ ] **Step 4: 실행 안내 문서**

- `apps/README.md` 134·240행의 `cd apps/web` → `cd front`, 이어지는 `npm install`/`npm run dev` 는 `pnpm install`/`pnpm dev:web` 로.
- `docs/conventions/onboarding.md` 29행 → `- [ ] web: \`cd front && pnpm install\` (앱과 같은 워크스페이스)`

- [ ] **Step 5: 인프라 문서**

- `docs/infra/deploy.md` 284행 → `docker build --target web -t itda-web:$TAG front`
- `docs/infra/docker.md` 130행의 `apps/web/nginx.conf` → `front/apps/web/nginx.conf`
- `docs/infra/docker.md` 168행 → `` `docker build --target web front`, `docker build apps/backend`, `docker build apps/ai`가 각각 성공한다 (운영 이미지 전체 빌드) ``

- [ ] **Step 6: 저장소 구조 문서**

`docs/conventions/repository-structure.md` 에서 web이 `apps/web` 으로 적힌 곳을 `front/apps/web` 으로 바꾸고, `front/` 가 앱과 웹을 함께 담는 pnpm 워크스페이스라는 한 줄을 추가한다.

- [ ] **Step 7: 참조가 남지 않았는지 확인**

```bash
git grep -n "apps/web" -- ':!docs/superpowers' ':!Claude outputs' ':!.superpowers' | grep -v "front/apps/web"
```

Expected: 출력 없음

- [ ] **Step 8: Commit**

```bash
git add AGENTS.md .claude/hooks/post-edit-lint.sh apps/README.md docs/conventions docs/infra
git commit -m "docs(web): 강사 웹의 새 위치와 검증 명령을 반영한다"
```

---

## 완료 확인 (MR 전)

- [ ] `docker build --target test front` 성공
- [ ] `docker build --target web front` 성공, 컨테이너가 `/`·`/signup` 에 200
- [ ] `git grep "apps/web"`(제외 경로 빼고)가 `front/apps/web` 외에 없음
- [ ] mr-draft 스킬로 MR 초안 작성 — 본문에 "Jenkins 배포가 이미 `--target web front` 를 쓰므로 이 MR 병합 후 웹 배포가 front 변경으로 다시 동작한다"를 적는다
