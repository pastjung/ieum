# 실시간 수업 API (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 강사가 수업을 만들고·시작·종료하고, 학습자가 코드로 입장하며, 둘 다 OpenVidu(LiveKit) 음성 토큰을 받는 HTTP API를 만든다.

**Architecture:** 기존 `lecture` 도메인(`Lecture`·`Participant`) 위에 `lecture/service`·`lecture/controller`·`lecture/dto` 를 얹는다. OpenVidu 연동은 `lecture/media/LiveRoomGateway` 인터페이스 뒤에 두고 `io.livekit:livekit-server` SDK 구현체가 토큰 서명과 방 생성·메타데이터·삭제를 맡는다. 상태 변경은 DB 커밋 뒤에 방을 조작하고, 방 조작 실패는 `503 MEDIA_SERVER_UNAVAILABLE` 로 돌려 같은 요청 재전송(멱등)으로 복구한다.

**Tech Stack:** Spring Boot 4.0.8, Java 25, Spring Data JPA, Spring Security(Access JWT), `io.livekit:livekit-server:0.16.0`, JUnit 5 + Mockito + AssertJ, nimbus-jose-jwt(토큰 검증 테스트)

**Spec:** `docs/superpowers/specs/2026-09-26-live-lecture-mvp-design.md` §2, §5 / 계약 원본 `docs/product/api-spec.md` §8

## Global Constraints

- 브랜치: `feat/lecture-api` (`feat/lecture` 에서 분기), MR 대상 `feat/lecture`
- 커밋: `type(lecture): 한글 제목` + `Co-Authored-By` 는 attribution 규칙대로
- context-path가 `/api/v1` 이므로 매핑·SecurityConfig에는 그 뒤 경로만 적는다
- ID는 JSON에서 10진수 문자열, 시각은 ISO 8601 UTC `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`
- 오류 code는 계약 문서(api-spec §8.4)에 먼저 적고 `ErrorCode` 에 추가한다 — `ErrorCode.java` 주석의 규칙
- ID 기반 자원에 권한이 없으면 존재를 숨기고 `404 LECTURE_NOT_FOUND`
- 방 이름 `lecture-{lectureId}`, 참가자 identity `user-{userId}`, 토큰 TTL 10분
- 강사 토큰: OPEN에서만, 오디오(microphone) publish. 학습자 토큰: SCHEDULED·OPEN의 활성 참여자, subscribe만
- 비밀값 기본값 금지 — `LIVEKIT_API_KEY`·`LIVEKIT_API_SECRET` 은 환경변수로만. `.env*` 파일은 읽거나 고치지 않는다(`.env.example` 키 추가는 사람에게 요청)
- 기존 `POST /lectures/{lectureId}/problems`(`ProblemController`)는 건드리지 않는다
- 검증: `cd apps/backend && ./gradlew test` 통과, 포맷은 `./gradlew spotlessApply`(google-java-format)

## File Structure

| 파일 | 책임 |
| --- | --- |
| `lecture/repository/ParticipantRepository.java` (신규) | participants 조회·저장 |
| `lecture/repository/LectureRepository.java` (수정) | 코드 조회·중복 확인, 강사별 페이지 조회 |
| `lecture/media/LiveRoomGateway.java` (신규) | OpenVidu 경계 인터페이스 + 요청/결과 record |
| `lecture/media/LectureRoom.java` (신규) | 방 이름·identity 규칙 한 곳 |
| `lecture/media/LiveKitProperties.java` (신규) | `itda.livekit.*` 설정 |
| `lecture/media/LiveKitRoomGateway.java` (신규) | SDK 구현체 |
| `lecture/media/LiveKitConfiguration.java` (신규) | bean 조립 |
| `lecture/service/ParticipationCodeGenerator.java` (신규) | 6자리 코드 난수 |
| `lecture/service/LectureSnapshot.java` (신규) | 서비스→컨트롤러 조회 결과 |
| `lecture/service/LectureService.java` (신규) | 생성·목록·조회·상태 변경 |
| `lecture/service/LectureParticipationService.java` (신규) | 코드 입장 |
| `lecture/service/LectureMediaService.java` (신규) | 음성 토큰 발급 권한 판정 |
| `lecture/dto/*.java` (신규) | 요청·응답 DTO |
| `lecture/controller/LectureController.java`, `LectureParticipationController.java` (신규) | HTTP 매핑 |
| `global/error/ErrorCode.java` (수정) | 오류 code 4개 추가 |
| `global/security/SecurityConfig.java` (수정) | 보호 경로 6개 추가 |

(경로의 기준은 `apps/backend/src/main/java/com/itda/`, 테스트는 `apps/backend/src/test/java/com/itda/`)

---

### Task 1: 계약 문서와 오류 code

**Files:**
- Modify: `docs/product/api-spec.md` §8 (A-21 행, §8.2 표, §8.4 표·마지막 문단, §9 표 OpenVidu 행)
- Modify: `apps/backend/src/main/java/com/itda/global/error/ErrorCode.java` (lecture 구역)

**Interfaces:**
- Produces: `ErrorCode.LEARNER_ACCESS_REQUIRED`, `LECTURE_PARTICIPATION_REQUIRED`, `LECTURE_NOT_JOINABLE`, `MEDIA_SERVER_UNAVAILABLE`

- [ ] **Step 1: api-spec §8.2 표에 음성 토큰 행 추가**

`POST /api/v1/lectures/{lectureId}/realtime-tickets` 행 아래에 추가한다.

```markdown
| `POST /api/v1/lectures/{lectureId}/media-tokens` | 201 | 담당 INSTRUCTOR(OPEN) 또는 SCHEDULED/OPEN의 활성 LEARNER. OpenVidu 음성 접속 토큰 발급 (8.5절). |
```

- [ ] **Step 2: api-spec에 §8.5 신설 (§8.4 뒤, §9 앞)**

```markdown
### 8.5 OpenVidu 음성 접속

OpenVidu 3.x(LiveKit 기반)를 사용한다. 음성 토큰은 participants 행·WebSocket 티켓과 별개이며 서버에 저장하지 않는다.

`POST /api/v1/lectures/{lectureId}/media-tokens` 는 body가 없고 응답은 다음과 같다.

​```json
{ "serverUrl": "wss://openvidu.i-eum.site", "token": "<LiveKit JWT>", "expiresAt": "2026-09-26T10:10:00.000Z" }
​```

| 요청자 | 허용 상태 | 권한 |
| --- | --- | --- |
| 담당 INSTRUCTOR | OPEN | 입장, 마이크 음성 publish, subscribe |
| 활성 참여 LEARNER (left_at NULL) | SCHEDULED, OPEN | 입장, subscribe만. publish·데이터 전송 불가 |

- 방 이름은 `lecture-{lectureId}`, 참가자 identity는 `user-{userId}`, 표시 이름은 username이다.
- 토큰 유효기간은 10분이다. 접속 중 갱신은 OpenVidu가 처리하며, 클라이언트는 재접속할 때만 새 토큰을 받는다.
- 강사가 OPEN으로 바꾸면 서버가 방을 만들고 방 metadata를 `{"status":"OPEN"}` 으로 설정한다. 학습자는 SCHEDULED에서 미리 접속해 대기할 수 있으므로 클라이언트는 접속 시 metadata와 metadata 변경 이벤트로 시작을 안다.
- 강사가 CLOSED로 바꾸면 서버가 방을 삭제한다. 클라이언트는 연결 해제 사유 `ROOM_DELETED` 를 수업 종료로, 그 밖의 해제를 음성 장애로 구분한다. 음성 장애가 곧 수업 종료는 아니며 `GET /api/v1/lectures/{lectureId}` 로 상태를 확인한다.
- 상태 저장이 끝난 뒤 방 생성·삭제가 실패하면 상태는 유지하고 `503 MEDIA_SERVER_UNAVAILABLE` 로 응답한다. 강사는 같은 상태로 다시 요청해 방 작업만 재시도한다(동일 상태 재요청은 현재 자원 — 8.2절).
```

