# 운영 배포 재구축 — 인프라(compose·nginx·Jenkins·앱 릴리스 스크립트·문서) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 지금 코드를 운영에 올리면 backend가 뜨지 않고(`env` 5개뿐·Redis 없음) `i-eum.site` 서비스 블록이 사라지는(저장소 nginx가 옛 도메인) 문제를 저장소 쪽에서 고친다. 앱(APK) 배포 경로(`/app/`)·릴리스 스크립트·사람이 할 일 문서까지 한 브랜치에 담는다.

**Architecture:** 기존 Jenkins + blue-green 흐름은 그대로 둔다. compose는 backend에 `env_file: .env.config`를 주고 blue-green 대상이 아닌 `redis`(컨테이너 `itda-redis`)를 더한다. 호스트 nginx는 사이트 파일 두 개(`itda.conf` = i-eum.site·옛 도메인 넘김·AI 내부 리스너, `openvidu.conf` = openvidu.i-eum.site)를 저장소가 원본으로 갖고, Jenkins Sync가 셋(`connection-upgrade.conf` 포함)을 백업 → 복사 → `nginx -t` → 실패 시 셋 다 복원한다. Sync와 Deploy 사이에 `Redis` 단계를 새로 두어 `up -d redis`와 PONG을 보장한다. APK는 사람이 PC Git Bash에서 `scripts/release-app.sh`로 빌드해 GitLab 일반 패키지 + Release와 서버 `/home/ubuntu/app/downloads/`(임시 이름으로 올린 뒤 `mv`) 두 곳에 올린다.

