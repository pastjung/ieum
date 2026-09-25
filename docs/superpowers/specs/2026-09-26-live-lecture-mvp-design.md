# 실시간 수업 MVP 설계

작성일: 2026-09-26
대상: `apps/backend`, `front/apps/web`(신규 위치), `front/apps/mobile`, `docs/product/api-spec.md`

강사가 웹에서 수업을 만들고 시작하면, 학습자가 앱에서 6자리 코드로 입장해 **강사 음성을 듣는다.**
강사가 종료하면 학습자 앱은 종료 화면으로 간다. 이 한 바퀴를 실제로 돌리는 것이 MVP다.
문제 출제·답안·통계·리포트는 화면만 두고 목 데이터로 채운다.

## 1. 전제 (확인된 사실)

- EC2에 **OpenVidu 3.8.0**(LiveKit 기반, 단일 노드)이 떠 있다. 호스트 nginx가
  `openvidu.i-eum.site` 의 443을 받아 caddy(`127.0.0.1:7880`)로 넘긴다.
  `curl -sI https://openvidu.i-eum.site` → `302 /meet/` 로 외부 응답을 확인했다.
- 미디어는 livekit-server가 직접 받는다 — TCP 7881, UDP 50000-60000, TURN `turn.i-eum.site`(UDP 443, TLS 5349).
  보안 그룹 개방 여부는 E2E(음성이 실제로 들리는가)로 확인한다.
- 백엔드는 도커 컨테이너 안에서 돈다. 컨테이너의 `127.0.0.1` 은 호스트가 아니므로
  **서버 API 호출도 공개 주소 `https://openvidu.i-eum.site` 를 쓴다.**
- backend에는 `Lecture`·`Participant`·`LectureProblem` 엔티티와 도메인 테스트가 있고,
  API 계층(컨트롤러·서비스)과 `Participant` 리포지토리가 없다.
- 앱(`front/apps/mobile`)의 수업 화면(코드 입력 V20 → 대기 V22 → 풀이 V23 → 종료·끊김)은 있고,
  목은 `src/services/classService.ts` 한 곳이다. 앱은 `expo run:android`(개발 빌드)로 돌린다.
- 강사 웹은 `apps/web` 에 가입 페이지만 있다. 로그인·인증 상태가 없다.
- Jenkins는 `front/` 변경을 웹 배포 대상으로 보고 `docker build --target web front` 를 부르지만,
  `front/Dockerfile` 에 `web` 스테이지가 없다. 웹을 `front/apps/web` 으로 옮겨 이 어긋남을 해소한다.

## 2. 구조

```
[강사 웹]  생성 → 시작(OPEN) → 마이크 publish ─┐
                                              ├─ OpenVidu 3.8  wss://openvidu.i-eum.site
[학습자 앱] 코드 입장 → 대기 → 음성 subscribe ─┘       방 이름: lecture-{lectureId}
[backend]  수업 CRUD · 입장 · 음성 토큰 발급 · 방 메타데이터 설정 · 종료 시 방 삭제
```

**수업 상태 신호는 LiveKit 방으로 전달한다.** 명세 §8.3의 WebSocket 채널은 문제 출제를 붙일 때 만든다.

| 사건 | 백엔드 동작 | 앱이 받는 신호 |
| --- | --- | --- |
| 강사가 시작 (`OPEN`) | 방 생성(이미 있으면 그대로) 후 메타데이터 `{"status":"OPEN"}` 설정 | `RoomMetadataChanged`, 또는 접속 시 `room.metadata` |
| 강사가 종료 (`CLOSED`) | 방 삭제 | `Disconnected(reason = ROOM_DELETED)` |

학습자는 SCHEDULED 상태에서도 토큰을 받아 방에 미리 들어가 있을 수 있다(LiveKit이 첫 접속 때 방을 자동 생성).
그래서 OPEN 전환 때 방 생성만으로는 부족하고, 메타데이터 설정을 항상 함께 한다.

## 3. MR 구성

| MR | 브랜치 | 내용 | 병합 대상 |
| --- | --- | --- | --- |
| ① | `chore/web-monorepo` (dev에서) | `apps/web` → `front/apps/web` 이전. 동작 변화 없음 | `dev` |
| ② | `feat/lecture-api` | backend 수업 API + 음성 토큰 API, api-spec 갱신 | `feat/lecture` |
| ③ | `feat/lecture-web` | 로그인, 수업 목록·개설, 실시간 수업 화면 | `feat/lecture` |
| ④ | `feat/lecture-app` | `classService` 실연동, LiveKit 음성 수신 | `feat/lecture` |

①과 ②는 병렬로 진행할 수 있다. ③은 ①·②가 필요하고(①이 dev에 들어간 뒤 `feat/lecture` 에 dev를 병합), ④는 ②가 필요하다.
`feat/lecture` 는 ②~④가 모두 들어오고 E2E를 통과하면 `dev` 로 MR한다.

