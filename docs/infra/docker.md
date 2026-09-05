# Docker 구성

## 1. 목적

이 문서는 서버 애플리케이션(web, backend, ai)의 컨테이너 구성과 Compose 사용법을 정의한다.

## 2. 파일 배치

```text
S15P21B201/
├─ docker-compose.yml            # 로컬 개발용: 소스에서 빌드해 전체 스택 기동
├─ apps/
│  ├─ backend/Dockerfile         # 각 앱의 이미지 정의는 앱 폴더에 둔다
│  ├─ web/Dockerfile
│  ├─ web/nginx.conf             # 컨테이너 내부 정적 서빙 설정
│  └─ ai/Dockerfile
├─ deploy/                       # EC2에서 사용하는 것만 둔다
│  ├─ docker-compose.prod.yml    # blue/green 서비스 정의 (이미지 참조만, 빌드 없음)
│  └─ .env.example               # 운영 환경변수 목록 (실제 값은 EC2의 deploy/.env)
└─ scripts/                      # 빌드·배포 스크립트 (2단계에서 추가)
```

Dockerfile을 `apps/` 안에 두는 이유: Jenkins의 changeset 변경 감지(`apps/backend/**`)에 Dockerfile 수정이 자연히 포함된다.

## 3. Dockerfile 전략

개발용/배포용 Dockerfile을 분리하지 않고 **단일 멀티 스테이지 Dockerfile**을 사용한다. 환경 차이는 파일 복제가 아니라 스테이지 선택(Compose의 `target`)과 환경변수로 표현한다.

- 운영: `docker build`가 기본으로 최종 스테이지까지 빌드한 이미지 사용 (dev 스테이지는 빌드되지 않음)
- 로컬: Compose가 중간 스테이지를 지정해 dev 서버를 실행 (web: vite, backend: bootRun + devtools, ai: uvicorn --reload)
- dev/prod 이미지 간 베이스·의존성 드리프트가 원천적으로 발생하지 않는다

| 앱 | dev 스테이지 (로컬 전용) | 운영 이미지 | 비고 |
| --- | --- | --- | --- |
| backend | `dev`: bootRun + devtools 자동 재시작 | temurin 25 JRE + bootJar, non-root | devtools는 developmentOnly라 jar에 미포함. 테스트는 MR 파이프라인 담당 |
| web | `build`: vite dev 서버 (핫리로드) | nginx alpine + dist | SPA 폴백, gzip, 캐시 헤더 포함 |
| ai | `dev`: uvicorn `--reload` + dev 의존성(pytest) | python 3.12-slim + uvicorn, non-root | 운영 이미지에는 운영 의존성만 설치 |

ai 의존성은 `requirements.txt`(운영)와 `requirements-dev.txt`(pytest 등)로 분리한다.

## 4. 로컬 개발

전제: Docker Desktop. DB는 backend의 `local` 프로필(H2 in-memory)을 사용하므로 필요 없다.

```bash
docker compose up --build --watch
```

`--watch`가 필수다. Windows에서는 바인드 마운트로 파일 변경 이벤트가 리눅스 컨테이너에 전달되지 않아 vite/devtools/uvicorn이 변경을 감지하지 못한다. compose watch는 변경 파일을 컨테이너 안으로 복사(sync)해 컨테이너 파일시스템에 실제 쓰기 이벤트를 발생시키므로 자동 반영이 동작한다. 의존성 파일(package.json, build.gradle, requirements)이 바뀌면 sync 대신 이미지를 재빌드한다.

| 서비스 | 주소 | 동작 |
| --- | --- | --- |
| web | http://localhost:5173 | vite dev 서버, src 변경 즉시 반영 |
| backend | http://localhost:8080/api/v1/health | bootRun + devtools, src 변경 시 자동 재시작 (H2 프로필) |
| ai | http://localhost:8000/api/v1/health | uvicorn --reload, app 변경 시 자동 재시작 |