**Tech Stack:** Docker Compose v2(프로젝트 `itda`), `redis:8.2.9-alpine`, 호스트 Nginx(Ubuntu 24.04, Let's Encrypt/certbot), Jenkins 선언형 파이프라인(Groovy), Bash(서버: Ubuntu bash / PC: Git for Windows bash), GitLab REST API v4(`lab.ssafy.com`), Node.js(JSON 생성), Expo prebuild + Gradle `assembleRelease`

**Spec:** `docs/superpowers/specs/2026-09-27-deploy-rebuild-design.md` §2, §3, §4, §5.4, §7, §8, §9 (커밋 `dea509a9`) / 교차 계약 `C:/Users/SSAFY/Desktop/S15P21B201/.superpowers/sdd/deploy-contract.md` (api·front 계획과 공유 — 경로·파일 이름·JSON 모양·키 이름을 바꾸지 않는다)

## Global Constraints

- 브랜치 `chore/deploy-rebuild-infra`(`chore/deploy-rebuild`에서 분기), 워크트리 `C:/Users/SSAFY/Desktop/wt-deploy-infra`. 완료 후 `chore/deploy-rebuild`에 **로컬 병합**(계약 문서) — dev MR은 세 계획이 모두 끝난 뒤 사람이 한 번 올린다. push·MR 생성은 사람이 한다
- 이 계획이 고치는 파일: `deploy/docker-compose.prod.yml`, `deploy/nginx/itda.conf`, `deploy/nginx/openvidu.conf`(신규), `Jenkinsfile`, `scripts/release-app.sh`(신규), `docs/infra/{README,architecture,deploy,docker,jenkins,openvidu,pipeline}.md`. 그 밖의 파일은 고치지 않는다(직교 수정 금지). `apps/backend`·`front/`는 다른 계획 몫이다
- **`.env` 계열·`*.pem`은 읽지도, 수정하지도, 커밋하지도 않는다.** `deploy/.env.config.example`도 에이전트가 고치지 않는다 — Task 5가 `docs/infra/deploy.md` 6.2.1절에 붙여 넣을 키 목록을 적고 사람이 반영한다. 검증용 가짜 설정 파일·가짜 인증서는 **저장소 밖 임시 디렉터리**(`mktemp -d`)에만 만들고 끝나면 지운다
- **서버(EC2)는 건드리지 않는다.** ssh·scp를 실행하지 않는다. `scripts/release-app.sh`는 네트워크 단계 전에 멈추는 입력 검사만 돌려 본다(Task 4). 서버 명령은 문서에 "사람이 실행"으로만 적는다
- 검증은 로컬만: `docker compose ... config`(가짜 env), `docker run --rm nginx:stable-alpine nginx -t` + 가짜 인증서로 띄운 컨테이너에 curl, shellcheck(docker `koalaman/shellcheck:stable`), `bash -n`, Groovy 파싱(docker `groovy:4.0-jdk17`)
- Git Bash에서 docker·openssl에 컨테이너 경로(`/etc/...`, `/w`)나 `-subj "/CN=..."`를 넘길 때는 명령 앞에 `MSYS_NO_PATHCONV=1`을 붙이고, 호스트 경로는 `cygpath -m`(예: `C:/Users/...`)으로 바꿔 넘긴다. 네이티브 Windows 프로그램(python·node·curl)에 파일을 줄 때는 경로 인자 대신 `<` 리다이렉트를 쓴다
- 도메인·경로 값(글자 그대로): 서비스 `i-eum.site`·`www.i-eum.site`(인증서 `/etc/letsencrypt/live/i-eum.site/`), 옛 도메인 `j15b201.p.ssafy.io`(인증서 `/etc/letsencrypt/live/j15b201.p.ssafy.io/`) → `301 https://i-eum.site$request_uri`, OpenVidu `openvidu.i-eum.site` → `127.0.0.1:7880`, 앱 파일 `/home/ubuntu/app/downloads/` = `https://i-eum.site/app/{ieum.apk,latest.json,ieum-<version>.apk}`
- compose 값(글자 그대로): 서비스 `redis`, `container_name: itda-redis`, `image: redis:8.2.9-alpine`, 볼륨 `itda-redis-data:/data`(볼륨 이름도 `itda-redis-data`), `--appendonly yes`, 포트 바인딩 없음, `restart: unless-stopped`. backend blue/green은 `env_file: .env.config` + 기존 `extra_hosts` 유지, `depends_on` 없음
- nginx `/api/` location에 `client_max_body_size 11m;`
- 커밋: `type(infra): 한글 제목`. 이슈 번호가 주어지지 않았으므로 `Refs #번호`는 생략(받으면 제목 아래 빈 줄 뒤 `Refs #번호`). 마지막 줄은 정확히 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `git push --force`·`--no-verify` 금지
- 문서·설정만 바뀌므로 앱 검증 명령(gradle·pnpm) 대신 Task별 로컬 검증 + `git diff` 확인으로 완료를 판단한다. 검증이 실패하면 고칠 때까지 커밋하지 않는다

## 다른 계획과 맞물리는 곳 (이 계획이 구현하지 않음 — 실행 전 확인)

| 필요 | 담당 | 이유 |
| --- | --- | --- |
| `application.yml` prod 프로필에 `spring.data.redis.host: ${REDIS_HOST}`·`port: ${REDIS_PORT:6379}` | api 계획 | 지금 prod 블록에는 Redis 설정이 없어(“운영 Redis 연결은 배포 작업에서 prod 프로필에 별도로 추가한다” 주석) `REDIS_HOST=redis`를 넣어도 backend는 `localhost:6379`로 붙으려다 기동에 실패한다. **api 계획에 없으면 반드시 추가를 요청한다** |
| `CARTESIA_API_KEY` 선택 설정(비어도 기동) | api 계획 | `.env.config`에 빈 값으로 두어도 backend가 떠야 한다 |
| `app.json`의 `expo.android.versionCode`, `plugins/withReleaseSigning.js` | front 계획 | `release-app.sh`가 versionCode를 읽고 서명된 release APK를 기대한다 |
| 웹 `/app` 다운로드 페이지(SPA 라우트) | front 계획 | 호스트 nginx는 `/app` 자체를 web으로 넘긴다 |

## File Structure

| 파일 | 책임 |
| --- | --- |
| `deploy/docker-compose.prod.yml` (수정) | backend `env_file`, `redis` 서비스·볼륨 |
| `deploy/nginx/itda.conf` (수정) | i-eum.site(+www) 80→443, 443 라우팅(`/app/` 3개 location 추가, `/api/` 11m), `j15b201.p.ssafy.io` 80·443 → 301, AI 내부 리스너 9100 |
| `deploy/nginx/openvidu.conf` (신규) | 서버 `sites-available/openvidu.conf` 내용 그대로 |
| `Jenkinsfile` (수정) | Sync: 사이트 파일 2개 + conf.d 1개 백업·복사·검증·복원, `downloads/` 생성 / 새 `Redis` 단계 |
| `scripts/release-app.sh` (신규) | PC에서 APK 릴리스(확인 → 테스트 → 빌드 → 해시 → latest.json → GitLab → 서버 교체) |
| `docs/infra/deploy.md` (수정) | 도메인, 파일 배치, 운영 설정 키 목록(6.2.1), 외부 DB 스키마 확인(6.3), 앱 배포(9), 사람이 할 일(10) |
| `docs/infra/{README,architecture,docker,jenkins,openvidu,pipeline}.md` (수정) | 도메인·구성 그림·Sync/Redis 단계·OpenVidu nginx 저장소 관리·Meet 끄기 |

---

### Task 0: 워크트리 준비 (커밋 없음)

**Files:** 없음

**Interfaces:**
- Produces: 워크트리 `C:/Users/SSAFY/Desktop/wt-deploy-infra` (브랜치 `chore/deploy-rebuild-infra`). 이후 모든 경로는 이 워크트리 기준이다. 이 계획 문서와 spec은 메인 폴더(`C:/Users/SSAFY/Desktop/S15P21B201`)에서 읽는다

- [ ] **Step 1: 워크트리 만들기**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git worktree list | grep -q 'wt-deploy-infra' \
  || git worktree add -b chore/deploy-rebuild-infra C:/Users/SSAFY/Desktop/wt-deploy-infra chore/deploy-rebuild
cd C:/Users/SSAFY/Desktop/wt-deploy-infra && git status --short --branch
```

Expected: `## chore/deploy-rebuild-infra`, 변경 없음. `deploy/nginx/itda.conf`의 `server_name`이 `j15b201.p.ssafy.io`인 것을 확인한다(`grep -n server_name deploy/nginx/itda.conf`).

- [ ] **Step 2: 도구 확인**

```bash
docker version --format '{{.Server.Version}}' && docker compose version && python --version && node --version && openssl version
```

Expected: 다섯 줄 모두 버전이 나온다. 하나라도 없으면 멈추고 사람에게 알린다.

---

### Task 1: 운영 compose — backend `env_file`과 `redis`

**Files:**
- Modify: `deploy/docker-compose.prod.yml` (전체 교체)

**Interfaces:**
- Consumes: 없음
- Produces: 서비스 이름 `redis`, 컨테이너 `itda-redis`, 볼륨 `itda-redis-data` — Task 3(Jenkins `Redis` 단계의 `up -d redis`·`docker exec itda-redis`)와 Task 5·6 문서가 이 이름을 쓴다. backend 컨테이너는 `.env.config`의 모든 키를 환경변수로 받는다(키 목록은 Task 5의 6.2.1절)

- [ ] **Step 1: 검증 준비 — 가짜 설정으로 compose를 해석해 검사하는 명령을 만든다**

저장소 밖 임시 디렉터리에 compose 사본과 가짜 설정을 둔다(`env_file` 상대 경로는 compose 파일 옆을 본다). 값은 전부 가짜다.

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
T="$(mktemp -d)"; echo "$T"
cat > "$T/.env.config" <<'EOF'
REGISTRY_PREFIX=
SPRING_PROFILES_ACTIVE=prod
AI_BASE_URL=http://host.docker.internal:9100/api/v1
DB_URL=jdbc:mariadb://db.example:3306/itda?connectionTimeZone=UTC
DB_USERNAME=dummy
DB_PASSWORD=dummy
REDIS_HOST=redis
REDIS_PORT=6379
AUTH_REDIS_KEY_PREFIX=prod
AUTH_JWT_ISSUER=itda-backend-prod
AUTH_ACCESS_ACTIVE_KID=access-dummy
AUTH_ACCESS_ACTIVE_KEY=dummy
AUTH_ACCESS_RETIRED_KEYS=
AUTH_REFRESH_ACTIVE_KID=refresh-dummy
AUTH_REFRESH_ACTIVE_KEY=dummy
AUTH_REFRESH_RETIRED_KEYS=
AUTH_ACTION_ACTIVE_KID=action-dummy
AUTH_ACTION_ACTIVE_KEY=dummy
AUTH_ACTION_RETIRED_KEYS=
MAIL_DISPATCH_ENABLED=false
MAIL_SMTP_HOST=smtp.example
MAIL_SMTP_PORT=587
MAIL_SMTP_USERNAME=
MAIL_SMTP_PASSWORD=
MAIL_SMTP_SECURITY=STARTTLS
MAIL_FROM_ADDRESS=noreply@example.com
MAIL_FROM_NAME=dummy
MAIL_REPLY_TO=
MAIL_LINK_EMAIL_VERIFICATION_URL=https://i-eum.site/auth/verify-email
MAIL_LINK_PASSWORD_RESET_URL=https://i-eum.site/auth/reset-password
LIVEKIT_URL=https://openvidu.i-eum.site
LIVEKIT_API_KEY=dummy
LIVEKIT_API_SECRET=dummy
CARTESIA_API_KEY=
EOF
printf 'WEB_TAG=t\nBACKEND_TAG=t\nAI_TAG=t\n' > "$T/.env.tags"
cat > "$T/check.py" <<'EOF'
import json, sys
c = json.load(sys.stdin)
s = c["services"]
r = s["redis"]
assert r["image"] == "redis:8.2.9-alpine", r["image"]
assert r["container_name"] == "itda-redis", r.get("container_name")
assert r["restart"] == "unless-stopped", r.get("restart")
assert r["command"] == ["redis-server", "--appendonly", "yes"], r.get("command")
assert "ports" not in r, r.get("ports")
assert "env_file" not in r and "environment" not in r, "redis에 설정을 주지 않는다"
assert any(v.get("source") == "itda-redis-data" and v.get("target") == "/data" for v in r["volumes"]), r["volumes"]
assert c["volumes"]["itda-redis-data"]["name"] == "itda-redis-data", c["volumes"]
for name, port in (("backend-blue", "8081"), ("backend-green", "8082")):
    b = s[name]
    env = b["environment"]
    for k in ("SPRING_PROFILES_ACTIVE", "DB_URL", "REDIS_HOST", "AUTH_JWT_ISSUER", "AUTH_REDIS_KEY_PREFIX", "LIVEKIT_URL", "MAIL_LINK_EMAIL_VERIFICATION_URL", "CARTESIA_API_KEY", "AI_BASE_URL"):
        assert k in env, (name, k)
    assert env["REDIS_HOST"] == "redis", env["REDIS_HOST"]
    assert b["extra_hosts"], (name, "extra_hosts")
    assert "depends_on" not in b, (name, "depends_on")
    p = b["ports"][0]
    assert p["host_ip"] == "127.0.0.1" and str(p["published"]) == port, p
for name in ("ai-blue", "ai-green", "web-blue", "web-green"):
    assert "DB_URL" not in s[name].get("environment", {}), name
assert s["ai-blue"]["environment"]["ITDA_DEBUG"] == "false"
print("COMPOSE OK")
EOF
echo "T=$T"
```

이후 Step에서 `T` 값을 그대로 다시 쓴다(셸이 바뀌면 위 출력의 경로로 `T=...`를 다시 지정).

- [ ] **Step 2: 현재 파일로 검사해 실패를 확인한다**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
cp deploy/docker-compose.prod.yml "$T/"
docker compose -f "$T/docker-compose.prod.yml" --env-file "$T/.env.config" --env-file "$T/.env.tags" config --format json > "$T/out.json" \
  && python "$(cygpath -m "$T/check.py")" < "$T/out.json"
```

Expected: FAIL — `KeyError: 'redis'` (redis 서비스 없음).

- [ ] **Step 3: `deploy/docker-compose.prod.yml`을 아래 내용으로 교체한다**

```yaml
# ==========================================================================
# 운영 배포용 Compose — 빌드하지 않고 이미지 태그만 참조한다.
# 기동은 scripts/deploy.sh가 서비스 단위로 수행한다.
#   예) docker compose -f docker-compose.prod.yml up -d backend-green
#
# 주의: 인자 없이 `up -d`를 실행하지 않는다 (blue/green 6개가 전부 뜬다).
# 포트 규칙 (전부 127.0.0.1 바인딩, 외부 트래픽은 호스트 Nginx가 받음):
#   web     blue 3001 / green 3002
#   backend blue 8081 / green 8082
#   ai      blue 8101 / green 8102  (외부 미노출 — Backend가 호스트 Nginx 내부
#                                    리스너 127.0.0.1:9100을 거쳐 활성 색으로 호출)
#   redis   바인딩 없음 (compose 기본 네트워크 안에서 backend만 redis:6379로 접근)
#
# 설정: backend는 같은 폴더의 .env.config(원본: Jenkins Secret file itda-env-config)를
#   env_file로 통째로 받는다 — 키 목록은 docs/infra/deploy.md 6.2.1절.
#   이미지 태그(WEB/BACKEND/AI_TAG)는 deploy.sh가 갱신하는 .env.tags에서 치환된다.
# ==========================================================================
name: itda

services:
  web-blue:
    image: ${REGISTRY_PREFIX:-}itda-web:${WEB_TAG:?WEB_TAG is required}
    restart: unless-stopped
    ports:
      - "127.0.0.1:3001:80"

  web-green:
    image: ${REGISTRY_PREFIX:-}itda-web:${WEB_TAG:?WEB_TAG is required}
    restart: unless-stopped
    ports:
      - "127.0.0.1:3002:80"

  # depends_on을 두지 않는다 — blue-green은 서비스 단위(--no-deps)로 기동하고,
  # redis는 Jenkins의 Redis 단계가 배포 전에 따로 보장한다.
  backend-blue:
    image: ${REGISTRY_PREFIX:-}itda-backend:${BACKEND_TAG:?BACKEND_TAG is required}
    restart: unless-stopped
    env_file: .env.config
    extra_hosts:
      - "host.docker.internal:host-gateway"
    ports:
      - "127.0.0.1:8081:8080"

  backend-green:
    image: ${REGISTRY_PREFIX:-}itda-backend:${BACKEND_TAG:?BACKEND_TAG is required}
    restart: unless-stopped
    env_file: .env.config
    extra_hosts:
      - "host.docker.internal:host-gateway"
    ports:
      - "127.0.0.1:8082:8080"

  ai-blue:
    image: ${REGISTRY_PREFIX:-}itda-ai:${AI_TAG:?AI_TAG is required}
    restart: unless-stopped
    environment:
      ITDA_DEBUG: "false"
    ports:
      - "127.0.0.1:8101:8000"

  ai-green:
    image: ${REGISTRY_PREFIX:-}itda-ai:${AI_TAG:?AI_TAG is required}
    restart: unless-stopped
    environment:
      ITDA_DEBUG: "false"
    ports:
      - "127.0.0.1:8102:8000"

  # 인증 토큰 상태·음성 합성 사용량 저장소. blue-green 대상이 아니다.
  # 컨테이너 이름을 고정해 OpenVidu의 `redis` 컨테이너와 겹치지 않게 한다.
  # 버전은 로컬 compose와 같다 (digest: sha256:30abb90e62f14b737010746def3ba99cc79fe19dcdb3d37b41f21fc62e7da19d)
  redis:
    image: redis:8.2.9-alpine
    container_name: itda-redis
    restart: unless-stopped
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - itda-redis-data:/data

volumes:
  itda-redis-data:
    name: itda-redis-data
```

- [ ] **Step 4: 검사가 통과하는지 확인한다**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
cp deploy/docker-compose.prod.yml "$T/"
docker compose -f "$T/docker-compose.prod.yml" --env-file "$T/.env.config" --env-file "$T/.env.tags" config --format json > "$T/out.json" \
  && python "$(cygpath -m "$T/check.py")" < "$T/out.json"
docker compose -f "$T/docker-compose.prod.yml" --env-file "$T/.env.config" --env-file "$T/.env.tags" config --services | sort
```

Expected: `COMPOSE OK`, 그리고 서비스 7개 `ai-blue ai-green backend-blue backend-green redis web-blue web-green`.

- [ ] **Step 5: `.env.config`가 없으면 파싱 단계에서 멈추는지 확인한다**

```bash
mv "$T/.env.config" "$T/.env.config.off"
docker compose -f "$T/docker-compose.prod.yml" --env-file "$T/.env.tags" config >/dev/null; echo "exit=$?"
mv "$T/.env.config.off" "$T/.env.config"
```

Expected: `exit=1`(0이 아님), 메시지에 `.env.config`와 `not found`가 들어 있다.

- [ ] **Step 6: 임시 디렉터리 정리 후 커밋**

```bash
rm -rf "$T"
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
git diff --stat
git add deploy/docker-compose.prod.yml
git commit -m "$(cat <<'EOF'
chore(infra): 운영 compose에 Redis를 더하고 backend 설정을 env_file로 받는다

backend가 application.yml이 읽는 AUTH·MAIL·LIVEKIT·REDIS 키를 받지 못해
기동하지 못하던 문제를 .env.config 전체 주입으로 고친다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 호스트 nginx — `itda.conf`를 i-eum.site로, 옛 도메인 넘김, `/app/`, `openvidu.conf`

**Files:**
- Create: `deploy/nginx/openvidu.conf`
- Modify: `deploy/nginx/itda.conf` (전체 교체)
- Test: 임시 디렉터리의 `check-nginx.sh` (저장소에 넣지 않음)

**Interfaces:**
- Consumes: `deploy/nginx/connection-upgrade.conf`(`$connection_upgrade` map), `deploy/nginx/inc/*.inc`(`$web_port`·`$backend_port`·`$ai_port`)
- Produces: 서버 배치 이름 `itda.conf` → `/etc/nginx/sites-available/itda`, `openvidu.conf` → `/etc/nginx/sites-available/openvidu.conf` (Task 3 Sync가 이 경로로 복사). 공개 URL `https://i-eum.site/app/latest.json`(`application/json; charset=utf-8`, `Cache-Control: no-cache`), `https://i-eum.site/app/ieum.apk`(`application/vnd.android.package-archive`, `no-cache`), `https://i-eum.site/app/ieum-<X.Y.Z>.apk` — Task 4 스크립트와 front 계획이 쓴다

**결정:** 계약의 `location ^~ /app/ieum.apk`는 접두어 일치라 업로드 중인 `/app/ieum.apk.tmp`까지 파일로 내준다. 공개 URL은 계약과 같게 두고 location만 `= /app/ieum.apk`(정확 일치) + 정규식 `^/app/(ieum-[0-9]+\.[0-9]+\.[0-9]+\.apk)$`로 좁힌다. 그 밖의 `/app/*`와 `/app` 자체는 web(SPA)으로 간다.

- [ ] **Step 1: `deploy/nginx/openvidu.conf`를 만든다 (서버 파일 내용 그대로 + 머리 주석)**

```nginx
# =========================================================================
# 호스트 Nginx — openvidu.i-eum.site (OpenVidu 3.8 / LiveKit 신호)
# /etc/nginx/sites-available/openvidu.conf 로 배치 (Jenkins Sync가 매 배포마다 반영)
# TLS는 여기서 끝내고 OpenVidu의 caddy(127.0.0.1:7880)로 넘긴다 (docs/infra/openvidu.md)
# =========================================================================

server {
    listen 80;
    server_name openvidu.i-eum.site;

    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name openvidu.i-eum.site;

    ssl_certificate /etc/letsencrypt/live/openvidu.i-eum.site/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/openvidu.i-eum.site/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    location / {
        proxy_pass http://127.0.0.1:7880;
        proxy_http_version 1.1;

        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_connect_timeout 10s;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
        proxy_buffering off;
    }
}
```

- [ ] **Step 2: 검증 스크립트를 임시 디렉터리에 만든다**

가짜 인증서(자체 서명)·가짜 `options-ssl-nginx.conf`·`ssl-dhparams.pem`·가짜 앱 파일을 만들고, `nginx -t` 후 컨테이너를 띄워 요청을 보낸다. 인자 1개 = 워크트리 경로.

```bash
N="$(mktemp -d)"; echo "N=$N"
cat > "$N/check-nginx.sh" <<'SCRIPT'
#!/usr/bin/env bash
# 호스트 nginx 설정 로컬 검증 — 가짜 인증서로 nginx -t, 이어서 실제 요청으로 동작 확인
set -uo pipefail
W="$(cygpath -m "$1")"
for f in itda.conf openvidu.conf connection-upgrade.conf; do
  [ -f "$1/deploy/nginx/$f" ] || { echo "FAIL 파일 없음: deploy/nginx/$f"; exit 1; }
done
T="$(mktemp -d)"; TM="$(cygpath -m "$T")"
NAME=ieum-nginx-check
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1; rm -rf "$T"; }
trap cleanup EXIT

for d in i-eum.site j15b201.p.ssafy.io openvidu.i-eum.site; do
  mkdir -p "$T/letsencrypt/live/$d"
  MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$d" \
    -keyout "$TM/letsencrypt/live/$d/privkey.pem" -out "$TM/letsencrypt/live/$d/fullchain.pem" >/dev/null 2>&1
done
printf 'ssl_protocols TLSv1.2 TLSv1.3;\n' > "$T/letsencrypt/options-ssl-nginx.conf"
MSYS_NO_PATHCONV=1 openssl dhparam -dsaparam -out "$TM/letsencrypt/ssl-dhparams.pem" 2048 >/dev/null 2>&1
mkdir -p "$T/downloads"
printf '{"version":"0.9.0"}\n' > "$T/downloads/latest.json"
printf 'apk' > "$T/downloads/ieum.apk"
printf 'old' > "$T/downloads/ieum-0.9.0.apk"
printf 'tmp' > "$T/downloads/ieum.apk.tmp"
head -c 11000000 /dev/zero > "$T/big.bin"

MOUNTS=(
  -v "$W/deploy/nginx/itda.conf:/etc/nginx/conf.d/itda.conf:ro"
  -v "$W/deploy/nginx/openvidu.conf:/etc/nginx/conf.d/openvidu.conf:ro"
  -v "$W/deploy/nginx/connection-upgrade.conf:/etc/nginx/conf.d/connection-upgrade.conf:ro"
  -v "$W/deploy/nginx/inc:/home/ubuntu/app/nginx/inc:ro"
  -v "$TM/letsencrypt:/etc/letsencrypt:ro"
  -v "$TM/downloads:/home/ubuntu/app/downloads:ro"
)
echo "== nginx -t"
MSYS_NO_PATHCONV=1 docker run --rm "${MOUNTS[@]}" nginx:stable-alpine nginx -t || { echo "FAIL nginx -t"; exit 1; }

MSYS_NO_PATHCONV=1 docker run -d --name "$NAME" --sysctl net.ipv6.conf.all.disable_ipv6=0 \
  -p 127.0.0.1:18080:80 -p 127.0.0.1:18443:443 "${MOUNTS[@]}" nginx:stable-alpine >/dev/null
for _ in $(seq 1 20); do curl -s -o /dev/null http://127.0.0.1:18080/ && break; sleep 1; done

fails=0
expect() { if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: 기대 [$2] 실제 [$3]"; fails=$((fails+1)); fi; }
https() { curl -sk --resolve "$1:18443:127.0.0.1" "${@:3}" "https://$1:18443$2"; }
status() { https "$1" "$2" -o /dev/null -w '%{http_code}'; }
header() { https "$1" "$2" -o /dev/null -D - | tr -d '\r' | awk -v h="$3" 'tolower($0) ~ "^" h ":" { sub(/^[^:]*: */, ""); print; exit }'; }
cert() { openssl s_client -connect 127.0.0.1:18443 -servername "$1" </dev/null 2>/dev/null | openssl x509 -noout -subject 2>/dev/null | grep -Eq "CN ?= ?$1\$" && echo ok || echo no; }

expect "latest.json 200"            200 "$(status i-eum.site /app/latest.json)"
expect "latest.json Content-Type"   "application/json; charset=utf-8" "$(header i-eum.site /app/latest.json content-type)"
expect "latest.json Cache-Control"  "no-cache" "$(header i-eum.site /app/latest.json cache-control)"
expect "www latest.json 200"        200 "$(status www.i-eum.site /app/latest.json)"
expect "ieum.apk 200"               200 "$(status i-eum.site /app/ieum.apk)"
expect "ieum.apk Content-Type"      "application/vnd.android.package-archive" "$(header i-eum.site /app/ieum.apk content-type)"
expect "ieum.apk Cache-Control"     "no-cache" "$(header i-eum.site /app/ieum.apk cache-control)"
expect "직전 apk 200"               200 "$(status i-eum.site /app/ieum-0.9.0.apk)"
expect "직전 apk Content-Type"      "application/vnd.android.package-archive" "$(header i-eum.site /app/ieum-0.9.0.apk content-type)"
expect ".tmp는 파일로 안 줌(web 502)" 502 "$(status i-eum.site /app/ieum.apk.tmp)"
expect "/app 은 web(502)"            502 "$(status i-eum.site /app)"
expect "옛 도메인 https 301"          "301 https://i-eum.site/x?y=1" "$(https j15b201.p.ssafy.io '/x?y=1' -o /dev/null -w '%{http_code} %{redirect_url}')"
expect "옛 도메인 http 301"           "301 https://i-eum.site/x?y=1" "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Host: j15b201.p.ssafy.io' 'http://127.0.0.1:18080/x?y=1')"
expect "i-eum http 301"             "301 https://i-eum.site/x?y=1" "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Host: i-eum.site' 'http://127.0.0.1:18080/x?y=1')"
expect "openvidu http 301"          "301 https://openvidu.i-eum.site/x" "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Host: openvidu.i-eum.site' 'http://127.0.0.1:18080/x')"
expect "/api 11MB 413"              413 "$(https i-eum.site /api/v1/voice/clones -o /dev/null -w '%{http_code}' -X POST --data-binary @- < "$T/big.bin")"
expect "/api 작은 본문은 통과(502)"  502 "$(https i-eum.site /api/v1/health -o /dev/null -w '%{http_code}' -X POST --data-binary 'x')"
expect "인증서 i-eum.site"           ok "$(cert i-eum.site)"
expect "인증서 j15b201"              ok "$(cert j15b201.p.ssafy.io)"
expect "인증서 openvidu"             ok "$(cert openvidu.i-eum.site)"

echo "== 실패 $fails 건"
[ "$fails" -eq 0 ]
SCRIPT
chmod +x "$N/check-nginx.sh"
```

- [ ] **Step 3: 현재 `itda.conf`로 돌려 실패를 확인한다**

```bash
bash "$N/check-nginx.sh" C:/Users/SSAFY/Desktop/wt-deploy-infra; echo "exit=$?"
```

Expected: `nginx -t`는 통과하고(`syntax is ok`), `FAIL latest.json 200`(502), `FAIL 옛 도메인 https 301`, `FAIL 인증서 i-eum.site` 등 여러 FAIL, `exit=1`. 컨테이너가 바로 죽어 모든 요청이 `000`이면 `docker logs ieum-nginx-check`를 보고(주로 IPv6 listen) 원인을 먼저 고친다 — 설정이 아니라 검증 환경 문제다.

- [ ] **Step 4: `deploy/nginx/itda.conf`를 아래 내용으로 교체한다**

```nginx
# =========================================================================
# 호스트 Nginx 설정 — /etc/nginx/sites-available/itda 로 배치 (Jenkins Sync가 매 배포마다 반영)
# blue/green 활성 포트는 /home/ubuntu/app/nginx/inc/*.inc 가 정의하며
# scripts/deploy.sh 가 전환 시 갱신 후 reload 한다.
# openvidu.i-eum.site 블록은 같은 폴더의 openvidu.conf 에 따로 있다.
# =========================================================================

# HTTP → HTTPS 리다이렉트
server {
    listen 80;
    listen [::]:80;
    server_name i-eum.site www.i-eum.site;

    return 301 https://$host$request_uri;
}

# 메인: HTTPS 종료 + 서비스 라우팅
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name i-eum.site www.i-eum.site;

    ssl_certificate /etc/letsencrypt/live/i-eum.site/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/i-eum.site/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    client_max_body_size 20m;

    include /home/ubuntu/app/nginx/inc/web.inc;
    include /home/ubuntu/app/nginx/inc/backend.inc;

    # backend API — WebSocket(실시간 수업) 겸용으로 Upgrade 헤더 상시 전달
    location /api/ {
        # 목소리 샘플(WAV ≤ 10MB) 업로드 상한. 넘으면 backend까지 가지 않고 413
        client_max_body_size 11m;

        proxy_pass http://127.0.0.1:$backend_port;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;

        proxy_connect_timeout 5s;
        proxy_send_timeout 120s;
        proxy_read_timeout 3600s;   # WebSocket 장시간 연결 유지
    }

    # 학습자 앱 배포 파일 — scripts/release-app.sh가 /home/ubuntu/app/downloads/에 올린다
    # 정확히 아래 세 모양만 파일로 준다. /app 자체(다운로드 페이지)와 그 밖의 /app/*는
    # location / (web SPA)로 간다 — 업로드 중인 *.tmp가 밖에 보이지 않는다.
    location = /app/latest.json {
        alias /home/ubuntu/app/downloads/latest.json;
        types { }
        default_type application/json;
        charset utf-8;
        charset_types application/json;
        add_header Cache-Control "no-cache" always;
    }

    location = /app/ieum.apk {
        alias /home/ubuntu/app/downloads/ieum.apk;
        types { }
        default_type application/vnd.android.package-archive;
        add_header Cache-Control "no-cache" always;
    }

    # 직전 버전 하나 (ieum-<X.Y.Z>.apk) — 새 버전에 문제가 있을 때 되돌려 설치하는 용도
    location ~ ^/app/(ieum-[0-9]+\.[0-9]+\.[0-9]+\.apk)$ {
        alias /home/ubuntu/app/downloads/$1;
        types { }
        default_type application/vnd.android.package-archive;
    }

    # Jenkins (127.0.0.1:8080, --prefix=/jenkins)
    location = /jenkins { return 301 /jenkins/; }
    location /jenkins/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_request_buffering off;
    }

    # web (SPA 정적 서빙 컨테이너)
    location / {
        proxy_pass http://127.0.0.1:$web_port;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

# 옛 도메인 — 모든 요청을 i-eum.site로 넘긴다 (경로·쿼리 유지). 인증서는 서버에 있다.
# 주의: 301은 POST를 GET으로 바꿀 수 있다 — GitLab 웹훅·Jenkins URL은 i-eum.site로 옮겨 둔다 (deploy.md 10절)
server {
    listen 80;
    listen [::]:80;
    server_name j15b201.p.ssafy.io;

    return 301 https://i-eum.site$request_uri;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name j15b201.p.ssafy.io;

    ssl_certificate /etc/letsencrypt/live/j15b201.p.ssafy.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/j15b201.p.ssafy.io/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    return 301 https://i-eum.site$request_uri;
}

# AI 내부 전용 리스너 — backend 컨테이너가 host.docker.internal:9100 으로 호출
# 외부(eth0)의 9100 접근은 UFW 기본 거부가 차단하고,
# 컨테이너 대역만 ufw allow from 172.16.0.0/12 to any port 9100 으로 허용한다.
server {
    listen 9100;

    include /home/ubuntu/app/nginx/inc/ai.inc;

    location / {
        proxy_pass http://127.0.0.1:$ai_port;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_read_timeout 300s;   # AI 추론 지연 여유
    }
}
```

메인 443 블록을 옛 도메인 블록보다 먼저 둔다 — 443의 첫 서버가 SNI 없는 요청의 기본 서버가 되므로 지금 서버 동작(i-eum.site 기본)을 유지한다.

- [ ] **Step 5: 검증 스크립트를 다시 돌려 통과를 확인한다**

```bash
bash "$N/check-nginx.sh" C:/Users/SSAFY/Desktop/wt-deploy-infra; echo "exit=$?"
```

Expected: `nginx: the configuration file /etc/nginx/nginx.conf syntax is ok`, `test is successful`, 20줄 모두 `PASS`, `== 실패 0 건`, `exit=0`.

- [ ] **Step 6: 옛 설정과의 차이를 눈으로 확인한다**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
git diff deploy/nginx/itda.conf
```

Expected: 바뀐 곳은 머리 주석 한 줄, `server_name`·인증서 경로(i-eum.site), `/api/`의 `client_max_body_size 11m` 두 줄, `/app/` location 3개, 옛 도메인 서버 블록 2개뿐. Jenkins·web·9100 블록은 그대로다.

- [ ] **Step 7: 정리 후 커밋**

```bash
rm -rf "$N"
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
git add deploy/nginx/itda.conf deploy/nginx/openvidu.conf
git commit -m "$(cat <<'EOF'
chore(infra): 호스트 nginx를 i-eum.site로 맞추고 앱 배포 경로를 연다

서버에만 있던 i-eum.site 도메인과 openvidu.i-eum.site 블록을 저장소로 옮긴다.
옛 도메인은 i-eum.site로 넘기고, /app/ 아래 APK·latest.json을 정적 제공한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Jenkinsfile — Sync(사이트 파일 2개 + 복원)와 Redis 단계

**Files:**
- Modify: `Jenkinsfile` (머리 주석 11~12행 뒤, Sync 단계 전체, Build와 Deploy 사이에 새 단계)

**Interfaces:**
- Consumes: Task 1의 서비스 `redis`·컨테이너 `itda-redis`, Task 2의 `deploy/nginx/openvidu.conf`
- Produces: 서버 경로 `/etc/nginx/sites-available/itda`, `/etc/nginx/sites-available/openvidu.conf`, `/etc/nginx/conf.d/connection-upgrade.conf`, 디렉터리 `/home/ubuntu/app/downloads` — Task 4 스크립트가 `downloads`가 있다고 가정한다. 단계 이름 `Redis` — Task 6 pipeline.md가 쓴다

- [ ] **Step 1: 바뀌기 전 상태를 확인한다 (기대: 모두 0)**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
grep -c "openvidu.rollback" Jenkinsfile; grep -c "stage('Redis')" Jenkinsfile; grep -c "app/downloads" Jenkinsfile
```

Expected: `0` 세 줄.

- [ ] **Step 2: Groovy 파싱 검사가 지금 파일에서 통과하는지 확인한다 (기준선)**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -m "$PWD"):/w" groovy:4.0-jdk17 \
  groovy -e "new GroovyShell().parse(new File('/w/Jenkinsfile')); println('PARSE OK')"
```

Expected: `PARSE OK`. (선언형 문법 자체를 검사하지는 않는다 — 괄호·문자열·`\$` 이스케이프가 깨졌는지만 본다.)

- [ ] **Step 3: Sync 단계 전체를 아래로 교체한다**

`// ── 2. 인프라 파일 동기화` 주석부터 Sync `stage` 닫는 괄호까지(현재 140~182행 부근)를 다음으로 바꾼다.

```groovy
        // ── 2. 인프라 파일 동기화 — 원본을 서버 사본에 매 배포마다 반영 ──
        //    .env.config는 Jenkins Secret file(itda-env-config)이 원본이다.
        //    nginx는 사이트 파일 2개(itda, openvidu.conf)와 conf.d/connection-upgrade.conf를
        //    모두 백업한 뒤 덮고, nginx -t가 실패하면 셋 다 되돌리고 배포를 멈춘다.
        //    downloads/(앱 배포 파일)는 없으면 만들기만 하고 내용은 건드리지 않는다.
        //    제외: .env.tags(활성 태그 상태 — deploy.sh가 갱신, 서버가 원본),
        //          nginx/inc(활성 슬롯 상태, 서버가 원본),
        //          deploy/jenkins(배포 중 자기 재생성 불가 — 수동 반영)
        stage('Sync') {
            when { expression { env.DEPLOY_SERVICES } }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'ec2-deploy-key',
                                                   keyFileVariable: 'SSH_KEY',
                                                   usernameVariable: 'SSH_USER'),
                                 file(credentialsId: 'itda-env-config', variable: 'ENV_CONFIG')]) {
                    sh """
                        scp -i "\$SSH_KEY" -o StrictHostKeyChecking=accept-new \\
                            deploy/docker-compose.prod.yml \\
                            "\$SSH_USER"@${DEPLOY_HOST}:/home/ubuntu/app/
                        scp -i "\$SSH_KEY" "\$ENV_CONFIG" \\
                            "\$SSH_USER"@${DEPLOY_HOST}:/home/ubuntu/app/.env.config
                        scp -i "\$SSH_KEY" scripts/deploy.sh scripts/cleanup-images.sh \\
                            "\$SSH_USER"@${DEPLOY_HOST}:/home/ubuntu/app/scripts/
                        scp -i "\$SSH_KEY" deploy/nginx/itda.conf deploy/nginx/openvidu.conf \\
                            deploy/nginx/connection-upgrade.conf \\
                            "\$SSH_USER"@${DEPLOY_HOST}:/tmp/
                        ssh -i "\$SSH_KEY" "\$SSH_USER"@${DEPLOY_HOST} '
                            set -e
                            chmod +x /home/ubuntu/app/scripts/*.sh
                            chmod 600 /home/ubuntu/app/.env.config
                            mkdir -p /home/ubuntu/app/downloads
                            sudo cp /etc/nginx/sites-available/itda /tmp/itda.rollback
                            sudo cp /etc/nginx/sites-available/openvidu.conf /tmp/openvidu.rollback
                            sudo cp /etc/nginx/conf.d/connection-upgrade.conf /tmp/connection-upgrade.rollback
                            sudo cp /tmp/itda.conf /etc/nginx/sites-available/itda
                            sudo cp /tmp/openvidu.conf /etc/nginx/sites-available/openvidu.conf
                            sudo cp /tmp/connection-upgrade.conf /etc/nginx/conf.d/connection-upgrade.conf
                            if sudo nginx -t; then
                                sudo systemctl reload nginx
                            else
                                echo "[sync] nginx 설정 오류 — 세 파일을 이전 설정으로 복원하고 배포를 중단한다"
                                sudo cp /tmp/itda.rollback /etc/nginx/sites-available/itda
                                sudo cp /tmp/openvidu.rollback /etc/nginx/sites-available/openvidu.conf
                                sudo cp /tmp/connection-upgrade.rollback /etc/nginx/conf.d/connection-upgrade.conf
                                exit 1
                            fi
                            rm -f /tmp/itda.conf /tmp/openvidu.conf /tmp/connection-upgrade.conf
                            sudo rm -f /tmp/itda.rollback /tmp/openvidu.rollback /tmp/connection-upgrade.rollback
                        '
                    """
                }
            }
        }
```

(`sites-enabled`의 심볼릭 링크는 Sync가 만들지 않는다 — 기존 서버에 이미 있고, 새 서버는 deploy.md 5절 최초 배치가 만든다.)

- [ ] **Step 4: Build 단계 뒤, Deploy 단계 앞에 Redis 단계를 넣는다**

`// ── 4. 자기 호스트에 SSH로 접속해 deploy.sh 실행` 주석 바로 위에 넣는다.

```groovy
        // ── 3-1. 운영 Redis 보장 — blue-green 대상이 아니다 ──────────────
        //    이미 떠 있으면 up -d는 아무것도 하지 않는다. backend가 기동 시 PING을
        //    보내므로 PONG까지 확인한 뒤 Deploy로 넘어간다.
        stage('Redis') {
            when { expression { env.DEPLOY_SERVICES } }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'ec2-deploy-key',
                                                   keyFileVariable: 'SSH_KEY',
                                                   usernameVariable: 'SSH_USER')]) {
                    sh """
                        ssh -i "\$SSH_KEY" -o StrictHostKeyChecking=accept-new \\
                            "\$SSH_USER"@${DEPLOY_HOST} '
                            set -e
                            cd /home/ubuntu/app
                            docker compose -f docker-compose.prod.yml --env-file .env.config --env-file .env.tags up -d redis
                            for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
                                if docker exec itda-redis redis-cli ping 2>/dev/null | grep -q PONG; then
                                    echo "[redis] itda-redis PONG"
                                    exit 0
                                fi
                                sleep 2
                            done
                            echo "[redis] itda-redis가 30초 안에 응답하지 않아 배포를 중단한다" >&2
                            docker logs --tail 30 itda-redis >&2 || true
                            exit 1
                        '
                    """
                }
            }
        }
```

(원격 셸 안에 `$`를 쓰지 않았으므로 Groovy 이스케이프가 필요 없다. `attempt`는 읽지 않는 반복 변수다.)

- [ ] **Step 5: 머리 주석에 단계 순서를 한 줄 더한다**

`//   → 사람이 수동 배포할 때와 100% 같은 스크립트, 같은 경로.` 줄 바로 아래에 추가:

```groovy
//
// 단계: Plan → Sync(compose·scripts·nginx 2사이트) → Build → Redis(itda-redis 보장) → Deploy → Cleanup
```

- [ ] **Step 6: 검사**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -m "$PWD"):/w" groovy:4.0-jdk17 \
  groovy -e "new GroovyShell().parse(new File('/w/Jenkinsfile')); println('PARSE OK')"