(위 코드 펜스 앞의 `​` 는 중첩 펜스를 피하려는 표기다. 실제 문서에는 일반 ```` ```json ```` 펜스를 쓴다.)

- [ ] **Step 3: api-spec §8.4 표에 503 행 추가, OpenVidu "다음 스프린트" 문구 정리**

§8.4 표의 422 행 아래:

```markdown
| 503 | MEDIA_SERVER_UNAVAILABLE | OpenVidu 토큰 서명 설정 누락 또는 방 생성·삭제 호출 실패 |
```

§8.4 마지막 문단의 "버전 선정과 이에 의존하는 SDK·토큰 endpoint·배포·복구 계약은 다음 스프린트에서 검토한다" → "버전·토큰 endpoint·복구는 8.5절을 따른다" 로 바꾼다.
§8 첫 표 A-21 행의 "버전·SDK·토큰 endpoint·미디어 식별·배포·복구는 다음 스프린트" → "토큰·방 규칙은 8.5절". §9 표 OpenVidu 행의 계약 상태 → "8.5절 (OpenVidu 3.x, 토큰 endpoint·방 규칙 확정)".

- [ ] **Step 4: ErrorCode 추가**

`ErrorCode.java` 의 `// lecture` 구역을 다음으로 바꾼다.

```java
  // lecture (docs/product/api-spec.md 8.4·8.5절)
  INSTRUCTOR_ACCESS_REQUIRED(HttpStatus.FORBIDDEN, "강사 권한이 필요합니다."),
  LEARNER_ACCESS_REQUIRED(HttpStatus.FORBIDDEN, "학습자 권한이 필요합니다."),
  LECTURE_PARTICIPATION_REQUIRED(HttpStatus.FORBIDDEN, "수업에 다시 입장해 주세요."),
  LECTURE_NOT_FOUND(HttpStatus.NOT_FOUND, "수업을 찾을 수 없습니다."),
  /** 코드가 없는 경우와 CLOSED인 경우를 구분하지 않는다 — 구분하면 코드 존재 여부를 탐색할 수 있다 (8.4절). */
  LECTURE_NOT_JOINABLE(HttpStatus.NOT_FOUND, "입장할 수 있는 수업이 없습니다. 참여 코드를 확인해 주세요."),
  INVALID_LECTURE_STATE(HttpStatus.CONFLICT, "현재 수업 상태에서는 요청을 처리할 수 없습니다."),
  MEDIA_SERVER_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "음성 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),
```

- [ ] **Step 5: 기존 ErrorCode 규칙 테스트 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.global.error.ErrorCodeTest"`
Expected: PASS (새 code도 UPPER_SNAKE_CASE·숫자 suffix 없음 규칙을 지킨다)

- [ ] **Step 6: Commit**

```bash
git add docs/product/api-spec.md apps/backend/src/main/java/com/itda/global/error/ErrorCode.java
git commit -m "docs(lecture): OpenVidu 음성 토큰 계약과 수업 오류 code를 정한다"
```

---

### Task 2: 저장소 — 참여자 리포지토리와 수업 조회

**Files:**
- Create: `lecture/repository/ParticipantRepository.java`
- Modify: `lecture/repository/LectureRepository.java`
- Test: `lecture/repository/LectureRepositoryTest.java` (신규, `@DataJpaTest`)

**Interfaces:**
- Produces:
  - `LectureRepository.findByParticipationCode(String): Optional<Lecture>`
  - `LectureRepository.existsByParticipationCode(String): boolean`
  - `LectureRepository.findAllByInstructorUserId(Long, Pageable): Page<Lecture>`
  - `LectureRepository.findAllByInstructorUserIdAndStatus(Long, LectureStatus, Pageable): Page<Lecture>`
  - `ParticipantRepository extends JpaRepository<Participant, ParticipantId>` + `findAllByIdLectureIdAndLeftAtIsNull(Long): List<Participant>`

- [ ] **Step 1: 실패하는 테스트 작성**

`lecture/domain/LectureJpaMappingTest.java` 가 `@DataJpaTest` 를 쓰는 방식을 먼저 읽고 같은 어노테이션 조합을 쓴다. `lectures.instructor_user_id` 에 users FK가 있으면 그 테스트가 사용자를 만드는 방식을 그대로 따라 `instructorId` 를 준비한다.

```java
package com.itda.lecture.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest
class LectureRepositoryTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Autowired private LectureRepository lectureRepository;
  @Autowired private ParticipantRepository participantRepository;

  private Lecture save(long instructorId, String code) {
    return lectureRepository.save(Lecture.schedule(instructorId, "약자 4회차", null, code, null, NOW));
  }

  @Test
  void 참여_코드로_수업을_찾는다() {
    Lecture saved = save(1L, "012345");

    assertThat(lectureRepository.findByParticipationCode("012345")).get().isEqualTo(saved);
    assertThat(lectureRepository.existsByParticipationCode("012345")).isTrue();
    assertThat(lectureRepository.existsByParticipationCode("999999")).isFalse();
  }

  @Test
  void 강사_본인_수업만_상태별로_페이지_조회한다() {
    save(1L, "000001");
    Lecture opened = save(1L, "000002");
    opened.open(NOW);
    save(2L, "000003");
    PageRequest page = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

    Page<Lecture> all = lectureRepository.findAllByInstructorUserId(1L, page);
    Page<Lecture> open =
        lectureRepository.findAllByInstructorUserIdAndStatus(1L, LectureStatus.OPEN, page);

    assertThat(all.getTotalElements()).isEqualTo(2);
    assertThat(open.getContent()).containsExactly(opened);
  }

  @Test
  void 수업의_활성_참여자만_찾는다() {
    Lecture lecture = save(1L, "000004");
    Participant active = participantRepository.save(Participant.join(lecture.getId(), 10L, NOW));
    Participant left = participantRepository.save(Participant.join(lecture.getId(), 11L, NOW));
    left.leave(NOW);
    participantRepository.flush();

    List<Participant> found =
        participantRepository.findAllByIdLectureIdAndLeftAtIsNull(lecture.getId());

    assertThat(found).extracting(Participant::getId).containsExactly(active.getId());
    assertThat(participantRepository.findById(ParticipantId.of(lecture.getId(), 11L))).isPresent();
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.itda.lecture.repository.LectureRepositoryTest"`
Expected: 컴파일 실패 — `ParticipantRepository`, `findByParticipationCode` 등이 없다

- [ ] **Step 3: 구현**

`ParticipantRepository.java`:

```java
package com.itda.lecture.repository;

import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantRepository extends JpaRepository<Participant, ParticipantId> {

  /** 수업 종료 때 left_at이 NULL인 참여자에게 종료 시각을 기록한다 (ERD 3.3절). */
  List<Participant> findAllByIdLectureIdAndLeftAtIsNull(Long lectureId);
}
```

`LectureRepository.java` 인터페이스 본문에 추가(기존 `findById` 선언은 그대로 둔다):

```java
  Optional<Lecture> findByParticipationCode(String participationCode);

  boolean existsByParticipationCode(String participationCode);

  Page<Lecture> findAllByInstructorUserId(Long instructorUserId, Pageable pageable);

  Page<Lecture> findAllByInstructorUserIdAndStatus(
      Long instructorUserId, LectureStatus status, Pageable pageable);
```

import: `com.itda.lecture.domain.LectureStatus`, `org.springframework.data.domain.Page`, `org.springframework.data.domain.Pageable`.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.itda.lecture.repository.LectureRepositoryTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add apps/backend/src/main/java/com/itda/lecture/repository apps/backend/src/test/java/com/itda/lecture/repository
git commit -m "feat(lecture): 참여 코드·강사별 수업 조회와 참여자 저장소를 추가한다"
```

---

### Task 3: OpenVidu 게이트웨이 — 토큰 서명과 방 조작

**Files:**
- Modify: `apps/backend/build.gradle` (dependencies, `applyTestSecrets`)
- Modify: `apps/backend/src/main/resources/application.yml` (`itda:` 아래)
- Create: `lecture/media/LiveRoomGateway.java`, `LectureRoom.java`, `LiveKitProperties.java`, `LiveKitRoomGateway.java`, `LiveKitConfiguration.java`
- Test: `lecture/media/LiveKitRoomGatewayTest.java`, `lecture/media/LiveKitPropertiesTest.java`

**Interfaces:**
- Produces:
  - `interface LiveRoomGateway { IssuedMediaToken issueToken(MediaTokenRequest request); void openRoom(String roomName); void deleteRoom(String roomName); }`
  - `record MediaTokenRequest(String roomName, String identity, String displayName, boolean publisher)`
  - `record IssuedMediaToken(String serverUrl, String token, Instant expiresAt)`
  - `LectureRoom.nameOf(long lectureId): String` → `"lecture-42"`, `LectureRoom.identityOf(long userId): String` → `"user-7"`
  - 방 조작 실패 시 `BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE)`

- [ ] **Step 1: 의존성·설정 추가**

`build.gradle` dependencies의 nimbus 블록 아래에 추가:

```groovy
    // OpenVidu 3.x(LiveKit) 음성 토큰 서명·방 관리 (docs/product/api-spec.md 8.5절).
    // Boot BOM이 관리하지 않아 버전을 명시 고정한다. Kotlin 표준 라이브러리·OkHttp·Retrofit이 함께 들어온다.
    implementation 'io.livekit:livekit-server:0.16.0'
```

`applyTestSecrets` 의 `doFirst` 블록 끝에 추가:

```groovy
        // OpenVidu 설정. 테스트는 실제 서버를 부르지 않는다 — 게이트웨이 단위 테스트는 토큰 서명만 검증한다.
        task.systemProperty 'LIVEKIT_URL', 'https://openvidu.test.invalid'
        task.systemProperty 'LIVEKIT_API_KEY', 'test-livekit-key'
        task.systemProperty 'LIVEKIT_API_SECRET', randomKey()
```

`application.yml` 의 `itda:` 아래 `ai:` 블록 다음에 추가:

```yaml
  # OpenVidu 3.x(LiveKit). 서버 API와 클라이언트 접속 주소가 같은 공개 도메인이다 — 백엔드 컨테이너에서
  # 127.0.0.1은 호스트가 아니기 때문이다. 클라이언트에는 https를 wss로 바꿔 내려준다 (LiveKitProperties).
  # key·secret은 /opt/openvidu/config/openvidu.env 의 LiveKit 값이며 기본값을 두지 않는다.
  livekit:
    url: ${LIVEKIT_URL}
    api-key: ${LIVEKIT_API_KEY}
    api-secret: ${LIVEKIT_API_SECRET}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`LiveKitPropertiesTest.java`:

