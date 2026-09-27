# 운영 배포 재구축 설계

작성일: 2026-09-27
대상: `deploy/`, `Jenkinsfile`, `scripts/`, `apps/backend`, `front/apps/web`, `front/apps/mobile`, `docs/infra/`
선행: 실시간 수업(양방향 음성·문제 출제) dev 병합(`f7c58e2c`)

기존 배포 파이프라인(Jenkins + blue-green)을 유지하고, 그동안 달라진 코드·서버와 어긋난 부분만 고친다.
학습자 앱은 서명한 APK를 GitLab Releases와 우리 서버 두 곳에 올려 배포한다.

## 1. 결정 사항 (사용자 확정)

| 질문 | 결정 |
| --- | --- |
| 서버 배포 방식 | A안 — 기존 Jenkins 흐름(`release/*`·`v*` → 빌드 → blue-green 교체, 롤백 작업) 유지, 빠진 부분만 수정 |
| DB | 외부 서버의 MariaDB. `DB_URL`로 연결 |
| Redis | EC2의 운영 compose에 추가 |
| 도메인 | `i-eum.site`로 통일. `j15b201.p.ssafy.io`는 `i-eum.site`로 넘김 |
| 앱 배포 | 서명한 릴리스 APK + 다운로드 페이지. APK는 PC에서 빌드 |
| APK 보관 | SSAFY GitLab Releases(기록·보관) + `i-eum.site/app`(학습자 다운로드) 두 곳 — GitLab 프로젝트가 비공개라 학습자가 로그인 없이 받을 수 없기 때문 |
| 음성 합성 키 | 백엔드가 대신 호출(a). APK에 키를 넣지 않는다 |

## 2. 현재 상태 (2026-09-27 서버 읽기 전용 점검)

- EC2: Ubuntu 24.04, 4코어, 메모리 15GB(가용 12GB), 디스크 309GB 중 39GB 사용 — Redis 추가에 여유 있음
- 서비스: `v0.0.4`(실시간 수업 이전 코드)가 blue-green으로 실행 중. 활성 web=blue(3001), backend=green(8082), ai=blue(8101). `https://i-eum.site` 200
- OpenVidu 3.8.0 전체 + Jenkins(`itda-jenkins:lts`)가 같은 서버에서 실행 중. OpenVidu는 자체 Redis(`redis` 컨테이너)를 쓴다
- 인증서(certbot): `i-eum.site`(+www), `openvidu.i-eum.site`, `j15b201.p.ssafy.io` — 12월 16~21일 만료
- ufw: 22, 80, 443/tcp, 9100(172.16.0.0/12), 7881/tcp, 5349/tcp, 443/udp, 50000:60000/udp
- 저장소와 어긋난 곳
  1. **서버 nginx `sites-available/itda`는 `i-eum.site`로 수정돼 있고 저장소 `deploy/nginx/itda.conf`는 `j15b201.p.ssafy.io`다.** Jenkins Sync 단계가 저장소 파일로 덮어쓰므로 지금 배포하면 `i-eum.site` 서비스 블록이 사라진다
  2. `j15b201.p.ssafy.io`를 받는 서버 블록이 없어 인증서 오류(curl 60)
  3. `sites-available/openvidu.conf`는 서버에만 있다
  4. 운영 compose가 backend에 `SPRING_PROFILES_ACTIVE`·`DB_*`·`AI_BASE_URL` 5개만 넘긴다. `application.yml`이 읽는 `AUTH_*`·`MAIL_*`·`LIVEKIT_*`·`REDIS_*`가 없어 지금 코드를 배포하면 backend가 뜨지 않는다(`LiveKitProperties`가 시작 시 예외)
  5. 운영 Redis 없음
  6. OpenVidu Meet(재시작 1,507회)·MinIO(1,320회)가 S3 연결 실패로 재시작 반복 — 우리 음성(LiveKit API 직접 사용)과 무관
- 운영 프로필은 `ddl-auto: validate` — 외부 DB 스키마가 엔티티와 다르면 backend가 뜨지 않는다

## 3. 서버 서비스와 설정

### 3.1 컨테이너 (`deploy/docker-compose.prod.yml`, 프로젝트 이름 `itda`)

| 서비스 | 변경 |
| --- | --- |
| web blue/green | 없음 |
| backend blue/green | `environment` 5개 대신 `env_file: .env.config`. `depends_on`은 두지 않는다(blue-green은 서비스 단위로 기동) |
| ai blue/green | 없음 |
| **`redis`(신규, 컨테이너 이름 `itda-redis`)** | `redis:8.2.9-alpine`(로컬과 동일 버전), `restart: unless-stopped`, 볼륨 `itda-redis-data:/data`, 포트 바인딩 없음(compose 기본 네트워크로만 접근), AOF 켬(`--appendonly yes`). OpenVidu의 `redis` 컨테이너와 이름·네트워크가 겹치지 않는다 |