grep -c "openvidu.rollback" Jenkinsfile
grep -n "stage('" Jenkinsfile
git diff --stat
```

Expected: `PARSE OK`; `openvidu.rollback` 3회(백업·복원·삭제); 단계 순서 `Plan, Sync, Build, Redis, Deploy, Cleanup`; 바뀐 파일은 `Jenkinsfile` 하나(`Jenkinsfile.mr`·`Jenkinsfile.rollback` 변경 없음).

- [ ] **Step 7: 커밋**

```bash
git add Jenkinsfile
git commit -m "$(cat <<'EOF'
ci(infra): 배포 Sync가 두 사이트 파일을 함께 반영하고 Redis를 먼저 띄운다

openvidu.conf까지 백업·복사하고 nginx -t 실패 시 모두 되돌린다.
downloads 디렉터리를 보장하고, Deploy 전에 itda-redis의 PONG을 확인한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `scripts/release-app.sh` — APK 릴리스 스크립트

**Files:**
- Create: `scripts/release-app.sh`

**Interfaces:**
- Consumes: `front/apps/mobile/app.json`의 `expo.version`(semver)·`expo.android.versionCode`(정수, front 계획), 빌드 산출물 `front/apps/mobile/android/app/build/outputs/apk/release/app-release.apk`, 서버 디렉터리 `/home/ubuntu/app/downloads`(Task 3), 공개 URL(Task 2)
- Produces: 명령 `scripts/release-app.sh "<변경 내용>"`, 환경변수 `GITLAB_TOKEN`(필수)·`IEUM_DEPLOY_KEY`(필수, pem 경로)·`IEUM_DEPLOY_HOST`(선택, 기본 `ubuntu@i-eum.site`). 산출물: GitLab 일반 패키지 `ieum-android/<version>/ieum-<version>.apk`(+`.sha256`), Release `app-v<version>`, 서버 `ieum.apk`·`latest.json`(계약 스키마)·`ieum-<직전버전>.apk` 하나 — Task 5 deploy.md 9절이 쓴다

