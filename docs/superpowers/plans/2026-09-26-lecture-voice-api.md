# 실시간 수업 양방향 음성 — backend·계약 문서 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 담당 강사가 참여 학습자의 발언(마이크 송출)을 허용·회수하는 `PUT /api/v1/lectures/{lectureId}/participants/{userId}/speaking` 을 만들고, 학습자 음성 토큰을 "숨김 없음 + 자기 속성 갱신 가능"으로 바꾼다. 계약 문서(api-spec §8·§8.5, feature-spec F-23)를 먼저 고친다.

**Architecture:** 기존 `lecture/media/LiveRoomGateway` 경계에 `setSpeaking(roomName, identity, allowed)` 를 더한다. LiveKit 구현체는 `RoomServiceClient.updateParticipant(room, identity, null, null, permission, attributes)` 로 참가자 권한·속성을 한 번에 바꾸고, HTTP 404(방에 그 참가자가 없음)는 `false` 로 돌려 서비스가 `LECTURE_PARTICIPANT_NOT_CONNECTED` 를 고르게 한다. 그 밖의 실패는 기존처럼 `MEDIA_SERVER_UNAVAILABLE`. 새 `LectureSpeakingService` 가 역할→소유→OPEN→활성 참여자 순으로 판정한 뒤 게이트웨이를 부른다. 상태는 DB에 저장하지 않는다 — LiveKit 참가자 권한·속성이 유일한 상태다.

**Tech Stack:** Spring Boot 4.0.8, Java 25, Spring Data JPA, Spring Security(Access JWT), `io.livekit:livekit-server:0.16.0`(Retrofit 3 + OkHttp, protobuf `livekit.LivekitModels`), JUnit 5 + Mockito 5.20 + AssertJ, nimbus-jose-jwt(토큰 검증 테스트)

**Spec:** `docs/superpowers/specs/2026-09-26-lecture-two-way-voice-design.md` §3, §6, §8 / 교차 계약 `.superpowers/sdd/voice-contract.md` (web·app 계획과 공유 — 이름·값을 바꾸지 않는다)

## Global Constraints