- backend는 `REDIS_HOST=redis`(compose 서비스 이름)로 접근한다.
- `scripts/deploy.sh`는 서비스 단위로 올리므로 `redis`는 Deploy 단계 전에 `docker compose up -d redis`로 한 번 보장한다(이미 떠 있으면 아무 일 없음). blue-green 교체 대상이 아니다.

### 3.2 설정 (`.env.config` = Jenkins Secret file `itda-env-config`)

값은 사람만 다룬다. 저장소에는 키 이름만(`deploy/.env.config.example`) 둔다.

| 구분 | 키 |
| --- | --- |
| compose 치환 | `REGISTRY_PREFIX`(선택) |
| backend 공통 | `SPRING_PROFILES_ACTIVE=prod`, `AI_BASE_URL`(현 값 유지) |
| DB | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |
| Redis | `REDIS_HOST=redis`, `REDIS_PORT=6379`, `AUTH_REDIS_KEY_PREFIX`(선택) |
| 인증 토큰 | `AUTH_JWT_ISSUER`, `AUTH_ACCESS_ACTIVE_KID`·`AUTH_ACCESS_ACTIVE_KEY`·`AUTH_ACCESS_RETIRED_KEYS`, `AUTH_REFRESH_*` 같은 3종, `AUTH_ACTION_*` 같은 3종 — **운영용으로 새로 생성**(로컬 키 재사용 금지). 생성 명령은 문서에 적는다 |
| 메일 | `MAIL_DISPATCH_ENABLED`, `MAIL_SMTP_HOST`·`PORT`·`USERNAME`·`PASSWORD`·`SECURITY`, `MAIL_FROM_ADDRESS`·`MAIL_FROM_NAME`, `MAIL_REPLY_TO`, `MAIL_LINK_EMAIL_VERIFICATION_URL=https://i-eum.site/auth/verify-email`, `MAIL_LINK_PASSWORD_RESET_URL=https://i-eum.site/auth/reset-password` |
| 음성 | `LIVEKIT_URL=https://openvidu.i-eum.site`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` |
| 음성 합성(신규) | `CARTESIA_API_KEY` |

- `.env.config.example`·`.env.example`은 비밀 파일 규칙상 에이전트가 읽거나 고치지 않는다 — 추가할 키 목록을 사람이 반영한다.

### 3.3 DB 스키마

- 첫 배포 전에 외부 DB가 현재 엔티티와 맞는지 확인한다: 로컬에서 운영 DB를 대상으로 `validate`만 수행하는 절차(읽기 전용 계정 권장)를 `docs/infra/deploy.md`에 적는다.
- 함께 확인: `lecture_problems (lecture_id, problem_id)`, `problem_attempts (lecture_id, user_id, problem_id)` UNIQUE 존재 — `SHOW CREATE TABLE`.
- 스키마 변경 도구(Flyway 등) 도입은 범위 밖.

## 4. nginx·도메인·인증서

| 주소 | 처리 |
| --- | --- |
| `https://i-eum.site`, `www.i-eum.site` | `/` web, `/api/` backend, `/jenkins/` Jenkins, **`/app/` 앱 배포 파일(신규)** |
| `https://openvidu.i-eum.site` | `127.0.0.1:7880`(서버 현재 설정 그대로) |
| `j15b201.p.ssafy.io` (80·443) | **신규:** `https://i-eum.site$request_uri`로 301. 인증서는 서버에 있음 |
| `turn.i-eum.site:5349` | nginx를 거치지 않음(OpenVidu가 직접). 문서만 |

- `deploy/nginx/itda.conf`를 서버 현재 내용(`i-eum.site`, 인증서 경로 `/etc/letsencrypt/live/i-eum.site/`)으로 맞추고 `j15b201` 넘김 블록을 더한다.
- `/app/` location: `alias /home/ubuntu/app/downloads/;` 정적 제공. `latest.json`은 `Cache-Control: no-cache`, `ieum.apk`는 `no-cache` + `Content-Type: application/vnd.android.package-archive`.
- `/api/` location에 `client_max_body_size 10m`(목소리 샘플 업로드).
- `deploy/nginx/openvidu.conf`: 서버 파일을 그대로 저장소에 둔다.
- `Jenkinsfile` Sync 단계: `itda.conf`·`openvidu.conf`·`connection-upgrade.conf`를 복사하기 전에 두 사이트 파일을 모두 백업하고, `nginx -t` 실패 시 모두 되돌린다. `downloads/` 디렉터리를 없으면 만든다(내용은 건드리지 않는다).
- 인증서: 첫 배포 때 `sudo certbot renew --dry-run`으로 자동 갱신만 확인한다.
- CORS·AI 내부 리스너(9100)·웹 컨테이너 nginx는 바꾸지 않는다.