- [ ] **Step 1: `scripts/release-app.sh`를 만든다**

```bash
#!/usr/bin/env bash
# =========================================================================
# 학습자 앱(APK) 릴리스 — 사람이 PC(Git Bash)에서 실행한다 (docs/infra/deploy.md 9절)
#
# 사용법: scripts/release-app.sh "<변경 내용 한두 줄>"
# 환경변수:
#   GITLAB_TOKEN      GitLab 개인 액세스 토큰(api scope). 저장소·파일에 두지 않는다
#   IEUM_DEPLOY_KEY   EC2 접속 pem 파일 경로 (저장소 밖)
#   IEUM_DEPLOY_HOST  (선택) 기본 ubuntu@i-eum.site
# 전제: HEAD에 app-v<app.json version> 태그가 있고 origin에도 같은 커밋으로 올라가 있다.
#       front/apps/mobile/.env.production, ~/.gradle/gradle.properties의 IEUM_UPLOAD_* 서명 속성.
#
# 순서: 확인 → 테스트 → 빌드 → 해시 → latest.json → GitLab Release → 서버 교체
# 실패하면 그 자리에서 멈추고 끝난 단계를 출력한다. 서버 교체가 마지막이므로
# 그 전에 멈추면 학습자가 받는 파일은 바뀌지 않는다.
# =========================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOBILE_DIR="$REPO_ROOT/front/apps/mobile"
APK_BUILT="$MOBILE_DIR/android/app/build/outputs/apk/release/app-release.apk"
GITLAB_API="https://lab.ssafy.com/api/v4"
PROJECT_ID="s15-bigdata-recom-sub1%2FS15P21B201"
PACKAGE_NAME="ieum-android"
PUBLIC_APK_URL="https://i-eum.site/app/ieum.apk"
PUBLIC_LATEST_URL="https://i-eum.site/app/latest.json"
REMOTE_DIR="/home/ubuntu/app/downloads"
DEPLOY_HOST="${IEUM_DEPLOY_HOST:-ubuntu@i-eum.site}"

DONE=()
WORK=""

step_done() {
  DONE+=("$1")
  echo "[release] 완료: $1"
}

on_exit() {
  local code=$?
  if [ "$code" -ne 0 ]; then
    echo "[release] 실패 (종료 코드 $code). 끝난 단계:" >&2
    if [ "${#DONE[@]}" -eq 0 ]; then
      echo "  (없음)" >&2
    else
      printf '  - %s\n' "${DONE[@]}" >&2
    fi
    if [ -n "$WORK" ]; then
      echo "[release] 중간 산출물: $WORK" >&2
    fi
  fi
}
trap on_exit EXIT

fail() {
  echo "[release] $*" >&2
  exit 1
}

# GitLab API 호출. 실패하면 응답 본문을 보여 주고 멈춘다 (토큰은 출력하지 않는다)
gitlab() {
  local out
  if ! out="$(curl --fail-with-body -sS -H "PRIVATE-TOKEN: $GITLAB_TOKEN" "$@")"; then
    echo "[release] GitLab API 실패: $out" >&2
    return 1
  fi
}

# ── 0. 입력 ──────────────────────────────────────────────────────────────
NOTES="${1:-}"
[ -n "$NOTES" ] || fail '사용법: scripts/release-app.sh "<변경 내용 한두 줄>"'
[ -n "${GITLAB_TOKEN:-}" ] || fail "GITLAB_TOKEN 환경변수가 없습니다 (api scope 개인 액세스 토큰)"
if [ -z "${IEUM_DEPLOY_KEY:-}" ] || [ ! -f "$IEUM_DEPLOY_KEY" ]; then
  fail "IEUM_DEPLOY_KEY가 서버 접속 pem 파일을 가리키지 않습니다"
fi
for cmd in git node pnpm npx curl scp ssh sha256sum; do
  command -v "$cmd" >/dev/null 2>&1 || fail "$cmd 명령을 찾을 수 없습니다"
done
SSH_OPTS=(-i "$IEUM_DEPLOY_KEY" -o StrictHostKeyChecking=accept-new)

# ── 1. 확인 ──────────────────────────────────────────────────────────────
cd "$REPO_ROOT"
[ -z "$(git status --porcelain --untracked-files=no)" ] || fail "커밋하지 않은 변경이 있습니다 (git status)"
VERSION="$(node -p "require('./front/apps/mobile/app.json').expo.version")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "app.json expo.version이 X.Y.Z 형식이 아닙니다: $VERSION"
TAG="app-v$VERSION"
HEAD_TAGS="$(git tag --points-at HEAD)"
grep -qx "$TAG" <<<"$HEAD_TAGS" || fail "HEAD에 $TAG 태그가 없습니다 (app.json version=$VERSION)"
VERSION_CODE="$(node -p "require('./front/apps/mobile/app.json').expo.android.versionCode")"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || fail "app.json expo.android.versionCode가 양의 정수가 아닙니다: $VERSION_CODE"
[ -f "$MOBILE_DIR/.env.production" ] || fail "front/apps/mobile/.env.production이 없습니다 (deploy.md 9.2)"
HEAD_SHA="$(git rev-parse HEAD)"
REMOTE_TAG="$(git ls-remote --tags origin "refs/tags/$TAG" "refs/tags/$TAG^{}")"
grep -q "^$HEAD_SHA" <<<"$REMOTE_TAG" || fail "origin의 $TAG가 HEAD를 가리키지 않습니다 (git push origin $TAG)"
gitlab "$GITLAB_API/projects/$PROJECT_ID" || fail "GITLAB_TOKEN으로 프로젝트를 읽지 못했습니다 (scope·만료 확인)"
# shellcheck disable=SC2029  # REMOTE_DIR은 이 파일의 상수라 클라이언트에서 펼쳐도 안전하다
ssh "${SSH_OPTS[@]}" "$DEPLOY_HOST" test -w "$REMOTE_DIR" \
  || fail "서버 $REMOTE_DIR에 쓸 수 없습니다 (서버 배포 Sync가 한 번 돌았는지 확인)"
step_done "확인 ($TAG, versionCode $VERSION_CODE)"

# ── 2. 테스트 ────────────────────────────────────────────────────────────
(cd "$REPO_ROOT/front" && pnpm --filter @ieum/mobile test && pnpm --filter @ieum/mobile typecheck)
step_done "테스트·타입 검사"

# ── 3. 빌드 ──────────────────────────────────────────────────────────────
(cd "$MOBILE_DIR" && CI=1 NODE_ENV=production npx expo prebuild -p android --clean)
(cd "$MOBILE_DIR/android" && NODE_ENV=production ./gradlew assembleRelease)
[ -f "$APK_BUILT" ] || fail "빌드 산출물이 없습니다: $APK_BUILT"
step_done "release APK 빌드"

# ── 4. 산출물·해시 ───────────────────────────────────────────────────────
WORK="$(mktemp -d)"
APK_NAME="ieum-$VERSION.apk"
cp "$APK_BUILT" "$WORK/$APK_NAME"
SHA256="$(sha256sum "$WORK/$APK_NAME" | cut -d' ' -f1)"
printf '%s  %s\n' "$SHA256" "$APK_NAME" > "$WORK/$APK_NAME.sha256"
printf '%s\n' "$NOTES" > "$WORK/notes.txt"
step_done "SHA-256 $SHA256"

# ── 5. latest.json ───────────────────────────────────────────────────────
REL_VERSION="$VERSION" REL_VERSION_CODE="$VERSION_CODE" \
REL_RELEASED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)" REL_SHA256="$SHA256" REL_URL="$PUBLIC_APK_URL" \
node -e '
const fs = require("fs");
const e = process.env;
const notes = fs.readFileSync(0, "utf8").trim();
const body = {
  version: e.REL_VERSION,
  versionCode: Number(e.REL_VERSION_CODE),
  releasedAt: e.REL_RELEASED_AT,
  sha256: e.REL_SHA256,
  url: e.REL_URL,
  notes: notes
};
process.stdout.write(JSON.stringify(body, null, 2) + "\n");
' < "$WORK/notes.txt" > "$WORK/latest.json"
step_done "latest.json 생성"

# ── 6. GitLab: 일반 패키지 업로드 + Release ──────────────────────────────
PKG_URL="$GITLAB_API/projects/$PROJECT_ID/packages/generic/$PACKAGE_NAME/$VERSION"
gitlab --upload-file "$WORK/$APK_NAME" "$PKG_URL/$APK_NAME"
gitlab --upload-file "$WORK/$APK_NAME.sha256" "$PKG_URL/$APK_NAME.sha256"
step_done "GitLab 패키지 업로드 ($PACKAGE_NAME/$VERSION)"

REL_TAG="$TAG" REL_VERSION="$VERSION" REL_VERSION_CODE="$VERSION_CODE" REL_SHA256="$SHA256" \
REL_APK_NAME="$APK_NAME" REL_PKG_URL="$PKG_URL" \
node -e '
const fs = require("fs");
const e = process.env;
const notes = fs.readFileSync(0, "utf8").trim();
const apk = e.REL_PKG_URL + "/" + e.REL_APK_NAME;
const body = {
  tag_name: e.REL_TAG,
  name: "I:EUM app " + e.REL_VERSION,
  description: notes + "\n\n- versionCode: " + e.REL_VERSION_CODE + "\n- SHA-256: " + e.REL_SHA256,
  assets: {
    links: [
      { name: e.REL_APK_NAME, url: apk, link_type: "package" },
      { name: e.REL_APK_NAME + ".sha256", url: apk + ".sha256", link_type: "other" }
    ]
  }
};
process.stdout.write(JSON.stringify(body));
' < "$WORK/notes.txt" > "$WORK/release.json"
gitlab -X POST -H "Content-Type: application/json" --data-binary @- \
  "$GITLAB_API/projects/$PROJECT_ID/releases" < "$WORK/release.json"
step_done "GitLab Release $TAG"

# ── 7. 서버 교체 — 임시 이름으로 올린 뒤 mv (받는 도중 파일이 바뀌지 않게) ──
scp "${SSH_OPTS[@]}" "$WORK/$APK_NAME" "$DEPLOY_HOST:$REMOTE_DIR/ieum.apk.tmp"
scp "${SSH_OPTS[@]}" "$WORK/latest.json" "$DEPLOY_HOST:$REMOTE_DIR/latest.json.tmp"
# shellcheck disable=SC2029  # VERSION·SHA256은 위에서 형식을 검사한 값이라 클라이언트에서 펼쳐도 안전하다
ssh "${SSH_OPTS[@]}" "$DEPLOY_HOST" "bash -s -- $VERSION $SHA256" <<'REMOTE'
set -euo pipefail
new_version="$1"
new_sha="$2"
cd /home/ubuntu/app/downloads
actual="$(sha256sum ieum.apk.tmp | cut -d' ' -f1)"
if [ "$actual" != "$new_sha" ]; then
  echo "[server] 올린 APK의 SHA-256이 다르다 — 교체하지 않는다" >&2
  rm -f ieum.apk.tmp latest.json.tmp
  exit 1
fi
keep=""
if [ -f ieum.apk ] && [ -f latest.json ]; then
  prev="$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' latest.json | head -n 1)"
  if [ -n "$prev" ] && [ "$prev" != "$new_version" ]; then
    cp -p ieum.apk "ieum-$prev.apk.tmp"
    mv -f "ieum-$prev.apk.tmp" "ieum-$prev.apk"
    keep="ieum-$prev.apk"
  fi
fi
mv -f ieum.apk.tmp ieum.apk
mv -f latest.json.tmp latest.json
chmod 644 ieum.apk latest.json
if [ -n "$keep" ]; then
  chmod 644 "$keep"
  find . -maxdepth 1 -type f -name 'ieum-*.apk' ! -name "$keep" -print -delete
fi
echo "[server] 교체 완료: ieum.apk=$new_version, 직전 보관: ${keep:-없음}"
REMOTE
step_done "서버 교체 ($REMOTE_DIR)"

LIVE="$(curl -fsS "$PUBLIC_LATEST_URL")" || fail "$PUBLIC_LATEST_URL을 읽지 못했습니다 (nginx·권한 확인: deploy.md 10절)"
grep -q "$SHA256" <<<"$LIVE" || fail "공개된 latest.json의 sha256이 이번 빌드와 다릅니다"
step_done "공개 주소 확인 ($PUBLIC_LATEST_URL)"

rm -rf "$WORK"
WORK=""
echo "[release] $TAG 배포 끝 — 폰에서 https://i-eum.site/app 으로 설치를 확인한다"
```

