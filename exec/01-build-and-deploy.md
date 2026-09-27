# 1. 빌드 및 배포 문서

GitLab 소스 클론 이후 각 서비스를 빌드·실행·배포하는 절차. 기준 브랜치 `release/0.1.0`.

## 1.1 저장소 구조

| 경로 | 역할 | 배포 형태 |
| --- | --- | --- |
| `apps/backend` | Spring Boot API 서버 (`/api/v1`) | Docker 이미지 `itda-backend`, blue/green |
| `apps/ai` | FastAPI — 규칙 점역기·오답 분석·맞춤 복습 (`/api/v1`) | Docker 이미지 `itda-ai`, blue/green |
| `front/apps/web` | 강사 웹 (React + Vite) — pnpm 워크스페이스 `@ieum/web` | Docker 이미지 `itda-web` (nginx 정적) |
| `front/apps/mobile` | 학습자 앱 (Expo / React Native, Android) — `@ieum/mobile` | APK (GitLab Releases + `https://i-eum.site/app`) |
| `front/packages/*` | 공용 패키지 `@ieum/domain`(점자 셀 로직) `@ieum/contracts`(zod 스키마) `@ieum/design-tokens` `@ieum/itda`(음성 비서 NLU·캡슐) | 워크스페이스 링크 |
| `iot/braille_solenoid` | Arduino 6점 촉각 모듈 펌웨어 | Arduino IDE로 업로드 |
| `deploy/` | 운영 compose·nginx·Jenkins 정의 | Sync 스테이지가 서버로 동기화 |
| `scripts/` | `deploy.sh`(blue/green 교체) `release-app.sh`(APK 릴리스) 검증 훅 | — |
| `docker-compose.yml` | 로컬 개발 스택 (web·backend·ai·db·redis) | — |

## 1.2 사용한 제품·버전

### 서버

| 구분 | 제품 | 버전 | 비고 |
| --- | --- | --- | --- |
| JVM | Eclipse Temurin (OpenJDK) | **25** | 빌드 `eclipse-temurin:25-jdk-alpine`, 실행 `25-jre-alpine`. Gradle toolchain `languageVersion = 25` |
| 프레임워크 | Spring Boot | **4.0.8** | Spring Data JPA(Hibernate), Spring Security, Validation |
| 빌드 | Gradle (wrapper) | **9.1.0** | `apps/backend/gradlew` |
| WAS | 내장 Tomcat (Spring Boot 기본) | Boot 4.0.8 동봉 | 포트 8080, context path `/api/v1` |
| DB | MariaDB | **11.8.9** | JDBC `jdbc:mariadb://…?connectionTimeZone=UTC` |
| 캐시·세션 | Redis | **8.2.9** (alpine) | 인증 토큰 상태 (`AUTH_REDIS_KEY_PREFIX`로 환경 분리) |
| AI 런타임 | Python | **3.12** (`python:3.12-slim`) | |
| AI 프레임워크 | FastAPI / Uvicorn / Pydantic | ≥0.115 / ≥0.30 / 2.x | `apps/ai/requirements.txt` |
| 웹서버(리버스 프록시) | nginx | 서버 패키지(stable) | `deploy/nginx/itda.conf`, `openvidu.conf`, `inc/*.inc` |
| 웹 정적 서빙 | nginx | `nginx:stable-alpine` | `front/Dockerfile` `web` 스테이지 |
| 컨테이너 | Docker / Docker Compose | 29.x / v5.x | 서버·로컬 동일 |
| CI/CD | Jenkins (Docker) | `deploy/jenkins/Dockerfile` | 잡 `itda-mr-verify`, `itda-deploy`, `itda-rollback` |
| 실시간 음성 | OpenVidu Community (single node) = LiveKit | 최신 설치 스크립트 | `openvidu.i-eum.site`, LiveKit :7880 |
| 인증서 | Let's Encrypt (certbot) | — | `/etc/letsencrypt/live/i-eum.site/` |
| 호스트 | AWS EC2 (SSAFY 제공) Ubuntu | `j15b201.p.ssafy.io` → `i-eum.site` | |

### 프론트·앱