## 4. ① 웹 이전 (`chore/web-monorepo`)

- `git mv apps/web front/apps/web`. 패키지 이름은 `@ieum/web`, npm → pnpm(워크스페이스 lockfile에 합류, `package-lock.json` 삭제).
- `front/Dockerfile` 에 `web` 스테이지(pnpm으로 `front/apps/web` 빌드 → nginx 런타임)를 추가하고,
  `test` 스테이지가 웹의 build·lint도 돌게 한다. `apps/web/Dockerfile` 은 삭제한다.
- 루트 `docker-compose.yml` 의 web 서비스 context, `Jenkinsfile.mr` 의 검증 경로, 문서
  (`docs/conventions/repository-structure.md`, `AGENTS.md` 의 web 경로·검증 명령, `apps/web/README.md`) 를 새 위치로 고친다.
- 완료 기준: `docker build --target test front` 와 `docker build --target web front` 가 성공하고,
  빌드된 웹의 기존 라우트(`/`, `/signup`, `/auth/*`)가 전과 같이 뜬다.

## 5. ② backend (`feat/lecture-api`)

### 5.1 엔드포인트

명세 §8.2의 경로·필드 규칙(ID는 10진수 문자열, 시각은 ISO 8601 UTC)을 따르되, MVP에 필요한 필드만 채운다.

| API | 권한 | 동작 |
| --- | --- | --- |
| `POST /api/v1/lectures` `{title, description?, scheduledAt?}` | INSTRUCTOR | `Lecture.schedule` — SCHEDULED, 6자리 코드 발급. `201` |
| `GET /api/v1/lectures?status&page&size` | INSTRUCTOR | 내 수업 목록 (기본 20, 최대 100) |
| `GET /api/v1/lectures/{lectureId}` | 소유자 또는 참여자 | 스냅샷. 강사에게만 `participationCode`, 학습자에게만 `myParticipation` |
| `PUT /api/v1/lectures/{lectureId}/status` `{status}` | 소유자 | OPEN: `lecture.open` 후 방 생성·메타데이터. CLOSED: `lecture.close` 후 방 삭제 |
| `POST /api/v1/lecture-participations` `{participationCode}` | LEARNER | 첫 입장 `201`, 재입장 `200`. CLOSED면 `LECTURE_NOT_JOINABLE` |
| **`POST /api/v1/lectures/{lectureId}/media-tokens`** (신규) | 소유자 또는 참여자 | `201 {serverUrl, token, expiresAt}` |

스냅샷의 `currentProblem`·`linkedProblems`·`myResult`·`reviewableCurriculumStages` 는 이번에 채우지 않는다(null 또는 빈 배열).

### 5.2 음성 토큰 규칙 (명세에 새로 추가)

| 요청자 | 발급 가능 상태 | 권한 (VideoGrant) |
| --- | --- | --- |
| 소유 강사 | OPEN | `roomJoin`, `canPublish`, `canPublishSources=[MICROPHONE]`, `canSubscribe` |
| 참여 학습자 | SCHEDULED, OPEN | `roomJoin`, `canSubscribe`, `canPublish=false`, `canPublishData=false` |

- 방 이름 `lecture-{lectureId}`, identity `user-{userId}`, name은 username.
- 토큰 TTL 10분. LiveKit 클라이언트가 접속 중에는 서버가 내려주는 토큰으로 스스로 갱신하므로 재접속 때만 새로 받는다.
- 오류: 참여하지 않은 학습자 `403 LECTURE_PARTICIPATION_REQUIRED`, 상태 불일치 `409 INVALID_LECTURE_STATE`,
  OpenVidu 호출 실패 `503 MEDIA_SERVER_UNAVAILABLE`(신규 코드).
- 음성 토큰은 참여(`participants`)·WebSocket 티켓과 별개라는 명세 원칙을 유지한다.

### 5.3 구성

- `lecture/controller`, `lecture/service`, `lecture/dto` 를 추가하고, `ParticipantRepository` 를 만든다.
- OpenVidu 연동은 `LiveRoomGateway` 인터페이스(`issueToken`, `openRoom`, `deleteRoom`) 뒤에 두고,
  구현체가 `io.livekit:livekit-server` SDK(`AccessToken`, `RoomServiceClient`)를 쓴다. 서비스 테스트는 가짜 게이트웨이로 돈다.
- 상태 변경과 방 조작의 순서: DB 커밋 후 방을 조작한다. 방 조작이 실패하면 상태는 유지하고 `503` 을 돌려,
  강사가 같은 요청을 다시 보내면 방 조작만 재시도되게 한다(`PUT status` 를 같은 값으로 다시 보내는 것을 허용).
- 기존 `POST /lectures/{id}/problems` 는 건드리지 않는다.

### 5.4 설정