- [ ] **Step 2: 문법·정적 검사**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
bash -n scripts/release-app.sh && echo "SYNTAX OK"
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -m "$PWD"):/mnt" -w /mnt koalaman/shellcheck:stable scripts/release-app.sh && echo "SHELLCHECK OK"
```

Expected: `SYNTAX OK`, `SHELLCHECK OK`(경고 0). 경고가 나오면 고친 뒤 다시 돌린다 — `disable` 주석은 스크립트에 이미 있는 SC2029 두 곳(서버 폴더 확인·서버 교체 ssh)만 허용한다.

- [ ] **Step 3: 네트워크 전에 멈추는 입력 검사를 돌려 본다 (서버·GitLab에 닿지 않는 경우만)**

가짜 키 파일은 임시 디렉터리에 `.pem`이 아닌 이름으로 만든다.

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
K="$(mktemp -d)"; : > "$K/dummy-key"
bash scripts/release-app.sh; echo "exit=$?"
env -u GITLAB_TOKEN bash scripts/release-app.sh "x"; echo "exit=$?"
GITLAB_TOKEN=dummy IEUM_DEPLOY_KEY="$K/nope" bash scripts/release-app.sh "x"; echo "exit=$?"
git add scripts/release-app.sh
GITLAB_TOKEN=dummy IEUM_DEPLOY_KEY="$K/dummy-key" bash scripts/release-app.sh "x"; echo "exit=$?"
git restore --staged scripts/release-app.sh
GITLAB_TOKEN=dummy IEUM_DEPLOY_KEY="$K/dummy-key" bash scripts/release-app.sh "x"; echo "exit=$?"
rm -rf "$K"
```

Expected (차례대로, 모두 `exit=1`, 끝에 `끝난 단계: (없음)`):
1. `사용법: scripts/release-app.sh "<변경 내용 한두 줄>"`
2. `GITLAB_TOKEN 환경변수가 없습니다`
3. `IEUM_DEPLOY_KEY가 서버 접속 pem 파일을 가리키지 않습니다`
4. `커밋하지 않은 변경이 있습니다` (스테이징된 새 파일)
5. `HEAD에 app-v0.1.0 태그가 없습니다 (app.json version=0.1.0)` (추적하지 않는 새 파일은 깨끗함 판정에서 빠진다)

이 검사들은 모두 `git ls-remote`·`curl`·`ssh`보다 앞이라 네트워크에 닿지 않는다. 5번이 다른 메시지로 멈추면(예: `pnpm 명령을 찾을 수 없습니다`) 그 도구가 PC에 없는 것이다 — 메시지를 기록하고 사람에게 알린다.

- [ ] **Step 4: 실행 권한과 함께 커밋**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
git add scripts/release-app.sh
git update-index --chmod=+x scripts/release-app.sh
git commit -m "$(cat <<'EOF'
chore(infra): 학습자 앱 릴리스 스크립트를 추가한다

서명한 release APK를 GitLab 패키지·Release와 서버 downloads에 올린다.
서버 파일은 임시 이름으로 올린 뒤 mv로 교체하고 직전 APK 하나만 남긴다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
git ls-files -s scripts/release-app.sh
```

Expected: 모드 `100755`.

---

### Task 5: `docs/infra/deploy.md` — 도메인·설정 키·DB 확인·앱 배포·사람이 할 일

**Files:**
- Modify: `docs/infra/deploy.md` (1절, 4.2절, 5절, 5.1절, 6.2절, 6.3절, 6.4절, 7.3절, 7.4절, 8절, 9·10절 신설)

**Interfaces:**
- Consumes: Task 1~4의 이름(`itda-redis`, `openvidu.conf`, `downloads`, `release-app.sh`, `GITLAB_TOKEN`, `IEUM_DEPLOY_KEY`, 단계 `Redis`)
- Produces: 절 번호 6.2.1(키 목록), 6.3(외부 DB 스키마 확인), 9(앱 배포), 10(서버에서 사람이 할 일) — Task 6의 다른 문서와 Task 4 스크립트 메시지가 이 번호를 가리킨다

- [ ] **Step 1: 1절 머리 목록(7~9행)을 교체한다**

~~~~markdown
- 도메인: `i-eum.site`(+`www.i-eum.site`) — 옛 도메인 `j15b201.p.ssafy.io`는 nginx가 `https://i-eum.site`로 301 넘긴다. 실시간 음성은 `openvidu.i-eum.site`
- 운영 DB: 외부 서버의 MariaDB — backend가 `DB_URL`로 연결 (첫 배포 전 스키마 확인: 6.3절)
- 운영 Redis: 같은 compose의 `redis` 서비스(컨테이너 `itda-redis`, 볼륨 `itda-redis-data`)
- 서버 접속: `ssh -i <pem파일> ubuntu@i-eum.site` (인증키는 저장소 밖에 보관, Git 커밋 금지)
~~~~

- [ ] **Step 2: 4.2절 발급 예시의 도메인을 바꾼다**

4.2절 코드 블록 안의 `j15b201.p.ssafy.io` 네 곳을 바꾼다: `server_name j15b201.p.ssafy.io;` → `server_name i-eum.site www.i-eum.site;`, `curl -I http://j15b201.p.ssafy.io` → `curl -I http://i-eum.site`, `sudo certbot --nginx -d j15b201.p.ssafy.io` → `sudo certbot --nginx -d i-eum.site -d www.i-eum.site`. 코드 블록 바로 아래 "인증서 유효기간은 90일이며…" 문단 뒤에 한 문단을 더한다:

~~~~markdown
현재 서버에는 인증서가 셋 있다: `i-eum.site`(+www), `openvidu.i-eum.site`, `j15b201.p.ssafy.io`(옛 도메인 넘김 전용). 셋 다 `certbot.timer`가 갱신하며, 설정을 바꾼 첫 배포 뒤에는 `sudo certbot renew --dry-run`이 세 인증서 모두 성공하는지 확인한다(10절). 옛 도메인은 80에서도 301을 돌려주므로 갱신 방식이 `webroot`이면 실패할 수 있다 — `/etc/letsencrypt/renewal/j15b201.p.ssafy.io.conf`의 `authenticator = nginx`를 확인한다.
~~~~

- [ ] **Step 3: 5절 디렉터리 구조·배치 표·최초 배치 명령을 바꾼다**

디렉터리 트리 코드 블록을 교체:

~~~~markdown
```text
/home/ubuntu/app/
├── docker-compose.prod.yml     # repo deploy/ 사본
├── .env.config                 # 운영 설정 (원본: Jenkins Secret file 'itda-env-config' — Sync가 배포, 키 목록 6.2.1)
├── .env.tags                   # 배포 태그 상태 (deploy.sh가 갱신 — 서버가 원본)
├── nginx/inc/                  # blue/green 활성 포트 (deploy.sh가 갱신)
│   ├── web.inc  backend.inc  ai.inc
├── downloads/                  # 학습자 앱 파일 (ieum.apk, latest.json, ieum-<직전>.apk — release-app.sh가 갱신, 9절)
└── scripts/                    # repo scripts/ 사본 (deploy.sh, cleanup-images.sh)
```
~~~~

"호스트 Nginx 쪽 파일 두 개는…" 문장과 표를 교체:

~~~~markdown
호스트 Nginx 쪽 파일 세 개는 root 영역에 배치한다.

| repo 파일 | EC2 배치 위치 | 역할 |
| --- | --- | --- |
| `deploy/nginx/itda.conf` | `/etc/nginx/sites-available/itda` | `i-eum.site` 라우팅 + SSL + `/app/` 앱 파일 + 옛 도메인 301 + AI 내부 리스너(9100) |
| `deploy/nginx/openvidu.conf` | `/etc/nginx/sites-available/openvidu.conf` | `openvidu.i-eum.site` → `127.0.0.1:7880` (OpenVidu) |
| `deploy/nginx/connection-upgrade.conf` | `/etc/nginx/conf.d/` | WebSocket Upgrade 공통 변수(map) |
~~~~

최초 배치 코드 블록을 교체:

~~~~markdown
```bash
# EC2에서 — 디렉토리 생성
mkdir -p /home/ubuntu/app/nginx/inc /home/ubuntu/app/scripts /home/ubuntu/app/downloads

# 본인 PC의 repo 루트에서 — 파일 전송
scp -i <pem> deploy/docker-compose.prod.yml ubuntu@i-eum.site:/home/ubuntu/app/
scp -i <pem> deploy/nginx/inc/*.inc ubuntu@i-eum.site:/home/ubuntu/app/nginx/inc/
scp -i <pem> scripts/deploy.sh scripts/cleanup-images.sh ubuntu@i-eum.site:/home/ubuntu/app/scripts/
scp -i <pem> deploy/nginx/itda.conf deploy/nginx/openvidu.conf deploy/nginx/connection-upgrade.conf ubuntu@i-eum.site:/home/ubuntu/

# EC2에서 — Nginx 설정 배치 (certbot이 만든 임시 설정을 최종본으로 교체)
sudo mv ~/connection-upgrade.conf /etc/nginx/conf.d/
sudo cp ~/itda.conf /etc/nginx/sites-available/itda
sudo cp ~/openvidu.conf /etc/nginx/sites-available/openvidu.conf
sudo ln -sfn /etc/nginx/sites-available/openvidu.conf /etc/nginx/sites-enabled/openvidu.conf
sudo nginx -t                    # 반드시 통과 확인 후
sudo systemctl reload nginx
chmod +x /home/ubuntu/app/scripts/*.sh
```
~~~~

그 아래 "이 시점에 `https://j15b201.p.ssafy.io`는" → "이 시점에 `https://i-eum.site`는".

- [ ] **Step 4: 5.1절의 도메인과 반영 설명**

5.1절 코드 블록의 `ubuntu@j15b201.p.ssafy.io` → `ubuntu@i-eum.site`. "**자동화 완료 (4단계):**" 문단의 "(복사 → `nginx -t` 검증 → reload, 실패 시 이전 설정 복원)"을 "(사이트 파일 `itda`·`openvidu.conf`와 `connection-upgrade.conf`를 모두 백업 → 복사 → `nginx -t` 검증 → reload, 실패 시 셋 다 복원)"으로 바꾼다.

- [ ] **Step 5: 6.2절 표와 `.env.config` 예시를 바꾸고 6.2.1절을 신설한다**