- 브랜치: `feat/lecture-voice-api` (`feat/lecture-voice` 에서 분기), 워크트리 `C:/Users/SSAFY/Desktop/wt-voice-api`, MR 대상 `feat/lecture-voice`. **push·MR 생성은 사람이 한다** — 이 계획은 로컬 커밋까지만
- 커밋: `type(lecture): 한글 제목`. 모든 커밋 메시지 마지막 줄은 정확히 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 이슈 번호가 주어지지 않았으므로 `Refs #번호` footer는 생략한다(번호를 받으면 제목 아래 빈 줄 뒤 `Refs #번호`, 그 다음 Co-Authored-By)
- `git push --force`·`--no-verify` 금지. main·dev·release/* 직접 push 금지
- `.env` 계열·`*.pem` 은 읽지도, 수정하지도 않는다. 이 작업은 새 환경변수를 추가하지 않는다
- context-path가 `/api/v1` 이므로 매핑·SecurityConfig에는 그 뒤 경로만 적는다
- ID는 JSON에서 10진수 문자열 (`"userId": "8"`)
- 오류 code는 계약 문서(api-spec §8.4·§8.5)에 먼저 적고 `ErrorCode` 에 추가한다 — `ErrorCode.java` 주석의 규칙
- 권한 없는 ID 기반 자원은 존재를 숨기고 `404 LECTURE_NOT_FOUND`
- 방 이름 `lecture-{lectureId}`, identity `user-{userId}` — `LectureRoom.nameOf/identityOf` 만 쓴다
- 참가자 속성 키·값은 교차 계약 그대로: `hand` = `up`|`down`, `speaking` = `allowed`|`none`
- 발언 허용 권한: canSubscribe=true, canPublish=true, canPublishSources=[MICROPHONE], canPublishData=false, canUpdateMetadata=true / 회수: 같되 canPublish=false, sources 없음
- 강사 토큰은 바꾸지 않는다
- **커밋 전 검증:** `cd apps/backend && ./gradlew test` BUILD SUCCESSFUL. 실패 상태로 커밋하지 않는다
- **포맷:** 커밋 전 `./gradlew spotlessApply` → `git status --short` 확인. 이 작업이 건드리지 않은 파일이 바뀌어 있으면 `git checkout -- <그 파일>` 로 되돌린 뒤 `./gradlew spotlessCheck` 를 다시 돌린다(직교 수정 금지). 2026-09-26 기준 `feat/lecture-voice` 에서 `spotlessCheck` 는 통과했다 — dev에서 포맷이 이미 정리됐으므로 무관 파일 변경은 없는 것이 정상이며, 생기면 되돌린다
- 기존 테스트의 기대값은 이 계획이 명시한 것(학습자 토큰 `hidden`)만 바꾼다

## File Structure

| 파일 | 책임 |
| --- | --- |
| `docs/product/api-spec.md` (수정) | §8 A-21, §8.2 표, §8.4 표·끝 문단, §8.5 권한 표·숨김 문구 제거·§8.5.1 신설, §9 OpenVidu 행 |
| `docs/product/feature-spec.md` (수정) | §6 F-23 행 |
| `global/error/ErrorCode.java` (수정) | `LECTURE_PARTICIPANT_NOT_FOUND`, `LECTURE_PARTICIPANT_NOT_CONNECTED` (둘 다 404) |
| `lecture/media/LiveRoomGateway.java` (수정) | `boolean setSpeaking(String, String, boolean)` 추가, 학습자 토큰 설명 갱신 |
| `lecture/media/LiveKitRoomGateway.java` (수정) | 학습자 토큰 grant 변경(Hidden 제거·CanUpdateOwnMetadata 추가), `setSpeaking` 구현, `execute` 가 404 허용 여부를 결과로 돌려줌 |
| `lecture/service/LectureSpeakingService.java` (신규) | 발언 허용·회수 권한 판정 후 게이트웨이 호출 |
| `lecture/dto/ChangeSpeakingRequest.java` (신규) | `{"allowed": boolean}` |
| `lecture/dto/SpeakingResponse.java` (신규) | `{"userId": "...", "allowed": ...}` |
| `lecture/controller/LectureSpeakingController.java` (신규) | `PUT /lectures/{lectureId}/participants/{userId}/speaking` |
| `global/security/SecurityConfig.java` (수정) | 보호 경로 1개(PUT) 추가 |
| 테스트 `lecture/media/LiveKitRoomGatewayTest.java` (수정) | 학습자 토큰 기대값, `setSpeaking` 권한·속성·404·실패 |
| 테스트 `lecture/service/RecordingLiveRoomGateway.java` (수정) | `setSpeaking` 기록·미접속 흉내 |
| 테스트 `lecture/service/LectureSpeakingServiceTest.java` (신규) | 모든 판정 분기 |
| 테스트 `lecture/controller/LectureAuthenticatedApiIntegrationTest.java` (수정) | 가짜 게이트웨이 `setSpeaking`, 인증 흐름 통합 테스트 |
| 테스트 `lecture/controller/LectureApiIntegrationTest.java` (수정) | 토큰 없음 401 |

(Java 경로의 기준은 `apps/backend/src/main/java/com/itda/`, 테스트는 `apps/backend/src/test/java/com/itda/`)

### 검증한 SDK API (livekit-server 0.16.0 jar, `javap` 로 확인)

- `io.livekit.server.RoomServiceClient#updateParticipant(String roomName, String identity, String name, String metadata, livekit.LivekitModels.ParticipantPermission permission, java.util.Map<String,String> attributes) : retrofit2.Call<livekit.LivekitModels.ParticipantInfo>` — name·metadata·permission·attributes가 null이면 요청에 넣지 않는다(바이트코드의 `ifnull` 분기). roomName·identity는 non-null 필수
- `livekit.LivekitModels.ParticipantPermission.newBuilder()` → `setCanSubscribe(boolean)`, `setCanPublish(boolean)`, `setCanPublishData(boolean)`, `addCanPublishSources(livekit.LivekitModels.TrackSource)`, `setCanUpdateMetadata(boolean)`; 읽기 `getCanPublishSourcesList()` 등
- `livekit.LivekitModels.TrackSource.MICROPHONE`
- `io.livekit.server.CanUpdateOwnMetadata(boolean)` — JWT `video` claim 키 `canUpdateOwnMetadata`
- 테스트용: `retrofit2.Response.success(T)`, `retrofit2.Response.error(int, okhttp3.ResponseBody)`, `okhttp3.ResponseBody.create(String, okhttp3.MediaType)` (OkHttp 4.12/5.3 모두 static 존재)

---

### Task 0: 워크트리와 브랜치

**Files:** 없음

- [ ] **Step 1: 워크트리 생성**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git fetch origin
git worktree add C:/Users/SSAFY/Desktop/wt-voice-api -b feat/lecture-voice-api feat/lecture-voice
```

Expected: `Preparing worktree (new branch 'feat/lecture-voice-api')`. 이미 브랜치가 있으면 멈추고 사람에게 확인한다(덮어쓰지 않는다).

- [ ] **Step 2: 기준선 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-voice-api
git log --oneline -1   # feat/lecture-voice 의 HEAD와 같아야 한다
cd apps/backend && ./gradlew test spotlessCheck
```

Expected: BUILD SUCCESSFUL. 실패하면 이 계획의 변경 전이므로 원인을 보고하고 멈춘다. push하지 않는다.

이후 모든 Task는 `C:/Users/SSAFY/Desktop/wt-voice-api` 에서 한다.

---

### Task 1: 계약 문서와 오류 code

**Files:**
- Modify: `docs/product/api-spec.md` (§8 첫 표 A-21 행, §8.2 표, §8.4 표·끝 문단, §8.5, §9 표 OpenVidu 행)
- Modify: `docs/product/feature-spec.md` (§6 표 F-23 행)
- Modify: `apps/backend/src/main/java/com/itda/global/error/ErrorCode.java` (lecture 구역)

**Interfaces:**
- Produces: `ErrorCode.LECTURE_PARTICIPANT_NOT_FOUND`(404), `ErrorCode.LECTURE_PARTICIPANT_NOT_CONNECTED`(404)

- [ ] **Step 1: api-spec §8 첫 표 A-21 행 교체**

기존:

```markdown
| A-21 / F-23 음성 접속 | 담당 강사·해당 참여 학습자의 수업 문맥 | OpenVidu 강사 송출·학습자 수신 단방향 음성. 학습자 마이크·영상·화면 공유·녹음/저장/전사/다시보기 제외 | 제품 범위 승인. 토큰·방 규칙은 8.5절 |
```

새 행:

```markdown
| A-21 / F-23 음성 접속 | 담당 강사·해당 참여 학습자의 수업 문맥 | OpenVidu 강사 송출·참가자 수신 음성. 강사가 허용한 학습자만 마이크 송출, 발언은 모든 참가자가 듣는다. 영상·화면 공유·녹음/저장/전사/다시보기 제외 | 제품 범위 승인. 토큰·방·발언 규칙은 8.5절 |
```

- [ ] **Step 2: api-spec §8.2 표에 발언 권한 행 추가**

`POST /api/v1/lectures/{lectureId}/media-tokens` 행 바로 아래:

```markdown
| `PUT /api/v1/lectures/{lectureId}/participants/{userId}/speaking` | 200 | 담당 INSTRUCTOR, OPEN 전용. body `allowed`(boolean). 활성 참여 학습자의 마이크 송출을 허용·회수한다. 멱등, DB에 저장하지 않는다 (8.5.1절). |
```

- [ ] **Step 3: api-spec §8.4 표·끝 문단**

(a) 403 INSTRUCTOR 행 교체:

```markdown
| 403 | INSTRUCTOR_ACCESS_REQUIRED | 강사 전용 collection 요청·발언 권한 변경에 INSTRUCTOR가 아님 |
```

(b) `| 404 | LECTURE_NOT_JOINABLE | ... |` 행 바로 아래에 두 행 추가:

```markdown
| 404 | LECTURE_PARTICIPANT_NOT_FOUND | 발언 권한 대상이 이 수업의 활성 참여자가 아님 (8.5.1절) |
| 404 | LECTURE_PARTICIPANT_NOT_CONNECTED | 발언 권한 대상이 활성 참여자지만 지금 음성 방에 접속해 있지 않음 (8.5.1절) |
```

(c) 503 행 교체:

```markdown
| 503 | MEDIA_SERVER_UNAVAILABLE | OpenVidu 방 생성·metadata 설정·삭제·참가자 권한 변경 호출 실패 (상태 변경, 강사 음성 토큰 요청의 방 다시 열기, 발언 권한 변경) |
```

(d) §8.4 마지막 문단 첫 두 문장 교체. 기존:

```markdown
OpenVidu 강사 단방향 음성은 제품 MVP에 포함하며 버전·토큰 endpoint·복구는 8.5절을 따른다. 강사 publish·학습자 subscribe만 허용하고 녹음·저장·전사·다시 듣기·학습자 마이크·영상·화면 공유는 추가하지 않는다.
```

새 문장(뒤의 "미디어 접속 상태·토큰은 …" 문장은 그대로 둔다):

```markdown
OpenVidu 음성은 제품 MVP에 포함하며 버전·토큰 endpoint·복구·발언 규칙은 8.5절을 따른다. 강사 publish·모든 참가자 subscribe이며, 학습자는 강사가 허용한 동안만 마이크를 publish한다. 녹음·저장·전사·다시 듣기·영상·화면 공유는 추가하지 않는다.
```

- [ ] **Step 4: api-spec §8.5 학습자 권한·숨김 문구**

(a) 권한 표의 학습자 행 교체:

```markdown
| 활성 참여 LEARNER (left_at NULL) | SCHEDULED, OPEN | 입장, subscribe, 자기 참가자 속성 갱신(손들기 — 8.5.1절). 입장 시에는 publish·데이터 전송 불가 |
```

(b) 다음 bullet을 **삭제**:

```markdown
- 학습자는 숨은 참가자(hidden)로 입장한다. 다른 참가자 목록에 나타나지 않아 학습자끼리 서로를 볼 수 없고, 강사는 모두에게 보인다.
```

그 자리에 추가:

```markdown
- 학습자는 숨지 않고 입장한다. 강사가 참가자 목록에서 학습자를 보고 발언을 허용해야 하며, 서로 목소리를 듣는 수업이므로 학습자끼리도 표시 이름(username)을 본다. HTTP Snapshot에서 다른 참여자를 노출하지 않는 규칙(8.2절)은 그대로다.
```

- [ ] **Step 5: api-spec §8.5.1 신설**

§8.5 마지막 문단 "토큰 서명은 네트워크 호출 없이 서버 안에서 끝나며, …" 바로 뒤, `## 9.` 앞에 다음을 넣는다.

~~~markdown
#### 8.5.1 발언 허용·손들기

학습자는 입장할 때 말할 수 없다. 손들기로 발언을 요청하고, 담당 강사가 허용한 동안만 마이크 음성을 publish한다. 발언은 강사와 다른 학습자 모두가 듣는다. 녹음·저장·전사는 하지 않는다.

참가자 속성(LiveKit participant attributes, 문자열 map)은 다음과 같다.

| 키 | 값 | 쓰는 쪽 |
| --- | --- | --- |
| `hand` | `up` / `down` | 학습자(손들기·내리기), 서버(허용·회수 시 `down`) |
| `speaking` | `allowed` / `none` | 서버만 |

- `speaking` 은 표시용이다. 실제 송출 가능 여부는 LiveKit 참가자 권한(`permissions.canPublish` 와 `canPublishSources` 의 MICROPHONE)으로 판단한다.
- 발언 허용·손들기 상태는 DB에 저장하지 않는다. LiveKit 참가자 권한·속성이 유일한 상태이며, 학습자가 재접속하면(새 토큰) 초기화된다.
- 참가자 identity `user-{userId}` 에서 `user-` 를 떼면 이 API의 `userId` 다.

`PUT /api/v1/lectures/{lectureId}/participants/{userId}/speaking` 요청과 성공(200) 응답은 다음과 같다.

```json
{ "allowed": true }
```

```json
{ "userId": "8", "allowed": true }
```

서버는 OpenVidu 참가자 `user-{userId}` 의 권한과 속성을 한 번에 바꾼다.

| allowed | 권한 | 속성 |
| --- | --- | --- |
| true | canSubscribe, canPublish, canPublishSources=[MICROPHONE], canUpdateMetadata. canPublishData 없음 | `hand=down`, `speaking=allowed` |
| false | canSubscribe, canUpdateMetadata. canPublish·canPublishData 없음 | `hand=down`, `speaking=none` |

- 회수하면 OpenVidu가 학습자가 게시한 음성 트랙을 즉시 내린다.
- 같은 값을 다시 보내도 결과가 같다(멱등).

오류는 위에서 아래 순서로 판정한다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 403 | INSTRUCTOR_ACCESS_REQUIRED | 요청자가 INSTRUCTOR가 아님 |
| 404 | LECTURE_NOT_FOUND | 수업이 없거나 요청 강사의 수업이 아님 |
| 409 | INVALID_LECTURE_STATE | 수업이 OPEN이 아님 |
| 404 | LECTURE_PARTICIPANT_NOT_FOUND | userId가 이 수업의 활성 참여자가 아님(참여한 적 없음·퇴장함) |
| 404 | LECTURE_PARTICIPANT_NOT_CONNECTED | 활성 참여자지만 지금 음성 방에 접속해 있지 않음 |
| 503 | MEDIA_SERVER_UNAVAILABLE | OpenVidu 참가자 권한 변경 호출 실패 |

`allowed` 누락은 400 `INVALID_REQUEST_PARAMETER` 다(경로 제약과 본문 검증을 함께 쓰는 endpoint의 기존 처리 — 8.2절 상태 변경과 같다). 그 밖의 본문·경로 형식 오류는 공통 HTTP 오류 표를 따른다.
~~~

- [ ] **Step 6: api-spec §9 표 OpenVidu 행 교체**

기존:

```markdown
| OpenVidu | 강사 publish·학습자 subscribe 단방향 음성 | 8.5절 (OpenVidu 3.x, 토큰 endpoint·방 규칙 확정) |
```

새 행:

```markdown
| OpenVidu | 강사 publish·모든 참가자 subscribe 음성, 강사가 허용한 학습자만 마이크 publish | 8.5절 (OpenVidu 3.x, 토큰 endpoint·방·발언 규칙 확정) |
```

- [ ] **Step 7: feature-spec §6 F-23 행 교체**

기존:

```markdown
| F-23 | 실시간 음성 설명 | OpenVidu 강사 publish·학습자 subscribe 단방향 음성 | 제품 MVP 포함. 녹음·저장·전사·다시 듣기·학습자 마이크·영상 없음. 버전·SDK·토큰/배포는 다음 스프린트 |
```

새 행:

```markdown
| F-23 | 실시간 음성 설명 | OpenVidu 강사 publish·참가자 subscribe 음성. 학습자는 손들기로 발언을 요청하고, 강사가 허용한 학습자만 마이크 송출, 발언은 모든 참가자가 듣는다 | 제품 MVP 포함. 녹음·저장·전사·다시 듣기·영상 없음. 토큰·방·발언 규칙은 API 명세 8.5절 |
```

- [ ] **Step 8: 문서 잔여 문구 확인**

```bash
grep -n "단방향\|학습자 마이크\|숨은 참가자\|hidden" docs/product/api-spec.md docs/product/feature-spec.md
```

Expected: 출력 없음. 남으면 그 줄이 음성 계약(§8·§9·F-23)에 속하는지 보고, 속하면 같은 취지로 고친다. 음성과 무관한 줄은 건드리지 않는다.

- [ ] **Step 9: ErrorCode 추가**

`ErrorCode.java` 의 lecture 구역에서 `LECTURE_NOT_JOINABLE(...)` 줄 바로 아래에 추가한다.

```java
  /** 발언 권한 대상이 이 수업의 활성 참여자(participants 행, left_at NULL)가 아니다 (8.5.1절). */
  LECTURE_PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "수업에 참여 중인 학습자가 아닙니다."),
  /** 활성 참여자지만 지금 음성 방에 접속해 있지 않다 — OpenVidu가 그 참가자를 모른다 (8.5.1절). */
  LECTURE_PARTICIPANT_NOT_CONNECTED(HttpStatus.NOT_FOUND, "지금 음성에 연결돼 있지 않은 학습자입니다."),