```java
package com.itda.lecture.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LiveKitPropertiesTest {

  @Test
  void 클라이언트_주소는_https를_wss로_바꾼다() {
    LiveKitProperties properties =
        new LiveKitProperties("https://openvidu.i-eum.site", "key", "secret");

    assertThat(properties.clientUrl()).isEqualTo("wss://openvidu.i-eum.site");
  }

  @Test
  void 미해결_플레이스홀더나_빈_값이면_기동을_막는다() {
    // Spring은 미해결 ${...}를 예외 없이 리터럴로 넘긴다 (application.yml local 프로필 주석 참고).
    assertThatThrownBy(() -> new LiveKitProperties("${LIVEKIT_URL}", "key", "secret"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LiveKitProperties("https://x", " ", "secret"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

`LiveKitRoomGatewayTest.java` — 서명된 JWT를 nimbus로 풀어 권한을 확인한다. 방 조작은 실제 서버가 필요하므로 여기서 검증하지 않는다(E2E).

```java
package com.itda.lecture.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.livekit.server.RoomServiceClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveKitRoomGatewayTest {

  private static final String SECRET = "test-secret-at-least-32-bytes-long-000";
  private static final Instant NOW = Instant.parse("2026-09-26T01:00:00Z");

  private final LiveKitProperties properties =
      new LiveKitProperties("https://openvidu.i-eum.site", "test-key", SECRET);
  private final LiveKitRoomGateway gateway =
      new LiveKitRoomGateway(
          properties,
          RoomServiceClient.createClient(properties.url(), properties.apiKey(), properties.apiSecret()),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private JWTClaimsSet verifiedClaims(String token) throws Exception {
    SignedJWT jwt = SignedJWT.parse(token);
    assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
    return jwt.getJWTClaimsSet();
  }

  @Test
  void 강사_토큰은_마이크_송출을_허용한다() throws Exception {
    IssuedMediaToken issued =
        gateway.issueToken(new MediaTokenRequest("lecture-42", "user-7", "teacher", true));

    JWTClaimsSet claims = verifiedClaims(issued.token());
    Map<String, Object> video = claims.getJSONObjectClaim("video");
    assertThat(claims.getSubject()).isEqualTo("user-7");
    assertThat(claims.getIssuer()).isEqualTo("test-key");
    assertThat(video).containsEntry("room", "lecture-42").containsEntry("roomJoin", true);
    assertThat(video).containsEntry("canPublish", true).containsEntry("canSubscribe", true);
    assertThat(video.get("canPublishSources")).isEqualTo(List.of("microphone"));
    assertThat(issued.serverUrl()).isEqualTo("wss://openvidu.i-eum.site");
    assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(600));
  }

  @Test
  void 학습자_토큰은_수신만_허용한다() throws Exception {
    IssuedMediaToken issued =
        gateway.issueToken(new MediaTokenRequest("lecture-42", "user-8", "learner", false));

    Map<String, Object> video = verifiedClaims(issued.token()).getJSONObjectClaim("video");
    assertThat(video).containsEntry("canPublish", false).containsEntry("canPublishData", false);
    assertThat(video).containsEntry("canSubscribe", true);
    assertThat(video).doesNotContainKey("canPublishSources");
  }

  @Test
  void 방_이름과_identity_규칙() {
    assertThat(LectureRoom.nameOf(42L)).isEqualTo("lecture-42");
    assertThat(LectureRoom.identityOf(7L)).isEqualTo("user-7");
  }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests "com.itda.lecture.media.*"`
Expected: 컴파일 실패 — `LiveKitProperties`, `LiveKitRoomGateway` 등이 없다

- [ ] **Step 4: 구현**

`LiveRoomGateway.java`:

```java
package com.itda.lecture.media;

import java.time.Instant;

/**
 * OpenVidu(LiveKit) 경계. 서비스는 SDK를 모르고 이 인터페이스만 본다 — 테스트에서 가짜로 바꿔 끼운다.
 *
 * <p>방 조작이 실패하면 {@code BusinessException(MEDIA_SERVER_UNAVAILABLE)}을 던진다 (API 명세 8.5절).
 */
public interface LiveRoomGateway {

  IssuedMediaToken issueToken(MediaTokenRequest request);

  /** 방을 만들고(이미 있으면 그대로) metadata를 OPEN으로 설정한다. 여러 번 불러도 결과가 같다. */
  void openRoom(String roomName);

  /** 방을 삭제한다. 이미 없으면 성공으로 본다. 참가자는 ROOM_DELETED 사유로 연결이 끊긴다. */
  void deleteRoom(String roomName);

  /** @param publisher true면 마이크 음성 송출 권한(강사), false면 수신만(학습자) */
  record MediaTokenRequest(String roomName, String identity, String displayName, boolean publisher) {}

  record IssuedMediaToken(String serverUrl, String token, Instant expiresAt) {}
}
```

테스트가 `MediaTokenRequest`·`IssuedMediaToken` 을 패키지에서 바로 쓰므로, 테스트 파일에 `import com.itda.lecture.media.LiveRoomGateway.IssuedMediaToken;` 과 `import com.itda.lecture.media.LiveRoomGateway.MediaTokenRequest;` 를 추가한다.

`LectureRoom.java`:

```java
package com.itda.lecture.media;

/** 수업 ↔ OpenVidu 방·참가자 식별 규칙 (API 명세 8.5절). 규칙이 흩어지면 웹·앱과 어긋난다. */
public final class LectureRoom {

  private LectureRoom() {}

  public static String nameOf(long lectureId) {
    return "lecture-" + lectureId;
  }

  public static String identityOf(long userId) {
    return "user-" + userId;
  }
}
```

`LiveKitProperties.java`:

```java
package com.itda.lecture.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code itda.livekit.*}. 기본값을 두지 않고, 빠지거나 미해결 플레이스홀더면 기동 단계에서 실패한다.
 *
 * @param url 서버 API 주소 (https). 클라이언트 접속 주소는 {@link #clientUrl()}
 */
@ConfigurationProperties(prefix = "itda.livekit")
public record LiveKitProperties(String url, String apiKey, String apiSecret) {

  public LiveKitProperties {
    requireValue(url, "itda.livekit.url");
    requireValue(apiKey, "itda.livekit.api-key");
    requireValue(apiSecret, "itda.livekit.api-secret");
  }

  /** 클라이언트 SDK는 WebSocket 주소로 접속한다. */
  public String clientUrl() {
    return url.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
  }

  private static void requireValue(String value, String name) {
    if (value == null || value.isBlank() || value.startsWith("${")) {
      throw new IllegalArgumentException(name + " 설정이 필요합니다.");
    }
  }
}
```

`LiveKitRoomGateway.java`:

```java
package com.itda.lecture.media;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.VideoGrant;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Call;
import retrofit2.Response;

@Slf4j
public class LiveKitRoomGateway implements LiveRoomGateway {

  /** API 명세 8.5절. 접속 중 갱신은 서버가 하므로 재접속 창만 덮으면 된다. */
  private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

  /** 강사가 한동안 비워 둔 방을 서버가 정리하는 시간. 종료는 deleteRoom이 담당한다. */
  private static final int EMPTY_TIMEOUT_SECONDS = 600;

  static final String OPEN_METADATA = "{\"status\":\"OPEN\"}";

  private static final int NOT_FOUND = 404;

  private final LiveKitProperties properties;
  private final RoomServiceClient roomServiceClient;
  private final Clock clock;

  public LiveKitRoomGateway(
      LiveKitProperties properties, RoomServiceClient roomServiceClient, Clock clock) {
    this.properties = properties;
    this.roomServiceClient = roomServiceClient;
    this.clock = clock;
  }

  @Override
  public IssuedMediaToken issueToken(MediaTokenRequest request) {
    AccessToken token = new AccessToken(properties.apiKey(), properties.apiSecret());
    token.setIdentity(request.identity());
    token.setName(request.displayName());
    token.setTtl(TOKEN_TTL.toMillis());

    List<VideoGrant> grants = new ArrayList<>();
    grants.add(new RoomJoin(true));
    grants.add(new RoomName(request.roomName()));
    grants.add(new CanSubscribe(true));
    grants.add(new CanPublishData(false));
    if (request.publisher()) {
      grants.add(new CanPublish(true));
      grants.add(new CanPublishSources(List.of("microphone")));
    } else {
      grants.add(new CanPublish(false));
    }
    token.addGrants(grants.toArray(VideoGrant[]::new));

    return new IssuedMediaToken(
        properties.clientUrl(), token.toJwt(), clock.instant().plus(TOKEN_TTL));
  }

  @Override
  public void openRoom(String roomName) {
    // 학습자가 SCHEDULED에서 먼저 접속하면 LiveKit이 metadata 없는 방을 자동 생성해 둔다.
    // 그 경우 createRoom은 기존 방을 돌려줄 뿐 metadata를 바꾸지 않으므로 갱신을 항상 함께 부른다.
    execute(
        roomServiceClient.createRoom(roomName, EMPTY_TIMEOUT_SECONDS, null, null, OPEN_METADATA),
        "createRoom",
        false);
    execute(roomServiceClient.updateRoomMetadata(roomName, OPEN_METADATA), "updateRoomMetadata", false);
  }

  @Override
  public void deleteRoom(String roomName) {
    execute(roomServiceClient.deleteRoom(roomName), "deleteRoom", true);
  }

  private void execute(Call<?> call, String operation, boolean notFoundIsDone) {
    try {
      Response<?> response = call.execute();
      if (response.isSuccessful() || (notFoundIsDone && response.code() == NOT_FOUND)) {
        return;
      }
      log.warn("OpenVidu {} 실패: HTTP {}", operation, response.code());
    } catch (IOException e) {
      log.warn("OpenVidu {} 호출 실패: {}", operation, e.getClass().getSimpleName());
    }
    throw new BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
}
```

SDK 0.16.0에서 grant 클래스 이름·`addGrants` 시그니처가 다르면 `~/.gradle` 의 `livekit-server-0.16.0-sources.jar` 또는 IDE에서 `io.livekit.server.Grants` 를 열어 실제 이름에 맞춘다. 테스트가 검증하는 JWT claim(`video.canPublish` 등)이 기준이다.

`LiveKitConfiguration.java`:

```java
package com.itda.lecture.media;

import io.livekit.server.RoomServiceClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiveKitProperties.class)
class LiveKitConfiguration {

  @Bean
  LiveRoomGateway liveRoomGateway(LiveKitProperties properties) {
    return new LiveKitRoomGateway(
        properties,
        RoomServiceClient.createClient(properties.url(), properties.apiKey(), properties.apiSecret()),
        Clock.systemUTC());
  }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "com.itda.lecture.media.*"`
Expected: PASS (5 tests)

- [ ] **Step 6: 전체 context 기동 확인**

Run: `./gradlew test --tests "com.itda.ItdaApplicationTests"`
Expected: PASS — `applyTestSecrets` 의 LIVEKIT_* 로 `LiveKitProperties` 가 바인딩된다

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add apps/backend/build.gradle apps/backend/src/main/resources/application.yml apps/backend/src/main/java/com/itda/lecture/media apps/backend/src/test/java/com/itda/lecture/media
git commit -m "feat(lecture): OpenVidu 음성 토큰 서명과 방 관리 게이트웨이를 추가한다"
```

---

### Task 4: 수업 생성·목록·조회·상태 변경 서비스

**Files:**
- Create: `lecture/service/ParticipationCodeGenerator.java`, `LectureSnapshot.java`, `LectureService.java`
- Test: `lecture/service/LectureServiceTest.java`, `lecture/service/RecordingLiveRoomGateway.java`(테스트용 가짜)

**Interfaces:**
- Consumes: Task 2 저장소, Task 3 `LiveRoomGateway`·`LectureRoom`, Task 1 `ErrorCode`
- Produces:
  - `ParticipationCodeGenerator#next(): String` (6자리 숫자)
  - `record LectureSnapshot(Lecture lecture, Participant participation)` — `participation == null` 이면 강사 보기. 팩토리 `ofInstructor(Lecture)`, `ofLearner(Lecture, Participant)`, `isInstructorView()`
  - `LectureService#create(AuthenticatedUser, String title, String description, LocalDateTime scheduledAt): LectureSnapshot`
  - `LectureService#listMine(AuthenticatedUser, LectureStatus statusOrNull, int page, int size): Page<Lecture>`
  - `LectureService#find(AuthenticatedUser, long lectureId): LectureSnapshot`
  - `LectureService#changeStatus(AuthenticatedUser, long lectureId, LectureStatus target): LectureSnapshot`
  - 테스트 지원: `RecordingLiveRoomGateway` — `openedRooms`, `deletedRooms`, `issuedRequests` 리스트와 `failRoomOperations` 플래그

- [ ] **Step 1: 테스트용 가짜 게이트웨이**

`src/test/java/com/itda/lecture/service/RecordingLiveRoomGateway.java`:

```java
package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.lecture.media.LiveRoomGateway;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 호출을 기록하는 가짜 OpenVidu. 저장소의 Recording* 테스트 대역과 같은 방식이다. */
class RecordingLiveRoomGateway implements LiveRoomGateway {

  final List<String> openedRooms = new ArrayList<>();
  final List<String> deletedRooms = new ArrayList<>();
  final List<MediaTokenRequest> issuedRequests = new ArrayList<>();
  boolean failRoomOperations;

  @Override
  public IssuedMediaToken issueToken(MediaTokenRequest request) {
    issuedRequests.add(request);
    return new IssuedMediaToken("wss://test", "token-" + request.identity(), Instant.EPOCH);
  }

  @Override
  public void openRoom(String roomName) {
    failIfRequested();
    openedRooms.add(roomName);
  }

  @Override
  public void deleteRoom(String roomName) {
    failIfRequested();
    deletedRooms.add(roomName);
  }

  private void failIfRequested() {
    if (failRoomOperations) {
      throw new BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    }
  }
}
```

- [ ] **Step 2: 실패하는 서비스 테스트 작성**

`LectureServiceTest.java`:

```java
package com.itda.lecture.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LectureServiceTest {

  private static final long LECTURE_ID = 42L;
  private static final AuthenticatedUser INSTRUCTOR = new AuthenticatedUser(7L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser OTHER_INSTRUCTOR =
      new AuthenticatedUser(9L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser LEARNER = new AuthenticatedUser(8L, UserRole.LEARNER);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Mock private LectureRepository lectureRepository;
  @Mock private ParticipantRepository participantRepository;
  @Mock private ParticipationCodeGenerator codeGenerator;

  private RecordingLiveRoomGateway gateway;
  private LectureService service;

  @BeforeEach
  void setUp() {
    gateway = new RecordingLiveRoomGateway();
    service =
        new LectureService(
            lectureRepository,
            participantRepository,
            codeGenerator,
            gateway,
            TransactionOperations.withoutTransaction());
    given(lectureRepository.save(any(Lecture.class))).willAnswer(call -> call.getArgument(0));
  }

  private Lecture lecture(long instructorId) {
    Lecture lecture = Lecture.schedule(instructorId, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(lecture, "id", LECTURE_ID);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(lecture));
    return lecture;
  }

  @Test
  void 강사가_만들면_SCHEDULED와_중복되지_않는_코드를_받는다() {
    given(codeGenerator.next()).willReturn("111111", "222222");
    given(lectureRepository.existsByParticipationCode("111111")).willReturn(true);
    given(lectureRepository.existsByParticipationCode("222222")).willReturn(false);

    LectureSnapshot created = service.create(INSTRUCTOR, "약자 4회차", "설명", null);

    assertThat(created.isInstructorView()).isTrue();
    assertThat(created.lecture().getStatus()).isEqualTo(LectureStatus.SCHEDULED);
    assertThat(created.lecture().getParticipationCode()).isEqualTo("222222");
    assertThat(created.lecture().getInstructorUserId()).isEqualTo(7L);
  }

  @Test
  void 학습자는_수업을_만들_수_없다() {
    assertThatThrownBy(() -> service.create(LEARNER, "제목", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
  }

  @Test
  void 담당_강사는_강사_보기로_조회한다() {
    lecture(7L);

    assertThat(service.find(INSTRUCTOR, LECTURE_ID).isInstructorView()).isTrue();
  }

  @Test
  void 참여한_학습자는_학습자_보기로_조회한다() {
    lecture(7L);
    Participant participant = Participant.join(LECTURE_ID, 8L, NOW);
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.of(participant));

    LectureSnapshot snapshot = service.find(LEARNER, LECTURE_ID);

    assertThat(snapshot.isInstructorView()).isFalse();
    assertThat(snapshot.participation()).isSameAs(participant);
  }

  @Test
  void 다른_강사와_참여하지_않은_학습자에게는_존재를_숨긴다() {
    lecture(7L);
    given(participantRepository.findById(any())).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.find(OTHER_INSTRUCTOR, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_FOUND);
    assertThatThrownBy(() -> service.find(LEARNER, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_FOUND);
  }

  @Test
  void 시작하면_OPEN이_되고_방을_연다() {
    Lecture lecture = lecture(7L);

    service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.OPEN);

    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.OPEN);
    assertThat(lecture.getStartedAt()).isNotNull();
    assertThat(gateway.openedRooms).containsExactly("lecture-42");
  }

  @Test
  void 종료하면_활성_참여자를_퇴장시키고_방을_삭제한다() {
    Lecture lecture = lecture(7L);
    lecture.open(NOW);
    Participant active = Participant.join(LECTURE_ID, 8L, NOW);
    given(participantRepository.findAllByIdLectureIdAndLeftAtIsNull(LECTURE_ID))
        .willReturn(List.of(active));

    service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.CLOSED);

    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.CLOSED);
    assertThat(active.isParticipating()).isFalse();
    assertThat(gateway.deletedRooms).containsExactly("lecture-42");
  }

  @Test
  void 같은_상태_재요청은_상태를_바꾸지_않고_방_작업만_다시_한다() {
    Lecture lecture = lecture(7L);
    lecture.open(NOW);
    LocalDateTime startedAt = lecture.getStartedAt();

    service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.OPEN);

    assertThat(lecture.getStartedAt()).isEqualTo(startedAt);
    assertThat(gateway.openedRooms).containsExactly("lecture-42");
  }

  @Test
  void 건너뛰거나_되돌리는_전이는_409다() {
    lecture(7L);

    assertThatThrownBy(() -> service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.CLOSED))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_LECTURE_STATE);
    assertThatThrownBy(
            () -> service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.SCHEDULED))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_LECTURE_STATE);
    assertThat(gateway.openedRooms).isEmpty();
    assertThat(gateway.deletedRooms).isEmpty();
  }

  @Test
  void 방_작업이_실패해도_상태는_저장된_채_503이다() {
    Lecture lecture = lecture(7L);
    gateway.failRoomOperations = true;

    assertThatThrownBy(() -> service.changeStatus(INSTRUCTOR, LECTURE_ID, LectureStatus.OPEN))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    assertThat(lecture.getStatus()).isEqualTo(LectureStatus.OPEN);
  }

  @Test
  void 다른_강사는_상태를_바꿀_수_없다() {
    lecture(7L);

    assertThatThrownBy(
            () -> service.changeStatus(OTHER_INSTRUCTOR, LECTURE_ID, LectureStatus.OPEN))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_FOUND);
  }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests "com.itda.lecture.service.LectureServiceTest"`
Expected: 컴파일 실패 — `LectureService` 등이 없다

- [ ] **Step 4: 구현**

`ParticipationCodeGenerator.java`:

```java
package com.itda.lecture.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 앞자리 0을 허용하는 숫자 6자리 참여 코드 (ERD 3.2절). 추측을 어렵게 하려고 SecureRandom을 쓴다. */
@Component
public class ParticipationCodeGenerator {

  private static final int CODE_SPACE = 1_000_000;

  private final SecureRandom random = new SecureRandom();

  public String next() {
    return String.format("%06d", random.nextInt(CODE_SPACE));
  }
}
```

`LectureSnapshot.java`:

```java
package com.itda.lecture.service;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;

/**
 * 요청자 관점의 수업 조회 결과. 강사와 학습자는 응답 필드가 다르다 (API 명세 8.2절).
 *
 * @param participation 학습자 보기에서만 값이 있다
 */
public record LectureSnapshot(Lecture lecture, Participant participation) {

  public static LectureSnapshot ofInstructor(Lecture lecture) {
    return new LectureSnapshot(lecture, null);
  }

  public static LectureSnapshot ofLearner(Lecture lecture, Participant participation) {
    return new LectureSnapshot(lecture, participation);
  }

  public boolean isInstructorView() {
    return participation == null;
  }
}
```

`LectureService.java`:

```java
package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.media.LectureRoom;
import com.itda.lecture.media.LiveRoomGateway;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class LectureService {

  /** 코드 공간이 100만이라 충돌은 드물다. 이 횟수를 넘기면 코드 공간 고갈을 의심해야 한다. */
  private static final int MAX_CODE_ATTEMPTS = 10;

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;
  private final ParticipationCodeGenerator codeGenerator;
  private final LiveRoomGateway liveRoomGateway;
  private final TransactionOperations transactionOperations;

  public LectureService(
      LectureRepository lectureRepository,
      ParticipantRepository participantRepository,
      ParticipationCodeGenerator codeGenerator,
      LiveRoomGateway liveRoomGateway,
      TransactionOperations transactionOperations) {
    this.lectureRepository = lectureRepository;
    this.participantRepository = participantRepository;
    this.codeGenerator = codeGenerator;
    this.liveRoomGateway = liveRoomGateway;
    this.transactionOperations = transactionOperations;
  }

  @Transactional
  public LectureSnapshot create(
      AuthenticatedUser user, String title, String description, LocalDateTime scheduledAt) {
    requireInstructor(user);
    Lecture lecture =
        Lecture.schedule(user.userId(), title, description, uniqueCode(), scheduledAt, now());
    return LectureSnapshot.ofInstructor(lectureRepository.save(lecture));
  }

  @Transactional(readOnly = true)
  public Page<Lecture> listMine(AuthenticatedUser user, LectureStatus status, int page, int size) {
    requireInstructor(user);
    PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    return status == null
        ? lectureRepository.findAllByInstructorUserId(user.userId(), pageable)
        : lectureRepository.findAllByInstructorUserIdAndStatus(user.userId(), status, pageable);
  }

  @Transactional(readOnly = true)
  public LectureSnapshot find(AuthenticatedUser user, long lectureId) {
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    if (isOwner(user, lecture)) {
      return LectureSnapshot.ofInstructor(lecture);
    }
    if (user.role() == UserRole.LEARNER) {
      return participantRepository
          .findById(ParticipantId.of(lectureId, user.userId()))
          .map(participant -> LectureSnapshot.ofLearner(lecture, participant))
          .orElseThrow(LectureService::notFound);
    }
    throw notFound();
  }

  /**
   * 상태를 저장한 <b>뒤</b> 방을 조작한다. 트랜잭션 안에서 외부 호출을 하면 OpenVidu 지연이 lectures 행 잠금을 붙들고, 호출은 성공했는데
   * 커밋이 실패하면 되돌릴 수 없다. 방 조작이 실패하면 상태는 남고 503이 나가며, 강사가 같은 상태를 다시 보내면 전이는 건너뛰고 방 조작만
   * 재시도된다 (API 명세 8.5절).
   */
  public LectureSnapshot changeStatus(
      AuthenticatedUser user, long lectureId, LectureStatus target) {
    Lecture lecture = transactionOperations.execute(tx -> transition(user, lectureId, target));
    String room = LectureRoom.nameOf(lectureId);
    if (target == LectureStatus.OPEN) {
      liveRoomGateway.openRoom(room);
    } else {
      liveRoomGateway.deleteRoom(room);
    }
    return LectureSnapshot.ofInstructor(lecture);
  }

  private Lecture transition(AuthenticatedUser user, long lectureId, LectureStatus target) {
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    if (!isOwner(user, lecture)) {
      throw notFound();
    }
    if (target == LectureStatus.SCHEDULED) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    if (lecture.getStatus() == target) {
      return lecture;
    }
    LocalDateTime now = now();
    if (target == LectureStatus.OPEN && lecture.getStatus() == LectureStatus.SCHEDULED) {
      lecture.open(now);
    } else if (target == LectureStatus.CLOSED && lecture.getStatus() == LectureStatus.OPEN) {
      lecture.close(now);
      participantRepository
          .findAllByIdLectureIdAndLeftAtIsNull(lectureId)
          .forEach(participant -> participant.leave(now));
    } else {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    return lecture;
  }

  private String uniqueCode() {
    for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
      String code = codeGenerator.next();
      if (!lectureRepository.existsByParticipationCode(code)) {
        return code;
      }
    }
    throw new IllegalStateException("참여 코드를 발급하지 못했습니다.");
  }

  static boolean isOwner(AuthenticatedUser user, Lecture lecture) {
    return user.role() == UserRole.INSTRUCTOR
        && lecture.getInstructorUserId() == user.userId();
  }

  private static void requireInstructor(AuthenticatedUser user) {
    if (user.role() != UserRole.INSTRUCTOR) {
      throw new BusinessException(ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
    }
  }

  static BusinessException notFound() {
    return new BusinessException(ErrorCode.LECTURE_NOT_FOUND);
  }

  static LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
```

주의: `lecture.getInstructorUserId()` 는 `Long` 이다. `== user.userId()`(long)는 언박싱 비교라 안전하다. `Long` 끼리 `==` 로 바꾸지 않는다.

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "com.itda.lecture.service.LectureServiceTest"`
Expected: PASS (10 tests)

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add apps/backend/src/main/java/com/itda/lecture/service apps/backend/src/test/java/com/itda/lecture/service
git commit -m "feat(lecture): 수업 생성·조회·상태 변경과 방 열기·닫기를 연결한다"
```

---

### Task 5: 코드 입장과 음성 토큰 서비스

**Files:**
- Create: `lecture/service/LectureParticipationService.java`, `LectureMediaService.java`
- Test: `lecture/service/LectureParticipationServiceTest.java`, `LectureMediaServiceTest.java`

**Interfaces:**
- Consumes: Task 4 `LectureSnapshot`, `LectureService.isOwner/notFound/now`(package-private static), `RecordingLiveRoomGateway`
- Produces:
  - `record ParticipationResult(LectureSnapshot snapshot, boolean firstJoin)` (LectureParticipationService 안의 중첩 record)
  - `LectureParticipationService#join(AuthenticatedUser, String participationCode): ParticipationResult`
  - `LectureMediaService#issue(AuthenticatedUser, long lectureId): LiveRoomGateway.IssuedMediaToken`

- [ ] **Step 1: 실패하는 테스트 작성**

`LectureParticipationServiceTest.java`:

```java
package com.itda.lecture.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
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
class LectureParticipationServiceTest {

  private static final long LECTURE_ID = 42L;
  private static final AuthenticatedUser LEARNER = new AuthenticatedUser(8L, UserRole.LEARNER);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Mock private LectureRepository lectureRepository;
  @Mock private ParticipantRepository participantRepository;

  private LectureParticipationService service;
  private Lecture lecture;

  @BeforeEach
  void setUp() {
    service = new LectureParticipationService(lectureRepository, participantRepository);
    lecture = Lecture.schedule(7L, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(lecture, "id", LECTURE_ID);
    given(lectureRepository.findByParticipationCode("012345")).willReturn(Optional.of(lecture));
    given(participantRepository.save(any(Participant.class)))
        .willAnswer(call -> call.getArgument(0));
  }

  @Test
  void 처음_입장하면_참여_행을_만든다() {
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.empty());

    LectureParticipationService.ParticipationResult result = service.join(LEARNER, "012345");

    assertThat(result.firstJoin()).isTrue();
    assertThat(result.snapshot().participation().isParticipating()).isTrue();
    assertThat(result.snapshot().lecture().getId()).isEqualTo(LECTURE_ID);
  }

  @Test
  void 퇴장했던_학습자가_다시_들어오면_joinedAt을_유지한다() {
    Participant existing = Participant.join(LECTURE_ID, 8L, NOW.minusHours(1));
    existing.leave(NOW);
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.of(existing));

    LectureParticipationService.ParticipationResult result = service.join(LEARNER, "012345");

    assertThat(result.firstJoin()).isFalse();
    assertThat(existing.isParticipating()).isTrue();
    assertThat(existing.getJoinedAt()).isEqualTo(NOW.minusHours(1));
  }

  @Test
  void 없는_코드와_종료된_수업은_구분하지_않고_거부한다() {
    lecture.open(NOW);
    lecture.close(NOW);

    assertThatThrownBy(() -> service.join(LEARNER, "012345"))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_JOINABLE);
    assertThatThrownBy(() -> service.join(LEARNER, "999999"))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_JOINABLE);
  }

  @Test
  void 강사는_코드로_입장할_수_없다() {
    assertThatThrownBy(
            () -> service.join(new AuthenticatedUser(7L, UserRole.INSTRUCTOR), "012345"))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LEARNER_ACCESS_REQUIRED);
  }
}
```

`LectureMediaServiceTest.java`:

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
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserJpaRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LectureMediaServiceTest {

  private static final long LECTURE_ID = 42L;
  private static final AuthenticatedUser INSTRUCTOR = new AuthenticatedUser(7L, UserRole.INSTRUCTOR);
  private static final AuthenticatedUser LEARNER = new AuthenticatedUser(8L, UserRole.LEARNER);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);

  @Mock private LectureRepository lectureRepository;
  @Mock private ParticipantRepository participantRepository;
  @Mock private UserJpaRepository userJpaRepository;

  private RecordingLiveRoomGateway gateway;
  private LectureMediaService service;
  private Lecture lecture;

  @BeforeEach
  void setUp() {
    gateway = new RecordingLiveRoomGateway();
    service =
        new LectureMediaService(lectureRepository, participantRepository, userJpaRepository, gateway);
    lecture = Lecture.schedule(7L, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(lecture, "id", LECTURE_ID);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(lecture));
    User teacher = Mockito.mock(User.class);
    given(teacher.getUsername()).willReturn("teacher01");
    User learner = Mockito.mock(User.class);
    given(learner.getUsername()).willReturn("learner01");
    given(userJpaRepository.findById(7L)).willReturn(Optional.of(teacher));
    given(userJpaRepository.findById(8L)).willReturn(Optional.of(learner));
  }

  private void participate(boolean active) {
    Participant participant = Participant.join(LECTURE_ID, 8L, NOW);
    if (!active) {
      participant.leave(NOW);
    }
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.of(participant));
  }

  @Test
  void 진행_중인_수업의_강사는_송출_토큰을_받는다() {
    lecture.open(NOW);

    service.issue(INSTRUCTOR, LECTURE_ID);

    assertThat(gateway.issuedRequests)
        .containsExactly(
            new com.itda.lecture.media.LiveRoomGateway.MediaTokenRequest(
                "lecture-42", "user-7", "teacher01", true));
  }

  @Test
  void 시작_전에는_강사도_토큰을_받을_수_없다() {
    assertThatThrownBy(() -> service.issue(INSTRUCTOR, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_LECTURE_STATE);
  }

  @Test
  void 대기_중인_학습자는_수신_토큰을_받는다() {
    participate(true);

    service.issue(LEARNER, LECTURE_ID);

    assertThat(gateway.issuedRequests.getFirst().publisher()).isFalse();
    assertThat(gateway.issuedRequests.getFirst().identity()).isEqualTo("user-8");
  }

  @Test
  void 퇴장한_학습자는_다시_입장해야_한다() {
    participate(false);

    assertThatThrownBy(() -> service.issue(LEARNER, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_PARTICIPATION_REQUIRED);
  }

  @Test
  void 참여한_적_없는_학습자에게는_존재를_숨긴다() {
    given(participantRepository.findById(ParticipantId.of(LECTURE_ID, 8L)))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> service.issue(LEARNER, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.LECTURE_NOT_FOUND);
  }

  @Test
  void 종료된_수업은_학습자도_토큰을_받을_수_없다() {
    participate(true);
    lecture.open(NOW);
    lecture.close(NOW);

    assertThatThrownBy(() -> service.issue(LEARNER, LECTURE_ID))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_LECTURE_STATE);
  }
}
```

`User.getUsername()` 이 `Username` 값 객체를 돌려주는 구조라면(`user/domain/Username.java` 존재), 먼저 `User.java` 의 username getter 반환 타입을 확인하고 테스트의 stub과 `LectureMediaService` 의 표시 이름 추출을 그 타입에 맞춘다(예: `getUsername().value()`).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.itda.lecture.service.Lecture*ServiceTest"`
Expected: 컴파일 실패 — 두 서비스가 없다

- [ ] **Step 3: 구현**

`LectureParticipationService.java`:

```java
package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LectureParticipationService {

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;

  /**
   * 코드로 입장한다. 최초 입장이면 참여 행을 만들고, 재입장이면 joined_at을 유지한 채 left_at만 되돌린다 (API 명세 8.1절).
   *
   * <p>같은 학습자의 최초 입장이 동시에 두 번 오면 복합 PK 충돌로 한쪽이 실패한다. MVP에서는 재시도(재입장 200)로 충분하다.
   */
  @Transactional
  public ParticipationResult join(AuthenticatedUser user, String participationCode) {
    if (user.role() != UserRole.LEARNER) {
      throw new BusinessException(ErrorCode.LEARNER_ACCESS_REQUIRED);
    }
    Lecture lecture =
        lectureRepository
            .findByParticipationCode(participationCode)
            .filter(Lecture::isJoinable)
            .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_NOT_JOINABLE));

    ParticipantId id = ParticipantId.of(lecture.getId(), user.userId());
    return participantRepository
        .findById(id)
        .map(
            existing -> {
              existing.rejoin();
              return new ParticipationResult(LectureSnapshot.ofLearner(lecture, existing), false);
            })
        .orElseGet(
            () -> {
              Participant joined =
                  participantRepository.save(
                      Participant.join(lecture.getId(), user.userId(), LectureService.now()));
              return new ParticipationResult(LectureSnapshot.ofLearner(lecture, joined), true);
            });
  }

  /** @param firstJoin true면 201, false면 200 (API 명세 8.2절) */
  public record ParticipationResult(LectureSnapshot snapshot, boolean firstJoin) {}
}
```

`LectureMediaService.java`:

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
import com.itda.lecture.media.LiveRoomGateway.IssuedMediaToken;
import com.itda.lecture.media.LiveRoomGateway.MediaTokenRequest;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 음성 토큰 발급 권한 판정 (API 명세 8.5절). 토큰은 저장하지 않는다. */
@Service
@RequiredArgsConstructor
public class LectureMediaService {

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;
  private final UserJpaRepository userJpaRepository;
  private final LiveRoomGateway liveRoomGateway;

  @Transactional(readOnly = true)
  public IssuedMediaToken issue(AuthenticatedUser user, long lectureId) {
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    boolean publisher;
    if (LectureService.isOwner(user, lecture)) {
      if (!lecture.isOpen()) {
        throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
      }
      publisher = true;
    } else if (user.role() == UserRole.LEARNER) {
      Participant participant =
          participantRepository
              .findById(ParticipantId.of(lectureId, user.userId()))
              .orElseThrow(LectureService::notFound);
      if (!participant.isParticipating()) {
        throw new BusinessException(ErrorCode.LECTURE_PARTICIPATION_REQUIRED);
      }
      if (!lecture.isJoinable()) {
        throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
      }
      publisher = false;
    } else {
      throw LectureService.notFound();
    }
    return liveRoomGateway.issueToken(
        new MediaTokenRequest(
            LectureRoom.nameOf(lectureId),
            LectureRoom.identityOf(user.userId()),
            displayName(user.userId()),
            publisher));
  }

  private String displayName(long userId) {
    return userJpaRepository
        .findById(userId)
        .map(found -> found.getUsername())
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
  }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.itda.lecture.service.*"`
Expected: PASS (Task 4의 10 + 4 + 6 = 20 tests)

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add apps/backend/src/main/java/com/itda/lecture/service apps/backend/src/test/java/com/itda/lecture/service
git commit -m "feat(lecture): 코드 입장과 음성 토큰 발급 권한을 판정한다"
```

---

### Task 6: HTTP 계층 — DTO, 컨트롤러, 보호 경로

**Files:**
- Create: `lecture/dto/CreateLectureRequest.java`, `ChangeLectureStatusRequest.java`, `JoinLectureRequest.java`, `InstructorLectureResponse.java`, `LearnerLectureResponse.java`, `LectureListResponse.java`, `MediaTokenResponse.java`, `UtcTimes.java`
- Create: `lecture/controller/LectureController.java`, `LectureParticipationController.java`
- Modify: `global/security/SecurityConfig.java`
- Test: `lecture/controller/LectureApiIntegrationTest.java`, `lecture/dto/LectureResponseJsonTest.java`

**Interfaces:**
- Consumes: Task 4·5 서비스 시그니처
- Produces (HTTP, Task 7 이후 웹·앱이 사용):
  - `POST /lectures` → 201 `InstructorLectureResponse`
  - `GET /lectures?status&page&size` → 200 `{items: InstructorLectureResponse[], page, size, totalElements, totalPages}`
  - `GET /lectures/{lectureId}` → 200 `InstructorLectureResponse` 또는 `LearnerLectureResponse`
  - `PUT /lectures/{lectureId}/status` `{status}` → 200 `InstructorLectureResponse`
  - `POST /lecture-participations` `{participationCode}` → 201/200 `LearnerLectureResponse`
  - `POST /lectures/{lectureId}/media-tokens` → 201 `{serverUrl, token, expiresAt}`
  - `InstructorLectureResponse` 필드: `lectureId, title, description, status, scheduledAt, startedAt, endedAt, currentProblem(null), createdAt, participationCode, linkedProblems([])`
  - `LearnerLectureResponse` 필드: `lectureId, title, description, status, scheduledAt, startedAt, endedAt, currentProblem(null), createdAt, myParticipation{joinedAt, leftAt}, reviewableCurriculumStages([])`

- [ ] **Step 1: 실패하는 통합 테스트 — 보호 경로**

기존 `LearningSessionApiIntegrationTest` 와 같은 방식이다. 토큰 없는 요청이 401이면 보호 chain에 들어갔다는 뜻이고, 500이면 SecurityConfig에서 빠졌다는 뜻이다.

`LectureApiIntegrationTest.java`:

```java
package com.itda.lecture.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 수업 API의 보호 여부를 실제 HTTP로 확인한다. 경로가 SecurityConfig에서 빠지면 401이 아니라 500이 된다
 * ({@link com.itda.learning.controller.LearningSessionApiIntegrationTest} 주석 참고).
 */
@WebApiTest
class LectureApiIntegrationTest {

  private static final String JSON = "application/json";

  @LocalServerPort private int port;

  private HttpTestClient client;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
  }

  private int status(String method, String path, String body) {
    HttpResponse<String> response =
        this.client.send(method, "/api/v1" + path, body, "Content-Type", JSON);
    return response.statusCode();
  }

  @Test
  void 토큰_없이_수업을_만들면_401이다() {
    assertThat(status("POST", "/lectures", "{\"title\":\"t\"}")).isEqualTo(401);
  }

  @Test
  void 토큰_없이_수업_목록을_보면_401이다() {
    assertThat(status("GET", "/lectures", null)).isEqualTo(401);
  }

  @Test
  void 토큰_없이_수업을_조회하면_401이다() {
    assertThat(status("GET", "/lectures/1", null)).isEqualTo(401);
  }

  @Test
  void 토큰_없이_상태를_바꾸면_401이다() {
    assertThat(status("PUT", "/lectures/1/status", "{\"status\":\"OPEN\"}")).isEqualTo(401);
  }

  @Test
  void 토큰_없이_코드로_입장하면_401이다() {
    assertThat(status("POST", "/lecture-participations", "{\"participationCode\":\"012345\"}"))
        .isEqualTo(401);
  }

  @Test
  void 토큰_없이_음성_토큰을_받으면_401이다() {
    assertThat(status("POST", "/lectures/1/media-tokens", null)).isEqualTo(401);
  }
}
```

`LectureResponseJsonTest.java` — 역할별 필드 분리와 ID 문자열화를 확인한다. `learning/dto/LearningSessionCompletionResponseJsonTest.java` 가 ObjectMapper를 얻는 방식을 먼저 읽고 같은 방식을 쓴다(아래는 `JsonMapper.builder().build()` 로 쓴 예시).

```java
package com.itda.lecture.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.service.LectureSnapshot;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LectureResponseJsonTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 1, 0);
  private final JsonMapper mapper = JsonMapper.builder().build();

  private Lecture lecture() {
    Lecture lecture = Lecture.schedule(7L, "약자 4회차", null, "012345", null, NOW);
    ReflectionTestUtils.setField(lecture, "id", 42L);
    return lecture;
  }

  @Test
  void 강사_응답은_참여_코드를_포함하고_학습자_필드가_없다() {
    JsonNode json =
        mapper.valueToTree(InstructorLectureResponse.from(LectureSnapshot.ofInstructor(lecture())));

    assertThat(json.get("lectureId").asString()).isEqualTo("42");
    assertThat(json.get("participationCode").asString()).isEqualTo("012345");
    assertThat(json.get("status").asString()).isEqualTo("SCHEDULED");
    assertThat(json.get("createdAt").asString()).isEqualTo("2026-09-26T01:00:00.000Z");
    assertThat(json.has("currentProblem")).isTrue();
    assertThat(json.has("myParticipation")).isFalse();
  }

  @Test
  void 학습자_응답은_참여_코드를_숨긴다() {
    Participant participant = Participant.join(42L, 8L, NOW);
    JsonNode json =
        mapper.valueToTree(
            LearnerLectureResponse.from(LectureSnapshot.ofLearner(lecture(), participant)));

    assertThat(json.has("participationCode")).isFalse();
    assertThat(json.get("myParticipation").get("joinedAt").asString())
        .isEqualTo("2026-09-26T01:00:00.000Z");
    assertThat(json.get("reviewableCurriculumStages").isArray()).isTrue();
  }
}
```

(Jackson 3에서 `JsonNode#asText()` 가 `asString()` 으로 바뀌었다. 컴파일 오류가 나면 저장소의 다른 JSON 테스트가 쓰는 메서드명을 따른다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.itda.lecture.controller.*" --tests "com.itda.lecture.dto.*"`
Expected: DTO 테스트는 컴파일 실패, 통합 테스트는 경로가 없어 401이 아니다