6.2절 표의 `.env.config` 행 내용 칸 `REGISTRY_PREFIX, BACKEND_PROFILE, DB_*, AI_BASE_URL` → `backend 설정 전체 + compose 치환값 (키 목록: 6.2.1절)`. 이어지는 코드 블록에서 `# .env.config — ...`부터 `EOF`(첫 번째)까지를 지우고 그 자리에 문장 하나를 둔다: "`.env.config`는 6.2.1절 키 목록대로 작성한다(값은 사람만 다룬다)." `.env.tags` 부분과 `chmod` 줄은 남기되 `chmod 600 /home/ubuntu/app/.env.config /home/ubuntu/app/.env.tags`는 그대로 둔다. 목록의 "`none`/`pending`은 자리 채우기 값이다…" → "`none`은 자리 채우기 값이다. compose가 두 파일의 필수 변수를 파싱 시점에 검사하므로, 사용하지 않는 값도 존재는 해야 한다." 그 뒤에 새 절을 넣는다:

~~~~markdown
#### 6.2.1 운영 설정 키 목록 (`.env.config` = Jenkins Secret file `itda-env-config`)

backend 컨테이너는 compose의 `env_file: .env.config`로 이 파일의 **모든 키**를 환경변수로 받는다. 필수 키가 빠지거나 형식이 틀리면 backend가 뜨지 않고 deploy.sh 헬스체크가 배포를 자동 취소한다(운영 트래픽은 직전 버전 유지). 값은 사람만 다루며 저장소에는 키 이름만(`deploy/.env.config.example`) 둔다.

| 구분 | 키 | 값·형식 | 필수 |
| --- | --- | --- | --- |
| compose 치환 | `REGISTRY_PREFIX` | 빈 값(로컬 이미지 저장소) | 선택 |
| backend 공통 | `SPRING_PROFILES_ACTIVE` | `prod` — **빠지면 local 프로필로 떠서 실패한다** (옛 `BACKEND_PROFILE`은 더 이상 읽지 않는다) | 필수 |
| | `AI_BASE_URL` | `http://host.docker.internal:9100/api/v1` (현 값 유지) | 필수 |
| DB | `DB_URL` | `jdbc:mariadb://<host>:<port>/<db>?connectionTimeZone=UTC` | 필수 |
| | `DB_USERNAME`·`DB_PASSWORD` | 운영 계정 | 필수 |
| Redis | `REDIS_HOST` | `redis` (compose 서비스 이름) | 필수 |
| | `REDIS_PORT` | `6379` | 필수 |
| | `AUTH_REDIS_KEY_PREFIX` | `prod` — 실행 프로필과 다르면 기동 실패 | 필수 |
| 인증 토큰 | `AUTH_JWT_ISSUER` | `itda-backend-prod` — 프로필과 다르면 기동 실패 | 필수 |
| | `AUTH_ACCESS_ACTIVE_KID`·`AUTH_ACCESS_ACTIVE_KEY` | kid(영문·숫자·`._~-` 1~64자)·Base64URL 256비트 이상 — **운영용으로 새로 생성** | 필수 |
| | `AUTH_ACCESS_RETIRED_KEYS` | `kid:key,kid:key` 또는 빈 값 | 선택 |
| | `AUTH_REFRESH_ACTIVE_KID`·`AUTH_REFRESH_ACTIVE_KEY`·`AUTH_REFRESH_RETIRED_KEYS` | 위와 같은 형식, 별도 key | 필수(retired는 선택) |
| | `AUTH_ACTION_ACTIVE_KID`·`AUTH_ACTION_ACTIVE_KEY`·`AUTH_ACTION_RETIRED_KEYS` | 위와 같은 형식, 별도 key. kid는 세 ring 통틀어 유일 | 필수(retired는 선택) |
| 메일 | `MAIL_DISPATCH_ENABLED` | `true`(발송) / `false` | 필수 |
| | `MAIL_SMTP_HOST`·`MAIL_SMTP_PORT`·`MAIL_SMTP_SECURITY` | 제공자 값, 보안 방식 `SSL` 또는 `STARTTLS` | 필수 |
| | `MAIL_SMTP_USERNAME`·`MAIL_SMTP_PASSWORD` | 제공자 계정 | 제공자에 따라 |
| | `MAIL_FROM_ADDRESS` | 발신 주소 | 필수 |
| | `MAIL_FROM_NAME`·`MAIL_REPLY_TO` | 표시 이름(기본 `이음(I:EUM)`)·회신 주소 | 선택 |
| | `MAIL_LINK_EMAIL_VERIFICATION_URL` | `https://i-eum.site/auth/verify-email` | 필수 |
| | `MAIL_LINK_PASSWORD_RESET_URL` | `https://i-eum.site/auth/reset-password` | 필수 |
| 음성 | `LIVEKIT_URL` | `https://openvidu.i-eum.site` | 필수 |
| | `LIVEKIT_API_KEY`·`LIVEKIT_API_SECRET` | `/opt/openvidu/config/openvidu.env`의 같은 이름 값 | 필수 |
| 음성 합성 | `CARTESIA_API_KEY` | Cartesia 키. 비우면 목소리 API만 503 | 선택 |

`deploy/.env.config.example`에 사람이 붙여 넣을 내용 (값 없이 키와 안내만):

```dotenv
# 운영 설정 — Jenkins Secret file 'itda-env-config'의 원본 구조 (docs/infra/deploy.md 6.2.1)
# 값에 $가 들어가면 값 전체를 작은따옴표로 감싼다 (compose가 $를 치환한다)
REGISTRY_PREFIX=
SPRING_PROFILES_ACTIVE=prod
AI_BASE_URL=http://host.docker.internal:9100/api/v1
DB_URL=jdbc:mariadb://<host>:<port>/<db>?connectionTimeZone=UTC
DB_USERNAME=
DB_PASSWORD=
REDIS_HOST=redis
REDIS_PORT=6379
AUTH_REDIS_KEY_PREFIX=prod
AUTH_JWT_ISSUER=itda-backend-prod
AUTH_ACCESS_ACTIVE_KID=
AUTH_ACCESS_ACTIVE_KEY=
AUTH_ACCESS_RETIRED_KEYS=
AUTH_REFRESH_ACTIVE_KID=
AUTH_REFRESH_ACTIVE_KEY=
AUTH_REFRESH_RETIRED_KEYS=
AUTH_ACTION_ACTIVE_KID=
AUTH_ACTION_ACTIVE_KEY=
AUTH_ACTION_RETIRED_KEYS=
MAIL_DISPATCH_ENABLED=true
MAIL_SMTP_HOST=
MAIL_SMTP_PORT=
MAIL_SMTP_USERNAME=
MAIL_SMTP_PASSWORD=
MAIL_SMTP_SECURITY=STARTTLS
MAIL_FROM_ADDRESS=
MAIL_FROM_NAME=
MAIL_REPLY_TO=
MAIL_LINK_EMAIL_VERIFICATION_URL=https://i-eum.site/auth/verify-email
MAIL_LINK_PASSWORD_RESET_URL=https://i-eum.site/auth/reset-password
LIVEKIT_URL=https://openvidu.i-eum.site
LIVEKIT_API_KEY=
LIVEKIT_API_SECRET=
CARTESIA_API_KEY=
```

운영용 서명 key 만들기 (ring마다 한 번씩, 로컬 key 재사용 금지):

```bash
openssl rand 32 | base64 | tr '+/' '-_' | tr -d '=\n'; echo   # Base64URL 256비트
# kid 예: access-202609, refresh-202609, action-202609 (세 ring 사이에 겹치면 기동 실패)
```

작성 규칙: `KEY=값` 사이에 공백을 두지 않는다. 값에 `$`가 있으면 `'...'`로 감싼다 — 같은 파일을 deploy.sh가 compose `--env-file`로도 읽어 `$`를 변수로 치환하기 때문이다.
~~~~

- [ ] **Step 6: 6.3절(H2 임시 운용)을 외부 DB 스키마 확인으로 교체한다**

`### 6.3 backend의 임시 H2 운용 (RDS 연결 전)`부터 6.4절 제목 직전까지를 교체:

~~~~markdown
### 6.3 외부 DB 스키마 확인 (첫 배포 전, 사람이 실행 — 읽기 전용)

운영 프로필은 `ddl-auto: validate`라 외부 DB 스키마가 엔티티와 다르면 backend가 뜨지 않는다(헬스체크가 배포를 자동 취소). 첫 배포 전에 본인 PC에서 운영 DB를 대상으로 **검증만** 하는 기동을 한 번 해 본다. 계정은 **읽기 전용**을 쓴다 — 기동 중 쓰기가 일어나도 DB가 거부한다.

```bash
# 저장소 루트 — 로컬 Redis만 띄운다 (prod 프로필도 기동 시 Redis PING을 보낸다)
docker compose up -d redis
cd apps/backend
k() { openssl rand 32 | base64 | tr '+/' '-_' | tr -d '=\n'; }   # 이 확인용 1회성 key
SPRING_PROFILES_ACTIVE=prod SERVER_PORT=18080 \
DB_URL='jdbc:mariadb://<host>:<port>/<db>?connectionTimeZone=UTC' DB_USERNAME='<읽기전용 계정>' DB_PASSWORD='<비밀번호>' \
REDIS_HOST=localhost REDIS_PORT=6379 AUTH_REDIS_KEY_PREFIX=prod AUTH_JWT_ISSUER=itda-backend-prod \
AUTH_ACCESS_ACTIVE_KID=check-access AUTH_ACCESS_ACTIVE_KEY="$(k)" \
AUTH_REFRESH_ACTIVE_KID=check-refresh AUTH_REFRESH_ACTIVE_KEY="$(k)" \
AUTH_ACTION_ACTIVE_KID=check-action AUTH_ACTION_ACTIVE_KEY="$(k)" \
MAIL_DISPATCH_ENABLED=false MAIL_SMTP_HOST=localhost MAIL_SMTP_PORT=587 MAIL_SMTP_SECURITY=STARTTLS \
MAIL_FROM_ADDRESS=check@i-eum.site \
MAIL_LINK_EMAIL_VERIFICATION_URL=https://i-eum.site/auth/verify-email \
MAIL_LINK_PASSWORD_RESET_URL=https://i-eum.site/auth/reset-password \
LIVEKIT_URL=https://openvidu.i-eum.site LIVEKIT_API_KEY=check LIVEKIT_API_SECRET=check \
./gradlew bootRun
```

- `MAIL_DISPATCH_ENABLED=false`를 반드시 준다 — 켜 두면 운영 DB의 메일 대기열을 PC가 발송할 수 있다.
- 로그에 `Started ...Application`이 찍히면 스키마가 엔티티와 맞는다 → Ctrl+C.
- `Schema-validation: missing table [...]`·`missing column [...]`·`wrong column type`이면 맞지 않는다 → 첫 배포를 멈추고 팀과 반영 방법을 정한다(스키마 변경 도구 도입은 범위 밖).

`validate`는 UNIQUE 제약을 보지 않으므로 DB 클라이언트로 따로 확인한다:

```sql
SHOW CREATE TABLE lecture_problems;   -- UNIQUE KEY (lecture_id, problem_id)
SHOW CREATE TABLE problem_attempts;   -- UNIQUE KEY (lecture_id, user_id, problem_id)
```
~~~~

- [ ] **Step 7: 6.4절·7.3절·7.4절**

6.4절 확인 코드 블록의 `https://j15b201.p.ssafy.io` 두 곳 → `https://i-eum.site`, 그리고 `docker ps` 위에 `docker exec itda-redis redis-cli ping                # PONG (운영 Redis)` 한 줄 추가. 6.4절 빌드·배포 코드 블록의 `cd /home/ubuntu/app` 다음 줄에 `docker compose -f docker-compose.prod.yml --env-file .env.config --env-file .env.tags up -d redis   # 최초 1회 (Jenkins는 Redis 단계가 수행)`를 넣는다.

7.3절 마지막 "**롤백이 못 되돌리는 것: 데이터베이스.**" 문단의 "DB 자체를 되돌리는 건 RDS 스냅샷 복원이라는" → "DB 자체를 되돌리는 건 외부 DB 백업 복원이라는".

7.4절 본문(제목 아래 전체)을 교체:

~~~~markdown
장애 상황에서 처음 해보지 않도록, 배포 구조를 바꿀 때마다 한 번씩 즉시 롤백을 훈련한다. 두 슬롯이 같은 외부 DB·Redis를 쓰므로 데이터로는 트래픽 이동을 구분할 수 없다 — 슬롯을 바꾼 뒤 `docker logs --since 1m itda-backend-<색>-1`에 요청 로그가 찍히는 쪽으로 확인한다.

1. 현재 활성 슬롯 확인: `cat /home/ubuntu/app/nginx/inc/backend.inc`
2. 7.3절 방법 1로 이전 슬롯으로 전환 → `curl https://i-eum.site/api/v1/health` 200
3. 이전 슬롯 컨테이너 로그에 방금 요청이 찍히는지 확인
4. 같은 방법으로 원래 슬롯으로 복귀
~~~~

- [ ] **Step 8: 8절 표에 행을 더한다**

8절 표 끝에 추가:

~~~~markdown
| 5 초기 배치 / Sync | `/etc/nginx/sites-available/openvidu.conf` | 사람(최초) → Jenkins Sync | repo `deploy/nginx/openvidu.conf` 변경 시 재반영 |
| Sync | `/home/ubuntu/app/downloads/` | Jenkins Sync(`mkdir -p`) | 내용은 `release-app.sh`만 바꾼다 (9절) |
| Jenkins Redis 단계 | 컨테이너 `itda-redis`, 볼륨 `itda-redis-data` | `docker compose up -d redis` | 없음 (blue-green 대상 아님). 볼륨을 지우면 로그인 상태가 모두 사라진다 |
~~~~

- [ ] **Step 9: 9절·10절을 문서 끝에 더한다**

~~~~markdown
## 9. 학습자 앱(APK) 배포

APK는 PC에서 빌드해 두 곳에 올린다: **GitLab Releases**(기록·보관 — 프로젝트가 비공개라 학습자는 못 받음)와 **`https://i-eum.site/app`**(학습자 다운로드). 서버 배포 파이프라인은 `app-v*` 태그를 무시한다.