- web의 `/api` 요청은 vite 프록시가 backend 컨테이너로 전달한다 (`VITE_PROXY_TARGET`)
- backend는 `AI_BASE_URL` 환경변수로 ai를 호출한다 (로컬: `http://ai:8000/api/v1`)
- backend의 자동 재시작 구조: `build --continuous`가 src 변경을 재컴파일하고, devtools가 바뀐 클래스를 감지해 JVM 재기동 없이 애플리케이션만 재시작한다 (수 초)
- ai의 자동 재시작 구조: `uvicorn --reload`가 WatchFiles로 `app/` 변경을 감지해 서버 프로세스를 재시작한다 (1~2초). 로그에 `WatchFiles detected changes ... Reloading...`이 찍히면 정상 동작이다
- dev 스테이지도 빌드 시점의 소스를 COPY로 포함한다 — compose watch의 sync는 변경분만 복사하므로, 이미지에 소스가 없으면 기동 자체가 실패한다. 기동 시 동기화는 `initial_sync: true`가 보장한다
- 단독 개발 시에는 네이티브 실행도 가능하다: backend는 IDE bootRun(devtools 동일 적용), ai는 `.venv` + `uvicorn --reload`, web은 `npm run dev`
- sync는 호스트 → 컨테이너 단방향 복사다. 컨테이너 안에서 파일이 바뀌어도 호스트로 역류하지 않는다
- 종료는 `docker compose down`을 사용한다. `-v`를 붙이지 않는다 — gradle 의존성 캐시 볼륨이 삭제되어 다음 기동이 느려진다

주의: 로컬 Compose는 각 서비스의 dev 스테이지까지만 빌드하므로, 운영 스테이지(web의 `npm run build`, backend의 `bootJar`, ai의 runtime)는 로컬 개발 중 한 번도 실행되지 않는다. Dockerfile이나 빌드 관련 설정을 수정했다면 `docker build apps/<서비스>`(target 없이)로 운영 경로가 깨지지 않았는지 별도로 확인한다. release 배포 파이프라인이 생기면 이 확인은 CI가 대신한다.

## 5. 운영 Compose

`deploy/docker-compose.prod.yml`은 이미지 태그만 참조하며, 값은 같은 폴더의 `.env`에서 주입된다.

```bash
# 기동은 서비스 단위로만 한다 (scripts/deploy.sh가 수행)
docker compose -f docker-compose.prod.yml up -d backend-green
```

EC2의 `.env` 작성 절차와 값의 의미는 deploy.md 6.2절을 따른다. `.env.example`은 필요한 키 목록의 원본이며 실제 값은 서버에만 존재한다 (Git 커밋 금지).

- 인자 없이 `up -d`를 실행하지 않는다 — blue/green 6개 컨테이너가 전부 뜬다
- `REGISTRY_PREFIX`는 현재 빈 값(로컬 이미지 저장소). 레지스트리 도입 시 이 값만 변경한다
- 서비스별 태그(`WEB_TAG`, `BACKEND_TAG`, `AI_TAG`)는 deploy.sh가 배포 시 갱신한다

이미지 보존 정책 (EC2 로컬 저장소가 곧 이미지 보관소이므로 반드시 지킨다):

| 대상 | 정책 |
| --- | --- |
| `v*` 이미지 | 최근 3개 릴리스 유지 — 롤백 지점. 그 이전은 삭제 가능 (필요 시 git 태그에서 재빌드) |
| `*-rc.*` 이미지 | 최근 3개만 유지, 배포 성공 시 이전 것 자동 정리 (`scripts/cleanup-images.sh`, 4단계에서 추가) |
| 태그 없는(dangling) 이미지·빌드 캐시 | `docker image prune`, `docker builder prune`으로 주기 정리 |

**금지: `docker image prune -a`** — 태그가 있어도 미사용이면 전부 삭제하므로 롤백 지점(`v*` 이미지)이 사라진다. 운영 서버에서 실행하지 않는다.

## 6. 포트 규칙

| 서비스 | blue | green | 컨테이너 내부 | 외부 노출 |
| --- | :---: | :---: | :---: | --- |
| web | 3001 | 3002 | 80 | Nginx `/` |
| backend | 8081 | 8082 | 8080 | Nginx `/api/` |
| ai | 8101 | 8102 | 8000 | 없음 (내부 전용, Nginx 127.0.0.1:9100 경유) |

모든 호스트 포트는 `127.0.0.1`에만 바인딩한다. 외부에 공개되는 포트는 호스트 Nginx의 80/443뿐이다.

## 7. 설계 근거

### 7.1 web 운영 이미지에 Nginx가 들어가는 이유

`vite build`의 산출물은 HTML/JS/CSS 정적 파일이라 스스로 실행되지 않으며, 이를 서빙할 웹 서버가 필요하다. Nginx를 선택한 근거는 다음과 같다.