- [ ] **Step 3: DTO 구현**

`UtcTimes.java`:

```java
package com.itda.lecture.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 응답 시각 형식. user.dto.Iso8601Utc와 같은 형식이다 (API 명세: ISO 8601 UTC). */
final class UtcTimes {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

  private UtcTimes() {}

  static String format(LocalDateTime utc) {
    return utc == null ? null : utc.atOffset(ZoneOffset.UTC).format(FORMATTER);
  }
}
```

`CreateLectureRequest.java`:

```java
package com.itda.lecture.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** API 명세 8.2절. title은 DB VARCHAR(150) 범위. */
public record CreateLectureRequest(
    @NotBlank @Size(max = 150) String title, String description, Instant scheduledAt) {

  public LocalDateTime scheduledAtUtc() {
    return scheduledAt == null ? null : LocalDateTime.ofInstant(scheduledAt, ZoneOffset.UTC);
  }
}
```

`ChangeLectureStatusRequest.java`:

```java
package com.itda.lecture.dto;

import com.itda.lecture.domain.LectureStatus;
import jakarta.validation.constraints.NotNull;

/** OPEN 또는 CLOSED. SCHEDULED로 되돌리는 요청은 서비스가 409로 거부한다. */
public record ChangeLectureStatusRequest(@NotNull LectureStatus status) {}
```