| 위치 | 내용 |
| --- | --- |
| GitLab 일반 패키지 `ieum-android/<version>/` | `ieum-<version>.apk`, `ieum-<version>.apk.sha256` |
| GitLab Release `app-v<version>` | 변경 내용 + versionCode + SHA-256, 위 두 파일 링크 |
| 서버 `/home/ubuntu/app/downloads/` | `ieum.apk`(최신), `latest.json`, `ieum-<직전 버전>.apk`(하나만) |

`latest.json` 형식 (앱의 새 버전 알림과 웹 다운로드 페이지가 읽는다):

```json
{ "version": "1.0.0", "versionCode": 1, "releasedAt": "2026-09-27T12:00:00Z", "sha256": "<hex>", "url": "https://i-eum.site/app/ieum.apk", "notes": "변경 내용 한두 줄" }
```

### 9.1 준비 (PC에서 한 번)

- 릴리스 keystore를 만들어 **저장소 밖**에 두고 백업한다 — 잃으면 이미 설치된 앱을 업데이트할 수 없다.
- `~/.gradle/gradle.properties`: `IEUM_UPLOAD_STORE_FILE`, `IEUM_UPLOAD_KEY_ALIAS`, `IEUM_UPLOAD_STORE_PASSWORD`, `IEUM_UPLOAD_KEY_PASSWORD` (없으면 release 빌드가 실패한다 — 디버그 키 서명 방지)
- `front/apps/mobile/.env.production` (git 제외): `EXPO_PUBLIC_API_URL=https://i-eum.site/api/v1`, `EXPO_PUBLIC_APP_RELEASE_URL=https://i-eum.site/app`. Cartesia 키는 넣지 않는다(backend가 대신 호출)
- GitLab 개인 액세스 토큰(scope `api`)을 발급해 **만료일을 팀 일정에 기록**한다. 파일에 저장하지 않고 실행할 때 환경변수로만 준다
- 서버 pem 파일 경로 (저장소 밖)

### 9.2 절차

1. `app.json`의 `expo.version`(예: `1.0.0`)과 `expo.android.versionCode`(릴리스마다 +1)를 함께 올리는 MR을 병합한다(브랜치 전략 7절의 `release/app-*` 흐름).
2. 병합된 main 커밋에 **GitLab 웹 UI로** `app-v<version>` 태그를 만든다.
3. PC에서 그 태그를 체크아웃하고 스크립트를 실행한다.

```bash
git fetch origin --tags && git checkout app-v1.0.0
export GITLAB_TOKEN='<개인 액세스 토큰>'
export IEUM_DEPLOY_KEY='C:/keys/<서버>.pem'
scripts/release-app.sh "첫 배포: 실시간 수업 입장, 목소리 기능"
```

스크립트 순서: 확인(작업 트리·태그·versionCode·`.env.production`·토큰·서버 폴더) → `pnpm --filter @ieum/mobile test`·`typecheck` → `expo prebuild -p android --clean` + `gradlew assembleRelease` → SHA-256 → `latest.json` → GitLab 패키지·Release → 서버에 `*.tmp`로 올린 뒤 해시를 다시 확인하고 `mv`로 교체(이전 `ieum.apk`는 `ieum-<직전 버전>.apk`로 하나만 남김) → 공개 `latest.json` 확인. 실패하면 그 자리에서 멈추고 끝난 단계를 출력한다. 서버 교체가 마지막이라 그 전에 멈추면 학습자가 받는 파일은 그대로다.

4. 확인: GitLab → Deploy → Releases에 `app-v<version>`, `https://i-eum.site/app`에 새 버전·배포일, 폰에서 설치 → 로그인 → 수업 입장.
5. APK에 Cartesia 키가 없는지: `unzip -p ieum.apk assets/index.android.bundle | strings | grep -c sk_car` → `0`.

다시 실행할 때: GitLab Release를 만든 뒤 실패했다면 같은 태그로는 Release 생성이 409로 거부된다 — GitLab에서 그 Release를 지우거나 versionCode를 올린 새 버전으로 한다. 윈도우 빌드가 `Filename longer than 260 characters`로 죽으면 `docs/solutions/2026-09-24-windows-ninja-long-path.md`.

### 9.3 되돌리기

새 APK에 문제가 있으면 학습자에게 `https://i-eum.site/app/ieum-<직전 버전>.apk`를 안내한다. 같은 versionCode로 되돌려 설치할 수는 없으므로(안드로이드는 낮은 versionCode 설치를 거부) 기존 설치를 지우고 설치하거나, 고친 새 버전(versionCode +1)을 올린다.

## 10. 서버에서 사람이 할 일 (에이전트는 서버 설정을 바꾸지 않는다)

첫 배포(`release/0.1.0`) 전에 순서대로 한다. 서버 명령은 모두 사람이 실행한다.

1. **읽기 전용 사전 확인** — 6.3절 스키마 확인, `sudo certbot renew --dry-run`(세 인증서 모두 성공), `ls -l /etc/nginx/sites-enabled/`에 `itda`와 `openvidu.conf`(또는 같은 파일을 가리키는 링크)가 하나씩만 있는지.
2. **서버 사이트 파일과 저장소 비교** — Sync가 덮기 전에 차이가 없는지 본다. 본인 PC에서 `scp -i <pem> deploy/nginx/itda.conf deploy/nginx/openvidu.conf ubuntu@i-eum.site:/tmp/` 후 서버에서 `sudo diff /etc/nginx/sites-available/openvidu.conf /tmp/openvidu.conf`, `sudo diff /etc/nginx/sites-available/itda /tmp/itda.conf`. openvidu는 머리 주석 외에, itda는 주석·`/app/`·`11m`·옛 도메인 블록 외에 차이가 있으면 저장소를 먼저 고친다(MR). 끝나면 `rm /tmp/itda.conf /tmp/openvidu.conf`.
3. **`itda-env-config` 갱신** — 6.2.1절 키를 모두 넣은 새 파일로 Jenkins credential 교체(README 3.5절). `deploy/.env.config.example`도 6.2.1절 내용으로 MR 갱신.
4. **`downloads/` 권한** — nginx 작업 프로세스(`www-data`)가 파일을 읽을 수 있어야 한다. Ubuntu 24.04의 홈 디렉터리는 기본 `750`이라 막힐 수 있다.

   ```bash
   ls -ld /home/ubuntu /home/ubuntu/app /home/ubuntu/app/downloads
   echo '{}' > /home/ubuntu/app/downloads/perm-check.json
   sudo -u www-data cat /home/ubuntu/app/downloads/perm-check.json && echo READ-OK
   rm /home/ubuntu/app/downloads/perm-check.json
   ```

   `READ-OK`가 안 나오면 경로 통과 권한만 준다: `chmod o+x /home/ubuntu /home/ubuntu/app` (목록 보기는 허용하지 않는다). `downloads/`는 첫 배포 Sync가 만들므로 이 확인은 첫 배포 직후, 앱 배포 전에 한다.
5. **옛 도메인으로 된 주소 옮기기** — `j15b201.p.ssafy.io`는 301로 넘어가며, 301은 POST 본문을 잃을 수 있다. GitLab 웹훅 두 개(`pipeline.md` 7.5절)를 `https://i-eum.site/jenkins/project/...`로, Jenkins → System → Jenkins URL을 `https://i-eum.site/jenkins/`로 바꾼다. 바꾼 뒤 웹훅 Test → 200.
6. **OpenVidu Meet 끄기(권장)·MinIO 확인** — `openvidu.md` 8절. 수업이 없는 시간에 한다(OpenVidu 재시작).
7. **배포 후 확인** — spec §8 완료 기준: 헬스체크 200, 가입 메일 링크가 `i-eum.site`, 재로그인·토큰 갱신(Redis), 수업 음성, `curl -sI http://j15b201.p.ssafy.io/x` → `301` + `Location: https://i-eum.site/x`, `https://openvidu.i-eum.site` 유지.
~~~~

- [ ] **Step 10: 검사**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
grep -n "j15b201" docs/infra/deploy.md
grep -n "BACKEND_PROFILE\|RDS\|H2" docs/infra/deploy.md
grep -c '^```' docs/infra/deploy.md
git diff --stat
```

Expected: `j15b201` 줄은 1절(넘김 안내)·4.2절 추가 문단·10절 5번·10절 7번에만 있다. `BACKEND_PROFILE`은 6.2.1절 표의 "옛 `BACKEND_PROFILE`은 더 이상 읽지 않는다" 한 곳뿐, `RDS`·`H2` 0건. 코드 펜스 수는 짝수. 바뀐 파일은 `docs/infra/deploy.md` 하나.

- [ ] **Step 11: 커밋**

```bash
git add docs/infra/deploy.md
git commit -m "$(cat <<'EOF'
docs(infra): 운영 설정 키·외부 DB 확인·앱 배포 절차를 deploy 문서에 담는다

도메인을 i-eum.site로 바꾸고, .env.config 키 목록(example 반영용)과
첫 배포 전 사람이 할 일을 정리한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 나머지 인프라 문서 — README·architecture·docker·jenkins·pipeline·openvidu

**Files:**
- Modify: `docs/infra/README.md`, `docs/infra/architecture.md`, `docs/infra/docker.md`, `docs/infra/jenkins.md`, `docs/infra/pipeline.md`, `docs/infra/openvidu.md`

**Interfaces:**
- Consumes: Task 5의 절 번호(deploy.md 6.2.1·6.3·9·10), Task 3의 단계 이름 `Redis`, Task 2의 `openvidu.conf`
- Produces: `openvidu.md` 8절(Meet·MinIO) — deploy.md 10절 6번이 가리킨다

- [ ] **Step 1: OpenVidu 3.8 문서의 Meet 끄는 방법을 다시 확인한다 (계획 작성 시 확인한 내용)**

계획 작성 시점 확인 결과: 단일 노드 설정은 `/opt/openvidu/config/`에서 고치고 `sudo systemctl restart openvidu`로 반영한다(공식 "Changing configuration"). Community 모듈은 `openvidu.env`의 `ENABLED_MODULES`(예: `ENABLED_MODULES=observability,openviduMeet`)로 켜고, `openviduMeet`를 빼면 Meet이 뜨지 않는다(설치 옵션 `--enabled-modules`, OpenVidu 포럼 답변). MinIO는 모듈이 아니라 기본 구성(S3 저장소)이며 끄는 공식 설정은 찾지 못했다. context7 또는 `https://openvidu.io/3.8/docs/self-hosting/configuration/changing-config/`를 다시 열어 바뀐 점이 있으면 Step 7 문구를 맞춘다. 바뀐 점이 없으면 그대로 쓴다.

- [ ] **Step 2: README.md**

1절 코드 블록의 첫 그림(`[사용자] ── HTTPS ──>` 네 줄)을 교체:

~~~~markdown
```
[사용자] ── HTTPS ──> EC2 호스트 Nginx ──┬── i-eum.site /      → web (React+nginx)     blue/green
                                         ├── i-eum.site /api/  → backend (Spring Boot) blue/green ──> Redis(itda-redis) · 외부 MariaDB
                                         ├── i-eum.site /app/  → 학습자 앱 파일 (APK · latest.json)
                                         ├── openvidu.i-eum.site → OpenVidu 3.8 (실시간 음성)
                                         ├── j15b201.p.ssafy.io → i-eum.site 로 301
                                         └── (내부)  → ai (FastAPI)          blue/green
                                                        ↑ backend만 호출 가능 (외부 차단)
```
~~~~

2절 표의 deploy.md 행 설명 → `EC2 구축, HTTPS(Certbot), 3중 방어, 운영 설정 키 목록, 외부 DB 스키마 확인, 수동 배포·롤백 절차(비상용), blue-green 원리, **학습자 앱(APK) 배포**, 첫 배포 전 사람이 할 일`. openvidu.md 행 끝에 `, Meet 끄기`를 붙인다.

3.5절 3번 항목 → `3. 항목(키)이 추가·삭제됐으면 repo \`deploy/.env.config.example\`도 MR로 갱신 (값 말고 구조의 원본은 repo). 현재 키 목록: [deploy.md 6.2.1절](./deploy.md)`.

3.5절 끝(“`.env.tags`(배포 태그 상태)는…” 문단 뒤)에 새 절:

~~~~markdown
### 3.6 학습자 앱 배포 — APK

앱은 서버 파이프라인과 따로, 릴리스 담당자가 PC에서 `scripts/release-app.sh`로 올린다 (GitLab Releases + `https://i-eum.site/app`). 준비물과 절차는 [deploy.md 9절](./deploy.md).
~~~~

5절 표의 `| - | RDS 연결 (현재 backend는 H2 임시 운용 — 재배포 시 데이터 소실) | 팀 일정 대기 |` → `| 6. 운영 재구축 | i-eum.site 통일, 외부 MariaDB·운영 Redis, OpenVidu nginx 저장소 관리, 앱(APK) 배포 경로 | 진행 중 |`.

- [ ] **Step 3: architecture.md**

9행의 `"https://j15b201.p.ssafy.io<br/>(HTTPS, 443만 개방)"` → `"https://i-eum.site<br/>(HTTPS, 443만 개방)"`. 32행 `BB -- "AI 기능 내부 호출<br/>(Nginx 내부 리스너 :9100 경유)" --> AB` 바로 아래(subgraph `EC2` 안)에 두 줄을 넣는다:

```text
        BB -- "인증 토큰 상태·사용량" --> RD[("🧠 Redis<br/>itda-redis")]
        NX -- "/app/ → APK·latest.json" --> DL["📦 downloads/<br/>(정적 파일)"]
```

34행(`BB -. "RDS 연결 예정...`)을 `    BB -- "DB_URL" --> DB[("🗄️ 외부 MariaDB")]`로 바꾼다. 1절 아래 두 번째 글머리표 끝에 문장 추가: ` 옛 도메인 \`j15b201.p.ssafy.io\`는 i-eum.site로 301 넘기고, 실시간 음성 신호는 \`openvidu.i-eum.site\`(같은 nginx → OpenVidu)로 들어온다.`

- [ ] **Step 4: docker.md**