- `application.yml`: `itda.livekit.url`, `itda.livekit.api-key`, `itda.livekit.api-secret`.
- 환경변수 `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` — `.env.example` 에 키를 추가한다.
- 운영 값은 Jenkins credential `itda-env-config` 에 사람이 넣는다(docs/infra 절차). 값은 `/opt/openvidu/config/openvidu.env` 의 LiveKit 키다.
- 테스트 프로필은 더미 값을 쓴다.

## 6. ③ 강사 웹 (`feat/lecture-web`)

`@ieum/api-client`(`platform: "WEB"`)를 재사용한다. 디자인은 와이어프레임의 라이트 민트 톤과 화면 배치만 따른다.

| 화면 | 실제 동작 | 목 |
| --- | --- | --- |
| 로그인 `/login` | `POST /auth-sessions`. access token은 `sessionStorage` | – |
| 01a 수업 목록 `/lectures` | 목록, 상태 배지, 참여 코드 | 참여 인원·정답률 |
| 02 수업 개설 `/lectures/new` | 이름, 시작 시각 → 생성 후 목록으로 | 문제 세트·참여 방식 단계는 비활성 표시 |
| 03 실시간 수업 `/lectures/:id/live` | 시작(OPEN) → 토큰 → 방 접속·마이크 publish, 마이크 끄기, 종료(CLOSED) → 목록으로. 학생 그리드의 입장/미입장은 LiveKit 참가자 목록 | 답안 상태·문제 패널 |

- 의존성: `livekit-client`.
- 인증 가드: INSTRUCTOR가 아니거나 토큰이 없으면 `/login`. 토큰 만료 시 재로그인(Refresh 쿠키 회전은 이번 범위 밖).
- 마이크 권한은 HTTPS 또는 localhost에서만 열린다. 로컬 개발은 `localhost` 로 확인한다.
- 강사 계정: 가입은 항상 LEARNER이므로, 개발·시연용 INSTRUCTOR 계정은 시드 SQL로 만든다.

## 7. ④ 학습자 앱 (`feat/lecture-app`)

화면 흐름은 유지하고 `classService.ts` 와 대기 화면만 바꾼다.

1. V20 코드 입력 → `POST /lecture-participations` → `lectureId` 를 수업 스토어에 둔다.
2. V22 대기 → `GET /lectures/{id}` → `POST /media-tokens` → `Room.connect(serverUrl, token)`.
   이미 OPEN이거나 `RoomMetadataChanged` 로 OPEN이 오면 `/class/play` 로 간다.
3. V23 풀이: 문제는 지금처럼 목(`getClassSession`), 음성은 방 연결이 유지되는 동안 계속 재생된다.
4. `Disconnected(ROOM_DELETED)` → `/class/end`. 그 밖의 끊김 → `/class/disconnected`
   (명세의 "음성 장애와 문제·답안 장애 구분"을 여기서 지킨다 — 음성 끊김이 수업 종료로 오인되지 않게).
5. 화면을 떠나면 `room.disconnect()`.

- 의존성: `@livekit/react-native`, `@livekit/react-native-webrtc`, `@livekit/react-native-expo-plugin`,
  `@config-plugins/react-native-webrtc`. `app.json` plugins 등록 후 prebuild. 앱 시작 시 `registerGlobals()`.
- 방 연결은 화면 사이를 넘나들므로 컴포넌트가 아니라 모듈 단위(`liveRoom` 서비스 + 스토어)가 소유한다.
- 위험: 강사 음성과 `expo-speech` TTS·음성 인식의 오디오 포커스 충돌. 실기기에서 확인하고, 문제가 있으면 기록 후 후속 이슈로 둔다.

## 8. 완료 기준

- ① `docker build --target test front`, `docker build --target web front` 성공.
- ② `./gradlew test` 통과. 서비스 테스트가 권한(강사/학습자/비참여자)·상태 전이·토큰 권한 분기·방 조작 호출을 검증한다.
- ③ `front/apps/web` build(tsc 포함)·lint 통과.
- ④ `front/apps/mobile` `npm test` 통과.
- 수동 E2E(운영 OpenVidu 대상): 강사가 웹에서 수업을 만들고 시작 → 안드로이드 개발 빌드에서 코드 입장 →
  **학습자가 강사 음성을 듣는다** → 강사가 종료 → **앱이 종료 화면으로 간다.**

## 9. 범위 밖

- 명세 §8.3 WebSocket 채널·실시간 티켓, 문제 출제(`current-problem`)·답안 제출, 회차 리포트(06), 통계.
- 웹 Refresh 쿠키 회전.
- 서버 nginx 설정(`i-eum.site`, `openvidu.i-eum.site` 블록)과 repo `deploy/nginx/itda.conf`(`j15b201.p.ssafy.io`)의
  불일치 — 다음 Sync 배포에서 덮일 수 있어 별도 인프라 이슈로 제기한다.