`JoinLectureRequest.java`:

```java
package com.itda.lecture.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record JoinLectureRequest(@NotNull @Pattern(regexp = "\\d{6}") String participationCode) {}
```

`InstructorLectureResponse.java`:

```java
package com.itda.lecture.dto;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.service.LectureSnapshot;
import java.util.List;

/**
 * 담당 강사용 Snapshot (API 명세 8.2절). currentProblem·linkedProblems는 문제 출제를 붙일 때 채운다 — MVP에서는 null과
 * 빈 배열이다.
 */
public record InstructorLectureResponse(
    String lectureId,
    String title,
    String description,
    LectureStatus status,
    String scheduledAt,
    String startedAt,
    String endedAt,
    Object currentProblem,
    String createdAt,
    String participationCode,
    List<Object> linkedProblems) {

  public static InstructorLectureResponse from(LectureSnapshot snapshot) {
    return from(snapshot.lecture());
  }

  public static InstructorLectureResponse from(Lecture lecture) {
    return new InstructorLectureResponse(
        String.valueOf(lecture.getId()),
        lecture.getTitle(),
        lecture.getDescription(),
        lecture.getStatus(),
        UtcTimes.format(lecture.getScheduledAt()),
        UtcTimes.format(lecture.getStartedAt()),
        UtcTimes.format(lecture.getEndedAt()),
        null,
        UtcTimes.format(lecture.getCreatedAt()),
        lecture.getParticipationCode(),
        List.of());
  }
}
```