| 구분 | 제품 | 버전 |
| --- | --- | --- |
| Node | Node.js | **22** (`node:22-alpine`) |
| 패키지 관리 | pnpm (corepack) | **10.x** — `node-linker=hoisted` 워크스페이스 |
| 웹 | React / React DOM | 19.2.3 |
| 웹 | Vite / TypeScript | 6.3 / 5.8 |
| 웹 | react-router-dom / livekit-client | 7.6 / 2.22.3 |
| 앱 | Expo SDK | **57** (`expo@57.0.24`), expo-router 57, New Architecture 켜짐, Hermes |
| 앱 | React Native | **0.86.3** |
| 앱 | @livekit/react-native / react-native-webrtc | 3.0.0 / 144.2.0 |
| 앱 | expo-audio, expo-speech-recognition, expo-speech, zustand, @tanstack/react-query | 57.x / 57.x / 57.x / 5.x / 5.x |
| Android | compileSdk / targetSdk / minSdk | 36 / 36 / 24 |
| Android | Build Tools / NDK / Kotlin / AGP Gradle | 36.0.0 / 27.1.12297006 / 2.1.20 / Gradle 9.3.1 |
| Android | 로컬 Expo 모듈 `modules/itda-tactile` | Bluetooth Classic SPP (HC-06) — Kotlin |

### IDE·개발 도구 (개발 시 사용)

| 도구 | 버전 | 용도 |
| --- | --- | --- |
| Android Studio | 2026.1 (build AI-261.25134.95) — JBR(JDK 21) 내장 | 앱 네이티브 빌드·디버그. Gradle JDK는 **Embedded JDK** 선택 |
| Visual Studio Code | 1.138 | 웹·앱·문서 |
| IntelliJ IDEA / 기타 | 무관 | 백엔드는 Gradle wrapper만 있으면 IDE에 의존하지 않음 |
| Arduino IDE | 2.x | `iot/braille_solenoid/braille_solenoid.ino` 업로드 (보드: Arduino Uno 계열, 9600bps) |
| Docker Desktop | 29.x | 로컬 스택 |
| Git Bash | — | `scripts/*.sh` 실행 |

## 1.3 로컬 빌드·실행 (클론 직후)

### 준비

```bash
git clone https://lab.ssafy.com/s15-bigdata-recom-sub1/S15P21B201.git
cd S15P21B201
cp .env.example .env                         # 루트 — backend·compose (env/root.env.example 참고)
cp front/apps/mobile/.env.example front/apps/mobile/.env   # 앱 (env/mobile.env.example 참고)
```

`.env`의 필수 키를 채운다(1.4절). 서명키(`AUTH_*_ACTIVE_KEY`)는 `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='`로 만든다(Base64URL 32바이트).

### 서버 스택 (Docker Compose 한 번에)

```bash
docker compose up -d db redis backend ai web
docker compose ps                            # db·redis healthy, backend/ai/web Up
curl 'http://127.0.0.1:8080/api/v1/username-availability?username=probe'   # backend
curl  http://127.0.0.1:8000/api/v1/health                                   # ai
```

| 서비스 | 로컬 포트 | 프로필·모드 |
| --- | --- | --- |
| web | 5173 | Vite dev server |
| backend | 8080 | `SPRING_PROFILES_ACTIVE=local` — compose의 `db`(MariaDB)·`redis` 사용, `ddl-auto: update`(테이블 자동 생성), bootRun + devtools |
| ai | 8000 | uvicorn `--reload` |
| db | 3306 | `mariadb:11.8.9`, 볼륨 `mariadb-data` |
| redis | 6379 | |

호스트 포트가 겹치면(예: PC의 MySQL 서비스가 3306, 다른 컨테이너가 8080) `docker-compose.local.yml` 같은 **개인 오버라이드**로 포트만 바꿔 `-f docker-compose.yml -f docker-compose.local.yml`로 띄운다(gitignore 대상).

### 개별 빌드

```bash
# backend
cd apps/backend && ./gradlew test && ./gradlew bootJar     # build/libs/*.jar
# ai
cd apps/ai && python -m venv .venv && .venv/Scripts/activate && pip install -r requirements.txt && pytest
uvicorn app.main:app --reload --port 8000
# front (web + mobile 공통)
cd front && corepack pnpm@10 install --frozen-lockfile && pnpm -r typecheck && pnpm -r test
pnpm --filter @ieum/web build                               # front/apps/web/dist
# CI와 같은 통합 검증
docker build --target test apps/backend ; docker build --target test apps/ai ; docker build --target test front
```

### 학습자 앱(APK)

```bash
cd front/apps/mobile
npx expo prebuild --platform android --clean                # android/ 생성 (CNG — android/는 git 제외)
cd android && ./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a
# app/build/outputs/apk/release/app-release.apk
```