## 5. 앱 배포

### 5.1 버전·태그
- `app.json`의 `version`(예: `1.0.0`)과 `android.versionCode`(정수, 릴리스마다 +1)를 함께 올린다.
- 태그 `app-v<version>`(브랜치 전략에 이미 있는 규칙). 서버 배포 Jenkinsfile은 이 태그를 무시한다(현행 동작 유지).

### 5.2 서명
- 릴리스 keystore를 한 번 만들어 저장소 밖에 보관·백업한다(잃으면 기존 설치 앱에 업데이트 불가).
- 경로·별칭·비밀번호는 PC `~/.gradle/gradle.properties`의 `IEUM_UPLOAD_STORE_FILE`·`IEUM_UPLOAD_KEY_ALIAS`·`IEUM_UPLOAD_STORE_PASSWORD`·`IEUM_UPLOAD_KEY_PASSWORD`.
- `front/apps/mobile/plugins/withReleaseSigning.js`(config plugin): prebuild 때 `android/app/build.gradle`에 release `signingConfig`를 넣는다. 속성이 없으면 release 빌드를 실패시킨다(디버그 키로 서명된 APK가 나가지 않게).

### 5.3 운영 설정
- `front/apps/mobile/.env.production`(git 제외, 사람이 작성): `EXPO_PUBLIC_API_URL=https://i-eum.site/api/v1`, `EXPO_PUBLIC_APP_RELEASE_URL=https://i-eum.site/app`.
- `EXPO_PUBLIC_CARTESIA_API_KEY`는 없앤다(6장).

### 5.4 릴리스 스크립트 `scripts/release-app.sh` (PC Git Bash)
1. 작업 트리가 깨끗하고 HEAD에 `app-v<version>` 태그가 있으며 `app.json` 버전과 같은지 확인
2. `pnpm --filter @ieum/mobile test`·`typecheck`
3. `NODE_ENV=production npx expo prebuild -p android --clean` → `android/gradlew assembleRelease`
4. 산출물을 `ieum.apk`로 복사, SHA-256 계산
5. `latest.json` 생성: `{"version","versionCode","releasedAt","sha256","url":"https://i-eum.site/app/ieum.apk","notes"}`
6. GitLab Release 생성: GitLab API(`POST /projects/:id/releases` + 패키지 업로드)로 태그에 APK·SHA-256 첨부. 토큰은 환경변수 `GITLAB_TOKEN`(개인 액세스 토큰, 저장소에 두지 않음)
7. 서버 업로드: `scp -i <pem>`으로 `ieum.apk.tmp`·`latest.json.tmp`를 `/home/ubuntu/app/downloads/`에 올린 뒤 `mv`로 교체(받는 도중 파일이 바뀌지 않게). 이전 APK는 `ieum-<version>.apk`로 하나만 남긴다
- 스크립트는 실패하면 중간 단계에서 멈추고 무엇이 끝났는지 출력한다.

### 5.5 다운로드 페이지 `https://i-eum.site/app` (강사 웹 안, 로그인 불필요)
- `/app/latest.json`을 읽어 버전·배포일을 보여 준다(없거나 실패하면 "준비 중이에요").
- 큰 "이음 앱 내려받기" 버튼 → `/app/ieum.apk`.
- 설치 안내(번호 목록, 짧은 문장): 알 수 없는 앱 설치 허용 → Play 프로텍트 경고 대처 → 설치 후 첫 실행.
- 이 페이지 주소를 가리키는 QR 이미지(정적) — 강사가 수업 화면에 띄워 학생 폰으로 찍게 한다.
- 라우팅: `/app`은 SPA 라우트, `/app/*.apk`·`/app/latest.json`은 호스트 nginx `alias`가 먼저 처리한다.

### 5.6 앱의 새 버전 알림
- 앱을 켤 때 `EXPO_PUBLIC_APP_RELEASE_URL/latest.json`을 읽어 `versionCode`를 설치된 값(`expo-application`의 빌드 번호)과 비교한다.
- 새 버전이면 "새 버전이 있어요. 설치 페이지를 열까요?"를 낭독하고, 확인하면 다운로드 페이지를 연다. 같은 버전은 하루에 한 번만 알린다.
- 네트워크 실패·형식 오류는 조용히 넘어간다.

## 6. 음성 합성 대리 호출 (backend)