`LearnerLectureResponse.java`:

```java
package com.itda.lecture.dto;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.service.LectureSnapshot;
import java.util.List;

/** 참여 학습자용 Snapshot (API 명세 8.2절). 참여 코드·다른 참여자 정보는 넣지 않는다. */
public record LearnerLectureResponse(
    String lectureId,
    String title,
    String description,
    LectureStatus status,
    String scheduledAt,
    String startedAt,
    String endedAt,
    Object currentProblem,
    String createdAt,
    MyParticipation myParticipation,
    List<Object> reviewableCurriculumStages) {

  public record MyParticipation(String joinedAt, String leftAt) {}

  public static LearnerLectureResponse from(LectureSnapshot snapshot) {
    Lecture lecture = snapshot.lecture();
    Participant participation = snapshot.participation();
    return new LearnerLectureResponse(
        String.valueOf(lecture.getId()),
        lecture.getTitle(),
        lecture.getDescription(),
        lecture.getStatus(),
        UtcTimes.format(lecture.getScheduledAt()),
        UtcTimes.format(lecture.getStartedAt()),
        UtcTimes.format(lecture.getEndedAt()),
        null,
        UtcTimes.format(lecture.getCreatedAt()),
        new MyParticipation(
            UtcTimes.format(participation.getJoinedAt()), UtcTimes.format(participation.getLeftAt())),
        List.of());
  }
}
```