- Android Studio에서는 `front/apps/mobile/android`를 열고 Gradle JDK를 Embedded JDK로 둔다.
- 릴리스 서명: `~/.gradle/gradle.properties`에 `IEUM_UPLOAD_STORE_FILE`, `IEUM_UPLOAD_KEY_ALIAS`, `IEUM_UPLOAD_STORE_PASSWORD`, `IEUM_UPLOAD_KEY_PASSWORD` (`plugins/withReleaseSigning.js`가 읽음, 없으면 릴리스 빌드 실패 — 디버그 키 서명 방지).
- 디버그 빌드는 Metro가 필요하다: `npx expo start --dev-client` + 실기기 `adb reverse tcp:8081 tcp:8081`.
- **윈도우 주의**: SDK의 ninja 1.10.2가 260자 경로를 못 열어 C++ 코드젠 빌드가 실패한다. `pip install ninja` 후 `~/.gradle/gradle.properties`에 `ieum.ninja=<경로>/Scripts/ninja.exe`를 두면 `plugins/withCmakeNinja.js`가 CMake에 물린다. 상세: `docs/solutions/2026-09-24-windows-ninja-long-path.md`.
- 촉각 모듈 펌웨어: Arduino IDE로 `iot/braille_solenoid/braille_solenoid.ino` 업로드. 프로토콜·배선은 `iot/braille_solenoid/README.md`.

## 1.4 빌드·실행 시 환경변수

값의 형식·필수 여부는 `env/` 템플릿과 아래 표를 따른다. 값은 저장소에 두지 않는다.

### backend (루트 `.env` → compose `env_file`, 운영은 Jenkins Secret file `.env.config`)

| 구분 | 키 | 형식·값 | 필수 |
| --- | --- | --- | --- |
| 프로필 | `SPRING_PROFILES_ACTIVE` | `local` / `prod` — prod에서 빠지면 local로 떠서 실패 | 필수 |
| DB(local) | `DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` `DB_PASSWORD` | compose가 `DB_HOST=db` 주입, 기본 `itda`/`itda` | 필수 |
| DB(prod) | `DB_URL` `DB_USERNAME` `DB_PASSWORD` | `jdbc:mariadb://<host>:<port>/<db>?connectionTimeZone=UTC` | 필수 |
| Redis | `REDIS_HOST` `REDIS_PORT` `AUTH_REDIS_KEY_PREFIX` | compose `redis`/6379, prefix는 `local`·`prod`(프로필과 일치해야 기동) | 필수 |
| JWT | `AUTH_JWT_ISSUER` | `itda-backend-local` / `itda-backend-prod` | 필수 |
| JWT | `AUTH_ACCESS_ACTIVE_KID` `AUTH_ACCESS_ACTIVE_KEY` (+`_RETIRED_KEYS`) | kid 1~64자, key Base64URL **256비트 이상**(HmacSHA256) | 필수(retired 선택) |
| JWT | `AUTH_REFRESH_ACTIVE_KID/KEY` `AUTH_ACTION_ACTIVE_KID/KEY` (+retired) | 위와 같은 형식, 링마다 별도 키. kid는 세 링을 통틀어 유일 | 필수 |
| AI | `AI_BASE_URL` | local: compose가 `http://ai:8000/api/v1` 주입 / prod: `http://host.docker.internal:9100/api/v1` | 필수 |
| 메일 | `MAIL_DISPATCH_ENABLED` | `true`/`false` — false면 outbox에 쌓이기만 하고 발송 없음 | 필수 |
| 메일 | `MAIL_SMTP_HOST` `MAIL_SMTP_PORT` `MAIL_SMTP_SECURITY` | 예: `smtp.naver.com` `465` `SSL` (`SSL`·`STARTTLS`·`PLAINTEXT`) | 필수 |
| 메일 | `MAIL_SMTP_USERNAME` `MAIL_SMTP_PASSWORD` | 제공자 계정(앱 비밀번호) — SSL/STARTTLS면 필수 | 조건부 |
| 메일 | `MAIL_FROM_ADDRESS` `MAIL_FROM_NAME` `MAIL_REPLY_TO` | 발신 주소 필수, 이름·회신 선택(이름은 줄을 빼면 기본 `이음(I:EUM)`) | 필수/선택 |
| 메일 링크 | `MAIL_LINK_EMAIL_VERIFICATION_URL` `MAIL_LINK_PASSWORD_RESET_URL` | 절대 https URL(쿼리·프래그먼트 없음), http는 루프백만 — prod `https://i-eum.site/auth/verify-email`, `/auth/reset-password` | 필수 |
| 실시간 음성 | `LIVEKIT_URL` `LIVEKIT_API_KEY` `LIVEKIT_API_SECRET` | `https://openvidu.i-eum.site` (https — 클라이언트용 wss는 코드가 변환) + OpenVidu 설치 시 키·시크릿 | 필수 |
| 음성 합성 | `CARTESIA_API_KEY` | Cartesia 키. 비우면 목소리 API만 503 | 선택 |
| compose | `REGISTRY_PREFIX` | 빈 값(로컬 이미지) | 선택 |
| 배포 태그 | `WEB_TAG` `BACKEND_TAG` `AI_TAG` | `.env.tags` — deploy.sh만 쓴다(사람이 편집 금지) | 자동 |