2절 트리의 `│  └─ .env.example               # 운영 환경변수 목록 (실제 값은 EC2의 deploy/.env)` → `│  ├─ .env.config.example        # 운영 설정 키 목록 (실제 값은 Jenkins itda-env-config)` 와 `│  └─ .env.tags.example          # 배포 태그 상태 키`. 5절 첫 문장 "`deploy/docker-compose.prod.yml`은 이미지 태그만 참조하며, 값은 같은 폴더의 `.env`에서 주입된다." → "`deploy/docker-compose.prod.yml`은 이미지 태그만 참조한다. 태그는 `.env.tags`(deploy.sh가 갱신)에서 치환되고, backend는 `.env.config`를 `env_file`로 통째로 받는다(키 목록: deploy.md 6.2.1절)." 그 아래 "EC2의 `.env` 작성 절차와 값의 의미는 deploy.md 6.2절을 따른다. `.env.example`은…" 문장 → "두 파일의 작성 절차는 deploy.md 6.2절을 따른다. `deploy/.env.config.example`은 키 목록의 원본이며 실제 값은 Jenkins credential에만 있다 (Git 커밋 금지)." 5절 글머리표 끝에 추가:

~~~~markdown
- `redis`(컨테이너 `itda-redis`, `redis:8.2.9-alpine`, AOF 켬, 볼륨 `itda-redis-data`)는 blue-green 대상이 아니다. 포트를 열지 않고 compose 기본 네트워크 안에서 backend만 `redis:6379`로 접근한다. Jenkins `Redis` 단계가 배포마다 `up -d redis`로 떠 있는지 보장한다. 볼륨을 지우면 모든 로그인 상태가 사라지므로 `docker compose down -v`를 운영에서 쓰지 않는다
~~~~

- [ ] **Step 5: jenkins.md**

`https://j15b201.p.ssafy.io/jenkins/` 세 곳(7·61·64행) → `https://i-eum.site/jenkins/`, 44행 `ubuntu@j15b201.p.ssafy.io` → `ubuntu@i-eum.site`. 64행 뒤에 한 줄: `   (옛 도메인으로 설정돼 있던 서버는 System → Jenkins URL을 이 값으로 바꾼다 — deploy.md 10절)`.

- [ ] **Step 6: pipeline.md**

5.1절 흐름 코드 블록을 교체:

~~~~markdown
```text
release/* push  또는  v* 태그 push (GitLab)
  → Webhook (Push events: release/* 필터 / Tag push events)
  → Jenkins 잡 itda-deploy
      1. Plan    : 무엇을 어떤 태그로 배포할지 결정 (아래 표)
      2. Sync    : 인프라 파일(compose·scripts·nginx 사이트 2개)을 서버로 동기화
      3. Build   : 대상 서비스 이미지 빌드 (호스트 Docker 저장소에 저장 = 롤백 지점)
      4. Redis   : 운영 Redis(itda-redis)를 up -d로 보장하고 PONG 확인 (blue-green 대상 아님)
      5. Deploy  : 자기 호스트에 SSH → 서비스마다 deploy.sh 실행 (blue-green)
      6. Cleanup : 이미지 보존 정책 집행 (rc/v* 각 최근 3개)
```
~~~~

그 아래 문단의 "`deploy/nginx/itda.conf`를 고치면 다음 배포에 실려 서버에 반영된다 (nginx 설정은 `nginx -t` 통과 시에만 reload, 실패 시 이전 설정 복원 후 배포 중단)" → "`deploy/nginx/itda.conf`·`openvidu.conf`·`connection-upgrade.conf`를 고치면 다음 배포에 실려 서버에 반영된다 (세 파일을 모두 백업한 뒤 덮고, `nginx -t` 통과 시에만 reload, 실패 시 셋 다 복원 후 배포 중단). 앱 배포 폴더 `/home/ubuntu/app/downloads/`는 없으면 만들기만 한다". 같은 문단의 "동기화하지 **않는** 것:" 목록 끝에 `, \`downloads/\` 안의 파일(학습자 앱 — \`release-app.sh\`가 원본, deploy.md 9절)`을 더한다.

7.5절 표의 `https://j15b201.p.ssafy.io/jenkins/project/itda-mr-verify` → `https://i-eum.site/jenkins/project/itda-mr-verify`, `https://j15b201.p.ssafy.io/jenkins/project/itda-deploy` → `https://i-eum.site/jenkins/project/itda-deploy`. 표 아래에 한 줄: `옛 도메인(\`j15b201.p.ssafy.io\`) 주소는 nginx가 301로 넘기지만 웹훅 POST는 넘어가지 않을 수 있으므로 반드시 i-eum.site 주소를 쓴다.`

- [ ] **Step 7: openvidu.md**

7~8행 인용문을 교체:

~~~~markdown
> 서버 nginx의 `openvidu.i-eum.site` 블록은 **repo `deploy/nginx/openvidu.conf`가 원본**이며 배포 Sync가 매번 반영한다.
> OpenVidu 설치본(`/opt/openvidu`), ufw 규칙, `livekit.yaml` 변경은 repo가 관리하지 않는다 — 서버를 새로 만들면 이 문서대로 사람이 다시 해야 한다.
~~~~

27~28행("호스트 nginx에는 … 7절 참고.")을 교체: `호스트 nginx의 \`openvidu.i-eum.site\` 서버 블록(80→443, Let's Encrypt)이 \`127.0.0.1:7880\`으로 프록시한다. 원본은 repo \`deploy/nginx/openvidu.conf\`(서버 \`/etc/nginx/sites-available/openvidu.conf\`).`

1절 표의 서비스 행은 그대로 두고, 7절 첫 글머리표("서버 nginx의 `i-eum.site`·…옮기는 작업이 필요하다.")를 지운다. 7절 끝에 8절을 더한다:

~~~~markdown
## 8. OpenVidu Meet·MinIO (사람이 실행, 수업 없는 시간에)

2026-09-27 점검에서 `openvidu-meet`(재시작 1,507회)과 `minio`(1,320회)가 S3 연결 실패로 재시작을 반복했다. 우리 음성은 LiveKit API를 직접 쓰므로 Meet이 필요 없다.

### 8.1 Meet 끄기

OpenVidu 3.8 단일 노드는 `/opt/openvidu/config/openvidu.env`의 `ENABLED_MODULES`로 선택 모듈(Community: `openviduMeet`, `observability`)을 켠다. `openviduMeet`만 빼고 재시작한다. **재시작 동안 진행 중인 수업 음성이 끊긴다.**

```bash
sudo cp /opt/openvidu/config/openvidu.env /opt/openvidu/config/openvidu.env.bak-$(date +%Y%m%d)
grep -n '^ENABLED_MODULES' /opt/openvidu/config/openvidu.env        # 예: ENABLED_MODULES=observability,openviduMeet
sudo sed -i -E '/^ENABLED_MODULES=/{s/(^|,)openviduMeet(,|$)/\1\2/; s/,,/,/; s/=,/=/; s/,$//}' /opt/openvidu/config/openvidu.env
grep -n '^ENABLED_MODULES' /opt/openvidu/config/openvidu.env        # openviduMeet이 빠졌는지 (observability는 남긴다)
sudo systemctl restart openvidu
```

확인:

```bash
docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'openvidu-meet|minio|openvidu$'   # openvidu-meet이 없어야 한다
docker logs openvidu --since 2m 2>&1 | grep "starting LiveKit server"                    # "nodeIP": "3.34.139.114" (3절 설정 유지)
```

이어서 5절 동작 확인(신호·음성)을 한다. 되돌리기: 백업한 `openvidu.env.bak-<날짜>`를 되돌려 놓고 `sudo systemctl restart openvidu`.

### 8.2 MinIO

MinIO는 선택 모듈이 아니라 기본 구성(녹화 등의 S3 저장소)이라 끄는 공식 설정이 없다. Meet을 끈 뒤에도 재시작이 이어지는지 먼저 본다:

```bash
docker inspect minio --format '{{.RestartCount}} {{.State.Status}}'
docker logs --tail 50 minio
```

재시작이 계속되면 로그의 원인(데이터 폴더 권한·자격 증명 등)을 기록해 팀과 조치를 정한다. 녹화·egress를 쓰지 않으므로 음성에는 영향이 없다.
~~~~

- [ ] **Step 8: 검사**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
grep -rn "j15b201" docs/infra
for f in docs/infra/*.md; do n=$(grep -c '^```' "$f"); [ $((n % 2)) -eq 0 ] || echo "홀수 펜스: $f"; done
grep -n "stage\|Redis" docs/infra/pipeline.md | head -20
git diff --stat
```

Expected: `j15b201`은 넘김 안내 줄(README 그림, architecture 글머리표, pipeline 7.5 아래 문장, deploy.md의 1절·4.2 추가 문단·10절)에만 있다 — 명령·URL 예시에는 없다. "홀수 펜스" 출력 없음. 바뀐 파일은 여섯 문서뿐(deploy.md는 Task 5에서 이미 커밋).

- [ ] **Step 9: 커밋**

```bash
git add docs/infra/README.md docs/infra/architecture.md docs/infra/docker.md docs/infra/jenkins.md docs/infra/pipeline.md docs/infra/openvidu.md
git commit -m "$(cat <<'EOF'
docs(infra): 인프라 문서를 i-eum.site 운영 구성에 맞춘다

Redis 단계·nginx 사이트 2개 동기화, OpenVidu nginx의 저장소 관리,
Meet 끄는 방법(사람이 실행)을 반영한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 최종 검증과 넘길 목록 (커밋 없음)

**Files:** 없음

- [ ] **Step 1: 브랜치 전체 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-infra
git log --oneline chore/deploy-rebuild..HEAD
git diff --stat chore/deploy-rebuild..HEAD
git diff --name-only chore/deploy-rebuild..HEAD | grep -E '(^|/)\.env|\.pem$' && echo "비밀 파일 포함!" || echo "비밀 파일 없음"
```

Expected: 커밋 6개(Task 1~6). 바뀐 파일은 Global Constraints의 목록과 정확히 같다. `비밀 파일 없음`.

- [ ] **Step 2: Task 1·2·4 검증을 한 번 더 돌린다**

Task 1 Step 1·4(compose 검사 → `COMPOSE OK`), Task 2 Step 2·5(nginx → `실패 0 건`), Task 4 Step 2(`SYNTAX OK`·`SHELLCHECK OK`), Task 3 Step 6(`PARSE OK`)을 다시 실행하고 결과를 보고에 붙인다. 임시 디렉터리는 지운다.

- [ ] **Step 3: 보고에 넣을 것**

- 확인한 참(검증 명령 출력 요약)
- 사람이 할 일: deploy.md 10절 1~7번, `deploy/.env.config.example`에 6.2.1절 블록 반영, `itda-env-config` 갱신
- 다른 계획 확인 요청: api 계획의 prod 프로필 Redis 설정(`REDIS_HOST`·`REDIS_PORT`) — 없으면 backend가 운영에서 기동 실패
- `chore/deploy-rebuild`로의 로컬 병합은 세 계획 조율자가 한다

---

## Self-Review

**Spec 대응:**

| spec | Task |
| --- | --- |
| §2 어긋난 곳 1(서버 nginx 도메인) | 2 |
| §2 어긋난 곳 2(j15b201 블록 없음) | 2 |
| §2 어긋난 곳 3(openvidu.conf 서버에만) | 2(파일), 3(Sync), 6(openvidu.md) |
| §2 어긋난 곳 4(backend env 5개) | 1(env_file), 5(6.2.1 키 목록) |
| §2 어긋난 곳 5(운영 Redis 없음) | 1(서비스), 3(Redis 단계) |
| §2 어긋난 곳 6(Meet·MinIO 재시작) | 6(openvidu.md 8절, 사람이 실행) |
| §2 `ddl-auto: validate` | 5(6.3절) |
| §3.1 compose 표·`up -d redis` 보장 | 1, 3 |
| §3.2 키 표(+`CARTESIA_API_KEY`), example은 사람이 | 5(6.2.1절 붙여 넣기 블록) |
| §3.3 DB 확인·UNIQUE | 5(6.3절) |
| §4 nginx 표·`/app/`·`client_max_body_size 11m`·openvidu.conf·Sync 백업/복원·downloads·certbot dry-run | 2, 3, 5(4.2·10절) |
| §5.4 릴리스 스크립트 1~7 + 실패 시 끝난 단계 출력 | 4 |
| §7 배포 순서(사전 확인·설정 준비·서버·앱) | 5(10절, 9.2절) |
| §8 저장소 검증(compose config 더미 env) | 1 (backend·front 검증은 다른 계획) |
| §8 서버 배포 후 1~8 | 5(10절 7번 — 사람이 확인) |
| §9 Meet·MinIO, env-config, downloads 권한 | 5(10절), 6(openvidu.md 8절) |

**계약에서 벗어난 곳(의도):** `location ^~ /app/ieum.apk` → `location = /app/ieum.apk` + 직전 버전 정규식. 공개 URL·헤더는 계약과 같고, `.tmp` 노출만 막는다. spec §3.2가 "선택"으로 적은 `AUTH_REDIS_KEY_PREFIX`는 `application.yml`에 기본값이 없고 prod에서 누락 시 기동 실패(api-spec 3.6.x)라 6.2.1절에서 필수로 적었다.

**Placeholder 점검:** 코드·설정 단계는 전체 내용을 담았다. 문서 단계는 바꿀 문장(원문→새 문장)과 새 절 전문을 담았다. `<host>`·`<pem>` 같은 꺾쇠는 사람이 채울 운영 값 자리이며 저장소에 값을 두지 않기 위한 것이다.

**이름 일관성:** `itda-redis`·`itda-redis-data`·`redis`(Task 1) = Task 3 `docker exec itda-redis`·`up -d redis` = Task 5·6 문서. `openvidu.conf` 서버 경로 `/etc/nginx/sites-available/openvidu.conf`(Task 2·3·5·6 동일). `release-app.sh` 환경변수 `GITLAB_TOKEN`·`IEUM_DEPLOY_KEY`·`IEUM_DEPLOY_HOST`(Task 4) = deploy.md 9.2절. 단계 이름 `Redis`(Task 3) = pipeline.md·docker.md.