`LectureListResponse.java`:

```java
package com.itda.lecture.dto;

import com.itda.lecture.domain.Lecture;
import java.util.List;
import org.springframework.data.domain.Page;

/** docs/conventions/code/api.md 페이지네이션 형식. */
public record LectureListResponse(
    List<InstructorLectureResponse> items, int page, int size, long totalElements, int totalPages) {

  public static LectureListResponse from(Page<Lecture> page) {
    return new LectureListResponse(
        page.getContent().stream().map(InstructorLectureResponse::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
```

`MediaTokenResponse.java`:

```java
package com.itda.lecture.dto;

import com.itda.lecture.media.LiveRoomGateway.IssuedMediaToken;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record MediaTokenResponse(String serverUrl, String token, String expiresAt) {

  public static MediaTokenResponse from(IssuedMediaToken issued) {
    return new MediaTokenResponse(
        issued.serverUrl(),
        issued.token(),
        UtcTimes.format(LocalDateTime.ofInstant(issued.expiresAt(), ZoneOffset.UTC)));
  }
}
```

- [ ] **Step 4: 컨트롤러 구현**

`LectureController.java`:

```java
package com.itda.lecture.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.dto.ChangeLectureStatusRequest;
import com.itda.lecture.dto.CreateLectureRequest;
import com.itda.lecture.dto.InstructorLectureResponse;
import com.itda.lecture.dto.LearnerLectureResponse;
import com.itda.lecture.dto.LectureListResponse;
import com.itda.lecture.dto.MediaTokenResponse;
import com.itda.lecture.service.LectureMediaService;
import com.itda.lecture.service.LectureService;
import com.itda.lecture.service.LectureSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API 명세 8.2·8.5절. 경로·body에서 사용자 식별자를 받지 않는다 — 대상은 principal뿐이다. */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/lectures")
public class LectureController {

  private final LectureService lectureService;
  private final LectureMediaService lectureMediaService;

  @PostMapping
  public ResponseEntity<InstructorLectureResponse> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateLectureRequest request) {
    LectureSnapshot created =
        lectureService.create(
            user, request.title(), request.description(), request.scheduledAtUtc());
    return ResponseEntity.status(HttpStatus.CREATED).body(InstructorLectureResponse.from(created));
  }

  @GetMapping
  public LectureListResponse list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) LectureStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return LectureListResponse.from(lectureService.listMine(user, status, page, size));
  }

  /** 강사와 학습자의 응답 필드가 달라 본문 타입이 요청자에 따라 갈린다. */
  @GetMapping("/{lectureId}")
  public Object find(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Positive Long lectureId) {
    LectureSnapshot snapshot = lectureService.find(user, lectureId);
    return snapshot.isInstructorView()
        ? InstructorLectureResponse.from(snapshot)
        : LearnerLectureResponse.from(snapshot);
  }

  @PutMapping("/{lectureId}/status")
  public InstructorLectureResponse changeStatus(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @Valid @RequestBody ChangeLectureStatusRequest request) {
    return InstructorLectureResponse.from(
        lectureService.changeStatus(user, lectureId, request.status()));
  }

  @PostMapping("/{lectureId}/media-tokens")
  public ResponseEntity<MediaTokenResponse> issueMediaToken(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Positive Long lectureId) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(MediaTokenResponse.from(lectureMediaService.issue(user, lectureId)));
  }
}
```