### ai (`apps/ai/.env`, 접두사 `ITDA_`)

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `ITDA_DEBUG` | `True` | 운영 `false` |
| `ITDA_APP_NAME` `ITDA_API_PREFIX` | `itda-ai` `/api/v1` | 보통 그대로 |
| `ITDA_ENGINE_VERSION` `ITDA_RULE_VERSION` | `kbraille-2024-r18-v1` `KBR-2024.1` | 규칙 점역기 버전 표기 |

### web

환경변수 없음 — API는 같은 오리진의 `/api/v1`로 상대 호출하고 nginx가 backend로 넘긴다.

### mobile (`front/apps/mobile/.env` 개발용, `.env.production` 릴리스 빌드용 — `EXPO_PUBLIC_*`는 번들에 인라인됨)

| 키 | 개발(.env) | 릴리스(.env.production) |
| --- | --- | --- |
| `EXPO_PUBLIC_API_URL` | `http://localhost:8080/api/v1`(USB + `adb reverse tcp:8080`) 또는 PC LAN IP | `https://i-eum.site/api/v1` |
| `EXPO_PUBLIC_APP_RELEASE_URL` | — | `https://i-eum.site/app` (새 버전 알림) |
| `EXPO_PUBLIC_CARTESIA_API_KEY` | 개발 편의용(선택) | **넣지 않는다** — 릴리스는 backend가 대신 호출 |

빌드 도구 속성(`~/.gradle/gradle.properties`, 저장소 밖): `IEUM_UPLOAD_*` 서명 4개, 윈도우 `ieum.ninja`.

## 1.5 배포 (운영)

### 흐름

```
release/X.Y.Z push 또는 vX.Y.Z 태그 (GitLab 웹) → Webhook → Jenkins itda-deploy
  Plan(변경 서비스·태그 결정) → Sync(compose·nginx·scripts·.env.config 서버 동기화)
  → Build(이미지 = 롤백 지점) → Redis(itda-redis 보장) → Deploy(서비스별 deploy.sh, blue/green) → Cleanup(rc·v* 각 3개 보존)
```

- `release/*` push → **변경된 서비스만** `X.Y.Z-rc.<빌드>-<sha>`로 검증 배포. `vX.Y.Z` 태그 → **세 서비스 전부** 태그명으로 릴리스 배포. dev/main 병합은 배포하지 않는다.
- blue/green: `deploy/docker-compose.prod.yml`에 web(3001/3002)·backend(8081/8082)·ai 슬롯 쌍. `scripts/deploy.sh <service> <tag>`가 대기 슬롯을 띄우고 헬스 확인 후 `deploy/nginx/inc/<svc>.inc`의 포트를 바꿔 `nginx reload`. 이전 슬롯은 롤백 대기.
- nginx: `i-eum.site`/`www` 443 → web·backend(`/api/`)·ai, `:9100` → ai(backend가 `host.docker.internal:9100`으로 호출), `openvidu.i-eum.site` → LiveKit 7880. 인증서 Let's Encrypt.
- 롤백: Jenkins `itda-rollback` (SERVICE, TAG) 1~2분. Jenkins 장애 시 서버에서 `/home/ubuntu/app/scripts/deploy.sh backend <태그>` 직접 실행.
- 운영 설정 변경: 서버 파일이 아니라 Jenkins Secret file `itda-env-config` 교체 → 현재 태그로 재배포(무중단 재기동). 서버의 `.env.config`를 손대면 다음 배포에 덮인다.
- APK: 서버 파이프라인과 별개. `app.json`의 `version`·`versionCode` 올린 커밋에 `app-v<version>` 태그 → PC에서 `scripts/release-app.sh "<변경 내용>"` (테스트→prebuild→assembleRelease→서명 확인→SHA-256→GitLab Release→서버 `/home/ubuntu/app/downloads/ieum.apk` 교체). 학습자는 `https://i-eum.site/app`에서 받는다.