```

- [ ] **Step 10: ErrorCode 규칙 테스트**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.global.error.ErrorCodeTest"`
Expected: PASS (UPPER_SNAKE_CASE, 숫자 suffix 없음)

- [ ] **Step 11: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short   # 이 Task의 세 파일만 바뀌어 있어야 한다
git add docs/product/api-spec.md docs/product/feature-spec.md apps/backend/src/main/java/com/itda/global/error/ErrorCode.java
git commit -m "docs(lecture): 발언 허용 API와 학습자 음성 권한 계약을 정한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 학습자 음성 토큰 — 숨김 제거, 자기 속성 갱신 허용

**Files:**
- Modify: `lecture/media/LiveKitRoomGateway.java` (`issueToken` 의 학습자 분기, import)
- Test: `lecture/media/LiveKitRoomGatewayTest.java` (`학습자_토큰은_수신만_허용한다` 교체)

**Interfaces:**
- Consumes: 없음
- Produces: 학습자 JWT `video` claim — `canPublish=false`, `canPublishData=false`, `canSubscribe=true`, `canUpdateOwnMetadata=true`, `hidden` 없음

- [ ] **Step 1: 테스트 기대값을 먼저 바꾼다 (RED)**

`LiveKitRoomGatewayTest` 의 `학습자_토큰은_수신만_허용한다` 메서드 전체를 다음으로 교체한다.