`LectureParticipationController.java`:

```java
package com.itda.lecture.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.dto.JoinLectureRequest;
import com.itda.lecture.dto.LearnerLectureResponse;
import com.itda.lecture.service.LectureParticipationService;
import com.itda.lecture.service.LectureParticipationService.ParticipationResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LectureParticipationController {

  private final LectureParticipationService participationService;

  /** 최초 입장 201, 재입장 200 (API 명세 8.2절). */
  @PostMapping("/lecture-participations")
  public ResponseEntity<LearnerLectureResponse> join(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody JoinLectureRequest request) {
    ParticipationResult result = participationService.join(user, request.participationCode());
    return ResponseEntity.status(result.firstJoin() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(LearnerLectureResponse.from(result.snapshot()));
  }
}
```

- [ ] **Step 5: SecurityConfig 보호 경로 추가**

`SecurityConfig.java` 의 `REVIEW_SESSION_PATH` 상수 아래에 추가:

```java
  /** 수업 (API 명세 8.2·8.5절). 모두 principal의 역할·소유·참여 관계로 판정하므로 인증이 필요하다. */
  private static final String LECTURES_PATH = "/lectures";

  private static final String LECTURE_PATH = "/lectures/{lectureId}";

  private static final String LECTURE_STATUS_PATH = "/lectures/{lectureId}/status";

  private static final String LECTURE_MEDIA_TOKENS_PATH = "/lectures/{lectureId}/media-tokens";

  private static final String LECTURE_PARTICIPATIONS_PATH = "/lecture-participations";
```

`OrRequestMatcher(...)` 의 마지막 항목(`REVIEW_SESSION_PATH`) 뒤에 추가:

```java
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, REVIEW_SESSION_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LECTURES_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, LECTURES_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, LECTURE_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, LECTURE_STATUS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LECTURE_MEDIA_TOKENS_PATH),
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, LECTURE_PARTICIPATIONS_PATH)))
```

(원래 `REVIEW_SESSION_PATH` 줄 끝의 `)))` 를 `),` 로 바꾸고 새 줄 끝에 `)))` 를 붙인다.)

- [ ] **Step 6: 통과 확인**

Run: `./gradlew test --tests "com.itda.lecture.*"`
Expected: PASS — 통합 테스트 6개 모두 401, DTO 테스트 2개, 기존 lecture 도메인 테스트 포함 전부 통과

- [ ] **Step 7: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply
git add apps/backend/src/main/java/com/itda/lecture apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java apps/backend/src/test/java/com/itda/lecture
git commit -m "feat(lecture): 수업·입장·음성 토큰 HTTP API를 연다"
```

---

### Task 7: 운영 설정 연결과 실서버 스모크

**Files:**
- Modify: `deploy/docker-compose.prod.yml:15-21` (`x-backend-env`)
- 사람에게 요청: 루트 `.env.example`, `deploy/.env.config.example` 에 키 추가 / Jenkins credential `itda-env-config` 갱신 / 강사 계정 시드

**Interfaces:**
- Consumes: Task 3의 환경변수 `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`

- [ ] **Step 1: 운영 compose에 환경변수 전달**

먼저 `AUTH_*` 같은 기존 backend 비밀값이 컨테이너에 어떤 경로로 들어가는지 확인한다(`x-backend-env` 에는 DB·AI 값만 있다). `git grep -n "AUTH_ACCESS_ACTIVE_KEY" deploy scripts Jenkinsfile docs/infra` 로 찾고, **같은 경로를 따른다.** `x-backend-env` 방식이 맞다면 다음 세 줄을 `AI_BASE_URL` 아래에 추가한다.

```yaml
  LIVEKIT_URL: ${LIVEKIT_URL:?LIVEKIT_URL is required}
  LIVEKIT_API_KEY: ${LIVEKIT_API_KEY:?LIVEKIT_API_KEY is required}
  LIVEKIT_API_SECRET: ${LIVEKIT_API_SECRET:?LIVEKIT_API_SECRET is required}
```

경로가 불분명하면 추측으로 고치지 말고 인프라 담당자에게 확인한 뒤 진행한다.

- [ ] **Step 2: 사람에게 요청할 항목 정리 (MR 본문 체크리스트로 옮긴다)**

에이전트는 `.env*` 를 읽거나 고치지 않으므로 다음을 사람이 한다.

1. 루트 `.env.example` 에 추가:
   ```
   # OpenVidu 3.x(LiveKit). 로컬에서 실서버를 쓰려면 openvidu.env의 LiveKit 값을 넣는다.
   LIVEKIT_URL=https://openvidu.i-eum.site
   LIVEKIT_API_KEY=
   LIVEKIT_API_SECRET=
   ```
2. `deploy/.env.config.example` 에 같은 세 키(값 비움) 추가.
3. Jenkins `itda-env-config` 에 실제 값 추가 — EC2 `/opt/openvidu/config/openvidu.env` 의 `LIVEKIT_API_KEY`·`LIVEKIT_API_SECRET`. **`feat/lecture` → `dev` 병합(배포) 전에 끝나야 한다.** 빠지면 `LiveKitProperties` 가 기동을 막는다.
4. 개인 `.env` 에 같은 값을 넣어 로컬 스모크(Step 3)를 준비.
5. 시연용 INSTRUCTOR 계정: 가입 API는 항상 LEARNER이므로, 가입·이메일 인증을 마친 계정의 역할을 DB에서 바꾼다.
   ```sql
   UPDATE users SET role = 'INSTRUCTOR' WHERE username = '<강사로 쓸 아이디>';
   ```
   (역할 변경 후 기존 Access Token의 role claim은 옛 값이므로 다시 로그인한다.)

- [ ] **Step 3: 로컬 스모크 — 실제 OpenVidu 대상**

`./gradlew bootRun` 후 (토큰은 `POST /api/v1/auth-sessions` 로 받은 accessToken):

```bash
API=http://localhost:8080/api/v1
T=<강사 accessToken>; L=<학습자 accessToken>
curl -s -X POST $API/lectures -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"title":"스모크 수업"}'
# → 201, participationCode 확인. 아래 ID·CODE에 넣는다
curl -s -X POST $API/lecture-participations -H "Authorization: Bearer $L" -H "Content-Type: application/json" -d '{"participationCode":"<CODE>"}'
# → 201
curl -s -X POST $API/lectures/<ID>/media-tokens -H "Authorization: Bearer $L"
# → 201 {serverUrl: wss://openvidu.i-eum.site, token, expiresAt}
curl -s -X PUT $API/lectures/<ID>/status -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"status":"OPEN"}'
# → 200 status OPEN (OpenVidu 방 생성·metadata 성공)
curl -s -X POST $API/lectures/<ID>/media-tokens -H "Authorization: Bearer $T"
# → 201
curl -s -X PUT $API/lectures/<ID>/status -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"status":"CLOSED"}'
# → 200 status CLOSED (방 삭제 성공)
```

학습자 토큰으로 실제 접속되는지는 https://meet.livekit.io 의 Custom 탭(serverUrl·token 입력)에서 확인할 수 있다. 강사 토큰으로 접속한 탭에서 마이크를 켜고, 학습자 토큰 탭에서 소리가 들리면 보안 그룹(UDP 50000-60000·TCP 7881)까지 확인된 것이다.

Expected: 모든 요청이 주석의 상태 코드. OPEN/CLOSED가 503이면 `LIVEKIT_URL`·키 값 또는 OpenVidu 도달성을 확인한다.

- [ ] **Step 4: Commit**

```bash
git add deploy/docker-compose.prod.yml
git commit -m "chore(infra): 운영 backend에 OpenVidu 설정을 전달한다"
```

---

## 완료 확인 (MR 전)

- [ ] `cd apps/backend && ./gradlew test` BUILD SUCCESSFUL
- [ ] `docker build --target test apps/backend` 성공 (MR verify와 동일)
- [ ] Task 7 Step 3 스모크의 모든 상태 코드 확인
- [ ] mr-draft 스킬로 MR 초안 작성 — 본문에 Task 7 Step 2의 사람 작업 체크리스트를 옮긴다

## 알려진 한계 (후속 이슈 후보)

- 기존 `POST /lectures/{lectureId}/problems` 가 SecurityConfig 보호 경로에 없다 — 토큰 없이 부르면 500. 이 MR에서는 고치지 않는다(직교 수정 금지).
- 최초 입장 동시 요청의 PK 충돌은 500이 된다(재시도하면 200).
- 종료와 LIVE 답안의 행 잠금 직렬화(API 명세 8.1절)는 답안 API를 붙일 때 구현한다.
- prod 프로필은 `ddl-auto: validate` 다. 운영 DB에 `lectures`·`participants` 테이블이 엔티티와 일치하는지 배포 전에 확인한다.