| method·path | 권한 | 동작 |
| --- | --- | --- |
| `POST /api/v1/voice/clones` | LEARNER | multipart WAV(최대 10MB) → Cartesia `/voices/clone` → `{"voiceId"}` |
| `POST /api/v1/voice/speech` | LEARNER | `{"voiceId","text"}`(text 1~300자) → Cartesia `/tts/bytes` → `audio/mpeg` |

- 키 `CARTESIA_API_KEY`는 backend 설정에만. 없으면 두 API가 503 `VOICE_SYNTHESIS_UNAVAILABLE`(앱은 기존처럼 "목소리 기능을 쓸 수 없어요" 안내).
- 사용량 제한: 학습자별 1분 30회(Redis 카운터). 초과 429 `VOICE_RATE_LIMITED`.
- Cartesia 오류는 502 `VOICE_SYNTHESIS_FAILED`로 감싸고 원문을 응답에 넣지 않는다.
- 요청 본문·모델 설정(`ttsRequestBody`)은 앱 코드의 현재 값을 backend로 옮긴다.
- 앱: `services/cartesia.ts`의 호출 대상을 이 두 API로 바꾸고 로컬 캐시(`cartesia-tts`)는 유지한다.
- 명세: `docs/product/api-spec.md`에 두 API와 오류 코드를 추가한다.

## 7. 배포 순서

0. **사전 확인(읽기 전용)**: 외부 DB `validate`·UNIQUE 확인, `certbot renew --dry-run`
1. **저장소 수정** — 브랜치 `chore/deploy-rebuild` 하나, dev로 MR 한 번
   - 3.1 compose, 4 nginx·Jenkinsfile Sync, 5 앱 서명 플러그인·릴리스 스크립트·다운로드 페이지·새 버전 알림, 6 음성 합성 대리 호출, `docs/infra/*` 갱신
2. **설정 준비(사람)**: `itda-env-config`에 3.2 키 추가, `deploy/.env.config.example`에 키 이름 추가, PC에 keystore·`gradle.properties`·`.env.production`, `GITLAB_TOKEN`
3. **서버 배포**: `release/0.1.0` push → rc 이미지 → blue-green 교체. 확인 후 `v0.1.0` 태그
4. **앱 배포**: `app-v1.0.0` 태그 → `release-app.sh` → GitLab Release·`i-eum.site/app` 확인 → 폰 설치

## 8. 완료 기준

- 저장소 검증: backend `./gradlew test spotlessCheck`, `docker build --target test front`, `docker compose -f deploy/docker-compose.prod.yml config`(치환 확인, 더미 env 파일로)
- 서버 배포 후
  1. backend 헬스체크 200, `https://i-eum.site` 로그인 화면
  2. 회원가입 → 인증 메일 도착 → 링크가 `i-eum.site`로 열림
  3. 로그아웃·재로그인, 토큰 갱신(Redis)
  4. 강사 수업 시작 → 학생 코드 입장 → 양방향 음성·손들기
  5. 문제 추가 → 출제 → 학생 제출 → 학생 카드 갱신
  6. 목소리 복제·합성이 백엔드를 거쳐 동작, APK에 Cartesia 키가 없음(`strings`로 확인)
  7. `j15b201.p.ssafy.io` → `i-eum.site` 301
  8. `nginx -t` 통과 상태로 `openvidu.i-eum.site` 유지
- 앱: `i-eum.site/app`에서 설치 → 로그인 → 수업 입장. 다음 버전 올릴 때 새 버전 알림 낭독
- 되돌리기: 이전 색으로 nginx 전환(이전 버전 `v0.0.4`는 반대 색에 남아 있음), 또는 `itda-rollback`으로 이전 태그 재배포

## 9. 서버에서 사람이 할 일 (에이전트는 서버 설정을 바꾸지 않는다)

- OpenVidu Meet·MinIO 재시작 반복: 사용하지 않으므로 끄기를 권장. `/opt/openvidu` 설정(서비스 비활성화)과 명령은 계획에서 확인해 사람이 실행한다.
- `itda-env-config` 갱신, 첫 배포 전 `downloads/` 권한 확인.

## 10. 범위 밖

- iOS 배포, Google Play 등록, 앱 자동 업데이트(OTA)
- 앱 빌드 자동화(Jenkins에서 APK 빌드)
- 스키마 마이그레이션 도구, 외부 DB 백업 정책
- 저장소 루트의 옛 복사본(`backend/`·`web/`·`ai/`·`mobile/`, `apps/mobile`) 정리 — 별도 작업
- 모니터링(Grafana·Prometheus는 OpenVidu 소속으로 이미 실행 중)