### 배포 시 특이사항

1. **prod 프로필은 `ddl-auto: validate`** — 스키마 변경은 마이그레이션(덤프)으로 먼저 반영해야 기동한다. local은 `update`.
2. `AUTH_REDIS_KEY_PREFIX`·`AUTH_JWT_ISSUER`가 프로필과 다르면 기동 거부(다른 환경의 토큰 상태를 읽는 사고 방지).
3. `LIVEKIT_URL`은 **https**로 둔다. 앱에 주는 접속 주소(wss)는 코드가 변환한다.
4. 메일 발송을 켜려면(`MAIL_DISPATCH_ENABLED=true`) SMTP 계정·링크 URL 전부가 유효해야 기동한다. 꺼져 있으면 가입 인증 메일이 나가지 않으므로 로컬에서는 `users.email_verified_at`을 직접 채워 로그인한다(03-db-dump 참고).
5. `release/*`·`main`·`dev`는 보호 브랜치 — 직접 push 불가, 모든 변경은 MR. `release/*` 생성은 GitLab 웹에서만.
6. 학습자 앱은 서명 keystore를 잃으면 기존 설치본을 업데이트할 수 없다 — keystore는 저장소 밖에 백업.
7. HC-06 촉각 모듈은 동시 연결 1개. PC 시리얼 터미널이 붙어 있으면 앱이 연결하지 못한다. 폰에 먼저 페어링(PIN `0713`)해 둔다.
8. 서버 nginx·compose를 직접 고치지 않는다 — 원본은 repo, Sync가 덮는다. `docker image prune -a` 금지(롤백 지점 소실).

## 1.6 계정·프로퍼티가 정의된 파일 목록

| 파일 | 내용 | 저장 위치 |
| --- | --- | --- |
| `/.env` (템플릿 `/.env.example`) | backend·compose 로컬 환경변수 (DB 계정, JWT 키, LiveKit, 메일, Cartesia) | 각 개발자 PC — git 제외 |
| `deploy/.env.config` (템플릿 `deploy/.env.config.example`) | 운영 환경변수 — 위 키의 prod 값 | Jenkins Secret file `itda-env-config` → 서버 `/home/ubuntu/app/.env.config` |
| `deploy/.env.tags` (템플릿 `.env.tags.example`) | 서비스별 현재 배포 태그 | 서버 — deploy.sh가 갱신 |
| `deploy/jenkins/.env` (템플릿 `.env.example`) | Jenkins 컨테이너 설정(`DOCKER_GID` 등) | 서버 |
| `front/apps/mobile/.env`, `.env.production` (템플릿 `.env.example`) | 앱 API 주소·릴리스 URL | 개발자 PC — git 제외 |
| `apps/ai/.env` | `ITDA_*` | 개발자 PC / 서버 compose |
| `~/.gradle/gradle.properties` | APK 서명 `IEUM_UPLOAD_*`, 윈도우 `ieum.ninja` | 릴리스 담당자 PC |
| `apps/backend/src/main/resources/application.yml` | 프로필별 프로퍼티 정의(값은 `${ENV}` 참조) | 저장소 |
| `apps/ai/app/core/config.py` | AI 설정 스키마(`ITDA_` 접두사) | 저장소 |
| `docker-compose.yml`, `deploy/docker-compose.prod.yml` | 서비스·포트·env 주입 정의 | 저장소 |
| `deploy/nginx/*.conf`, `inc/*.inc` | 도메인·업스트림·활성 슬롯 | 저장소(inc는 서버가 원본) |
| Jenkins Credentials | `gitlab-api-token`, `gitlab-api-token-text`, `gms-api-key`, `ec2-deploy-key`, `itda-env-config` | Jenkins |
| `/opt/openvidu/config/openvidu.env` | LiveKit API 키·시크릿 원본 | OpenVidu 서버 |
| 릴리스 keystore `ieum-upload.jks` | APK 서명 | 릴리스 담당자 PC(저장소 밖) + 백업 |

DB 스키마·계정은 [03-db-dump](./03-db-dump/README.md), 외부 서비스 계정은 [02-external-services.md](./02-external-services.md).