- 정적 파일 서빙 성능: 이벤트 기반 구조와 `sendfile` 같은 커널 수준 최적화로, 정적 콘텐츠 서빙에서 최고 수준의 처리량을 낸다. Node 기반 서버(serve, vite preview)로 같은 파일을 서빙하는 것보다 빠르고 메모리 사용량도 훨씬 적다
- 이미지 크기: 빌드 도구(node, node_modules)를 최종 이미지에서 제외하고 nginx alpine + 산출물만 남겨 50MB 미만으로 유지된다
- 설정의 코드화: gzip, 캐시 헤더, SPA 폴백 같은 웹 서빙 설정(`apps/web/nginx.conf`)이 소스와 함께 버전 관리되고, 배포·롤백 시 코드와 항상 함께 움직인다

호스트 Nginx(리버스 프록시)와 역할이 다르다는 점에 유의한다. 호스트 Nginx는 HTTPS 종료와 서비스 라우팅을, 컨테이너 내부 Nginx는 정적 파일 서빙만 담당한다.

### 7.2 bootRun과 bootJar의 차이 (dev 스테이지 vs 운영 이미지)

| | `./gradlew bootRun` (dev 스테이지) | `bootJar` 산출물 실행 (운영 이미지) |
| --- | --- | --- |
| 실행 방식 | Gradle이 컴파일된 클래스패스로 JVM을 직접 실행 | 패키징된 실행 가능 jar를 `java -jar`로 실행 |
| 필요 환경 | JDK + Gradle + 소스 코드 | JRE만 (Gradle·소스 불필요) |
| 코드 변경 반영 | 재컴파일 시 devtools가 애플리케이션만 재시작 (수 초) | 불가 — 이미지를 다시 빌드해야 함 |
| 산출물 | 없음 (개발 세션) | 단일 jar — 어디서 실행해도 동일한, 배포·롤백 가능한 아티팩트 |
| 용도 | 개발 중 빠른 반복 | 운영 배포 |

즉 bootRun은 "소스에서 바로 실행하는 개발 모드"이고 bootJar는 "배포 가능한 단일 산출물"이다. dev 스테이지가 bootRun을 쓰는 이유는 코드 변경을 수 초 안에 반영하기 위해서이고, 운영 이미지가 bootJar를 쓰는 이유는 Gradle과 소스 없이 JRE만으로 실행되는 가볍고 재현 가능한 아티팩트가 필요하기 때문이다. devtools는 `developmentOnly` 스코프라 bootJar에 포함되지 않으므로 두 모드는 같은 build.gradle을 공유하면서도 서로를 오염시키지 않는다.

### 7.3 ai dev 스테이지가 uvicorn `--reload`를 사용하는 이유 (운영에서는 미사용)

`--reload`는 WatchFiles로 소스 변경을 감시하다 서버 프로세스를 자동 재시작하는 uvicorn의 개발 전용 기능이다. dev 스테이지에서는 web의 vite, backend의 devtools와 같은 역할 — 코드 저장 즉시 반영 — 을 담당한다.

운영 이미지에서 사용하지 않는 이유는 다음과 같다.

- 파일 감시 프로세스가 상시 동작해 불필요한 오버헤드가 생기고, uvicorn 공식 문서도 운영 사용을 권장하지 않는다
- reload 모드는 단일 프로세스로 강제되어 멀티 워커 확장이 불가능하다
- 운영 컨테이너는 불변 이미지라 실행 중 소스가 바뀔 일 자체가 없다 — 감시할 대상이 없다

같은 원리로 web의 vite dev 서버, backend의 devtools도 운영 이미지에는 포함되지 않는다. "개발 편의 기능은 dev 스테이지에만, 운영 이미지는 실행에 필요한 최소만"이 세 서비스 공통 원칙이다.

## 8. 로컬 검증 체크리스트

- [ ] `docker compose up --build --watch`로 3개 서비스가 모두 기동된다
- [ ] `localhost:8080/api/v1/health`, `localhost:8000/api/v1/health`가 200을 반환한다
- [ ] web: src 수정 시 브라우저에 즉시 반영된다 (vite 핫리로드)
- [ ] backend: java 파일 수정 시 수 초 내 자동 재시작된다 (로그에 devtools restart 확인)
- [ ] ai: app 파일 수정 시 자동 재시작된다 (로그에 uvicorn reload 확인)
- [ ] `docker build apps/web`, `docker build apps/backend`, `docker build apps/ai`가 각각 성공한다 (운영 이미지 전체 빌드)