```java
  @Test
  void 학습자_토큰은_입장_시_수신과_자기_속성_갱신만_허용한다() throws Exception {
    IssuedMediaToken issued =
        gateway.issueToken(new MediaTokenRequest("lecture-42", "user-8", "learner", false));

    Map<String, Object> video = verifiedClaims(issued.token()).getJSONObjectClaim("video");
    assertThat(video).containsEntry("canPublish", false).containsEntry("canPublishData", false);
    assertThat(video).containsEntry("canSubscribe", true);
    assertThat(video).doesNotContainKey("canPublishSources");
    // 손들기(hand 속성)를 학습자가 직접 쓴다 (API 명세 8.5.1절).
    assertThat(video).containsEntry("canUpdateOwnMetadata", true);
    // 강사가 참가자 목록에서 학습자를 보고 발언을 허용해야 하므로 숨기지 않는다 (API 명세 8.5절).
    assertThat(video.get("hidden")).isNotEqualTo(true);
  }
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.media.LiveKitRoomGatewayTest"`
Expected: FAIL — `canUpdateOwnMetadata` 없음, `hidden` 이 true

- [ ] **Step 3: 구현**

`LiveKitRoomGateway.java`:
- import `io.livekit.server.Hidden;` 를 지우고 `import io.livekit.server.CanUpdateOwnMetadata;` 를 알파벳 순서 자리(`CanSubscribe` 다음)에 추가한다.
- `issueToken` 의 `else` 분기를 다음으로 교체한다.

```java
    } else {
      // 입장할 때는 말할 수 없다. 발언은 강사가 허용할 때 서버가 참가자 권한을 바꿔 준다 (setSpeaking).
      grants.add(new CanPublish(false));
      // 손들기 속성(hand)을 학습자가 직접 쓴다. 숨기지 않는다 — 강사 참가자 목록에 보여야 한다 (API 명세 8.5절).
      grants.add(new CanUpdateOwnMetadata(true));
    }
```

(`LiveRoomGateway.MediaTokenRequest` javadoc은 `setSpeaking` 을 가리키므로 Task 3 Step 3에서 고친다.)

- [ ] **Step 4: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.media.LiveKitRoomGatewayTest"`
Expected: PASS (강사 토큰 테스트도 그대로 통과)

- [ ] **Step 5: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/lecture/media/LiveKitRoomGateway.java apps/backend/src/test/java/com/itda/lecture/media/LiveKitRoomGatewayTest.java
git commit -m "feat(lecture): 학습자 음성 토큰을 숨기지 않고 손들기 속성 갱신을 허용한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 게이트웨이 `setSpeaking`

**Files:**
- Modify: `lecture/media/LiveRoomGateway.java`
- Modify: `lecture/media/LiveKitRoomGateway.java`
- Modify: `test/.../lecture/service/RecordingLiveRoomGateway.java`
- Modify: `test/.../lecture/controller/LectureAuthenticatedApiIntegrationTest.java` (가짜 게이트웨이만 — 컴파일 유지)
- Test: `lecture/media/LiveKitRoomGatewayTest.java`

**Interfaces:**
- Produces: `boolean LiveRoomGateway.setSpeaking(String roomName, String identity, boolean allowed)` — 적용되면 true, 방에 그 참가자가 없으면(OpenVidu 404) false, 그 밖의 실패는 `BusinessException(MEDIA_SERVER_UNAVAILABLE)`
- Produces (test): `RecordingLiveRoomGateway.speakingChanges : List<SpeakingChange>`, `RecordingLiveRoomGateway.participantDisconnected : boolean`, `record RecordingLiveRoomGateway.SpeakingChange(String roomName, String identity, boolean allowed)`
- Produces (test): `LectureAuthenticatedApiIntegrationTest.FakeLiveRoomGateway.speakingChanges : List<String>` (`"{room}/{identity}/{allowed}"`)

- [ ] **Step 1: 실패하는 테스트 작성 (RED)**

`LiveKitRoomGatewayTest` 에 import를 추가한다.

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.itda.global.error.ErrorCode;
import java.io.IOException;
import livekit.LivekitModels.ParticipantInfo;
import livekit.LivekitModels.ParticipantPermission;
import livekit.LivekitModels.TrackSource;
import okhttp3.ResponseBody;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;
```

클래스 끝(`방_이름과_identity_규칙` 뒤)에 추가한다.

```java
  // ---------- 발언 권한 (API 명세 8.5.1절) ----------

  /** RoomServiceClient는 Kotlin final 클래스지만 Mockito 5 기본 inline mock maker가 대역을 만든다. */
  private final RoomServiceClient roomClient = mock(RoomServiceClient.class);

  private final LiveKitRoomGateway speakingGateway =
      new LiveKitRoomGateway(properties, roomClient, Clock.fixed(NOW, ZoneOffset.UTC));

  @SuppressWarnings("unchecked")
  private void answer(Response<ParticipantInfo> response) throws IOException {
    Call<ParticipantInfo> call = mock(Call.class);
    given(call.execute()).willReturn(response);
    given(roomClient.updateParticipant(any(), any(), any(), any(), any(), any())).willReturn(call);
  }

  private static Response<ParticipantInfo> error(int status) {
    return Response.error(status, ResponseBody.create("", null));
  }

  @SuppressWarnings("unchecked")
  private void verifyUpdate(
      ArgumentCaptor<ParticipantPermission> permission,
      ArgumentCaptor<Map<String, String>> attributes) {
    verify(roomClient)
        .updateParticipant(
            eq("lecture-42"),
            eq("user-8"),
            isNull(),
            isNull(),
            permission.capture(),
            attributes.capture());
  }

  @Test
  @SuppressWarnings("unchecked")
  void 발언을_허용하면_마이크_송출_권한을_주고_손을_내린다() throws Exception {
    answer(Response.success(ParticipantInfo.getDefaultInstance()));

    assertThat(speakingGateway.setSpeaking("lecture-42", "user-8", true)).isTrue();

    ArgumentCaptor<ParticipantPermission> permission =
        ArgumentCaptor.forClass(ParticipantPermission.class);
    ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
    verifyUpdate(permission, attributes);
    ParticipantPermission granted = permission.getValue();
    assertThat(granted.getCanSubscribe()).isTrue();
    assertThat(granted.getCanPublish()).isTrue();
    assertThat(granted.getCanPublishSourcesList()).containsExactly(TrackSource.MICROPHONE);
    assertThat(granted.getCanPublishData()).isFalse();
    // 권한은 통째로 바뀐다. 빠뜨리면 학습자가 더는 손을 들 수 없다.
    assertThat(granted.getCanUpdateMetadata()).isTrue();
    assertThat(attributes.getValue())
        .containsExactlyInAnyOrderEntriesOf(Map.of("hand", "down", "speaking", "allowed"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void 발언을_회수하면_송출_권한을_거두고_손을_내린다() throws Exception {
    answer(Response.success(ParticipantInfo.getDefaultInstance()));

    assertThat(speakingGateway.setSpeaking("lecture-42", "user-8", false)).isTrue();

    ArgumentCaptor<ParticipantPermission> permission =
        ArgumentCaptor.forClass(ParticipantPermission.class);
    ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
    verifyUpdate(permission, attributes);
    ParticipantPermission revoked = permission.getValue();
    assertThat(revoked.getCanSubscribe()).isTrue();
    assertThat(revoked.getCanPublish()).isFalse();
    assertThat(revoked.getCanPublishSourcesList()).isEmpty();
    assertThat(revoked.getCanPublishData()).isFalse();
    assertThat(revoked.getCanUpdateMetadata()).isTrue();
    assertThat(attributes.getValue())
        .containsExactlyInAnyOrderEntriesOf(Map.of("hand", "down", "speaking", "none"));
  }

  @Test
  void 방에_그_참가자가_없으면_false다() throws Exception {
    answer(error(404));

    assertThat(speakingGateway.setSpeaking("lecture-42", "user-8", true)).isFalse();
  }

  @Test
  void 그_밖의_OpenVidu_오류는_503이다() throws Exception {
    answer(error(500));

    assertThatThrownBy(() -> speakingGateway.setSpeaking("lecture-42", "user-8", true))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }

  @Test
  @SuppressWarnings("unchecked")
  void OpenVidu에_닿지_않으면_503이다() throws Exception {
    Call<ParticipantInfo> call = mock(Call.class);
    given(call.execute()).willThrow(new IOException("connection refused"));
    given(roomClient.updateParticipant(any(), any(), any(), any(), any(), any())).willReturn(call);

    assertThatThrownBy(() -> speakingGateway.setSpeaking("lecture-42", "user-8", false))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
```

(`ResponseBody.create("", null)` 가 모호성 오류를 내면 `ResponseBody.create(new byte[0], null)` 로 바꾼다. 둘 다 OkHttp 4.12/5.3의 static 메서드다.)

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.media.LiveKitRoomGatewayTest"`
Expected: FAIL — `compileTestJava` 에서 `setSpeaking` 을 찾을 수 없음

- [ ] **Step 3: 인터페이스에 메서드 추가**

`LiveRoomGateway.java` 의 `MediaTokenRequest` javadoc을 교체한다.

```java
  /**
   * @param publisher true면 마이크 음성 송출 권한(강사), false면 수신과 자기 속성 갱신만(학습자 — 발언은 {@link
   *     #setSpeaking}으로 따로 허용)
   */
```

`deleteRoom` 선언 아래에 추가한다.

```java
  /**
   * 참가자의 발언(마이크 송출) 권한을 바꾸고 손을 내린다(속성 {@code hand=down}, {@code speaking=allowed|none}). 권한을 거두면
   * OpenVidu가 그 참가자가 게시한 트랙을 내린다. 여러 번 불러도 결과가 같다 (API 명세 8.5.1절).
   *
   * @return 적용했으면 true, 방에 그 참가자가 접속해 있지 않으면 false
   */
  boolean setSpeaking(String roomName, String identity, boolean allowed);
```

- [ ] **Step 4: LiveKit 구현**

`LiveKitRoomGateway.java`:

import 추가:

```java
import java.util.Map;
import livekit.LivekitModels.ParticipantPermission;
import livekit.LivekitModels.TrackSource;
```

상수 추가(`NOT_FOUND` 아래):

```java
  /** 참가자 속성 규약 (API 명세 8.5.1절). 웹·앱이 같은 키·값을 읽는다. */
  static final String HAND_ATTRIBUTE = "hand";

  static final String HAND_DOWN = "down";
  static final String SPEAKING_ATTRIBUTE = "speaking";
  static final String SPEAKING_ALLOWED = "allowed";
  static final String SPEAKING_NONE = "none";
```

`deleteRoom` 과 `execute` 를 다음으로 교체하고 `setSpeaking` 을 추가한다.

```java
  @Override
  public void deleteRoom(String roomName) {
    execute(roomServiceClient.deleteRoom(roomName), "deleteRoom", true);
  }

  @Override
  public boolean setSpeaking(String roomName, String identity, boolean allowed) {
    // 권한은 부분 갱신이 아니라 통째로 바뀐다. 구독·자기 속성 갱신(손들기)을 매번 다시 적는다.
    ParticipantPermission.Builder permission =
        ParticipantPermission.newBuilder()
            .setCanSubscribe(true)
            .setCanPublish(allowed)
            .setCanPublishData(false)
            .setCanUpdateMetadata(true);
    if (allowed) {
      permission.addCanPublishSources(TrackSource.MICROPHONE);
    }
    Map<String, String> attributes =
        Map.of(
            HAND_ATTRIBUTE,
            HAND_DOWN,
            SPEAKING_ATTRIBUTE,
            allowed ? SPEAKING_ALLOWED : SPEAKING_NONE);
    // 표시 이름·metadata는 null로 보내 바꾸지 않는다.
    return execute(
        roomServiceClient.updateParticipant(
            roomName, identity, null, null, permission.build(), attributes),
        "updateParticipant",
        true);
  }

  /**
   * @param notFoundAllowed true면 404를 실패가 아니라 "대상 없음"으로 본다
   * @return 성공이면 true, 404를 허용한 호출에서 대상이 없으면 false
   */
  private boolean execute(Call<?> call, String operation, boolean notFoundAllowed) {
    try {
      Response<?> response = call.execute();
      if (response.isSuccessful()) {
        return true;
      }
      if (notFoundAllowed && response.code() == NOT_FOUND) {
        return false;
      }
      log.warn("OpenVidu {} 실패: HTTP {}", operation, response.code());
    } catch (IOException e) {
      log.warn("OpenVidu {} 호출 실패: {}", operation, e.getClass().getSimpleName());
    }
    throw new BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
```

(`openRoom` 의 두 `execute(..., false)` 호출은 반환값을 버리므로 그대로 둔다. `deleteRoom` 의 "이미 없으면 성공" 동작도 그대로다.)

- [ ] **Step 5: 테스트 대역 두 곳 갱신 (컴파일 유지)**

`RecordingLiveRoomGateway.java` — 필드와 메서드를 추가한다.

```java
  final List<SpeakingChange> speakingChanges = new ArrayList<>();

  /** true면 OpenVidu가 그 참가자를 모르는 경우(404)를 흉내 낸다. */
  boolean participantDisconnected;
```

```java
  @Override
  public boolean setSpeaking(String roomName, String identity, boolean allowed) {
    failIfRequested();
    if (participantDisconnected) {
      return false;
    }
    speakingChanges.add(new SpeakingChange(roomName, identity, allowed));
    return true;
  }

  record SpeakingChange(String roomName, String identity, boolean allowed) {}
```

`LectureAuthenticatedApiIntegrationTest.FakeLiveRoomGateway` — 필드와 메서드를 추가한다.

```java
    /** {@code "{room}/{identity}/{allowed}"}. 같은 context를 여러 테스트가 공유하므로 고유한 수업 ID로 찾는다. */
    final List<String> speakingChanges = new CopyOnWriteArrayList<>();
```

```java
    @Override
    public boolean setSpeaking(String roomName, String identity, boolean allowed) {
      this.speakingChanges.add(roomName + "/" + identity + "/" + allowed);
      return true;
    }
```

- [ ] **Step 6: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.*"`
Expected: PASS

- [ ] **Step 7: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/lecture/media/LiveRoomGateway.java apps/backend/src/main/java/com/itda/lecture/media/LiveKitRoomGateway.java apps/backend/src/test/java/com/itda/lecture/media/LiveKitRoomGatewayTest.java apps/backend/src/test/java/com/itda/lecture/service/RecordingLiveRoomGateway.java apps/backend/src/test/java/com/itda/lecture/controller/LectureAuthenticatedApiIntegrationTest.java
git commit -m "feat(lecture): OpenVidu 참가자 발언 권한을 바꾸는 게이트웨이를 추가한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `LectureSpeakingService` — 권한 판정

**Files:**
- Create: `lecture/service/LectureSpeakingService.java`
- Test: `lecture/service/LectureSpeakingServiceTest.java`

**Interfaces:**
- Consumes: `LectureService.isOwner(AuthenticatedUser, Lecture)`(package-private static), `LectureService.notFound()`, `Lecture.isOpen()`, `Participant.isParticipating()`, `ParticipantRepository.findById(ParticipantId)`, `LiveRoomGateway.setSpeaking`, `LectureRoom.nameOf/identityOf`
- Produces: `void LectureSpeakingService.changeSpeaking(AuthenticatedUser user, long lectureId, long learnerUserId, boolean allowed)`

- [ ] **Step 1: 실패하는 테스트 작성 (RED)**

```java
package com.itda.lecture.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.lecture.service.RecordingLiveRoomGateway.SpeakingChange;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LectureSpeakingServiceTest {

  private static final long LECTURE_ID = 42L;
  private static final long LEARNER_ID = 8L;
  private static final AuthenticatedUser INSTRUCTOR =
      new AuthenticatedUser(7L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser OTHER_INSTRUCTOR =
      new AuthenticatedUser(9L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser LEARNER =
      new AuthenticatedUser(LEARNER_ID, UserRole.LEARNER);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Mock private LectureRepository lectureRepository;
  @Mock private ParticipantRepository participantRepository;

  private RecordingLiveRoomGateway gateway;
  private LectureSpeakingService service;
  private Lecture lecture;

  @BeforeEach
  void setUp() {
    gateway = new RecordingLiveRoomGateway();
    service = new LectureSpeakingService(lectureRepository, participantRepository, gateway);
    lecture = scheduledLecture();
    lecture.open(NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(lecture));
    participate(true);
  }

  private static Lecture scheduledLecture() {
    Lecture scheduled = Lecture.schedule(7L, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(scheduled, "id", LECTURE_ID);
    return scheduled;
  }

  private void participate(boolean active) {
    Participant participant = Participant.join(LECTURE_ID, LEARNER_ID, NOW);
    if (!active) {
      participant.leave(NOW);
    }
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, LEARNER_ID)))
        .willReturn(Optional.of(participant));
  }

  private void assertRejected(AuthenticatedUser user, ErrorCode expected) {
    assertThatThrownBy(() -> service.changeSpeaking(user, LECTURE_ID, LEARNER_ID, true))
        .extracting("errorCode")
        .isEqualTo(expected);
    assertThat(gateway.speakingChanges).isEmpty();
  }

  @Test
  void 담당_강사가_활성_참여자의_발언을_허용한다() {
    service.changeSpeaking(INSTRUCTOR, LECTURE_ID, LEARNER_ID, true);

    assertThat(gateway.speakingChanges)
        .containsExactly(new SpeakingChange("lecture-42", "user-8", true));
  }

  @Test
  void 담당_강사가_발언을_회수한다() {
    service.changeSpeaking(INSTRUCTOR, LECTURE_ID, LEARNER_ID, false);

    assertThat(gateway.speakingChanges)
        .containsExactly(new SpeakingChange("lecture-42", "user-8", false));
  }

  @Test
  void 강사가_아니면_403이다() {
    assertRejected(LEARNER, ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
  }

  @Test
  void 수업이_없으면_404다() {
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.empty());

    assertRejected(INSTRUCTOR, ErrorCode.LECTURE_NOT_FOUND);
  }

  @Test
  void 담당이_아닌_강사에게는_수업_존재를_숨긴다() {
    assertRejected(OTHER_INSTRUCTOR, ErrorCode.LECTURE_NOT_FOUND);
  }

  @Test
  void 시작_전_수업에서는_409다() {
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(scheduledLecture()));

    assertRejected(INSTRUCTOR, ErrorCode.INVALID_LECTURE_STATE);
  }

  @Test
  void 종료된_수업에서는_409다() {
    lecture.close(NOW);

    assertRejected(INSTRUCTOR, ErrorCode.INVALID_LECTURE_STATE);
  }

  @Test
  void 참여한_적_없는_사용자는_대상이_아니다() {
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, LEARNER_ID)))
        .willReturn(Optional.empty());

    assertRejected(INSTRUCTOR, ErrorCode.LECTURE_PARTICIPANT_NOT_FOUND);
  }

  @Test
  void 퇴장한_학습자는_대상이_아니다() {
    participate(false);

    assertRejected(INSTRUCTOR, ErrorCode.LECTURE_PARTICIPANT_NOT_FOUND);
  }

  @Test
  void 음성_방에_접속해_있지_않으면_404다() {
    gateway.participantDisconnected = true;

    assertRejected(INSTRUCTOR, ErrorCode.LECTURE_PARTICIPANT_NOT_CONNECTED);
  }

  @Test
  void OpenVidu_호출이_실패하면_503이다() {
    gateway.failRoomOperations = true;

    assertRejected(INSTRUCTOR, ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.service.LectureSpeakingServiceTest"`
Expected: FAIL — `LectureSpeakingService` 를 찾을 수 없음(컴파일 오류)

- [ ] **Step 3: 구현**

```java
package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.media.LectureRoom;
import com.itda.lecture.media.LiveRoomGateway;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 강사의 학습자 발언 허용·회수 (API 명세 8.5.1절). 상태는 OpenVidu 참가자 권한·속성뿐이고 DB에 저장하지 않는다.
 *
 * <p>트랜잭션을 두지 않는다. 쓰기가 없고, OpenVidu 호출 동안 DB 연결을 붙들 이유가 없다(open-in-view는 꺼져 있다).
 */
@Service
@RequiredArgsConstructor
public class LectureSpeakingService {

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;
  private final LiveRoomGateway liveRoomGateway;

  /** 판정 순서는 명세 오류 표 순서와 같다 — 역할, 소유, 상태, 참여, 접속. */
  public void changeSpeaking(
      AuthenticatedUser user, long lectureId, long learnerUserId, boolean allowed) {
    if (user.role() != UserRole.INSTRUCTOR) {
      throw new BusinessException(ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
    }
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    if (!LectureService.isOwner(user, lecture)) {
      throw LectureService.notFound();
    }
    if (!lecture.isOpen()) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    boolean participating =
        participantRepository
            .findById(ParticipantId.of(lectureId, learnerUserId))
            .map(Participant::isParticipating)
            .orElse(false);
    if (!participating) {
      throw new BusinessException(ErrorCode.LECTURE_PARTICIPANT_NOT_FOUND);
    }
    boolean connected =
        liveRoomGateway.setSpeaking(
            LectureRoom.nameOf(lectureId), LectureRoom.identityOf(learnerUserId), allowed);
    if (!connected) {
      throw new BusinessException(ErrorCode.LECTURE_PARTICIPANT_NOT_CONNECTED);
    }
  }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.service.LectureSpeakingServiceTest"`
Expected: PASS (11개)

- [ ] **Step 5: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/lecture/service/LectureSpeakingService.java apps/backend/src/test/java/com/itda/lecture/service/LectureSpeakingServiceTest.java
git commit -m "feat(lecture): 강사의 학습자 발언 허용·회수 권한 판정을 추가한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: HTTP endpoint·보호 경로·통합 테스트

**Files:**
- Create: `lecture/dto/ChangeSpeakingRequest.java`, `lecture/dto/SpeakingResponse.java`
- Create: `lecture/controller/LectureSpeakingController.java`
- Modify: `global/security/SecurityConfig.java`
- Test: `lecture/controller/LectureApiIntegrationTest.java`, `lecture/controller/LectureAuthenticatedApiIntegrationTest.java`

**Interfaces:**
- Consumes: `LectureSpeakingService.changeSpeaking(AuthenticatedUser, long, long, boolean)`
- Produces: `PUT /api/v1/lectures/{lectureId}/participants/{userId}/speaking` body `{"allowed": boolean}` → 200 `{"userId": "<decimal string>", "allowed": boolean}`
- Produces: `record ChangeSpeakingRequest(@NotNull Boolean allowed)`, `record SpeakingResponse(String userId, boolean allowed)` + `static SpeakingResponse of(long userId, boolean allowed)`

별도 컨트롤러로 두는 이유: `LectureController` 는 "경로·body에서 사용자 식별자를 받지 않는다 — 대상은 principal뿐이다"를 클래스 규칙으로 적어 두었다. 이 endpoint는 대상 학습자를 경로로 받으므로 그 규칙을 깨지 않게 나눈다.

- [ ] **Step 1: 토큰 없음 401 테스트 (RED)**

`LectureApiIntegrationTest` 끝에 추가한다.

```java
  @Test
  void 토큰_없이_발언_권한을_바꾸면_401이다() {
    assertThat(status("PUT", "/lectures/1/participants/2/speaking", "{\"allowed\":true}"))
        .isEqualTo(401);
  }
```

- [ ] **Step 2: 인증 흐름 테스트 (RED)**

`LectureAuthenticatedApiIntegrationTest` 를 다음처럼 바꾼다.

(a) 필드 — `learnerToken`·`otherLearnerToken` 선언 아래에 추가:

```java
  private long learnerUserId;
  private long otherLearnerUserId;
```

(b) `setUp` 과 `tokenFor` 를 교체한다(`tokenFor` 는 다른 테스트가 계속 쓰므로 남긴다).

```java
  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    this.instructorToken = tokenFor(UserRole.INSTRUCTOR);
    Account learner = accountFor(UserRole.LEARNER);
    this.learnerToken = learner.token();
    this.learnerUserId = learner.userId();
    Account otherLearner = accountFor(UserRole.LEARNER);
    this.otherLearnerToken = otherLearner.token();
    this.otherLearnerUserId = otherLearner.userId();
  }

  private record Account(long userId, String token) {}

  private String tokenFor(UserRole role) {
    return accountFor(role).token();
  }

  /** 매번 새 계정을 만든다. 단조 증가 수열이라 같은 실행 안에서 UNIQUE 제약에 걸리지 않는다. */
  private Account accountFor(UserRole role) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long sequence = ACCOUNT_SEQUENCE.incrementAndGet();
    User user =
        User.create(
            "lecture" + role.name().toLowerCase() + sequence,
            "argon2id$hash",
            role,
            Email.of("lecture" + role.name().toLowerCase() + sequence + "@example.test"),
            now);
    user.verifyEmail(now);
    long userId = this.userRepository.save(user).getId();
    String token =
        this.accessTokenIssuer
            .issue(new AccessTokenIssuer.Claims(userId, role, 0))
            .compactToken();
    return new Account(userId, token);
  }
```

(c) `// ---------- 파라미터·본문 검증 ----------` 구역 **앞**에 발언 권한 구역을 추가한다.

```java
  // ---------- 발언 권한 (API 명세 8.5.1절) ----------

  /** 학습자(learnerToken)가 코드로 입장한 수업. open이면 강사가 시작까지 한다. */
  private String lectureJoinedByLearner(boolean open) {
    String lectureId = createLecture();
    String code =
        (String)
            HttpTestClient.json(send("GET", "/lectures/" + lectureId, null, this.instructorToken))
                .get("participationCode");
    assertThat(
            send(
                    "POST",
                    "/lecture-participations",
                    "{\"participationCode\":\"" + code + "\"}",
                    this.learnerToken)
                .statusCode())
        .isEqualTo(201);
    if (open) {
      assertThat(
              send(
                      "PUT",
                      "/lectures/" + lectureId + "/status",
                      "{\"status\":\"OPEN\"}",
                      this.instructorToken)
                  .statusCode())
          .isEqualTo(200);
    }
    return lectureId;
  }

  private static String speakingPath(String lectureId, long userId) {
    return "/lectures/" + lectureId + "/participants/" + userId + "/speaking";
  }

  @Test
  void 강사가_참여_학습자의_발언을_허용한다() {
    String lectureId = lectureJoinedByLearner(true);

    HttpResponse<String> response =
        send(
            "PUT",
            speakingPath(lectureId, this.learnerUserId),
            "{\"allowed\":true}",
            this.instructorToken);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(HttpTestClient.json(response))
        .containsEntry("userId", String.valueOf(this.learnerUserId))
        .containsEntry("allowed", true);
    assertThat(this.gateway.speakingChanges)
        .contains("lecture-" + lectureId + "/user-" + this.learnerUserId + "/true");
  }

  @Test
  void 담당이_아닌_강사에게는_발언_권한_대상_수업을_숨긴다() {
    String lectureId = lectureJoinedByLearner(true);

    assertError(
        send(
            "PUT",
            speakingPath(lectureId, this.learnerUserId),
            "{\"allowed\":true}",
            tokenFor(UserRole.INSTRUCTOR)),
        404,
        "LECTURE_NOT_FOUND");
  }

  @Test
  void 학습자는_발언_권한을_바꿀_수_없다() {
    String lectureId = lectureJoinedByLearner(true);

    assertError(
        send(
            "PUT",
            speakingPath(lectureId, this.learnerUserId),
            "{\"allowed\":true}",
            this.learnerToken),
        403,
        "INSTRUCTOR_ACCESS_REQUIRED");
  }

  @Test
  void 시작_전_수업에서는_발언을_허용할_수_없다() {
    String lectureId = lectureJoinedByLearner(false);

    assertError(
        send(
            "PUT",
            speakingPath(lectureId, this.learnerUserId),
            "{\"allowed\":true}",
            this.instructorToken),
        409,
        "INVALID_LECTURE_STATE");
  }

  @Test
  void 참여하지_않은_학습자는_발언_권한_대상이_아니다() {
    String lectureId = lectureJoinedByLearner(true);

    assertError(
        send(
            "PUT",
            speakingPath(lectureId, this.otherLearnerUserId),
            "{\"allowed\":true}",
            this.instructorToken),
        404,
        "LECTURE_PARTICIPANT_NOT_FOUND");
  }
```

(d) `// ---------- 파라미터·본문 검증 ----------` 구역의 `제약_경로와_함께_받은_잘못된_상태_body는_400이다` 아래에 추가한다.

```java
  @Test
  void 제약_경로와_함께_받은_allowed_없는_발언_body는_400이다() {
    String lectureId = lectureJoinedByLearner(true);

    assertError(
        send("PUT", speakingPath(lectureId, this.learnerUserId), "{}", this.instructorToken),
        400,
        "INVALID_REQUEST_PARAMETER");
  }
```

- [ ] **Step 3: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.controller.*"`
Expected: FAIL — 새 테스트들이 404 `ENDPOINT_NOT_FOUND`(endpoint 없음)로 실패. 기존 테스트는 통과

- [ ] **Step 4: DTO**

`lecture/dto/ChangeSpeakingRequest.java`:

```java
package com.itda.lecture.dto;

import jakarta.validation.constraints.NotNull;

/** true면 발언 허용, false면 회수 (API 명세 8.5.1절). */
public record ChangeSpeakingRequest(@NotNull Boolean allowed) {}
```

`lecture/dto/SpeakingResponse.java`:

```java
package com.itda.lecture.dto;

/** userId는 JSON에서 10진수 문자열이다 (API 명세 8.2절). */
public record SpeakingResponse(String userId, boolean allowed) {

  public static SpeakingResponse of(long userId, boolean allowed) {
    return new SpeakingResponse(String.valueOf(userId), allowed);
  }
}
```

- [ ] **Step 5: 컨트롤러**

`lecture/controller/LectureSpeakingController.java`:

```java
package com.itda.lecture.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.dto.ChangeSpeakingRequest;
import com.itda.lecture.dto.SpeakingResponse;
import com.itda.lecture.service.LectureSpeakingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 담당 강사가 참여 학습자의 발언을 허용·회수한다 (API 명세 8.5.1절). 대상 학습자를 경로로 받으므로 principal만 대상으로 삼는 {@link
 * LectureController}와 나눈다.
 */
@RestController
@RequiredArgsConstructor
public class LectureSpeakingController {

  private final LectureSpeakingService speakingService;

  /** 멱등 — 같은 값을 다시 보내도 결과가 같다. */
  @PutMapping("/lectures/{lectureId}/participants/{userId}/speaking")
  public SpeakingResponse changeSpeaking(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @PathVariable @Positive Long userId,
      @Valid @RequestBody ChangeSpeakingRequest request) {
    speakingService.changeSpeaking(user, lectureId, userId, request.allowed());
    return SpeakingResponse.of(userId, request.allowed());
  }
}
```

- [ ] **Step 6: SecurityConfig 보호 경로**

`LECTURE_MEDIA_TOKENS_PATH` 선언 아래에 추가한다.

```java
  private static final String LECTURE_PARTICIPANT_SPEAKING_PATH =
      "/lectures/{lectureId}/participants/{userId}/speaking";
```

`securityMatcher` 의 `LECTURE_MEDIA_TOKENS_PATH` 줄 아래에 추가한다.

```java
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.PUT, LECTURE_PARTICIPANT_SPEAKING_PATH),
```

- [ ] **Step 7: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.*"`
Expected: PASS. `제약_경로와_함께_받은_allowed_없는_발언_body는_400이다` 만 422 `INVALID_REQUEST_INPUT` 으로 실패하면, 오류 처리기가 이 조합을 422로 매핑하는 것이다 — 테스트 기대값과 Task 1 Step 5 문서 문장("`allowed` 누락은 …")을 실제 값에 맞춰 함께 고치고 그 사실을 보고한다(코드를 문서에 억지로 맞추지 않는다).

- [ ] **Step 8: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/lecture/dto/ChangeSpeakingRequest.java apps/backend/src/main/java/com/itda/lecture/dto/SpeakingResponse.java apps/backend/src/main/java/com/itda/lecture/controller/LectureSpeakingController.java apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java apps/backend/src/test/java/com/itda/lecture/controller/LectureApiIntegrationTest.java apps/backend/src/test/java/com/itda/lecture/controller/LectureAuthenticatedApiIntegrationTest.java
git commit -m "feat(lecture): 강사의 학습자 발언 허용 API를 연다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(Step 7에서 문서 문장을 고쳤다면 `docs/product/api-spec.md` 도 이 커밋에 함께 넣는다.)

---

## 완료 확인 (MR 전)

- [ ] `cd apps/backend && ./gradlew test` BUILD SUCCESSFUL 을 확인했다
- [ ] `cd apps/backend && ./gradlew spotlessCheck` BUILD SUCCESSFUL, `git status --short` 가 비어 있음을 확인했다
- [ ] `docker build --target test apps/backend` 성공을 확인했다 (Docker가 없으면 "미실행"으로 보고한다 — MR verify가 같은 것을 돌린다)
- [ ] `git log --oneline feat/lecture-voice..HEAD` 가 커밋 5개(Task 1~5)이고, 모두 `type(lecture): 한글 제목` + 마지막 줄 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 임을 확인했다 (`git log --format=%B feat/lecture-voice..HEAD`)
- [ ] `git diff --stat feat/lecture-voice..HEAD` 에 File Structure 표 밖의 파일이 없음을 확인했다
- [ ] `grep -rn "Hidden" apps/backend/src/main` 출력이 없음을 확인했다
- [ ] api-spec §8.5.1의 경로·body·응답·오류 code·속성 키/값이 교차 계약(`voice-contract.md`)과 글자 그대로 같음을 확인했다
- [ ] push하지 않았다. mr-draft 스킬로 MR 초안(대상 `feat/lecture-voice`)을 만들어 사람에게 넘긴다
- [ ] 실서버 스모크(선택, 사람이 `.env` 를 준비한 경우): 강사 토큰·학습자 토큰으로 https://meet.livekit.io Custom 탭에 접속 → `PUT .../speaking {"allowed":true}` 200 → 학습자 탭에서 마이크 게시 가능 → `{"allowed":false}` 200 → 학습자 트랙이 내려감. 학습자 탭을 닫은 뒤 호출 → 404 `LECTURE_PARTICIPANT_NOT_CONNECTED`

## 알려진 한계 (후속 이슈 후보)

- **방 자체가 없을 때의 응답:** LiveKit은 방에 참가자가 없으면 404를 주지만, 방이 어느 노드에도 없을 때(모두 나가 방이 닫힘)는 404가 아닌 다른 오류를 줄 수 있다. 그러면 `LECTURE_PARTICIPANT_NOT_CONNECTED` 대신 503이 나간다. 강사가 접속해 있는 동안은 방이 있으므로 정상 흐름에는 영향이 없다. 실서버 스모크 마지막 항목에서 확인한다.
- **`canUpdateOwnMetadata` 의 범위:** 이 grant는 속성뿐 아니라 자기 표시 이름·metadata도 바꾸게 한다. 학습자가 `speaking` 속성이나 표시 이름을 스스로 바꿀 수 있다. `speaking` 은 표시용이고 실제 송출은 서버 권한으로만 열리므로 발언 통제는 깨지지 않지만, 강사 화면의 이름은 username과 다를 수 있다. 클라이언트는 identity(`user-{userId}`)를 기준으로 삼는다.
- **손 상태 경합:** 학습자가 손을 드는 순간 서버가 `hand=down` 을 쓰면 나중 쓰기가 이긴다. 영속하지 않는 표시 상태라 받아들인다.
- **재접속 초기화:** 학습자가 재접속하면 새 토큰이라 발언 허용이 풀린다(설계 §9 범위 밖). 강사가 다시 허용한다.
- **숨김 해제의 노출:** 학습자끼리 LiveKit 참가자 목록으로 서로의 username을 본다(설계 §3.1에서 수용). HTTP Snapshot 비노출은 그대로다.
- api-spec 머리말의 "수업 12개" endpoint 수는 media-tokens 추가 때부터 실제 표(이제 14행)와 맞지 않는다. 음성 계약과 무관한 문장이라 이 MR에서는 고치지 않는다.
