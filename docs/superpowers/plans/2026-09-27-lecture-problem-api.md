# 실시간 수업 문제 출제·답안 — backend·계약 문서 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 강사가 글자를 입력해 수업 문제를 연결하고(`POST /lectures/{id}/problems` 개편), 골라서 출제하고(`PUT /current-problem`), 학습자가 LIVE 답안을 제출·채점받고(`POST .../answers`), 강사가 문제별 답안 목록을 보는(`GET .../answers`) backend와, 이를 알리는 LiveKit 방 metadata·데이터 메시지를 만든다. 계약 문서(api-spec §8.2~8.5)를 먼저 고친다.

**Architecture:** 입력 해석은 순수 파서(`ProblemTextQuery`)와 표시 규칙(`ProblemDisplay`)으로 떼고, 문제·정답 점형·표시 문구를 한 번에 읽는 `LinkedProblemReader` 를 problem·lecture 양쪽이 공유한다. lecture 쪽은 Snapshot 조립(`LectureProblemQuery`), 출제(`LectureDeliveryService`), 답안(`LectureAnswerService` + 순수 채점기 `LiveAnswerGrader`)으로 나눈다. 상태를 바꾸는 트랜잭션은 lectures 행을 `PESSIMISTIC_WRITE` 로 잠가 종료와 직렬화하고, LiveKit 호출(방 metadata·데이터 메시지)은 커밋 **뒤**에 한다(기존 `LectureService.changeStatus` 규칙). 스키마는 바꾸지 않는다 — 멱등은 기존 `uk_problem_attempts_lecture_user_problem` UNIQUE가 맡는다.

**Tech Stack:** Spring Boot 4.0.8, Java 25, Spring Data JPA(Hibernate, H2 2.4.240 MySQL 모드 테스트 / MariaDB 운영), Jackson 3(`tools.jackson.*`), `io.livekit:livekit-server:0.16.0`(Retrofit + protobuf `livekit.LivekitModels`), JUnit 5 + Mockito 5 + AssertJ, `@DataJpaTest`, `@WebApiTest`(실제 Tomcat + JDK HttpClient)

**Spec:** `docs/superpowers/specs/2026-09-27-lecture-problem-delivery-design.md` §3, §6, §8 / 교차 계약 `C:/Users/SSAFY/Desktop/S15P21B201/.superpowers/sdd/problem-contract.md` (web·app 계획과 공유 — 경로·필드 이름·JSON 모양·오류 code·신호 payload를 바꾸지 않는다)

## Global Constraints

- 브랜치 `feat/lecture-problem-api`(`feat/lecture-problem` 에서 분기), 워크트리 `C:/Users/SSAFY/Desktop/wt-problem-api`, MR 대상 `feat/lecture-problem`. **push·MR 생성은 사람이 한다** — 이 계획은 로컬 커밋까지만. 문제 등록 API(`ProblemController`/`ProblemService`)는 팀원 코드이므로 MR 초안에 그 담당자를 리뷰어로 적는다
- 커밋: `type(scope): 한글 제목` (scope `lecture` 또는 `problem`). 마지막 줄은 정확히 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 이슈 번호가 주어지지 않았으므로 `Refs #번호` 는 생략한다(받으면 제목 아래 빈 줄 뒤 `Refs #번호`, 그 다음 Co-Authored-By)
- `git push --force`·`--no-verify` 금지. main·dev·release/* 직접 push 금지. `.env` 계열·`*.pem` 은 읽지도 수정하지도 않는다. 새 환경변수 없음
- context-path가 `/api/v1` 이므로 매핑·SecurityConfig에는 그 뒤 경로만 적는다. **새 경로는 모두 `SecurityConfig` 보호 matcher에 넣는다** — 빠지면 principal이 null이라 401이 아니라 500이 된다(현재 `POST /lectures/{lectureId}/problems` 도 빠져 있어 Task 3에서 넣는다)
- **컨트롤러 클래스에 `@Validated` 를 붙이지 않는다** — 이 코드베이스에서 검증 오류가 500으로 바뀐다. 파라미터 제약(`@Positive`·`@Pattern`)과 `@Valid @RequestBody` 를 함께 쓰면 Spring이 `HandlerMethodValidationException` 하나로 묶어 400 `INVALID_REQUEST_PARAMETER` 가 된다(기존 `PUT /status`·`/speaking` 과 같음). 계약의 "400"은 이 경로로 만든다
- 모든 ID는 JSON·경로에서 10진수 문자열. 오류 body `{"code","message"}` (PROBLEM_TEXT_AMBIGUOUS만 `candidates` 추가)
- 교차 계약 값(글자 그대로): 방 metadata `{"status":"OPEN","currentProblemId":"<id>"}`(현재 문제 없으면 `{"status":"OPEN"}`), 제출 신호 대상 `user-{instructorUserId}`·topic `lecture`·reliable·payload `{"type":"ANSWER_SUBMITTED","problemId":"<id>"}`, `Idempotency-Key` UUID v4(저장하지 않음), `durationMs` 0 이상(저장하지 않음), 답안 칸 1~12개·값 0~63
- 오류 code는 계약 문서(api-spec §8.4)에 먼저 적고 `ErrorCode` 에 추가한다(`ErrorCode.java` 주석 규칙) — Task 1이 둘을 한 커밋에 담는다
- **스키마 변경 금지**: `problem_attempts` 에 시간·멱등 키 컬럼을 더하지 않는다. `attempt_cells` 두 패턴은 NOT NULL — 모자란 쪽은 0으로 채운다
- 로컬 DB에는 같은 target_text가 여럿 있다(`ㄱ` CONSONANT 2건: SKILL_01 첫소리 / SKILL_03 받침, `가` CONTRACTION·WORD, `아`·`이`·`오` WORD 2건). 단건 `Optional` 조회를 쓰지 않는다 — 기존 `findByTargetText` 는 이 때문에 `IncorrectResultSizeDataAccessException` 을 낼 수 있어 Task 3에서 지운다
- **커밋 전 검증:** `cd apps/backend && ./gradlew test` BUILD SUCCESSFUL. 실패 상태로 커밋하지 않는다
- **포맷:** 커밋 전 `./gradlew spotlessApply` → `git status --short`. 이 Task가 건드리지 않은 파일이 바뀌어 있으면 `git checkout -- <그 파일>` 로 되돌리고 `./gradlew spotlessCheck` 를 다시 돌린다(직교 수정 금지)
- 기존 테스트의 기대값은 이 계획이 명시한 것만 바꾼다: `ProblemServiceTest.진행_중이_아닌_수업에는_문제를_등록할_수_없다`(SCHEDULED 허용으로 바뀜 → CLOSED 테스트로 교체), 테스트 대역의 `openRoom` 시그니처, `LectureServiceTest`·`LectureMediaServiceTest` 생성자 인자

## File Structure

(Java 경로 기준 `apps/backend/src/main/java/com/itda/`, 테스트 `apps/backend/src/test/java/com/itda/`)

| 파일 | 책임 |
| --- | --- |
| `docs/product/api-spec.md` (수정) | §8.2 표·문단, §8.2.1·8.2.2 신설, §8.3 MVP 안내, §8.4 표, §8.5 metadata 문구, §8.5.2 신설 |
| `global/error/ErrorCode.java` (수정) | `LECTURE_PROBLEM_NOT_CURRENT`, `LECTURE_PROBLEM_ALREADY_ANSWERED`(409), `INVALID_ANSWER_CELLS`(422), `PROBLEM_NOT_FOUND`(404), `PROBLEM_TEXT_AMBIGUOUS`(409) |
| `global/error/CandidatesBusinessException.java` (신규) | 후보 목록을 싣는 비즈니스 예외 |
| `global/error/CandidatesErrorResponse.java` (신규) | `{code, message, candidates}` body |
| `global/error/GlobalExceptionHandler.java` (수정) | 후보 예외 처리기 |
| `problem/service/ProblemTextQuery.java` (신규) | `초성 ㄱ` 입력을 구분어·글자로 나누는 순수 파서 |
| `problem/service/ProblemDisplay.java` (신규) | level·학습 요소 → `displayText` 순수 규칙 |
| `problem/service/LinkedProblem.java` (신규) | 연결 문제 값(id·글자·표시·유형·정답 점형) |
| `problem/service/LinkedProblemReader.java` (신규) | 문제 목록 → `LinkedProblem` (셀·요소 일괄 조회) |
| `problem/service/RegisteredProblem.java` (신규) | 등록 결과(`LinkedProblem`, 새 연결 여부) |
| `problem/service/ProblemService.java` (수정) | `registerProblem` 재작성, 입력 해석, 상태 규칙(SCHEDULED·OPEN) |
| `problem/repository/ProblemRepository.java` (수정) | 해석용 조회 3개 추가, `findByTargetText` 제거 |
| `problem/dto/RegisterProblemRequest.java` (수정) | `@NotBlank targetText` |
| `problem/dto/LinkedProblemResponse.java` (신규) | LinkedProblem JSON |
| `problem/controller/ProblemController.java` (수정) | 201/200 + 본문, UNIQUE 경합 재시도 |
| `lecture/repository/LectureProblemRepository.java` (수정) | `findByLecture_IdAndProblem_Id`, `findAllWithProblemByLectureId` |
| `lecture/repository/LectureRepository.java` (수정) | `findByIdForUpdate` (PESSIMISTIC_WRITE) |
| `learning/domain/ProblemAttemptRepository.java` (수정) | LIVE 답안 단건·문제별 목록 조회 |
| `lecture/service/AnswerCell.java`·`LiveAnswer.java`·`LiveAnswerGrader.java` (신규) | 채점 값·규칙 |
| `lecture/service/LectureSnapshot.java` (수정) | currentProblem·linkedProblems·myResult |
| `lecture/service/LectureProblemQuery.java` (신규) | 강사·학습자 Snapshot 조립, 현재 문제 id, 저장된 답안 읽기 |
| `lecture/service/LectureService.java` (수정) | Snapshot 조립 위임, OPEN 때 현재 문제 metadata |
| `lecture/service/LectureMediaService.java` (수정) | 강사 재접속 방 다시 열기에 현재 문제 포함 |
| `lecture/service/LectureDeliveryService.java` (신규) | 출제 |
| `lecture/service/LectureAnswerService.java`·`SubmittedAnswer.java`·`LectureAnswers.java`·`LearnerAnswer.java` (신규) | 제출·강사 답안 목록 |
| `lecture/media/LiveRoomGateway.java`·`LiveKitRoomGateway.java` (수정) | `openRoom(room, currentProblemId)`, `notifyAnswerSubmitted` |
| `lecture/dto/*` (수정·신규) | `InstructorLectureResponse`·`LearnerLectureResponse` 채우기, `LearnerCurrentProblemResponse`, `LiveAnswerResponse`, `AnswerCellResponse`, `DeliverProblemRequest`, `SubmitAnswerRequest`, `LectureAnswersResponse` |
| `lecture/controller/LectureProblemController.java` (신규) | current-problem·answers 3개 경로 |
| `global/security/SecurityConfig.java` (수정) | 보호 경로 4개 |
| 테스트 | `global/error/CandidatesErrorResponseTest`, `problem/service/{ProblemTextQueryTest,ProblemDisplayTest,LinkedProblemReaderTest,ProblemServiceTest}`, `problem/repository/ProblemRepositoryTest`, `lecture/service/{LiveAnswerGraderTest,LectureServiceTest,LectureMediaServiceTest,RecordingLiveRoomGateway}`, `lecture/media/LiveKitRoomGatewayTest`, `lecture/dto/LectureResponseJsonTest`, `lecture/repository/LectureRepositoryTest`, `lecture/controller/{LectureProblemApiIntegrationTest(신규),LectureAuthenticatedApiIntegrationTest,LectureApiIntegrationTest}` |

### 검증한 SDK·환경 (2026-09-27, 로컬 jar·JDK로 확인)

- `javap -cp livekit-server-0.16.0.jar io.livekit.server.RoomServiceClient`:
  `public final retrofit2.Call<java.lang.Void> sendData(String roomName, byte[] data, livekit.LivekitModels$DataPacket$Kind kind, List<String> destinationSids, List<String> destinationIdentities, String topic)` — 바이트코드상 roomName·data·kind·destinationSids·destinationIdentities는 `checkNotNullParameter`(null 금지 → 빈 목록을 넘긴다), topic은 `ifnull` 뒤 `setTopic`. 호출문: `roomServiceClient.sendData(roomName, payload, DataPacket.Kind.RELIABLE, List.of(), List.of(identity), "lecture")`
- `livekit.LivekitModels.DataPacket.Kind` 값: `RELIABLE`, `LOSSY`, `UNRECOGNIZED`
- `createRoom(String name, Integer emptyTimeout, Integer maxParticipants, String nodeId, String metadata) : Call<LivekitModels.Room>`(5인자 오버로드는 하나), `updateRoomMetadata(String, String) : Call<LivekitModels.Room>`
- H2 2.4.240(MySQL 모드)에서 `TEXT` 컬럼 `=` 비교와 `SELECT ... FOR UPDATE` 가 동작함을 스크래치 JDBC로 확인했다 — 테스트에서 `target_text` 조회·`@Lock(PESSIMISTIC_WRITE)` 를 그대로 쓸 수 있다

---

### Task 0: 워크트리와 브랜치

**Files:** 없음

- [ ] **Step 1: 워크트리 생성**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git worktree add -b feat/lecture-problem-api C:/Users/SSAFY/Desktop/wt-problem-api feat/lecture-problem
```

Expected: `Preparing worktree (new branch 'feat/lecture-problem-api')`. 브랜치·경로가 이미 있으면 멈추고 사람에게 확인한다(덮어쓰지 않는다).

- [ ] **Step 2: 기준선 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-problem-api
git log --oneline -1   # feat/lecture-problem 의 HEAD(cbc1e243 이후)와 같아야 한다
cd apps/backend && ./gradlew test spotlessCheck
```

Expected: BUILD SUCCESSFUL. 실패하면 변경 전 상태이므로 원인을 보고하고 멈춘다.

이후 모든 Task는 `C:/Users/SSAFY/Desktop/wt-problem-api` 에서 한다.

---

### Task 1: 계약 문서, 오류 code, 후보 오류 body

**Files:**
- Modify: `docs/product/api-spec.md` (§8.2, §8.3, §8.4, §8.5)
- Modify: `apps/backend/src/main/java/com/itda/global/error/ErrorCode.java`
- Create: `apps/backend/src/main/java/com/itda/global/error/CandidatesBusinessException.java`
- Create: `apps/backend/src/main/java/com/itda/global/error/CandidatesErrorResponse.java`
- Modify: `apps/backend/src/main/java/com/itda/global/error/GlobalExceptionHandler.java`
- Test: `apps/backend/src/test/java/com/itda/global/error/CandidatesErrorResponseTest.java`

**Interfaces:**
- Produces: `ErrorCode.LECTURE_PROBLEM_NOT_CURRENT`(409), `ErrorCode.LECTURE_PROBLEM_ALREADY_ANSWERED`(409), `ErrorCode.INVALID_ANSWER_CELLS`(422), `ErrorCode.PROBLEM_NOT_FOUND`(404), `ErrorCode.PROBLEM_TEXT_AMBIGUOUS`(409)
- Produces: `new CandidatesBusinessException(ErrorCode, List<CandidatesBusinessException.Candidate>)`, `record CandidatesBusinessException.Candidate(String input, String displayText)`, `ResponseEntity<CandidatesErrorResponse> GlobalExceptionHandler.handleCandidates(CandidatesBusinessException, HttpServletRequest)`

- [ ] **Step 1: api-spec §8.2 표 — 세 행 교체, 한 행 추가**

(a) 기존:

```markdown
| `POST /api/v1/lectures/{lectureId}/problems` | 201 신규 / 200 기존 연결 | 담당 INSTRUCTOR. body는 `problemId`만 받으며 검증된 저장 문제를 연결한다. |
```

새 행:

```markdown
| `POST /api/v1/lectures/{lectureId}/problems` | 201 신규 / 200 기존 연결 | 담당 INSTRUCTOR, SCHEDULED·OPEN. body는 `targetText`만 받는다. 구분어 규칙으로 문제를 찾아(없는 낱말은 AI 분석으로 생성) 연결하고 연결된 문제 정보(LinkedProblem)를 돌려준다 (8.2.1절). |
```

(b) 기존:

```markdown
| `PUT /api/v1/lectures/{lectureId}/current-problem` | 200 | 담당 INSTRUCTOR, OPEN 전용. body의 `problemId`를 같은 수업에 연결된 문제에서 찾아 현재 문제로 교체한다. 동일 값 재요청은 새 이벤트를 만들지 않는다. |
```

새 행:

```markdown
| `PUT /api/v1/lectures/{lectureId}/current-problem` | 200 | 담당 INSTRUCTOR, OPEN 전용. body의 `problemId`를 같은 수업에 연결된 문제에서 찾아 현재 문제로 교체하고 강사 Snapshot을 돌려준다. 동일 값 재요청은 DB를 바꾸지 않고 방 metadata만 다시 쓴다 (8.5.2절). |
```

(c) 기존:

```markdown
| `POST /api/v1/lectures/{lectureId}/problems/{problemId}/answers` | 201 신규 / 동일 멱등 재전송 200 | 활성 LEARNER·OPEN·현재 문제 전용. `Idempotency-Key` UUID v4 필수. |
```

새 행 두 개(두 번째가 추가):

```markdown
| `POST /api/v1/lectures/{lectureId}/problems/{problemId}/answers` | 201 신규 / 같은 cells 재전송 200 | 활성 LEARNER·OPEN·현재 문제 전용. `Idempotency-Key` UUID v4 필수(형식만 검사하고 저장하지 않는다). 서버가 채점·저장하고 담당 강사에게 제출 알림을 보낸다 (8.2.2·8.5.2절). |
| `GET /api/v1/lectures/{lectureId}/problems/{problemId}/answers` | 200 | 담당 INSTRUCTOR, 상태 무관. 이 수업에 연결된 문제의 제출 답안 목록 — 제출한 학습자만, 제출 시각 오름차순 (8.2.2절). |
```

- [ ] **Step 2: api-spec §8.2 문단 두 곳**

(a) "강의 상세 Snapshot의 공통 필드는 …" 문단의 마지막 문장 `현행 계약에는 참여자 상세 목록·정답률·대시보드 응답이 없다.` 를 다음으로 바꾼다.

```markdown
강사는 문제별 답안 목록(8.2.2절)만 조회하며, 참여자 상세 목록·정답률·대시보드 응답은 없다.
```

(b) 기존 문단:

```markdown
학습자 HTTP 상세의 `currentProblem`은 null이거나 `problemId`, `problemType`, `targetText`, `cellCount`, `submitted`를 가진다. submitted와 myResult는 요청 학습자 본인의 제출 여부·결과다. myResult는 제출한 현재 문제에만 포함한다. 담당 강사의 문제 관리 응답은 정답 Cell을 포함할 수 있다. 공통 WebSocket 알림에는 정답과 특정 학습자의 제출 여부·결과를 넣지 않는다.
```

새 문단:

```markdown
학습자 HTTP 상세의 `currentProblem`은 null이거나 `problemId`, `problemType`(problems.level), `targetText`, `displayText`, `cellCount`, `submitted`를 가진다. submitted와 myResult는 요청 학습자 본인의 제출 여부·결과다. myResult는 제출한 현재 문제에만 포함하며 형태는 LIVE 답안 응답(8.2.2절)과 같다. 담당 강사 Snapshot의 `currentProblem`(null 가능)과 `linkedProblems`(연결 순서 = lecture_problems.id 오름차순)는 LinkedProblem(8.2.1절)이며 정답 Cell(`expectedPattern`)을 포함한다. 수업 목록(`GET /api/v1/lectures`)의 항목은 문제 정보를 채우지 않는다(`currentProblem` null, `linkedProblems` 빈 배열). 공통 알림에는 정답과 특정 학습자의 제출 여부·결과를 넣지 않는다.
```

- [ ] **Step 3: api-spec §8.2.1·8.2.2 신설**

§8.2 마지막 문단("LIVE 답안 body는 개인 답안과 같은 …") 바로 뒤, `### 8.3` 앞에 넣는다.

~~~markdown
#### 8.2.1 문제 연결 입력과 응답

요청은 `{"targetText": "초성 ㄱ"}` 이다. 서버가 앞뒤 공백을 지운다. 구분어(`초성`·`받침`·`모음`·`음절`)와 글자 사이에는 공백이 하나 이상 있어야 한다. 입력 문자열은 저장하지 않는다.

| 입력 | 고르는 문제 (조건에 맞는 문제가 여럿이면 id 오름차순 첫 번째) |
| --- | --- |
| `초성 {자음}` | target_text = 자음, level CONSONANT, 학습 요소 SKILL_01(첫소리 기본 자음)에 연결된 문제 |
| `받침 {자음}` | target_text = 자음, level CONSONANT, 학습 요소 SKILL_03(받침 기본 자음)에 연결된 문제 |
| `모음 {모음}` | target_text = 모음, level VOWEL |
| `음절 {글자}` | target_text = 글자, level CONTRACTION(약자) |
| 구분어 없음, 호환 자모 한 글자(U+3131~U+3163) | level CONSONANT·VOWEL 후보. 표시 문구가 하나면 연결, 둘 이상이면 409 `PROBLEM_TEXT_AMBIGUOUS`, 후보가 없으면 404 `PROBLEM_NOT_FOUND` |
| 구분어 없음, 그 밖 | level WORD·CONTRACTION_WORD·SENTENCE를 우선, 없으면 다른 level의 같은 글자, 그것도 없으면 AI 분석으로 새 문제를 만든다 |

- 구분어가 붙은 입력에 맞는 문제가 없으면 404 `PROBLEM_NOT_FOUND` 다. 자모·약자는 고정 목록이므로 AI로 만들지 않는다.
- 이 수업에 이미 연결된 문제면 저장하지 않고 200, 새로 연결하면 201이다. 동시 요청으로 UNIQUE가 겹치면 200이다.
- `targetText` 누락·공백은 400 `INVALID_REQUEST_PARAMETER` 다.

응답(LinkedProblem):

```json
{ "problemId": "26578", "targetText": "ㄱ", "displayText": "초성 ㄱ", "problemType": "CONSONANT", "cellCount": 1, "expectedPattern": [8] }
```

- `problemType` 은 problems.level(CONSONANT·VOWEL·CONTRACTION·CONTRACTION_WORD·WORD·SENTENCE)이다.
- `displayText`: SKILL_01에 연결된 CONSONANT는 `초성 {글자}`, SKILL_03에 연결된 CONSONANT는 `받침 {글자}`, VOWEL은 `모음 {글자}`, CONTRACTION은 `음절 {글자}`, 그 밖은 targetText 그대로. 서버가 level과 학습 요소로 정한다.
- `expectedPattern` 은 problem_cells.expected_pattern을 cell_index 순서로 담은 6비트 정수 배열(bit0=1점 … bit5=6점)이다.

구분어 없는 낱자에 후보가 둘 이상이면 공통 오류 body에 `candidates` 만 더한다. 클라이언트는 후보의 `input` 으로 다시 요청한다.

```json
{ "code": "PROBLEM_TEXT_AMBIGUOUS", "message": "어떤 문제인지 골라 주세요.", "candidates": [ { "input": "초성 ㄱ", "displayText": "초성 ㄱ" }, { "input": "받침 ㄱ", "displayText": "받침 ㄱ" } ] }
```

#### 8.2.2 LIVE 답안 응답·강사 답안 목록

LIVE 답안 요청 헤더는 `Idempotency-Key: <UUID v4>`, body는 `{"cells":[8],"durationMs":1900}` 이다. 성공 응답(LiveAnswer)은 다음과 같다.

```json
{ "attemptId": "901", "lectureId": "42", "problemId": "26578", "isCorrect": true, "score": 100, "attemptedAt": "2026-09-27T10:05:00.000Z", "cells": [ { "cellIndex": 1, "inputPattern": 8, "expectedPattern": 8, "diffMask": 0, "isCorrect": true } ] }
```

- 채점은 서버가 칸별로 한다. cells는 입력·정답 중 긴 쪽 칸 수만큼 만들고 모자란 쪽 패턴은 0이다. `cellIndex` 는 1부터, `diffMask = inputPattern XOR expectedPattern`.
- `isCorrect` 는 칸 수가 같고 모든 칸의 diffMask가 0일 때 true다. `score` 는 정답이면 100, 아니면 floor(100 × diffMask가 0인 칸 수 ÷ 긴 쪽 칸 수)다.
- AI 분석·BKT·user_skill_memory 갱신은 하지 않는다 (8.1절 LIVE 제외).
- 멱등: `Idempotency-Key` 는 형식만 검사하고 저장하지 않는다. (lecture_id, user_id, problem_id) UNIQUE로 한 번만 저장하며, 이미 풀이가 있으면 같은 cells(저장된 칸에 0을 채워 비교)면 200과 기존 결과, 다른 cells면 409 `LECTURE_PROBLEM_ALREADY_ANSWERED` 다.
- `durationMs` 는 0 이상인지만 검증하고 저장하지 않는다(problem_attempts에 시간 컬럼이 없다).

`GET /api/v1/lectures/{lectureId}/problems/{problemId}/answers` 응답은 다음과 같다. 제출한 학습자만 제출 시각 오름차순으로 담는다. 미제출·미입장 판정은 클라이언트가 접속자 목록과 맞춰 한다.

```json
{ "problemId": "26578", "answers": [ { "userId": "8", "username": "learner01", "isCorrect": false, "attemptedAt": "2026-09-27T10:05:00.000Z", "cells": [ { "cellIndex": 1, "inputPattern": 9, "expectedPattern": 8, "diffMask": 1, "isCorrect": false } ] } ] }
```

담당 강사가 아니면 404 `LECTURE_NOT_FOUND`, 이 수업에 연결되지 않은 문제면 404 `PROBLEM_NOT_FOUND` 다. 수업 상태와 관계없이(CLOSED 뒤에도) 조회할 수 있다.
~~~

- [ ] **Step 4: api-spec §8.3 MVP 안내**

`### 8.3 WebSocket 인증·이벤트·재연결` 제목 바로 아래(첫 문단 앞)에 넣는다.

```markdown
> MVP 구현 상태: 이 절의 WebSocket(티켓·이벤트)은 후속이다. MVP는 OpenVidu 방 metadata(`currentProblemId`)와 담당 강사 대상 데이터 메시지(`ANSWER_SUBMITTED`)로 변경을 알리고, 클라이언트는 신호를 받으면 HTTP로 최신 상태를 조회한다 (8.5.2절).
```

- [ ] **Step 5: api-spec §8.4 표**

(a) 기존 `| 403 | INSTRUCTOR_ACCESS_REQUIRED | 강사 전용 collection 요청·발언 권한 변경에 INSTRUCTOR가 아님 |` →

```markdown
| 403 | INSTRUCTOR_ACCESS_REQUIRED | 강사 전용 collection 요청·문제 연결·발언 권한 변경에 INSTRUCTOR가 아님 |
```

(b) 기존 `| 404 | PROBLEM_NOT_FOUND | 연결하려는 검증 문제 없음 |` →

```markdown
| 404 | PROBLEM_NOT_FOUND | 문제 연결 입력(구분어가 붙은 입력·구분어 없는 낱자)에 맞는 문제가 없음, 출제·답안 목록 대상 문제가 이 수업에 연결되지 않음 |
```

(c) `| 409 | INVALID_LECTURE_STATE | ... |` 행 바로 아래에 추가:

```markdown
| 409 | PROBLEM_TEXT_AMBIGUOUS | 구분어 없는 낱자에 문제 후보가 둘 이상. body에 `candidates`(각 `input`·`displayText`)를 더한다 (8.2.1절) |
```

(d) 기존 `| 409 | LECTURE_PROBLEM_ALREADY_ANSWERED | 다른 멱등 키로 같은 수업·학습자·문제 재제출 |` →

```markdown
| 409 | LECTURE_PROBLEM_ALREADY_ANSWERED | 같은 수업·학습자·문제에 다른 cells로 재제출 (같은 cells면 200과 기존 결과) |
```

(e) 기존 `| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키에 다른 정규화 답안 내용 |` →

```markdown
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키에 다른 정규화 답안 내용. LIVE 답안은 MVP에서 쓰지 않는다 — 키를 저장하지 않고 (lecture_id, user_id, problem_id) UNIQUE로 판정한다 |
```

(f) 기존 `| 422 | INVALID_ANSWER_CELLS | Cell 수·순번·범위 검증 실패 |` →

```markdown
| 422 | INVALID_ANSWER_CELLS | cells가 없거나 비었음, 12칸 초과, 값이 0~63 밖 |
```

(g) 503 행 →

```markdown
| 503 | MEDIA_SERVER_UNAVAILABLE | OpenVidu 방 생성·metadata 설정·삭제·참가자 권한 변경 호출 실패 (상태 변경, 강사 음성 토큰 요청의 방 다시 열기, 발언 권한 변경, 문제 출제의 metadata 쓰기) |
```

(h) 표 바로 아래 문단 `오류 body는 …` 앞에 새 문단을 넣는다.

```markdown
LIVE 답안의 `Idempotency-Key` 누락·UUID v4 형식 오류와 `durationMs` 누락·음수는 400 `INVALID_REQUEST_PARAMETER` 다. LIVE 답안 오류는 LEARNER_ACCESS_REQUIRED → LECTURE_NOT_FOUND(수업 없음·참여 이력 없음) → INVALID_LECTURE_STATE → LECTURE_PARTICIPATION_REQUIRED → LECTURE_PROBLEM_NOT_CURRENT → INVALID_ANSWER_CELLS → LECTURE_PROBLEM_ALREADY_ANSWERED 순서로 판정한다.
```

- [ ] **Step 6: api-spec §8.5 metadata 문구**

기존 bullet:

```markdown
- 강사가 OPEN으로 바꾸면 서버가 방을 만들고 방 metadata를 `{"status":"OPEN"}` 으로 설정한다. 학습자는 SCHEDULED에서 미리 접속해 대기할 수 있으므로 클라이언트는 접속 시 metadata와 metadata 변경 이벤트로 시작을 안다.
```

새 bullet:

```markdown
- 강사가 OPEN으로 바꾸면 서버가 방을 만들고 방 metadata를 `{"status":"OPEN"}` 으로 설정한다(현재 문제가 있으면 `currentProblemId` 를 더한다 — 8.5.2절). 학습자는 SCHEDULED에서 미리 접속해 대기할 수 있으므로 클라이언트는 접속 시 metadata와 metadata 변경 이벤트로 시작을 안다.
```

- [ ] **Step 7: api-spec §8.5.2 신설**

§8.5.1 마지막 문단("`allowed` 누락은 400 …") 바로 뒤, `## 9.` 앞에 넣는다.

~~~markdown
#### 8.5.2 문제 출제·제출 알림

MVP는 8.3절 WebSocket 대신 OpenVidu 방 기능으로 알린다. 신호는 "다시 조회하라"는 뜻일 뿐이며 정답·답안·결과를 싣지 않는다. 신호를 놓쳐도 `GET /api/v1/lectures/{lectureId}` 와 답안 목록 조회로 전체 화면을 복원한다.

- 방 metadata(JSON 문자열): `{"status":"OPEN","currentProblemId":"26578"}`. 현재 문제가 없으면 `currentProblemId` 를 넣지 않는다. 서버는 OPEN 전환, 출제(같은 값 재요청 포함), 강사 음성 토큰 발급 때의 방 다시 열기에서 이 값을 쓴다. 클라이언트는 metadata를 try/catch로 해석하며 시작 판정(`status`) 규칙은 8.5절 그대로다.
- 출제의 DB 저장은 metadata 쓰기 전에 커밋된다. metadata 쓰기가 실패하면 503 `MEDIA_SERVER_UNAVAILABLE` 이고, 강사가 같은 문제로 다시 출제하면 metadata만 다시 쓴다. 값이 같으면 OpenVidu가 변경 이벤트를 내지 않는다.
- 제출 알림: 새 LIVE 답안이 저장되면(201) 서버가 담당 강사 identity `user-{instructorUserId}` 에게만 OpenVidu 데이터 메시지를 보낸다. topic `lecture`, reliable, payload는 UTF-8 JSON `{"type":"ANSWER_SUBMITTED","problemId":"26578"}`. 같은 cells 재전송(200)에는 보내지 않는다. 전송 실패는 서버 로그만 남기고 제출은 성공으로 응답한다.
~~~

- [ ] **Step 8: 문서 diff 확인**

```bash
git diff --stat docs/product/api-spec.md
grep -n "body는 \`problemId\`만\|새 이벤트를 만들지 않는다\|현행 계약에는 참여자" docs/product/api-spec.md
```

Expected: 첫 줄은 api-spec 한 파일, grep 출력 없음.

- [ ] **Step 9: 실패하는 테스트 작성 (RED)**

`apps/backend/src/test/java/com/itda/global/error/CandidatesErrorResponseTest.java`:

```java
package com.itda.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.global.error.CandidatesBusinessException.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** PROBLEM_TEXT_AMBIGUOUS는 공통 오류 body에 candidates만 더한다 (API 명세 8.2.1절). */
class CandidatesErrorResponseTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private static CandidatesBusinessException ambiguous() {
    return new CandidatesBusinessException(
        ErrorCode.PROBLEM_TEXT_AMBIGUOUS,
        List.of(new Candidate("초성 ㄱ", "초성 ㄱ"), new Candidate("받침 ㄱ", "받침 ㄱ")));
  }

  @Test
  void 후보_목록을_공통_오류_body에_더한다() {
    ResponseEntity<CandidatesErrorResponse> response =
        handler.handleCandidates(ambiguous(), new MockHttpServletRequest());

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    CandidatesErrorResponse body = response.getBody();
    assertThat(body.code()).isEqualTo("PROBLEM_TEXT_AMBIGUOUS");
    assertThat(body.message()).isEqualTo(ErrorCode.PROBLEM_TEXT_AMBIGUOUS.getMessage());
    assertThat(body.candidates())
        .containsExactly(new Candidate("초성 ㄱ", "초성 ㄱ"), new Candidate("받침 ㄱ", "받침 ㄱ"));
  }

  @Test
  void JSON을_받지_않는_요청이면_상태는_두고_body만_생략한다() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Accept", "text/html");

    ResponseEntity<CandidatesErrorResponse> response = handler.handleCandidates(ambiguous(), request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isNull();
  }
}
```

- [ ] **Step 10: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.global.error.CandidatesErrorResponseTest"`
Expected: FAIL — compileTestJava에서 `CandidatesBusinessException`·`PROBLEM_TEXT_AMBIGUOUS` 를 찾을 수 없음

- [ ] **Step 11: ErrorCode 추가**

lecture 구역에서 `INVALID_LECTURE_STATE(...)` 줄 바로 아래에 추가한다.

```java
  /** 제출 경로의 문제가 지금 현재 문제가 아니다 — 그 사이 다음 문제로 바뀌었다 (8.4절). */
  LECTURE_PROBLEM_NOT_CURRENT(HttpStatus.CONFLICT, "지금 풀고 있는 문제가 아닙니다."),
  /** 같은 수업·학습자·문제에 다른 답안이 이미 저장돼 있다. 같은 답안 재전송은 200이다 (8.2.2절). */
  LECTURE_PROBLEM_ALREADY_ANSWERED(HttpStatus.CONFLICT, "이미 답안을 제출한 문제입니다."),
  /** cells 누락·빈 목록·12칸 초과·0~63 밖 값 (8.4절). */
  INVALID_ANSWER_CELLS(HttpStatus.UNPROCESSABLE_CONTENT, "답안 점자 칸을 확인해 주세요."),
```

problem 구역에서 `PROBLEM_VALIDATION_UNAVAILABLE(...)` 줄 **위**에 추가한다(마지막 상수의 `;` 는 그대로 둔다).

```java
  /** 연결 입력에 맞는 문제가 없거나, 출제·답안 목록 대상이 이 수업에 연결된 문제가 아니다 (8.4절). */
  PROBLEM_NOT_FOUND(HttpStatus.NOT_FOUND, "문제를 찾을 수 없습니다."),
  /** 구분어 없는 낱자에 후보가 둘 이상이다. body에 candidates를 더한다 — CandidatesBusinessException (8.2.1절). */
  PROBLEM_TEXT_AMBIGUOUS(HttpStatus.CONFLICT, "어떤 문제인지 골라 주세요."),
```

- [ ] **Step 12: 후보 예외·body·처리기**

`global/error/CandidatesBusinessException.java`:

```java
package com.itda.global.error;

import java.util.List;
import lombok.Getter;

/** 오류 body에 고를 수 있는 후보를 함께 싣는 비즈니스 예외 (API 명세 8.2.1절 PROBLEM_TEXT_AMBIGUOUS). */
@Getter
public class CandidatesBusinessException extends BusinessException {

  private final List<Candidate> candidates;

  public CandidatesBusinessException(ErrorCode errorCode, List<Candidate> candidates) {
    super(errorCode);
    this.candidates = List.copyOf(candidates);
  }

  /**
   * @param input 클라이언트가 그대로 다시 보낼 요청 값
   * @param displayText 후보 버튼 문구
   */
  public record Candidate(String input, String displayText) {}
}
```

`global/error/CandidatesErrorResponse.java`:

```java
package com.itda.global.error;

import com.itda.global.error.CandidatesBusinessException.Candidate;
import java.util.List;

/** 공통 오류 body {code, message}에 candidates만 더한 형태 (API 명세 8.2.1절). */
public record CandidatesErrorResponse(String code, String message, List<Candidate> candidates) {

  static CandidatesErrorResponse of(CandidatesBusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    return new CandidatesErrorResponse(
        errorCode.getCode(), errorCode.getMessage(), e.getCandidates());
  }
}
```

`GlobalExceptionHandler.java` — import `org.springframework.http.MediaType;` 를 추가하고 `handleBusiness` 바로 아래에 메서드를 추가한다. 같은 advice 안에서는 가장 가까운 예외 타입의 처리기가 골라지므로 `handleBusiness` 보다 이 메서드가 이긴다.

```java
  /** 공통 body에 candidates만 더한다. Accept가 JSON을 허용하지 않으면 다른 오류처럼 상태만 두고 body를 생략한다. */
  @ExceptionHandler(CandidatesBusinessException.class)
  public ResponseEntity<CandidatesErrorResponse> handleCandidates(
      CandidatesBusinessException e, HttpServletRequest request) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getErrorCode().getStatus());
    if (!ErrorHttpResponseFactory.acceptsJson(request)) {
      return builder.build();
    }
    return builder.contentType(MediaType.APPLICATION_JSON).body(CandidatesErrorResponse.of(e));
  }
```

- [ ] **Step 13: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.global.error.*"`
Expected: PASS (`ErrorCodeTest` 의 이름 규칙 포함)

- [ ] **Step 14: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short   # 아래 6개 파일만
git add docs/product/api-spec.md apps/backend/src/main/java/com/itda/global/error/ErrorCode.java apps/backend/src/main/java/com/itda/global/error/CandidatesBusinessException.java apps/backend/src/main/java/com/itda/global/error/CandidatesErrorResponse.java apps/backend/src/main/java/com/itda/global/error/GlobalExceptionHandler.java apps/backend/src/test/java/com/itda/global/error/CandidatesErrorResponseTest.java
git commit -m "docs(lecture): 수업 문제 출제·LIVE 답안·알림 계약과 오류 code를 정한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 입력 해석 파서·표시 규칙·연결 문제 읽기

**Files:**
- Create: `problem/service/ProblemTextQuery.java`, `problem/service/ProblemDisplay.java`, `problem/service/LinkedProblem.java`, `problem/service/LinkedProblemReader.java`
- Modify: `problem/repository/ProblemRepository.java` (조회 3개 추가 — `findByTargetText` 는 Task 3에서 지운다)
- Test: `problem/service/ProblemTextQueryTest.java`, `problem/service/ProblemDisplayTest.java`, `problem/service/LinkedProblemReaderTest.java`, `problem/repository/ProblemRepositoryTest.java`(추가)

**Interfaces:**
- Produces: `record ProblemTextQuery(ProblemTextQuery.Prefix prefix, String text)` — `static ProblemTextQuery parse(String raw)`, `boolean isSingleJamo()`; `enum ProblemTextQuery.Prefix { ONSET, CODA, VOWEL, SYLLABLE }` + `String label()`
- Produces: `static String ProblemDisplay.displayText(Level level, String targetText, Set<SkillType> skillTypes)`
- Produces: `record LinkedProblem(long problemId, String targetText, String displayText, Level problemType, List<Integer> expectedPattern)` + `int cellCount()`
- Produces: `@Component LinkedProblemReader` — `LinkedProblem read(Problem)`, `List<LinkedProblem> readAll(List<Problem>)` (입력 순서 유지)
- Produces: `ProblemRepository.findAllByTargetTextAndLevelAndSkillType(String, Level, SkillType)`, `findAllByTargetTextAndLevelInOrderByIdAsc(String, Collection<Level>)`, `findAllByTargetTextOrderByIdAsc(String)` — 모두 id 오름차순 `List<Problem>`

- [ ] **Step 1: 파서·표시 규칙 테스트 (RED)**

`problem/service/ProblemTextQueryTest.java`:

```java
package com.itda.problem.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.problem.service.ProblemTextQuery.Prefix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 강사 입력 규칙: 구분어 + 공백 1개 이상 + 글자 (API 명세 8.2.1절). */
class ProblemTextQueryTest {

  @ParameterizedTest
  @CsvSource({"초성 ㄱ,ONSET,ㄱ", "받침 ㄱ,CODA,ㄱ", "모음 ㅏ,VOWEL,ㅏ", "음절 가,SYLLABLE,가"})
  void 구분어와_글자를_나눈다(String raw, Prefix prefix, String text) {
    ProblemTextQuery query = ProblemTextQuery.parse(raw);

    assertThat(query.prefix()).isEqualTo(prefix);
    assertThat(query.text()).isEqualTo(text);
  }

  @Test
  void 앞뒤_공백을_지우고_여러_칸_공백도_구분_공백으로_본다() {
    ProblemTextQuery query = ProblemTextQuery.parse("  초성   ㄱ  ");

    assertThat(query.prefix()).isEqualTo(Prefix.ONSET);
    assertThat(query.text()).isEqualTo("ㄱ");
  }

  @Test
  void 전각_공백도_구분_공백으로_본다() {
    ProblemTextQuery query = ProblemTextQuery.parse("받침\u3000ㄱ");

    assertThat(query.prefix()).isEqualTo(Prefix.CODA);
    assertThat(query.text()).isEqualTo("ㄱ");
  }

  @Test
  void 공백_없이_붙으면_구분어로_보지_않는다() {
    ProblemTextQuery query = ProblemTextQuery.parse("초성ㄱ");

    assertThat(query.prefix()).isNull();
    assertThat(query.text()).isEqualTo("초성ㄱ");
  }

  @Test
  void 구분어만_있으면_낱말로_본다() {
    ProblemTextQuery query = ProblemTextQuery.parse("초성");

    assertThat(query.prefix()).isNull();
    assertThat(query.text()).isEqualTo("초성");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ㄱ", "ㅎ", "ㅏ", "ㅣ", "ㅆ", " ㄱ "})
  void 구분어_없는_호환_자모_한_글자는_낱자다(String raw) {
    assertThat(ProblemTextQuery.parse(raw).isSingleJamo()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"가", "ㄱㄴ", "초성 ㄱ", "그리고", "a"})
  void 음절_여러_글자_구분어_입력은_낱자가_아니다(String raw) {
    assertThat(ProblemTextQuery.parse(raw).isSingleJamo()).isFalse();
  }

  @Test
  void 구분어_문구는_표시_문구와_같다() {
    assertThat(Prefix.ONSET.label()).isEqualTo("초성");
    assertThat(Prefix.CODA.label()).isEqualTo("받침");
    assertThat(Prefix.VOWEL.label()).isEqualTo("모음");
    assertThat(Prefix.SYLLABLE.label()).isEqualTo("음절");
  }
}
```

`problem/service/ProblemDisplayTest.java`:

```java
package com.itda.problem.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.SkillType;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** displayText 규칙 (API 명세 8.2.1절). 입력 문자열이 아니라 level·학습 요소로 정한다. */
class ProblemDisplayTest {

  @Test
  void 첫소리_요소에_연결된_자음은_초성을_붙인다() {
    assertThat(ProblemDisplay.displayText(Level.CONSONANT, "ㄱ", Set.of(SkillType.SKILL_01)))
        .isEqualTo("초성 ㄱ");
  }

  @Test
  void 받침_요소에_연결된_자음은_받침을_붙인다() {
    assertThat(ProblemDisplay.displayText(Level.CONSONANT, "ㄱ", Set.of(SkillType.SKILL_03)))
        .isEqualTo("받침 ㄱ");
  }

  @Test
  void 첫소리와_받침_요소가_모두_있으면_초성을_붙인다() {
    assertThat(
            ProblemDisplay.displayText(
                Level.CONSONANT, "ㄱ", Set.of(SkillType.SKILL_01, SkillType.SKILL_03)))
        .isEqualTo("초성 ㄱ");
  }

  @Test
  void 둘_다_아닌_자음은_글자_그대로다() {
    assertThat(ProblemDisplay.displayText(Level.CONSONANT, "ㅆ", Set.of(SkillType.SKILL_04)))
        .isEqualTo("ㅆ");
  }

  @Test
  void 모음은_모음을_약자는_음절을_붙인다() {
    assertThat(ProblemDisplay.displayText(Level.VOWEL, "ㅏ", Set.of())).isEqualTo("모음 ㅏ");
    assertThat(ProblemDisplay.displayText(Level.CONTRACTION, "가", Set.of())).isEqualTo("음절 가");
  }

  @Test
  void 낱말_약어_문장은_글자_그대로다() {
    assertThat(ProblemDisplay.displayText(Level.WORD, "가", Set.of(SkillType.SKILL_01)))
        .isEqualTo("가");
    assertThat(ProblemDisplay.displayText(Level.CONTRACTION_WORD, "그리고", Set.of()))
        .isEqualTo("그리고");
    assertThat(ProblemDisplay.displayText(Level.SENTENCE, "나는 간다", Set.of()))
        .isEqualTo("나는 간다");
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.problem.service.ProblemTextQueryTest" --tests "com.itda.problem.service.ProblemDisplayTest"`
Expected: FAIL — compileTestJava에서 `ProblemTextQuery`·`ProblemDisplay` 없음

- [ ] **Step 3: 파서·표시 규칙 구현**

`problem/service/ProblemTextQuery.java`:

```java
package com.itda.problem.service;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 강사가 입력한 문제 글자. {@code 초성 ㄱ}처럼 구분어 + 공백 1개 이상 + 글자면 구분어를 떼고, 아니면 전체를 글자로 본다 (API 명세 8.2.1절).
 *
 * @param prefix 구분어가 없으면 null
 */
public record ProblemTextQuery(Prefix prefix, String text) {

  /** 전각 공백도 강사가 흔히 넣는다 — UNICODE_CHARACTER_CLASS로 \s가 그것까지 받게 한다. */
  private static final Pattern PREFIXED =
      Pattern.compile("^(초성|받침|모음|음절)\\s+(.+)$", Pattern.UNICODE_CHARACTER_CLASS);

  /** 한글 호환 자모 ㄱ(U+3131)~ㅣ(U+3163). 자음·모음 문제의 target_text가 이 범위다. */
  private static final char FIRST_JAMO = '\u3131';

  private static final char LAST_JAMO = '\u3163';

  public static ProblemTextQuery parse(String raw) {
    String trimmed = raw.strip();
    Matcher matcher = PREFIXED.matcher(trimmed);
    if (matcher.matches()) {
      return new ProblemTextQuery(Prefix.fromLabel(matcher.group(1)), matcher.group(2).strip());
    }
    return new ProblemTextQuery(null, trimmed);
  }

  /** 구분어 없는 호환 자모 한 글자 — 자음·모음 후보 중 고르게 한다. */
  public boolean isSingleJamo() {
    return prefix == null
        && text.length() == 1
        && text.charAt(0) >= FIRST_JAMO
        && text.charAt(0) <= LAST_JAMO;
  }

  /** 구분어. label은 입력 문구이자 displayText 앞머리다 — 두 곳이 어긋나지 않게 한 곳에 둔다. */
  public enum Prefix {
    ONSET("초성"),
    CODA("받침"),
    VOWEL("모음"),
    SYLLABLE("음절");

    private final String label;

    Prefix(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    static Prefix fromLabel(String label) {
      return Arrays.stream(values())
          .filter(prefix -> prefix.label.equals(label))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("알 수 없는 구분어: " + label));
    }
  }
}
```

`problem/service/ProblemDisplay.java`:

```java
package com.itda.problem.service;

import com.itda.problem.domain.Level;
import com.itda.problem.domain.SkillType;
import com.itda.problem.service.ProblemTextQuery.Prefix;
import java.util.Set;

/**
 * 문제의 표시 문구 (API 명세 8.2.1절). 같은 글자라도 초성·받침·모음·약자는 구분어를 붙여야 학습자가 무엇을 입력할지 안다.
 *
 * <p>입력 문자열이 아니라 문제의 level과 연결된 학습 요소로 정한다 — 같은 문제는 누가 어떻게 입력했든 같은 문구다.
 */
public final class ProblemDisplay {

  private ProblemDisplay() {}

  public static String displayText(Level level, String targetText, Set<SkillType> skillTypes) {
    return switch (level) {
      case CONSONANT -> {
        if (skillTypes.contains(SkillType.SKILL_01)) {
          yield prefixed(Prefix.ONSET, targetText);
        }
        if (skillTypes.contains(SkillType.SKILL_03)) {
          yield prefixed(Prefix.CODA, targetText);
        }
        yield targetText;
      }
      case VOWEL -> prefixed(Prefix.VOWEL, targetText);
      case CONTRACTION -> prefixed(Prefix.SYLLABLE, targetText);
      case WORD, SENTENCE, CONTRACTION_WORD -> targetText;
    };
  }

  private static String prefixed(Prefix prefix, String targetText) {
    return prefix.label() + " " + targetText;
  }
}
```

`problem/service/LinkedProblem.java`:

```java
package com.itda.problem.service;

import com.itda.problem.domain.Level;
import java.util.List;

/**
 * 수업에 연결된 문제 하나 (API 명세 8.2.1절 LinkedProblem). 강사에게는 정답 점형까지, 학습자에게는 그 일부만 내보낸다 — 가르는 것은 DTO다.
 *
 * @param problemType problems.level
 * @param expectedPattern cell_index 순서의 정답 점형(6비트)
 */
public record LinkedProblem(
    long problemId,
    String targetText,
    String displayText,
    Level problemType,
    List<Integer> expectedPattern) {

  public int cellCount() {
    return expectedPattern.size();
  }
}
```

- [ ] **Step 4: 파서·표시 규칙 통과 확인**

Run: 위 Step 2 명령
Expected: PASS

- [ ] **Step 5: 저장소 조회·읽기 테스트 (RED)**

`problem/repository/ProblemRepositoryTest.java` — import에 `com.itda.problem.domain.LearningSkill`, `ProblemSkill`, `SkillType` 이 이미 있다(없으면 추가). 클래스 끝의 `persistWithCells` 위에 추가한다.

```java
  @Test
  void 같은_자음이라도_학습_요소_유형으로_가른다() {
    // 로컬 DB에 'ㄱ' CONSONANT가 첫소리(SKILL_01)·받침(SKILL_03) 두 건 있다. '초성 ㄱ'·'받침 ㄱ'이 서로를 고르면 안 된다.
    LearningSkill onset =
        entityManager.persist(LearningSkill.of(SkillType.SKILL_01, "ㄱ", "첫소리 기본 자음", null));
    LearningSkill coda =
        entityManager.persist(LearningSkill.of(SkillType.SKILL_03, "ㄱ", "받침 기본 자음", null));
    Problem onsetProblem = persistWithCells(Level.CONSONANT, "ㄱ", List.of(8));
    Problem codaProblem = persistWithCells(Level.CONSONANT, "ㄱ", List.of(1));
    entityManager.persist(new ProblemSkill(onsetProblem, onset));
    entityManager.persist(new ProblemSkill(codaProblem, coda));
    entityManager.flush();

    assertThat(
            problemRepository.findAllByTargetTextAndLevelAndSkillType(
                "ㄱ", Level.CONSONANT, SkillType.SKILL_01))
        .extracting(Problem::getId)
        .containsExactly(onsetProblem.getId());
    assertThat(
            problemRepository.findAllByTargetTextAndLevelAndSkillType(
                "ㄱ", Level.CONSONANT, SkillType.SKILL_03))
        .extracting(Problem::getId)
        .containsExactly(codaProblem.getId());
  }

  @Test
  void 지정한_단계만_id_오름차순으로_고르고_단계를_빼면_전부_고른다() {
    Problem contraction = persistWithCells(Level.CONTRACTION, "가", List.of(43));
    Problem firstWord = persistWithCells(Level.WORD, "가", List.of(43));
    Problem secondWord = persistWithCells(Level.WORD, "가", List.of(43));
    persistWithCells(Level.WORD, "가방", List.of(43, 24));

    assertThat(
            problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc(
                "가", List.of(Level.WORD, Level.CONTRACTION_WORD, Level.SENTENCE)))
        .extracting(Problem::getId)
        .containsExactly(firstWord.getId(), secondWord.getId());
    assertThat(problemRepository.findAllByTargetTextOrderByIdAsc("가"))
        .extracting(Problem::getId)
        .containsExactly(contraction.getId(), firstWord.getId(), secondWord.getId());
  }
```

`problem/service/LinkedProblemReaderTest.java`:

```java
package com.itda.problem.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSkill;
import com.itda.problem.domain.SkillType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(LinkedProblemReader.class)
class LinkedProblemReaderTest {

  @Autowired private TestEntityManager entityManager;
  @Autowired private LinkedProblemReader reader;

  @Test
  void 칸_순서대로_정답_점형을_담고_학습_요소로_표시_문구를_만든다() {
    LearningSkill onset =
        entityManager.persist(LearningSkill.of(SkillType.SKILL_01, "ㄱ", "첫소리 기본 자음", null));
    Problem problem = entityManager.persistFlushFind(new Problem(Level.CONSONANT, "ㄱ"));
    // 저장 순서와 칸 순서가 달라도 cell_index 순서여야 한다.
    entityManager.persist(new ProblemCell(problem, 2, 3));
    entityManager.persist(new ProblemCell(problem, 1, 8));
    entityManager.persist(new ProblemSkill(problem, onset));
    entityManager.flush();
    entityManager.clear();

    LinkedProblem linked = reader.read(entityManager.find(Problem.class, problem.getId()));

    assertThat(linked.problemId()).isEqualTo(problem.getId());
    assertThat(linked.targetText()).isEqualTo("ㄱ");
    assertThat(linked.displayText()).isEqualTo("초성 ㄱ");
    assertThat(linked.problemType()).isEqualTo(Level.CONSONANT);
    assertThat(linked.expectedPattern()).containsExactly(8, 3);
    assertThat(linked.cellCount()).isEqualTo(2);
  }

  @Test
  void 여러_문제를_받은_순서대로_돌려준다() {
    Problem word = entityManager.persistFlushFind(new Problem(Level.WORD, "그리고"));
    entityManager.persist(new ProblemCell(word, 1, 1));
    Problem vowel = entityManager.persistFlushFind(new Problem(Level.VOWEL, "ㅏ"));
    entityManager.persist(new ProblemCell(vowel, 1, 35));
    entityManager.flush();

    List<LinkedProblem> linked = reader.readAll(List.of(vowel, word));

    assertThat(linked).extracting(LinkedProblem::displayText).containsExactly("모음 ㅏ", "그리고");
    assertThat(linked).extracting(LinkedProblem::expectedPattern)
        .containsExactly(List.of(35), List.of(1));
  }

  @Test
  void 빈_목록이면_빈_목록이다() {
    assertThat(reader.readAll(List.of())).isEmpty();
  }
}
```

- [ ] **Step 6: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.problem.repository.ProblemRepositoryTest" --tests "com.itda.problem.service.LinkedProblemReaderTest"`
Expected: FAIL — compileTestJava에서 새 조회 메서드·`LinkedProblemReader` 없음

- [ ] **Step 7: 저장소 조회 추가**

`ProblemRepository.java` — import `com.itda.problem.domain.SkillType;` 를 추가하고 `findByTargetText` 선언 바로 아래에 추가한다.

```java
  /**
   * '초성 ㄱ'·'받침 ㄱ' 해석용. 같은 글자의 자음 문제를 연결된 학습 요소 유형으로 가른다 (API 명세 8.2.1절).
   *
   * <p>로컬 DB에 같은 target_text가 여럿 있으므로 목록으로 받아 id 오름차순 첫 번째를 쓴다.
   */
  @Query(
      """
      SELECT DISTINCT p
      FROM Problem p
      JOIN ProblemSkill ps ON ps.problem = p
      WHERE p.targetText = :targetText
        AND p.level = :level
        AND ps.skill.skillType = :skillType
      ORDER BY p.id
      """)
  List<Problem> findAllByTargetTextAndLevelAndSkillType(
      @Param("targetText") String targetText,
      @Param("level") Level level,
      @Param("skillType") SkillType skillType);

  /** 같은 글자 중 지정한 단계만, id 오름차순 (API 명세 8.2.1절). */
  List<Problem> findAllByTargetTextAndLevelInOrderByIdAsc(
      String targetText, Collection<Level> levels);

  /** 같은 글자 전부, id 오름차순. 구분어 없는 입력의 마지막 후보다. */
  List<Problem> findAllByTargetTextOrderByIdAsc(String targetText);
```

- [ ] **Step 8: `LinkedProblemReader` 구현**

`problem/service/LinkedProblemReader.java`:

```java
package com.itda.problem.service;

import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.SkillType;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemSkillRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 문제 → {@link LinkedProblem}. 문제 등록 응답과 수업 Snapshot이 같은 모양을 쓰므로 한 곳에서 만든다. 셀·학습 요소를 문제 수와 관계없이 두
 * 번의 조회로 읽는다. 호출자의 트랜잭션 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
public class LinkedProblemReader {

  private final ProblemCellRepository problemCellRepository;
  private final ProblemSkillRepository problemSkillRepository;

  public LinkedProblem read(Problem problem) {
    return readAll(List.of(problem)).getFirst();
  }

  /** 받은 순서 그대로 돌려준다. */
  public List<LinkedProblem> readAll(List<Problem> problems) {
    if (problems.isEmpty()) {
      return List.of();
    }
    List<Long> problemIds = problems.stream().map(Problem::getId).distinct().toList();
    Map<Long, List<Integer>> patterns =
        problemCellRepository
            .findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(problemIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    cell -> cell.getProblem().getId(),
                    Collectors.mapping(ProblemCell::getExpectedPattern, Collectors.toList())));
    Map<Long, Set<SkillType>> skillTypes =
        problemSkillRepository.findAllWithSkillByProblemIdIn(problemIds).stream()
            .collect(
                Collectors.groupingBy(
                    link -> link.getProblem().getId(),
                    Collectors.mapping(link -> link.getSkill().getSkillType(), Collectors.toSet())));
    return problems.stream()
        .map(
            problem ->
                new LinkedProblem(
                    problem.getId(),
                    problem.getTargetText(),
                    ProblemDisplay.displayText(
                        problem.getLevel(),
                        problem.getTargetText(),
                        skillTypes.getOrDefault(problem.getId(), Set.of())),
                    problem.getLevel(),
                    List.copyOf(patterns.getOrDefault(problem.getId(), List.of()))))
        .toList();
  }
}
```

- [ ] **Step 9: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.problem.*"`
Expected: PASS

- [ ] **Step 10: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/problem/service/ProblemTextQuery.java apps/backend/src/main/java/com/itda/problem/service/ProblemDisplay.java apps/backend/src/main/java/com/itda/problem/service/LinkedProblem.java apps/backend/src/main/java/com/itda/problem/service/LinkedProblemReader.java apps/backend/src/main/java/com/itda/problem/repository/ProblemRepository.java apps/backend/src/test/java/com/itda/problem/service/ProblemTextQueryTest.java apps/backend/src/test/java/com/itda/problem/service/ProblemDisplayTest.java apps/backend/src/test/java/com/itda/problem/service/LinkedProblemReaderTest.java apps/backend/src/test/java/com/itda/problem/repository/ProblemRepositoryTest.java
git commit -m "feat(problem): 수업 문제 입력을 구분어로 해석하고 표시 문구를 만든다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 문제 등록 API 개편 (중복 연결 확인, 201/200, LinkedProblem 응답)

**Files:**
- Create: `problem/service/RegisteredProblem.java`, `problem/dto/LinkedProblemResponse.java`
- Modify: `problem/service/ProblemService.java` (`registerProblem`·`createProblem`·`validateInstructor`, 해석 메서드 추가, 필드 `linkedProblemReader`)
- Modify: `problem/repository/ProblemRepository.java` (`findByTargetText` 제거)
- Modify: `problem/dto/RegisterProblemRequest.java`, `problem/controller/ProblemController.java`
- Modify: `lecture/repository/LectureProblemRepository.java`
- Modify: `global/security/SecurityConfig.java`
- Test: `problem/service/ProblemServiceTest.java`(수정·추가), `lecture/controller/LectureProblemApiIntegrationTest.java`(신규)

**Interfaces:**
- Consumes: Task 2의 `ProblemTextQuery`, `LinkedProblemReader`, `LinkedProblem`, 저장소 조회 3개; Task 1의 `CandidatesBusinessException`, `ErrorCode.PROBLEM_NOT_FOUND`·`PROBLEM_TEXT_AMBIGUOUS`
- Produces: `RegisteredProblem ProblemService.registerProblem(AuthenticatedUser, Long lectureId, RegisterProblemRequest)`; `record RegisteredProblem(LinkedProblem problem, boolean created)`
- Produces: `record LinkedProblemResponse(String problemId, String targetText, String displayText, Level problemType, int cellCount, List<Integer> expectedPattern)` + `static LinkedProblemResponse from(LinkedProblem)` — Task 4 Snapshot DTO가 쓴다
- Produces: `Optional<LectureProblem> LectureProblemRepository.findByLecture_IdAndProblem_Id(Long lectureId, Long problemId)` — Task 5·7이 쓴다
- Produces (test): `LectureProblemApiIntegrationTest` 와 그 도우미(`accountFor`, `send`, `createLecture`, `join`, `changeStatus`, `seedWord`, `register`, `assertError`, `map`, `list`) — Task 4~7이 같은 파일에 테스트를 더한다

- [ ] **Step 1: 단위 테스트 수정·추가 (RED)**

`ProblemServiceTest.java`:

(a) import 추가:

```java
import com.itda.global.error.CandidatesBusinessException;
import com.itda.global.error.CandidatesBusinessException.Candidate;
```

(b) `@Mock private UserJpaRepository userJpaRepository;` 아래에 추가:

```java
  @Mock private LinkedProblemReader linkedProblemReader;
```

(c) 기존 `진행_중이_아닌_수업에는_문제를_등록할_수_없다` 메서드 전체를 다음 두 메서드로 교체한다(SCHEDULED도 연결할 수 있게 바뀐다 — API 명세 8.1절).

```java
  @Test
  void 종료된_수업에는_문제를_연결할_수_없다() {
    Lecture closed = Lecture.schedule(INSTRUCTOR_ID, "점자 기초", null, "123456", null, NOW);
    closed.open(NOW);
    closed.close(NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(closed));

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가방")),
        ErrorCode.INVALID_LECTURE_STATE);

    verify(fastApiClient, never()).analyze(any());
    verify(problemRepository, never()).save(any());
  }

  @Test
  void 시작_전_수업에도_문제를_연결한다() {
    Lecture scheduled = Lecture.schedule(INSTRUCTOR_ID, "점자 기초", null, "123456", null, NOW);
    given(lectureRepository.findById(LECTURE_ID)).willReturn(Optional.of(scheduled));
    Problem word = problem(Level.WORD, "그리고", 501L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("그리고", BARE_LEVELS))
        .willReturn(List.of(word));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("그리고"));

    verify(lectureProblemRepository).save(lectureProblemCaptor.capture());
    assertThat(lectureProblemCaptor.getValue().getProblem()).isSameAs(word);
  }
```

(d) 클래스 끝 도우미 영역(`givenLecture` 위)에 추가:

```java
  private static final List<Level> BARE_LEVELS =
      List.of(Level.WORD, Level.CONTRACTION_WORD, Level.SENTENCE);
  private static final List<Level> JAMO_LEVELS = List.of(Level.CONSONANT, Level.VOWEL);

  private static Problem problem(Level level, String targetText, long id) {
    Problem problem = new Problem(level, targetText);
    ReflectionTestUtils.setField(problem, "id", id);
    return problem;
  }

  private static LinkedProblem linked(long id, String displayText) {
    return new LinkedProblem(id, "ㄱ", displayText, Level.CONSONANT, List.of(8));
  }

  private Problem savedLinkProblem() {
    verify(lectureProblemRepository).save(lectureProblemCaptor.capture());
    return lectureProblemCaptor.getValue().getProblem();
  }
```

(e) `분석_결과에_문제_유형이_없으면_저장하지_않는다` 위에 입력 해석 테스트를 추가한다.

```java
  // ---------- 입력 해석 (API 명세 8.2.1절) ----------

  @Test
  void 초성_구분어는_첫소리_요소에_연결된_자음을_고른다() {
    givenLecture();
    Problem onset = problem(Level.CONSONANT, "ㄱ", 26578L);
    given(
            problemRepository.findAllByTargetTextAndLevelAndSkillType(
                "ㄱ", Level.CONSONANT, SkillType.SKILL_01))
        .willReturn(List.of(onset));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("초성 ㄱ"));

    assertThat(savedLinkProblem()).isSameAs(onset);
    verify(fastApiClient, never()).analyze(any());
  }

  @Test
  void 받침_구분어는_받침_요소에_연결된_자음을_고른다() {
    givenLecture();
    Problem coda = problem(Level.CONSONANT, "ㄱ", 26600L);
    given(
            problemRepository.findAllByTargetTextAndLevelAndSkillType(
                "ㄱ", Level.CONSONANT, SkillType.SKILL_03))
        .willReturn(List.of(coda));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("받침 ㄱ"));

    assertThat(savedLinkProblem()).isSameAs(coda);
  }

  @Test
  void 모음_구분어는_모음_문제를_고른다() {
    givenLecture();
    Problem vowel = problem(Level.VOWEL, "ㅏ", 26591L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㅏ", List.of(Level.VOWEL)))
        .willReturn(List.of(vowel));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("모음 ㅏ"));

    assertThat(savedLinkProblem()).isSameAs(vowel);
  }

  @Test
  void 음절_구분어는_약자_문제를_고른다() {
    givenLecture();
    Problem contraction = problem(Level.CONTRACTION, "가", 26620L);
    given(
            problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc(
                "가", List.of(Level.CONTRACTION)))
        .willReturn(List.of(contraction));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("음절 가"));

    assertThat(savedLinkProblem()).isSameAs(contraction);
  }

  @Test
  void 구분어가_붙은_입력에_문제가_없으면_404이고_AI로_만들지_않는다() {
    givenLecture();

    assertError(
        () ->
            problemService.registerProblem(
                INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("받침 ㅃ")),
        ErrorCode.PROBLEM_NOT_FOUND);

    verify(fastApiClient, never()).analyze(any());
    verify(lectureProblemRepository, never()).save(any());
  }

  @Test
  void 구분어_없는_낱자에_후보가_둘이면_후보를_돌려주고_연결하지_않는다() {
    givenLecture();
    List<Problem> candidates =
        List.of(problem(Level.CONSONANT, "ㄱ", 1L), problem(Level.CONSONANT, "ㄱ", 2L));
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㄱ", JAMO_LEVELS))
        .willReturn(candidates);
    given(linkedProblemReader.readAll(candidates))
        .willReturn(List.of(linked(1L, "초성 ㄱ"), linked(2L, "받침 ㄱ")));

    assertThatThrownBy(
            () ->
                problemService.registerProblem(
                    INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㄱ")))
        .isInstanceOfSatisfying(
            CandidatesBusinessException.class,
            e -> {
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROBLEM_TEXT_AMBIGUOUS);
              assertThat(e.getCandidates())
                  .containsExactly(
                      new Candidate("초성 ㄱ", "초성 ㄱ"), new Candidate("받침 ㄱ", "받침 ㄱ"));
            });
    verify(lectureProblemRepository, never()).save(any());
  }

  @Test
  void 구분어_없는_낱자에_후보가_하나면_바로_연결한다() {
    givenLecture();
    Problem vowel = problem(Level.VOWEL, "ㅏ", 26591L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㅏ", JAMO_LEVELS))
        .willReturn(List.of(vowel));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㅏ"));

    assertThat(savedLinkProblem()).isSameAs(vowel);
  }

  @Test
  void 표시_문구가_같은_중복_후보는_하나로_보고_id가_작은_것을_연결한다() {
    givenLecture();
    Problem first = problem(Level.VOWEL, "ㅏ", 10L);
    List<Problem> candidates = List.of(first, problem(Level.VOWEL, "ㅏ", 11L));
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("ㅏ", JAMO_LEVELS))
        .willReturn(candidates);
    given(linkedProblemReader.readAll(candidates))
        .willReturn(List.of(linked(10L, "모음 ㅏ"), linked(11L, "모음 ㅏ")));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㅏ"));

    assertThat(savedLinkProblem()).isSameAs(first);
  }

  @Test
  void 구분어_없는_낱자에_후보가_없으면_404다() {
    givenLecture();

    assertError(
        () -> problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("ㄳ")),
        ErrorCode.PROBLEM_NOT_FOUND);

    verify(fastApiClient, never()).analyze(any());
  }

  @Test
  void 구분어_없는_글자는_낱말_단계를_먼저_고른다() {
    givenLecture();
    Problem word = problem(Level.WORD, "가", 20L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("가", BARE_LEVELS))
        .willReturn(List.of(word));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("가"));

    assertThat(savedLinkProblem()).isSameAs(word);
    verify(problemRepository, never()).findAllByTargetTextOrderByIdAsc(any());
    verify(fastApiClient, never()).analyze(any());
  }

  @Test
  void 낱말_단계에_없으면_다른_단계의_같은_글자를_고른다() {
    givenLecture();
    Problem contraction = problem(Level.CONTRACTION, "억", 30L);
    given(problemRepository.findAllByTargetTextOrderByIdAsc("억")).willReturn(List.of(contraction));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("억"));

    assertThat(savedLinkProblem()).isSameAs(contraction);
    verify(fastApiClient, never()).analyze(any());
  }

  @Test
  void 앞뒤_공백을_지운_글자로_AI에_묻고_저장한다() {
    givenLecture();
    givenAnalysis(Level.WORD, List.of(0b000001), List.of(skillSet("ㄱ")));
    givenSkills(skill("ㄱ"));

    problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("  가방  "));

    ArgumentCaptor<RegisterProblemRequest> asked =
        ArgumentCaptor.forClass(RegisterProblemRequest.class);
    verify(fastApiClient).analyze(asked.capture());
    assertThat(asked.getValue().targetText()).isEqualTo("가방");
    verify(problemRepository).save(problemCaptor.capture());
    assertThat(problemCaptor.getValue().getTargetText()).isEqualTo("가방");
  }

  // ---------- 중복 연결 ----------

  @Test
  void 이미_연결된_문제면_저장하지_않고_기존_연결로_답한다() {
    Lecture lecture = givenLecture();
    Problem word = problem(Level.WORD, "그리고", 501L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("그리고", BARE_LEVELS))
        .willReturn(List.of(word));
    given(lectureProblemRepository.findByLecture_IdAndProblem_Id(any(), eq(501L)))
        .willReturn(Optional.of(new LectureProblem(lecture, word)));

    RegisteredProblem result =
        problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("그리고"));

    assertThat(result.created()).isFalse();
    verify(lectureProblemRepository, never()).save(any());
  }

  @Test
  void 새로_연결하면_created이고_읽은_문제_정보를_돌려준다() {
    givenLecture();
    Problem word = problem(Level.WORD, "그리고", 501L);
    given(problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc("그리고", BARE_LEVELS))
        .willReturn(List.of(word));
    LinkedProblem read = new LinkedProblem(501L, "그리고", "그리고", Level.WORD, List.of(1, 2));
    given(linkedProblemReader.read(word)).willReturn(read);

    RegisteredProblem result =
        problemService.registerProblem(INSTRUCTOR, LECTURE_ID, new RegisterProblemRequest("그리고"));

    assertThat(result.created()).isTrue();
    assertThat(result.problem()).isSameAs(read);
  }
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.problem.service.ProblemServiceTest"`
Expected: FAIL — compileTestJava에서 `RegisteredProblem`·`findByLecture_IdAndProblem_Id` 없음, `registerProblem` 이 void

- [ ] **Step 3: 등록 결과·응답 DTO·요청 검증**

`problem/service/RegisteredProblem.java`:

```java
package com.itda.problem.service;

/** @param created 이번 요청이 새로 연결했으면 true(201), 이미 연결돼 있었으면 false(200) */
public record RegisteredProblem(LinkedProblem problem, boolean created) {}
```

`problem/dto/LinkedProblemResponse.java`:

```java
package com.itda.problem.dto;

import com.itda.problem.domain.Level;
import com.itda.problem.service.LinkedProblem;
import java.util.List;

/** API 명세 8.2.1절 LinkedProblem. 문제 등록 응답과 강사 Snapshot이 같이 쓴다. problemId는 10진수 문자열이다. */
public record LinkedProblemResponse(
    String problemId,
    String targetText,
    String displayText,
    Level problemType,
    int cellCount,
    List<Integer> expectedPattern) {

  public static LinkedProblemResponse from(LinkedProblem problem) {
    return new LinkedProblemResponse(
        String.valueOf(problem.problemId()),
        problem.targetText(),
        problem.displayText(),
        problem.problemType(),
        problem.cellCount(),
        problem.expectedPattern());
  }
}
```

`problem/dto/RegisterProblemRequest.java`:

```java
package com.itda.problem.dto;

import jakarta.validation.constraints.NotBlank;

/** 강사 입력 그대로의 글자. 구분어 해석은 ProblemTextQuery가 한다 (API 명세 8.2.1절). AI 분석 요청 body로도 쓴다. */
public record RegisterProblemRequest(@NotBlank String targetText) {}
```

`LectureProblemRepository.java`:

```java
package com.itda.lecture.repository;

import com.itda.lecture.domain.LectureProblem;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LectureProblemRepository extends JpaRepository<LectureProblem, Long> {

  /** (lecture, problem) UNIQUE이므로 많아야 한 건이다. */
  Optional<LectureProblem> findByLecture_IdAndProblem_Id(Long lectureId, Long problemId);
}
```

- [ ] **Step 4: `ProblemService` 재작성**

(a) import 추가(와일드카드 `com.itda.problem.domain.*`·`java.util.*` 는 이미 있다):

```java
import com.itda.global.error.CandidatesBusinessException;
import com.itda.global.error.CandidatesBusinessException.Candidate;
import com.itda.problem.service.ProblemTextQuery.Prefix;
```

(b) `REVIEW_PROBLEMS_PER_SKILL` 상수 아래에 추가:

```java
  /** 구분어 없는 입력에서 먼저 고르는 단계 — 낱말·약어·문장 (API 명세 8.2.1절). */
  private static final List<Level> BARE_TEXT_LEVELS =
      List.of(Level.WORD, Level.CONTRACTION_WORD, Level.SENTENCE);

  /** 구분어 없는 낱자의 후보 단계. */
  private static final List<Level> JAMO_LEVELS = List.of(Level.CONSONANT, Level.VOWEL);
```

(c) 필드 목록 끝(`userJpaRepository` 아래)에 추가:

```java
  private final LinkedProblemReader linkedProblemReader;
```

(d) 기존 `registerProblem`·`createProblem`·`validateInstructor` 를 다음으로 교체한다(`// TODO:수강생들에게 문제 전달` 과 쓰이지 않던 `ProblemDetail` 조립은 지운다 — 전달은 출제 API가 맡는다).

```java
  /**
   * 강사 입력으로 문제를 찾아(없는 낱말은 AI로 만들어) 수업에 연결한다 (API 명세 8.2.1절). 이미 연결된 문제면 저장하지 않는다.
   *
   * <p>동시 요청이 (lecture_id, problem_id) UNIQUE에 걸리면 DataIntegrityViolationException이 나가고 이 트랜잭션은 롤백된다 —
   * 컨트롤러가 다시 불러 기존 연결(200)로 답한다.
   */
  @Transactional
  public RegisteredProblem registerProblem(
      AuthenticatedUser user, Long lectureId, RegisterProblemRequest registerProblemRequest) {
    Lecture lecture = validateInstructor(user, lectureId);
    Problem problem =
        resolveProblem(ProblemTextQuery.parse(registerProblemRequest.targetText()));
    boolean created =
        lectureProblemRepository
            .findByLecture_IdAndProblem_Id(lecture.getId(), problem.getId())
            .isEmpty();
    if (created) {
      lectureProblemRepository.save(new LectureProblem(lecture, problem));
    }
    return new RegisteredProblem(linkedProblemReader.read(problem), created);
  }

  private Problem resolveProblem(ProblemTextQuery query) {
    String text = query.text();
    if (query.prefix() != null) {
      // 자모·약자는 고정 목록이다. 없으면 AI로 만들지 않고 404로 알린다.
      return firstOrNotFound(prefixedCandidates(query.prefix(), text));
    }
    if (query.isSingleJamo()) {
      return pickJamo(text);
    }
    return problemRepository
        .findAllByTargetTextAndLevelInOrderByIdAsc(text, BARE_TEXT_LEVELS)
        .stream()
        .findFirst()
        // 약자만 있는 글자('억' 등)를 낱말로 또 만들지 않는다 — 같은 글자의 기존 문제를 먼저 쓴다.
        .or(() -> problemRepository.findAllByTargetTextOrderByIdAsc(text).stream().findFirst())
        .orElseGet(() -> createProblem(text));
  }

  private List<Problem> prefixedCandidates(Prefix prefix, String text) {
    return switch (prefix) {
      case ONSET ->
          problemRepository.findAllByTargetTextAndLevelAndSkillType(
              text, Level.CONSONANT, SkillType.SKILL_01);
      case CODA ->
          problemRepository.findAllByTargetTextAndLevelAndSkillType(
              text, Level.CONSONANT, SkillType.SKILL_03);
      case VOWEL ->
          problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc(text, List.of(Level.VOWEL));
      case SYLLABLE ->
          problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc(
              text, List.of(Level.CONTRACTION));
    };
  }

  /** 'ㄱ'처럼 구분어 없는 낱자. 표시 문구가 둘 이상 갈리면 강사에게 고르게 한다 (API 명세 8.2.1절). */
  private Problem pickJamo(String text) {
    List<Problem> candidates =
        problemRepository.findAllByTargetTextAndLevelInOrderByIdAsc(text, JAMO_LEVELS);
    if (candidates.size() <= 1) {
      return firstOrNotFound(candidates);
    }
    // 표시 문구가 같은 중복 문제는 하나로 본다 — 같은 버튼을 두 번 보이지 않는다. id 오름차순이라 작은 id가 남는다.
    List<LinkedProblem> linked = linkedProblemReader.readAll(candidates);
    Map<String, Problem> byDisplayText = new LinkedHashMap<>();
    for (int i = 0; i < candidates.size(); i++) {
      byDisplayText.putIfAbsent(linked.get(i).displayText(), candidates.get(i));
    }
    if (byDisplayText.size() == 1) {
      return byDisplayText.values().iterator().next();
    }
    throw new CandidatesBusinessException(
        ErrorCode.PROBLEM_TEXT_AMBIGUOUS,
        byDisplayText.keySet().stream()
            .map(displayText -> new Candidate(displayText, displayText))
            .toList());
  }

  private static Problem firstOrNotFound(List<Problem> candidates) {
    return candidates.stream()
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.PROBLEM_NOT_FOUND));
  }

  private Problem createProblem(String targetText) {
    ProblemAnalyzeResponse analyzeResponse =
        fastApiClient.analyze(new RegisterProblemRequest(targetText));

    validateAnalyzeResponse(analyzeResponse);

    Level level = analyzeResponse.level();
    List<Integer> cellPatterns = analyzeResponse.cells();
    List<SkillSet> skillSets = analyzeResponse.skillSets();

    Problem problem = new Problem(level, targetText);
    problemRepository.save(problem);

    List<LearningSkill> learningSkills = learningSkillQueryRepository.findAllBySkillSets(skillSets);

    List<ProblemSkill> problemSkills =
        learningSkills.stream().map(skill -> new ProblemSkill(problem, skill)).toList();

    problemSkillRepository.saveAll(problemSkills);

    List<ProblemCell> cells = new ArrayList<>();
    for (int i = 0; i < cellPatterns.size(); i++) {
      cells.add(new ProblemCell(problem, i + 1, cellPatterns.get(i)));
    }

    problemCellRepository.saveAll(cells);

    return problem;
  }

  /** 담당 강사만, SCHEDULED·OPEN에서 연결한다. CLOSED는 409 (API 명세 8.1절). */
  private Lecture validateInstructor(AuthenticatedUser user, Long lectureId) {
    if (!user.role().equals(UserRole.INSTRUCTOR)) {
      throw new BusinessException(ErrorCode.INSTRUCTOR_ACCESS_REQUIRED);
    }

    Lecture lecture =
        lectureRepository
            .findById(lectureId)
            .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_NOT_FOUND));

    if (!lecture.getInstructorUserId().equals(user.userId())) {
      throw new BusinessException(ErrorCode.LECTURE_NOT_FOUND);
    }
    if (!lecture.isJoinable()) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }

    return lecture;
  }
```

(e) `ProblemRepository.java` 에서 `Optional<Problem> findByTargetText(String s);` 와, 쓰이지 않게 된 `import java.util.Optional;` 을 지운다(같은 글자가 여럿이면 단건 조회가 예외를 낸다).

- [ ] **Step 5: 단위 테스트 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.problem.service.ProblemServiceTest"`
Expected: PASS (기존 AI 등록 테스트 포함 — "가방"은 두 조회가 빈 목록이라 AI 경로로 간다)

- [ ] **Step 6: 통합 테스트 작성 (RED)**

`lecture/controller/LectureProblemApiIntegrationTest.java` (신규):

```java
package com.itda.lecture.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.controller.LectureAuthenticatedApiIntegrationTest.FakeLiveRoomGateway;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSkill;
import com.itda.problem.domain.SkillType;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemRepository;
import com.itda.problem.repository.ProblemSkillRepository;
import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserRepository;
import com.itda.user.service.AccessTokenIssuer;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

/**
 * 수업 문제 연결·출제·LIVE 답안을 실제 HTTP와 H2로 검증한다 (API 명세 8.2·8.5.2절). OpenVidu 대역은 {@link
 * LectureAuthenticatedApiIntegrationTest}의 구성을 그대로 가져와 애플리케이션 context를 공유한다.
 */
@WebApiTest
@Import(LectureAuthenticatedApiIntegrationTest.FakeGatewayConfiguration.class)
class LectureProblemApiIntegrationTest {

  private static final String JSON = "application/json";

  /** 같은 DB를 다른 테스트 클래스와 나눠 쓰므로 username·낱말에 이 클래스만의 접두어와 수열을 쓴다. */
  private static final AtomicLong SEQUENCE = new AtomicLong();

  @LocalServerPort private int port;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private UserRepository userRepository;
  @Autowired private FakeLiveRoomGateway gateway;
  @Autowired private ProblemRepository problemRepository;
  @Autowired private ProblemCellRepository problemCellRepository;
  @Autowired private LearningSkillRepository learningSkillRepository;
  @Autowired private ProblemSkillRepository problemSkillRepository;

  private HttpTestClient client;
  private Account instructor;
  private Account learner;
  private Account otherLearner;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    this.instructor = accountFor(UserRole.INSTRUCTOR);
    this.learner = accountFor(UserRole.LEARNER);
    this.otherLearner = accountFor(UserRole.LEARNER);
  }

  // ---------- 도우미 ----------

  private record Account(long userId, String username, String token) {}

  private Account accountFor(UserRole role) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    String username = "lp" + role.name().toLowerCase() + SEQUENCE.incrementAndGet();
    User user =
        User.create(username, "argon2id$hash", role, Email.of(username + "@example.test"), now);
    user.verifyEmail(now);
    long userId = this.userRepository.save(user).getId();
    String token =
        this.accessTokenIssuer.issue(new AccessTokenIssuer.Claims(userId, role, 0)).compactToken();
    return new Account(userId, username, token);
  }

  private HttpResponse<String> send(
      String method, String path, String body, Account who, String... extraHeaders) {
    String[] headers = new String[4 + extraHeaders.length];
    headers[0] = "Content-Type";
    headers[1] = JSON;
    headers[2] = HttpHeaders.AUTHORIZATION;
    headers[3] = "Bearer " + who.token();
    System.arraycopy(extraHeaders, 0, headers, 4, extraHeaders.length);
    return this.client.send(method, "/api/v1" + path, body, headers);
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(HttpTestClient.json(response)).containsEntry("code", code);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  private String createLecture() {
    HttpResponse<String> created =
        send("POST", "/lectures", "{\"title\":\"문제 출제 수업\"}", this.instructor);
    assertThat(created.statusCode()).isEqualTo(201);
    return (String) HttpTestClient.json(created).get("lectureId");
  }

  private void join(String lectureId, Account who) {
    String code =
        (String)
            HttpTestClient.json(send("GET", "/lectures/" + lectureId, null, this.instructor))
                .get("participationCode");
    int status =
        send("POST", "/lecture-participations", "{\"participationCode\":\"" + code + "\"}", who)
            .statusCode();
    assertThat(status).isIn(200, 201);
  }

  private void changeStatus(String lectureId, String status) {
    assertThat(
            send(
                    "PUT",
                    "/lectures/" + lectureId + "/status",
                    "{\"status\":\"" + status + "\"}",
                    this.instructor)
                .statusCode())
        .isEqualTo(200);
  }

  /** AI를 부르지 않도록 낱말 문제를 미리 만든다. 글자는 테스트마다 다르다. */
  private Problem seedWord(List<Integer> cells) {
    Problem problem =
        this.problemRepository.save(new Problem(Level.WORD, "낱말" + SEQUENCE.incrementAndGet()));
    for (int i = 0; i < cells.size(); i++) {
      this.problemCellRepository.save(new ProblemCell(problem, i + 1, cells.get(i)));
    }
    return problem;
  }

  private LearningSkill skill(SkillType type, String metadata) {
    return this.learningSkillRepository
        .findBySkillTypeAndMetadata(type, metadata)
        .orElseGet(
            () -> this.learningSkillRepository.save(LearningSkill.of(type, metadata, "테스트 요소", null)));
  }

  private Problem seedConsonant(String text, SkillType type, int pattern) {
    Problem problem = this.problemRepository.save(new Problem(Level.CONSONANT, text));
    this.problemCellRepository.save(new ProblemCell(problem, 1, pattern));
    this.problemSkillRepository.save(new ProblemSkill(problem, skill(type, text)));
    return problem;
  }

  private HttpResponse<String> register(String lectureId, String targetText, Account who) {
    return send(
        "POST",
        "/lectures/" + lectureId + "/problems",
        "{\"targetText\":\"" + targetText + "\"}",
        who);
  }

  // ---------- 문제 연결 (API 명세 8.2.1절) ----------

  @Test
  void 강사가_낱말을_연결하면_201과_정답_점형을_받고_다시_연결하면_200이다() {
    String lectureId = createLecture();
    Problem word = seedWord(List.of(8, 3));

    HttpResponse<String> first = register(lectureId, word.getTargetText(), this.instructor);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(HttpTestClient.json(first))
        .containsEntry("problemId", String.valueOf(word.getId()))
        .containsEntry("targetText", word.getTargetText())
        .containsEntry("displayText", word.getTargetText())
        .containsEntry("problemType", "WORD")
        .containsEntry("cellCount", 2)
        .containsEntry("expectedPattern", List.of(8, 3));

    HttpResponse<String> again = register(lectureId, word.getTargetText(), this.instructor);

    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(HttpTestClient.json(again)).containsEntry("problemId", String.valueOf(word.getId()));
  }

  @Test
  void 구분어_없는_낱자에_후보가_둘이면_409와_후보를_받고_후보로_다시_연결한다() {
    // 'ㅎ'은 이 테스트만 만든다 — 다른 테스트가 같은 글자를 쓰면 후보가 늘어난다.
    Problem onset = seedConsonant("ㅎ", SkillType.SKILL_01, 26);
    seedConsonant("ㅎ", SkillType.SKILL_03, 22);
    String lectureId = createLecture();

    HttpResponse<String> ambiguous = register(lectureId, "ㅎ", this.instructor);

    assertError(ambiguous, 409, "PROBLEM_TEXT_AMBIGUOUS");
    assertThat(list(HttpTestClient.json(ambiguous).get("candidates")))
        .containsExactly(
            Map.of("input", "초성 ㅎ", "displayText", "초성 ㅎ"),
            Map.of("input", "받침 ㅎ", "displayText", "받침 ㅎ"));

    HttpResponse<String> chosen = register(lectureId, "초성 ㅎ", this.instructor);

    assertThat(chosen.statusCode()).isEqualTo(201);
    assertThat(HttpTestClient.json(chosen))
        .containsEntry("problemId", String.valueOf(onset.getId()))
        .containsEntry("displayText", "초성 ㅎ")
        .containsEntry("problemType", "CONSONANT");
  }

  @Test
  void 구분어가_붙었는데_문제가_없으면_404다() {
    assertError(
        register(createLecture(), "음절 뷁", this.instructor), 404, "PROBLEM_NOT_FOUND");
  }

  @Test
  void 종료된_수업에는_문제를_연결할_수_없다() {
    String lectureId = createLecture();
    changeStatus(lectureId, "OPEN");
    changeStatus(lectureId, "CLOSED");

    assertError(
        register(lectureId, seedWord(List.of(1)).getTargetText(), this.instructor),
        409,
        "INVALID_LECTURE_STATE");
  }

  @Test
  void 학습자는_문제를_연결할_수_없다() {
    assertError(
        register(createLecture(), "가", this.learner), 403, "INSTRUCTOR_ACCESS_REQUIRED");
  }

  @Test
  void 담당이_아닌_강사에게는_수업을_숨긴다() {
    assertError(
        register(createLecture(), "가", accountFor(UserRole.INSTRUCTOR)),
        404,
        "LECTURE_NOT_FOUND");
  }

  @Test
  void 빈_targetText는_400이다() {
    assertError(register(createLecture(), " ", this.instructor), 400, "INVALID_REQUEST_PARAMETER");
  }
}
```

- [ ] **Step 7: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.controller.LectureProblemApiIntegrationTest"`
Expected: FAIL — 보호 경로가 아니라 principal이 null이어서 500, 성공 시 204(본문 없음)

- [ ] **Step 8: 컨트롤러·보호 경로**

`ProblemController.java` — import 추가:

```java
import com.itda.problem.dto.LinkedProblemResponse;
import com.itda.problem.service.RegisteredProblem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
```

`registerProblem` 메서드를 교체한다.

```java
  /** 201 새 연결 / 200 이미 연결됨 (API 명세 8.2.1절). */
  @PostMapping("/lectures/{lectureId}/problems")
  public ResponseEntity<LinkedProblemResponse> registerProblem(
      @PathVariable @Positive Long lectureId,
      @Valid @RequestBody RegisterProblemRequest registerProblemRequest,
      @AuthenticationPrincipal AuthenticatedUser user) {
    RegisteredProblem registered;
    try {
      registered = problemService.registerProblem(user, lectureId, registerProblemRequest);
    } catch (DataIntegrityViolationException e) {
      // 같은 문제를 동시에 연결하면 한쪽이 (lecture_id, problem_id) UNIQUE에 걸린다. 그 트랜잭션은 이미 롤백됐으므로
      // 다시 불러 기존 연결(200)로 답한다.
      registered = problemService.registerProblem(user, lectureId, registerProblemRequest);
    }
    return ResponseEntity.status(registered.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(LinkedProblemResponse.from(registered.problem()));
  }
```

`SecurityConfig.java` — `LECTURE_PARTICIPANT_SPEAKING_PATH` 선언 아래에 추가:

```java
  private static final String LECTURE_PROBLEMS_PATH = "/lectures/{lectureId}/problems";
```

`securityMatcher` 의 `LECTURE_PARTICIPANT_SPEAKING_PATH` matcher 줄 아래에 추가:

```java
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LECTURE_PROBLEMS_PATH),
```

- [ ] **Step 9: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.controller.*" --tests "com.itda.problem.*"`
Expected: PASS. `빈_targetText는_400이다` 만 422 `INVALID_REQUEST_INPUT` 으로 실패하면 `@Positive` 가 빠져 `MethodArgumentNotValidException` 경로로 간 것이다 — `lectureId` 의 `@Positive` 를 확인한다(클래스에 `@Validated` 를 붙여 해결하지 않는다).

- [ ] **Step 10: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/problem apps/backend/src/main/java/com/itda/lecture/repository/LectureProblemRepository.java apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java apps/backend/src/test/java/com/itda/problem/service/ProblemServiceTest.java apps/backend/src/test/java/com/itda/lecture/controller/LectureProblemApiIntegrationTest.java
git commit -m "feat(problem): 수업 문제 연결이 구분어 입력을 받고 연결 문제 정보를 돌려준다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(`git add apps/backend/src/main/java/com/itda/problem` 전에 `git status --short` 에서 그 폴더에 이 Task가 만든·고친 파일만 있는지 본다.)

---

### Task 4: 채점 값과 수업 Snapshot 채우기

**Files:**
- Create: `lecture/service/AnswerCell.java`, `lecture/service/LiveAnswer.java`, `lecture/service/LiveAnswerGrader.java`, `lecture/service/LectureProblemQuery.java`
- Create: `lecture/dto/LearnerCurrentProblemResponse.java`, `lecture/dto/LiveAnswerResponse.java`, `lecture/dto/AnswerCellResponse.java`
- Modify: `lecture/service/LectureSnapshot.java`, `lecture/service/LectureService.java`
- Modify: `lecture/dto/InstructorLectureResponse.java`, `lecture/dto/LearnerLectureResponse.java`
- Modify: `lecture/repository/LectureProblemRepository.java`, `learning/domain/ProblemAttemptRepository.java`
- Test: `lecture/service/LiveAnswerGraderTest.java`(신규), `lecture/service/LectureServiceTest.java`(생성자), `lecture/dto/LectureResponseJsonTest.java`(추가), `lecture/controller/LectureProblemApiIntegrationTest.java`(추가)

**Interfaces:**
- Consumes: `LinkedProblem`, `LinkedProblemReader`(Task 2), `LinkedProblemResponse`(Task 3)
- Produces: `record AnswerCell(int cellIndex, int inputPattern, int expectedPattern)` + `int diffMask()`, `boolean correct()`, `static AnswerCell from(AttemptCell)`(package-private)
- Produces: `record LiveAnswer(long attemptId, long lectureId, long problemId, boolean correct, int score, LocalDateTime attemptedAt, List<AnswerCell> cells)` + `static LiveAnswer of(ProblemAttempt, List<AttemptCell>)`(package-private)
- Produces: `final class LiveAnswerGrader`(package-private) — `static final int MAX_CELLS = 12`, `static void validate(List<Integer>)`(422 `INVALID_ANSWER_CELLS`), `static List<AnswerCell> grade(List<Integer> input, List<Integer> expected)`, `static boolean isCorrect(List<Integer>, List<Integer>)`, `static int score(boolean, List<AnswerCell>)`, `static boolean sameInput(List<Integer> submitted, List<AnswerCell> stored)`
- Produces: `LectureSnapshot(Lecture lecture, Participant participation, LinkedProblem currentProblem, List<LinkedProblem> linkedProblems, LiveAnswer myResult)` + `ofInstructor(Lecture)`, `ofInstructor(Lecture, LinkedProblem, List<LinkedProblem>)`, `ofLearner(Lecture, Participant)`, `ofLearner(Lecture, Participant, LinkedProblem, LiveAnswer)`, `Long currentProblemId()`
- Produces: `@Component LectureProblemQuery` — `LectureSnapshot instructor(Lecture)`, `LectureSnapshot learner(Lecture, Participant)`, `Long currentProblemId(Lecture)`, `Optional<LiveAnswer> findAnswer(long lectureId, long userId, long problemId)` (모두 트랜잭션 안에서 부른다)
- Produces: `LectureProblemRepository.findAllWithProblemByLectureId(Long)`, `ProblemAttemptRepository.findByLectureIdAndUserIdAndProblemId(Long, Long, Long)`
- Produces: `LiveAnswerResponse.from(LiveAnswer)`, `AnswerCellResponse.from(AnswerCell)` — Task 6·7이 쓴다
- Produces: `LectureService` 생성자 인자 순서 `(LectureRepository, ParticipantRepository, ParticipationCodeGenerator, LiveRoomGateway, LectureProblemQuery, PlatformTransactionManager|TransactionOperations)`

- [ ] **Step 1: 채점 테스트 (RED)**

`lecture/service/LiveAnswerGraderTest.java`:

```java
package com.itda.lecture.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.global.error.ErrorCode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LIVE 채점 규칙 (API 명세 8.2.2절). 경계값을 우선한다 (testing.md). */
class LiveAnswerGraderTest {

  @Test
  void 모든_칸이_같으면_정답이고_100점이다() {
    List<AnswerCell> cells = LiveAnswerGrader.grade(List.of(8, 3), List.of(8, 3));

    assertThat(LiveAnswerGrader.isCorrect(List.of(8, 3), List.of(8, 3))).isTrue();
    assertThat(LiveAnswerGrader.score(true, cells)).isEqualTo(100);
    assertThat(cells).extracting(AnswerCell::cellIndex).containsExactly(1, 2);
    assertThat(cells).extracting(AnswerCell::diffMask).containsExactly(0, 0);
  }

  @Test
  void 틀린_칸의_diffMask는_입력과_정답의_XOR이다() {
    AnswerCell cell = LiveAnswerGrader.grade(List.of(9), List.of(8)).getFirst();

    assertThat(cell.inputPattern()).isEqualTo(9);
    assertThat(cell.expectedPattern()).isEqualTo(8);
    assertThat(cell.diffMask()).isEqualTo(1);
    assertThat(cell.correct()).isFalse();
  }

  @Test
  void 입력이_짧으면_긴_쪽만큼_칸을_만들고_입력을_0으로_채운다() {
    List<AnswerCell> cells = LiveAnswerGrader.grade(List.of(8), List.of(8, 3));

    assertThat(cells).hasSize(2);
    assertThat(cells.get(1).inputPattern()).isZero();
    assertThat(cells.get(1).expectedPattern()).isEqualTo(3);
    assertThat(LiveAnswerGrader.isCorrect(List.of(8), List.of(8, 3))).isFalse();
    assertThat(LiveAnswerGrader.score(false, cells)).isEqualTo(50);
  }

  @Test
  void 입력이_길면_정답을_0으로_채우고_점수는_내림한다() {
    List<AnswerCell> cells = LiveAnswerGrader.grade(List.of(8, 3, 1), List.of(8, 3));

    assertThat(cells).hasSize(3);
    assertThat(cells.get(2).expectedPattern()).isZero();
    assertThat(LiveAnswerGrader.score(false, cells)).isEqualTo(66);
  }

  @Test
  void 맞은_칸이_없으면_0점이다() {
    assertThat(LiveAnswerGrader.score(false, LiveAnswerGrader.grade(List.of(1), List.of(2))))
        .isZero();
  }

  @Test
  void 한_칸부터_열두_칸까지_0에서_63은_유효하다() {
    assertThatCode(() -> LiveAnswerGrader.validate(List.of(0))).doesNotThrowAnyException();
    assertThatCode(() -> LiveAnswerGrader.validate(Collections.nCopies(12, 63)))
        .doesNotThrowAnyException();
  }

  @Test
  void 없거나_빈_cells는_422다() {
    assertInvalid(null);
    assertInvalid(List.of());
  }

  @Test
  void 열세_칸은_422다() {
    assertInvalid(Collections.nCopies(13, 1));
  }

  @Test
  void 범위_밖_값과_null_칸은_422다() {
    assertInvalid(List.of(64));
    assertInvalid(List.of(-1));
    assertInvalid(Arrays.asList(8, null));
  }

  @Test
  void 저장된_칸과_0을_채워_비교해_같은_답인지_본다() {
    List<AnswerCell> stored = LiveAnswerGrader.grade(List.of(8), List.of(8, 3));

    assertThat(LiveAnswerGrader.sameInput(List.of(8), stored)).isTrue();
    assertThat(LiveAnswerGrader.sameInput(List.of(8, 0), stored)).isTrue();
    assertThat(LiveAnswerGrader.sameInput(List.of(8, 0, 0), stored)).isFalse();
    assertThat(LiveAnswerGrader.sameInput(List.of(9), stored)).isFalse();
  }

  private static void assertInvalid(List<Integer> cells) {
    assertThatThrownBy(() -> LiveAnswerGrader.validate(cells))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_ANSWER_CELLS);
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.service.LiveAnswerGraderTest"`
Expected: FAIL — `LiveAnswerGrader`·`AnswerCell` 없음

- [ ] **Step 3: 채점 값·규칙 구현**

`lecture/service/AnswerCell.java`:

```java
package com.itda.lecture.service;

import com.itda.learning.domain.AttemptCell;

/** 채점한 한 칸. cellIndex는 1부터, 모자란 쪽 패턴은 0이다 (API 명세 8.2.2절). */
public record AnswerCell(int cellIndex, int inputPattern, int expectedPattern) {

  public int diffMask() {
    return inputPattern ^ expectedPattern;
  }

  public boolean correct() {
    return diffMask() == 0;
  }

  static AnswerCell from(AttemptCell cell) {
    return new AnswerCell(cell.getCellIndex(), cell.getInputPattern(), cell.getExpectedPattern());
  }
}
```

`lecture/service/LiveAnswer.java`:

```java
package com.itda.lecture.service;

import com.itda.learning.domain.AttemptCell;
import com.itda.learning.domain.ProblemAttempt;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** 채점·저장된 LIVE 답안 (API 명세 8.2.2절 LiveAnswer). attemptedAt은 UTC다. */
public record LiveAnswer(
    long attemptId,
    long lectureId,
    long problemId,
    boolean correct,
    int score,
    LocalDateTime attemptedAt,
    List<AnswerCell> cells) {

  /** 저장된 풀이에서 다시 만든다. 점수는 저장하지 않으므로 칸에서 다시 계산한다 — 제출 때와 같은 규칙이다. */
  static LiveAnswer of(ProblemAttempt attempt, List<AttemptCell> storedCells) {
    List<AnswerCell> cells =
        storedCells.stream()
            .sorted(Comparator.comparingInt(AttemptCell::getCellIndex))
            .map(AnswerCell::from)
            .toList();
    return new LiveAnswer(
        attempt.getId(),
        attempt.getLectureId(),
        attempt.getProblemId(),
        attempt.isCorrect(),
        LiveAnswerGrader.score(attempt.isCorrect(), cells),
        attempt.getAttemptedAt(),
        cells);
  }
}
```

`lecture/service/LiveAnswerGrader.java`:

```java
package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;

/**
 * LIVE 답안 채점 규칙 (API 명세 8.2.2절). 칸별 XOR만 본다 — LIVE는 AI 분석·BKT에서 빠진다 (8.1절).
 *
 * <p>칸 수는 입력·정답 중 긴 쪽이고 모자란 쪽은 0으로 채운다. attempt_cells의 두 패턴이 모두 NOT NULL이라 저장 모양과 응답 모양을 같게 둔다.
 */
final class LiveAnswerGrader {

  /** 개인 학습·AI 입력 상한(ProblemService.MAX_ASSIGNABLE_CELLS)과 같은 12칸. */
  static final int MAX_CELLS = 12;

  private static final int MAX_PATTERN = 0b111111;
  private static final int FULL_SCORE = 100;

  private LiveAnswerGrader() {}

  static void validate(List<Integer> cells) {
    if (cells == null || cells.isEmpty() || cells.size() > MAX_CELLS) {
      throw invalid();
    }
    for (Integer cell : cells) {
      if (cell == null || cell < 0 || cell > MAX_PATTERN) {
        throw invalid();
      }
    }
  }

  static List<AnswerCell> grade(List<Integer> input, List<Integer> expected) {
    int length = Math.max(input.size(), expected.size());
    List<AnswerCell> cells = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      cells.add(new AnswerCell(i + 1, patternAt(input, i), patternAt(expected, i)));
    }
    return List.copyOf(cells);
  }

  /** 칸 수가 같고 모든 칸이 같아야 정답이다. */
  static boolean isCorrect(List<Integer> input, List<Integer> expected) {
    return input.equals(expected);
  }

  /** 정답이면 100, 아니면 diffMask가 0인 칸의 비율을 내림한다. 분모는 긴 쪽 칸 수다. */
  static int score(boolean correct, List<AnswerCell> cells) {
    if (correct) {
      return FULL_SCORE;
    }
    long matched = cells.stream().filter(AnswerCell::correct).count();
    return (int) (FULL_SCORE * matched / cells.size());
  }

  /**
   * 저장된 풀이와 같은 답인지. 저장된 입력은 0으로 채워져 있어 원래 길이를 모른다 — 제출값을 저장된 칸 수만큼 0으로 채워 비교하고, 저장된 칸보다
   * 길면 다른 답으로 본다.
   */
  static boolean sameInput(List<Integer> submitted, List<AnswerCell> stored) {
    if (submitted.size() > stored.size()) {
      return false;
    }
    for (int i = 0; i < stored.size(); i++) {
      if (patternAt(submitted, i) != stored.get(i).inputPattern()) {
        return false;
      }
    }
    return true;
  }

  private static int patternAt(List<Integer> patterns, int index) {
    return index < patterns.size() ? patterns.get(index) : 0;
  }

  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.INVALID_ANSWER_CELLS);
  }
}
```

- [ ] **Step 4: 채점 테스트 통과 확인**

Run: Step 2 명령
Expected: PASS

- [ ] **Step 5: Snapshot JSON·통합 테스트 (RED)**

`LectureResponseJsonTest.java` — import 추가:

```java
import com.itda.lecture.service.AnswerCell;
import com.itda.lecture.service.LiveAnswer;
import com.itda.problem.domain.Level;
import com.itda.problem.service.LinkedProblem;
import java.util.List;
```

클래스 끝에 추가:

```java
  private static final LinkedProblem CONSONANT =
      new LinkedProblem(26578L, "ㄱ", "초성 ㄱ", Level.CONSONANT, List.of(8));

  @Test
  void 강사_응답은_현재_문제와_연결_문제를_정답_점형과_함께_담는다() {
    JsonNode json =
        this.objectMapper.valueToTree(
            InstructorLectureResponse.from(
                LectureSnapshot.ofInstructor(lecture(), CONSONANT, List.of(CONSONANT))));

    JsonNode current = json.get("currentProblem");
    assertThat(current.get("problemId").asString()).isEqualTo("26578");
    assertThat(current.get("displayText").asString()).isEqualTo("초성 ㄱ");
    assertThat(current.get("problemType").asString()).isEqualTo("CONSONANT");
    assertThat(current.get("cellCount").asInt()).isEqualTo(1);
    assertThat(current.get("expectedPattern").get(0).asInt()).isEqualTo(8);
    assertThat(json.get("linkedProblems").size()).isEqualTo(1);
  }

  @Test
  void 학습자_현재_문제에는_정답_점형이_없고_제출했으면_결과를_담는다() {
    Participant participant = Participant.join(42L, 8L, NOW);
    LiveAnswer answer =
        new LiveAnswer(
            5L, 42L, 26578L, false, 50, NOW, List.of(new AnswerCell(1, 8, 8), new AnswerCell(2, 0, 3)));

    JsonNode json =
        this.objectMapper.valueToTree(
            LearnerLectureResponse.from(
                LectureSnapshot.ofLearner(lecture(), participant, CONSONANT, answer)));

    JsonNode current = json.get("currentProblem");
    assertThat(current.has("expectedPattern")).isFalse();
    assertThat(current.get("problemId").asString()).isEqualTo("26578");
    assertThat(current.get("displayText").asString()).isEqualTo("초성 ㄱ");
    assertThat(current.get("cellCount").asInt()).isEqualTo(1);
    assertThat(current.get("submitted").asBoolean()).isTrue();
    JsonNode result = json.get("myResult");
    assertThat(result.get("attemptId").asString()).isEqualTo("5");
    assertThat(result.get("lectureId").asString()).isEqualTo("42");
    assertThat(result.get("isCorrect").asBoolean()).isFalse();
    assertThat(result.get("score").asInt()).isEqualTo(50);
    assertThat(result.get("attemptedAt").asString()).isEqualTo("2026-09-26T01:00:00.000Z");
    JsonNode second = result.get("cells").get(1);
    assertThat(second.get("cellIndex").asInt()).isEqualTo(2);
    assertThat(second.get("diffMask").asInt()).isEqualTo(3);
    assertThat(second.get("isCorrect").asBoolean()).isFalse();
  }

  @Test
  void 학습자가_제출하지_않았으면_submitted는_false이고_myResult는_null이다() {
    Participant participant = Participant.join(42L, 8L, NOW);

    JsonNode json =
        this.objectMapper.valueToTree(
            LearnerLectureResponse.from(
                LectureSnapshot.ofLearner(lecture(), participant, CONSONANT, null)));

    assertThat(json.get("currentProblem").get("submitted").asBoolean()).isFalse();
    assertThat(json.get("myResult").isNull()).isTrue();
  }
```

`LectureProblemApiIntegrationTest.java` — 클래스 끝에 추가:

```java
  // ---------- Snapshot (API 명세 8.2절) ----------

  @Test
  void 강사_Snapshot은_연결_순서대로_문제를_담고_출제_전_현재_문제는_null이다() {
    String lectureId = createLecture();
    Problem first = seedWord(List.of(1));
    Problem second = seedWord(List.of(2, 4));
    register(lectureId, first.getTargetText(), this.instructor);
    register(lectureId, second.getTargetText(), this.instructor);

    Map<String, Object> view =
        HttpTestClient.json(send("GET", "/lectures/" + lectureId, null, this.instructor));

    assertThat(view.get("currentProblem")).isNull();
    assertThat(list(view.get("linkedProblems")))
        .extracting(problem -> map(problem).get("problemId"))
        .containsExactly(String.valueOf(first.getId()), String.valueOf(second.getId()));
    assertThat(map(list(view.get("linkedProblems")).get(1))).containsEntry("expectedPattern", List.of(2, 4));
  }

  @Test
  void 학습자_Snapshot은_출제_전_현재_문제와_결과가_null이다() {
    String lectureId = createLecture();
    join(lectureId, this.learner);

    Map<String, Object> view =
        HttpTestClient.json(send("GET", "/lectures/" + lectureId, null, this.learner));

    assertThat(view).containsEntry("currentProblem", null).containsEntry("myResult", null);
  }
```

- [ ] **Step 6: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.dto.LectureResponseJsonTest" --tests "com.itda.lecture.controller.LectureProblemApiIntegrationTest"`
Expected: FAIL — `LectureSnapshot.ofInstructor(Lecture, LinkedProblem, List)` 등 없음

- [ ] **Step 7: 저장소 조회 추가**

`LectureProblemRepository.java` — import `java.util.List;`, `org.springframework.data.jpa.repository.Query;`, `org.springframework.data.repository.query.Param;` 을 추가하고 메서드를 추가한다.

```java
  /** 강사 Snapshot의 linkedProblems. 연결 순서(id 오름차순)대로 문제를 함께 읽는다 (API 명세 8.2절). */
  @Query(
      "select lp from LectureProblem lp join fetch lp.problem "
          + "where lp.lecture.id = :lectureId order by lp.id")
  List<LectureProblem> findAllWithProblemByLectureId(@Param("lectureId") Long lectureId);
```

`ProblemAttemptRepository.java` — import `java.util.Optional;` 을 추가하고 메서드를 추가한다.

```java
  /** LIVE 풀이 한 건. (lecture_id, user_id, problem_id) UNIQUE이므로 많아야 한 건이다 (API 명세 8.1절). */
  Optional<ProblemAttempt> findByLectureIdAndUserIdAndProblemId(
      Long lectureId, Long userId, Long problemId);
```

- [ ] **Step 8: Snapshot·조립기**

`lecture/service/LectureSnapshot.java` 전체:

```java
package com.itda.lecture.service;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.problem.service.LinkedProblem;
import java.util.List;

/**
 * 요청자 관점의 수업 조회 결과. 강사와 학습자는 응답 필드가 다르다 (API 명세 8.2절).
 *
 * @param participation 학습자 보기에서만 값이 있다
 * @param currentProblem 현재 문제. 없으면 null
 * @param linkedProblems 강사 보기에서만 채운다(연결 순서)
 * @param myResult 학습자가 현재 문제에 제출했을 때만 값이 있다
 */
public record LectureSnapshot(
    Lecture lecture,
    Participant participation,
    LinkedProblem currentProblem,
    List<LinkedProblem> linkedProblems,
    LiveAnswer myResult) {

  /** 문제 정보를 채우지 않은 강사 보기 — 생성 직후·목록 항목처럼 연결 문제가 필요 없을 때. */
  public static LectureSnapshot ofInstructor(Lecture lecture) {
    return new LectureSnapshot(lecture, null, null, List.of(), null);
  }

  public static LectureSnapshot ofInstructor(
      Lecture lecture, LinkedProblem currentProblem, List<LinkedProblem> linkedProblems) {
    return new LectureSnapshot(lecture, null, currentProblem, List.copyOf(linkedProblems), null);
  }

  public static LectureSnapshot ofLearner(Lecture lecture, Participant participation) {
    return new LectureSnapshot(lecture, participation, null, List.of(), null);
  }

  public static LectureSnapshot ofLearner(
      Lecture lecture, Participant participation, LinkedProblem currentProblem, LiveAnswer myResult) {
    return new LectureSnapshot(lecture, participation, currentProblem, List.of(), myResult);
  }

  public boolean isInstructorView() {
    return participation == null;
  }

  /** 방 metadata에 쓰는 현재 문제 id (API 명세 8.5.2절). 없으면 null. */
  public Long currentProblemId() {
    return currentProblem == null ? null : currentProblem.problemId();
  }
}
```

`lecture/service/LectureProblemQuery.java`:

```java
package com.itda.lecture.service;

import com.itda.learning.domain.AttemptCellRepository;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureProblem;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.problem.domain.Problem;
import com.itda.problem.service.LinkedProblem;
import com.itda.problem.service.LinkedProblemReader;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수업의 문제 쪽 상태를 읽는다 — Snapshot 조립, 현재 문제, 저장된 LIVE 답안 (API 명세 8.2절). 문제는 지연 로딩이므로 호출자의 트랜잭션 안에서
 * 부른다.
 */
@Component
@RequiredArgsConstructor
public class LectureProblemQuery {

  private final LectureProblemRepository lectureProblemRepository;
  private final ProblemAttemptRepository problemAttemptRepository;
  private final AttemptCellRepository attemptCellRepository;
  private final LinkedProblemReader linkedProblemReader;

  public LectureSnapshot instructor(Lecture lecture) {
    List<LectureProblem> links =
        lectureProblemRepository.findAllWithProblemByLectureId(lecture.getId());
    List<LinkedProblem> linked =
        linkedProblemReader.readAll(links.stream().map(LectureProblem::getProblem).toList());
    LinkedProblem current = null;
    for (int i = 0; i < links.size(); i++) {
      if (links.get(i).getId().equals(lecture.getCurrentLectureProblemId())) {
        current = linked.get(i);
      }
    }
    return LectureSnapshot.ofInstructor(lecture, current, linked);
  }

  /** 정답 점형은 LinkedProblem에 있지만 학습자 DTO가 내보내지 않는다. */
  public LectureSnapshot learner(Lecture lecture, Participant participant) {
    Optional<Problem> current = currentProblem(lecture);
    if (current.isEmpty()) {
      return LectureSnapshot.ofLearner(lecture, participant);
    }
    Problem problem = current.get();
    LiveAnswer myResult =
        findAnswer(lecture.getId(), participant.getId().getUserId(), problem.getId()).orElse(null);
    return LectureSnapshot.ofLearner(
        lecture, participant, linkedProblemReader.read(problem), myResult);
  }

  /** 현재 문제의 problems.id. 없으면 null. */
  public Long currentProblemId(Lecture lecture) {
    return currentProblem(lecture).map(Problem::getId).orElse(null);
  }

  public Optional<LiveAnswer> findAnswer(long lectureId, long userId, long problemId) {
    return problemAttemptRepository
        .findByLectureIdAndUserIdAndProblemId(lectureId, userId, problemId)
        .map(
            attempt ->
                LiveAnswer.of(
                    attempt, attemptCellRepository.findAllByAttemptIds(List.of(attempt.getId()))));
  }

  private Optional<Problem> currentProblem(Lecture lecture) {
    Long lectureProblemId = lecture.getCurrentLectureProblemId();
    if (lectureProblemId == null) {
      return Optional.empty();
    }
    return lectureProblemRepository.findById(lectureProblemId).map(LectureProblem::getProblem);
  }
}
```

- [ ] **Step 9: `LectureService` 가 조립기를 쓴다**

`LectureService.java`:
- 필드 `private final LectureProblemQuery problemQuery;` 를 `liveRoomGateway` 아래에 추가한다.
- 두 생성자에 `LectureProblemQuery problemQuery` 인자를 `LiveRoomGateway liveRoomGateway` 바로 뒤에 추가하고, `@Autowired` 생성자의 `this(...)` 호출에도 같은 자리에 넘기며, package-private 생성자에서 `this.problemQuery = problemQuery;` 를 대입한다.
- `find` 를 교체한다.

```java
  @Transactional(readOnly = true)
  public LectureSnapshot find(AuthenticatedUser user, long lectureId) {
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    if (isOwner(user, lecture)) {
      return problemQuery.instructor(lecture);
    }
    if (user.role() == UserRole.LEARNER) {
      return participantRepository
          .findById(ParticipantId.of(lectureId, user.userId()))
          .map(participant -> problemQuery.learner(lecture, participant))
          .orElseThrow(LectureService::notFound);
    }
    throw notFound();
  }
```

- `changeStatus` 본문을 교체한다(방 조작은 그대로 — Task 5에서 현재 문제를 싣는다).

```java
  public LectureSnapshot changeStatus(
      AuthenticatedUser user, long lectureId, LectureStatus target) {
    LectureSnapshot snapshot =
        transactionOperations.execute(
            tx -> problemQuery.instructor(transition(user, lectureId, target)));
    String room = LectureRoom.nameOf(lectureId);
    if (target == LectureStatus.OPEN) {
      liveRoomGateway.openRoom(room);
    } else {
      liveRoomGateway.deleteRoom(room);
    }
    return snapshot;
  }
```

`LectureServiceTest.java`:
- import `com.itda.lecture.domain.Participant;` (없으면)
- `@Mock private ParticipationCodeGenerator codeGenerator;` 아래에 `@Mock private LectureProblemQuery problemQuery;` 추가
- `setUp` 의 생성자 호출을 `new LectureService(lectureRepository, participantRepository, codeGenerator, gateway, problemQuery, TransactionOperations.withoutTransaction());` 로 바꾸고, 그 아래에 추가한다(이 테스트는 LENIENT라 쓰이지 않는 stub이 허용된다).

```java
    // 문제 쪽 조립은 LectureProblemQuery가 맡는다. 여기서는 수업 상태만 본다.
    given(problemQuery.instructor(any(Lecture.class)))
        .willAnswer(call -> LectureSnapshot.ofInstructor(call.getArgument(0)));
    given(problemQuery.learner(any(Lecture.class), any(Participant.class)))
        .willAnswer(call -> LectureSnapshot.ofLearner(call.getArgument(0), call.getArgument(1)));
```

- [ ] **Step 10: Snapshot DTO**

`lecture/dto/AnswerCellResponse.java`:

```java
package com.itda.lecture.dto;

import com.itda.lecture.service.AnswerCell;

public record AnswerCellResponse(
    int cellIndex, int inputPattern, int expectedPattern, int diffMask, boolean isCorrect) {

  public static AnswerCellResponse from(AnswerCell cell) {
    return new AnswerCellResponse(
        cell.cellIndex(), cell.inputPattern(), cell.expectedPattern(), cell.diffMask(), cell.correct());
  }
}
```

`lecture/dto/LiveAnswerResponse.java`:

```java
package com.itda.lecture.dto;

import com.itda.lecture.service.LiveAnswer;
import java.util.List;

/** API 명세 8.2.2절 LiveAnswer. 제출 응답과 학습자 Snapshot의 myResult가 같이 쓴다. */
public record LiveAnswerResponse(
    String attemptId,
    String lectureId,
    String problemId,
    boolean isCorrect,
    int score,
    String attemptedAt,
    List<AnswerCellResponse> cells) {

  public static LiveAnswerResponse from(LiveAnswer answer) {
    return new LiveAnswerResponse(
        String.valueOf(answer.attemptId()),
        String.valueOf(answer.lectureId()),
        String.valueOf(answer.problemId()),
        answer.correct(),
        answer.score(),
        UtcTimes.format(answer.attemptedAt()),
        answer.cells().stream().map(AnswerCellResponse::from).toList());
  }
}
```

`lecture/dto/LearnerCurrentProblemResponse.java`:

```java
package com.itda.lecture.dto;

import com.itda.problem.domain.Level;
import com.itda.problem.service.LinkedProblem;

/** 학습자에게 보이는 현재 문제. 제출 전에 정답 점형을 주지 않는다 (API 명세 8.2절). */
public record LearnerCurrentProblemResponse(
    String problemId,
    Level problemType,
    String targetText,
    String displayText,
    int cellCount,
    boolean submitted) {

  static LearnerCurrentProblemResponse from(LinkedProblem problem, boolean submitted) {
    return new LearnerCurrentProblemResponse(
        String.valueOf(problem.problemId()),
        problem.problemType(),
        problem.targetText(),
        problem.displayText(),
        problem.cellCount(),
        submitted);
  }
}
```

`InstructorLectureResponse.java` 전체:

```java
package com.itda.lecture.dto;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.service.LectureSnapshot;
import com.itda.problem.dto.LinkedProblemResponse;
import java.util.List;

/** 담당 강사용 Snapshot (API 명세 8.2절). currentProblem·linkedProblems는 정답 점형을 포함한다. */
public record InstructorLectureResponse(
    String lectureId,
    String title,
    String description,
    LectureStatus status,
    String scheduledAt,
    String startedAt,
    String endedAt,
    LinkedProblemResponse currentProblem,
    String createdAt,
    String participationCode,
    List<LinkedProblemResponse> linkedProblems) {

  public static InstructorLectureResponse from(LectureSnapshot snapshot) {
    Lecture lecture = snapshot.lecture();
    return new InstructorLectureResponse(
        String.valueOf(lecture.getId()),
        lecture.getTitle(),
        lecture.getDescription(),
        lecture.getStatus(),
        UtcTimes.format(lecture.getScheduledAt()),
        UtcTimes.format(lecture.getStartedAt()),
        UtcTimes.format(lecture.getEndedAt()),
        snapshot.currentProblem() == null
            ? null
            : LinkedProblemResponse.from(snapshot.currentProblem()),
        UtcTimes.format(lecture.getCreatedAt()),
        lecture.getParticipationCode(),
        snapshot.linkedProblems().stream().map(LinkedProblemResponse::from).toList());
  }

  /** 수업 목록 항목. 문제 정보는 채우지 않는다 (API 명세 8.2절). */
  public static InstructorLectureResponse from(Lecture lecture) {
    return from(LectureSnapshot.ofInstructor(lecture));
  }
}
```

`LearnerLectureResponse.java` 전체:

```java
package com.itda.lecture.dto;

import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureStatus;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.service.LectureSnapshot;
import java.util.List;

/** 참여 학습자용 Snapshot (API 명세 8.2절). 참여 코드·다른 참여자 정보·제출 전 정답은 넣지 않는다. */
public record LearnerLectureResponse(
    String lectureId,
    String title,
    String description,
    LectureStatus status,
    String scheduledAt,
    String startedAt,
    String endedAt,
    LearnerCurrentProblemResponse currentProblem,
    String createdAt,
    MyParticipation myParticipation,
    LiveAnswerResponse myResult,
    List<Object> reviewableCurriculumStages) {

  public record MyParticipation(String joinedAt, String leftAt) {}

  public static LearnerLectureResponse from(LectureSnapshot snapshot) {
    Lecture lecture = snapshot.lecture();
    Participant participation = snapshot.participation();
    boolean submitted = snapshot.myResult() != null;
    return new LearnerLectureResponse(
        String.valueOf(lecture.getId()),
        lecture.getTitle(),
        lecture.getDescription(),
        lecture.getStatus(),
        UtcTimes.format(lecture.getScheduledAt()),
        UtcTimes.format(lecture.getStartedAt()),
        UtcTimes.format(lecture.getEndedAt()),
        snapshot.currentProblem() == null
            ? null
            : LearnerCurrentProblemResponse.from(snapshot.currentProblem(), submitted),
        UtcTimes.format(lecture.getCreatedAt()),
        new MyParticipation(
            UtcTimes.format(participation.getJoinedAt()),
            UtcTimes.format(participation.getLeftAt())),
        submitted ? LiveAnswerResponse.from(snapshot.myResult()) : null,
        List.of());
  }
}
```

- [ ] **Step 11: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.*"`
Expected: PASS. JSON 테스트에서 `isCorrect` 대신 `correct` 가 나오면 Jackson이 record의 `is` 접두어를 벗긴 것이다 — `AnswerCellResponse`·`LiveAnswerResponse` 의 해당 컴포넌트에 `@JsonProperty("isCorrect")`(`com.fasterxml.jackson.annotation.JsonProperty`)를 붙이고 다시 돌린다.

- [ ] **Step 12: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/lecture apps/backend/src/main/java/com/itda/learning/domain/ProblemAttemptRepository.java apps/backend/src/test/java/com/itda/lecture/service/LiveAnswerGraderTest.java apps/backend/src/test/java/com/itda/lecture/service/LectureServiceTest.java apps/backend/src/test/java/com/itda/lecture/dto/LectureResponseJsonTest.java apps/backend/src/test/java/com/itda/lecture/controller/LectureProblemApiIntegrationTest.java
git commit -m "feat(lecture): 수업 Snapshot에 현재 문제·연결 문제·내 결과를 채운다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 출제와 방 metadata (강사 토큰 재오픈 포함)

**Files:**
- Modify: `lecture/media/LiveRoomGateway.java`, `lecture/media/LiveKitRoomGateway.java`
- Modify: `lecture/service/LectureService.java` (`changeStatus`), `lecture/service/LectureMediaService.java`
- Modify: `lecture/repository/LectureRepository.java`
- Create: `lecture/service/LectureDeliveryService.java`, `lecture/dto/DeliverProblemRequest.java`, `lecture/controller/LectureProblemController.java`
- Modify: `global/security/SecurityConfig.java`
- Test: `lecture/media/LiveKitRoomGatewayTest.java`, `lecture/service/RecordingLiveRoomGateway.java`, `lecture/service/LectureMediaServiceTest.java`, `lecture/service/LectureServiceTest.java`, `lecture/repository/LectureRepositoryTest.java`, `lecture/controller/LectureAuthenticatedApiIntegrationTest.java`(대역), `lecture/controller/LectureProblemApiIntegrationTest.java`

**Interfaces:**
- Consumes: `LectureProblemQuery.instructor/currentProblemId`, `LectureSnapshot.currentProblemId()`(Task 4), `LectureProblemRepository.findByLecture_IdAndProblem_Id`(Task 3)
- Produces: `void LiveRoomGateway.openRoom(String roomName, Long currentProblemId)`(기존 1인자 메서드를 대체), `static String LiveKitRoomGateway.openMetadata(Long)`(package-private)
- Produces: `Optional<Lecture> LectureRepository.findByIdForUpdate(Long)` — Task 6이 쓴다
- Produces: `LectureSnapshot LectureDeliveryService.deliver(AuthenticatedUser, long lectureId, long problemId)`
- Produces: `LectureProblemController`(`@RequestMapping("/lectures/{lectureId}")`) — Task 6·7이 메서드를 더한다
- Produces (test): `RecordingLiveRoomGateway.openedCurrentProblemIds : List<Long>`; `FakeLiveRoomGateway.openedMetadata : List<String>`(`"{room}/{currentProblemId}"`), `FakeLiveRoomGateway.failRoomOperations : boolean`
- `LectureMediaService` 생성자 인자 순서: `(LectureRepository, ParticipantRepository, UserJpaRepository, LiveRoomGateway, LectureProblemQuery)`

- [ ] **Step 1: 게이트웨이 테스트 (RED)**

`LiveKitRoomGatewayTest.java` — import 추가 `livekit.LivekitModels.Room;`. 클래스 끝에 추가:

```java
  // ---------- 방 metadata (API 명세 8.5.2절) ----------

  @SuppressWarnings("unchecked")
  private static Call<Room> roomCall(Response<Room> response) throws IOException {
    Call<Room> call = mock(Call.class);
    given(call.execute()).willReturn(response);
    return call;
  }

  @Test
  void 현재_문제가_있으면_방_metadata에_문제_id를_싣는다() throws Exception {
    Call<Room> ok = roomCall(Response.success(Room.getDefaultInstance()));
    given(roomClient.createRoom(any(), any(), any(), any(), any())).willReturn(ok);
    given(roomClient.updateRoomMetadata(any(), any())).willReturn(ok);

    speakingGateway.openRoom("lecture-42", 26578L);

    String expected = "{\"status\":\"OPEN\",\"currentProblemId\":\"26578\"}";
    verify(roomClient).createRoom(eq("lecture-42"), eq(600), isNull(), isNull(), eq(expected));
    verify(roomClient).updateRoomMetadata("lecture-42", expected);
  }

  @Test
  void 현재_문제가_없으면_status만_싣는다() throws Exception {
    Call<Room> ok = roomCall(Response.success(Room.getDefaultInstance()));
    given(roomClient.createRoom(any(), any(), any(), any(), any())).willReturn(ok);
    given(roomClient.updateRoomMetadata(any(), any())).willReturn(ok);

    speakingGateway.openRoom("lecture-42", null);

    verify(roomClient).updateRoomMetadata("lecture-42", "{\"status\":\"OPEN\"}");
  }

  @Test
  void metadata_쓰기가_실패하면_503이다() throws Exception {
    given(roomClient.createRoom(any(), any(), any(), any(), any()))
        .willReturn(roomCall(Response.success(Room.getDefaultInstance())));
    given(roomClient.updateRoomMetadata(any(), any()))
        .willReturn(roomCall(Response.error(500, ResponseBody.create("", null))));

    assertThatThrownBy(() -> speakingGateway.openRoom("lecture-42", 26578L))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.media.LiveKitRoomGatewayTest"`
Expected: FAIL — `openRoom(String, Long)` 없음

- [ ] **Step 3: 게이트웨이 시그니처 변경**

`LiveRoomGateway.java` — `openRoom` 선언을 교체한다.

```java
  /**
   * 방을 만들고(이미 있으면 그대로) metadata를 {@code {"status":"OPEN","currentProblemId":"<id>"}}로 설정한다. 현재 문제가 없으면
   * {@code currentProblemId}를 넣지 않는다. 여러 번 불러도 결과가 같다 (API 명세 8.5·8.5.2절).
   *
   * @param currentProblemId 현재 문제의 problems.id, 없으면 null
   */
  void openRoom(String roomName, Long currentProblemId);
```

`LiveKitRoomGateway.java` — `static final String OPEN_METADATA = "{\"status\":\"OPEN\"}";` 를 지우고 `openRoom` 을 교체한다.

```java
  @Override
  public void openRoom(String roomName, Long currentProblemId) {
    String metadata = openMetadata(currentProblemId);
    // 학습자가 SCHEDULED에서 먼저 접속하면 LiveKit이 metadata 없는 방을 자동 생성해 둔다.
    // 그 경우 createRoom은 기존 방을 돌려줄 뿐 metadata를 바꾸지 않으므로 갱신을 항상 함께 부른다.
    execute(
        roomServiceClient.createRoom(roomName, EMPTY_TIMEOUT_SECONDS, null, null, metadata),
        "createRoom",
        false);
    execute(roomServiceClient.updateRoomMetadata(roomName, metadata), "updateRoomMetadata", false);
  }

  /** 방 metadata 규약 (API 명세 8.5.2절). id는 숫자라 JSON 이스케이프가 필요 없다. 현재 문제가 없으면 키를 넣지 않는다. */
  static String openMetadata(Long currentProblemId) {
    return currentProblemId == null
        ? "{\"status\":\"OPEN\"}"
        : "{\"status\":\"OPEN\",\"currentProblemId\":\"" + currentProblemId + "\"}";
  }
```

- [ ] **Step 4: 대역·호출부 갱신 (컴파일 유지)**

`RecordingLiveRoomGateway.java` — `openedRooms` 아래에 필드를 추가하고 `openRoom` 을 교체한다.

```java
  /** openRoom마다 받은 현재 문제 id(없으면 null). openedRooms와 같은 순서다. */
  final List<Long> openedCurrentProblemIds = new ArrayList<>();
```

```java
  @Override
  public void openRoom(String roomName, Long currentProblemId) {
    failIfRequested();
    openedRooms.add(roomName);
    openedCurrentProblemIds.add(currentProblemId);
  }
```

`LectureAuthenticatedApiIntegrationTest.FakeLiveRoomGateway` — import `com.itda.global.error.BusinessException;`, `com.itda.global.error.ErrorCode;` 추가. `openedRooms` 아래에 필드를 추가하고 `openRoom` 을 교체한다.

```java
    /** {@code "{room}/{currentProblemId}"} — 현재 문제가 없으면 {@code "null"}. */
    final List<String> openedMetadata = new CopyOnWriteArrayList<>();

    /** true면 방 조작이 503으로 실패한다. context를 공유하므로 쓴 테스트가 finally에서 되돌린다. */
    volatile boolean failRoomOperations;
```

```java
    @Override
    public void openRoom(String roomName, Long currentProblemId) {
      if (this.failRoomOperations) {
        throw new BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
      }
      this.openedRooms.add(roomName);
      this.openedMetadata.add(roomName + "/" + currentProblemId);
    }
```

`LectureService.changeStatus` — `liveRoomGateway.openRoom(room);` 를 다음으로 바꾼다.

```java
      // 같은 OPEN 재요청으로 방을 복구할 때 현재 문제도 되살린다 (API 명세 8.5.2절).
      liveRoomGateway.openRoom(room, snapshot.currentProblemId());
```

`LectureMediaService.java` — 필드 `private final LectureProblemQuery problemQuery;` 를 `liveRoomGateway` 아래에 추가하고, `liveRoomGateway.openRoom(room);` 을 교체한다.

```java
      // 방을 다시 열 때 현재 문제도 metadata에 되살린다 — 재접속한 학습자가 풀던 문제를 다시 받는다 (API 명세 8.5.2절).
      liveRoomGateway.openRoom(room, problemQuery.currentProblemId(lecture));
```

`LectureMediaServiceTest.java` — `@Mock private LectureProblemQuery problemQuery;` 를 추가하고 생성자 호출을 `new LectureMediaService(lectureRepository, participantRepository, userJpaRepository, gateway, problemQuery)` 로 바꾼다. 클래스 끝에 테스트를 추가한다.

```java
  @Test
  void 강사가_재접속해_방을_다시_열면_현재_문제를_metadata에_싣는다() {
    lecture.open(NOW);
    given(problemQuery.currentProblemId(lecture)).willReturn(26578L);

    service.issue(new AuthenticatedUser(7L, UserRole.INSTRUCTOR), LECTURE_ID);

    assertThat(gateway.openedCurrentProblemIds).containsExactly(26578L);
  }
```

(import `com.itda.global.security.AuthenticatedUser`·`com.itda.user.domain.UserRole` 은 이미 있다. 없으면 추가한다.)

`LectureServiceTest.java` — 클래스 끝에 테스트를 추가한다.

```java
  @Test
  void 시작하면_현재_문제를_방_metadata에_싣는다() {
    Lecture lecture = lecture(7L);
    given(problemQuery.instructor(lecture))
        .willAnswer(
            call ->
                LectureSnapshot.ofInstructor(
                    lecture,
                    new com.itda.problem.service.LinkedProblem(
                        26578L, "ㄱ", "초성 ㄱ", com.itda.problem.domain.Level.CONSONANT, List.of(8)),
                    List.of()));

    service.changeStatus(new AuthenticatedUser(7L, UserRole.INSTRUCTOR), LECTURE_ID, LectureStatus.OPEN);

    assertThat(gateway.openedCurrentProblemIds).containsExactly(26578L);
  }
```

(`List`·`LectureStatus`·`AuthenticatedUser`·`UserRole` import가 없으면 추가한다. 정규화된 이름 대신 import를 써도 된다.)

- [ ] **Step 5: 게이트웨이·서비스 테스트 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.*"`
Expected: PASS

- [ ] **Step 6: 출제 테스트 (RED)**

`LectureRepositoryTest.java` — 클래스 끝에 추가(이 클래스의 기존 수업 저장 방식을 따른다. `entityManager`·`lectureRepository` 필드 이름이 다르면 기존 이름을 쓴다).

```java
  @Test
  void 잠금_조회로_수업을_읽는다() {
    Lecture saved =
        lectureRepository.saveAndFlush(
            Lecture.schedule(7L, "잠금 수업", null, "654321", null, LocalDateTime.of(2026, 9, 27, 1, 0)));

    assertThat(lectureRepository.findByIdForUpdate(saved.getId())).contains(saved);
  }
```

`LectureProblemApiIntegrationTest.java` — import `java.util.stream.Collectors;` 불필요. 클래스 끝에 추가:

```java
  // ---------- 출제 (API 명세 8.2·8.5.2절) ----------

  private record Live(String lectureId, long problemId) {}

  /** 학습자가 입장하고 강사가 시작한 수업에 낱말 문제 하나를 연결한다. 출제는 하지 않는다. */
  private Live openLectureWithLinkedWord(List<Integer> cells) {
    String lectureId = createLecture();
    join(lectureId, this.learner);
    changeStatus(lectureId, "OPEN");
    Problem word = seedWord(cells);
    assertThat(register(lectureId, word.getTargetText(), this.instructor).statusCode()).isEqualTo(201);
    return new Live(lectureId, word.getId());
  }

  private HttpResponse<String> deliver(String lectureId, long problemId, Account who) {
    return send(
        "PUT",
        "/lectures/" + lectureId + "/current-problem",
        "{\"problemId\":\"" + problemId + "\"}",
        who);
  }

  private long metadataWrites(Live live) {
    String entry = "lecture-" + live.lectureId() + "/" + live.problemId();
    return this.gateway.openedMetadata.stream().filter(entry::equals).count();
  }

  @Test
  void 강사가_출제하면_현재_문제가_바뀌고_방_metadata에_실린다() {
    Live live = openLectureWithLinkedWord(List.of(8, 3));

    HttpResponse<String> delivered = deliver(live.lectureId(), live.problemId(), this.instructor);

    assertThat(delivered.statusCode()).isEqualTo(200);
    assertThat(map(HttpTestClient.json(delivered).get("currentProblem")))
        .containsEntry("problemId", String.valueOf(live.problemId()))
        .containsEntry("expectedPattern", List.of(8, 3));
    assertThat(metadataWrites(live)).isEqualTo(1);

    Map<String, Object> learnerView =
        HttpTestClient.json(send("GET", "/lectures/" + live.lectureId(), null, this.learner));
    Map<String, Object> current = map(learnerView.get("currentProblem"));
    assertThat(current)
        .containsEntry("problemId", String.valueOf(live.problemId()))
        .containsEntry("problemType", "WORD")
        .containsEntry("cellCount", 2)
        .containsEntry("submitted", false)
        .doesNotContainKey("expectedPattern");
    assertThat(learnerView).containsEntry("myResult", null);
  }

  @Test
  void 같은_문제를_다시_출제해도_200이고_metadata를_다시_쓴다() {
    Live live = openLectureWithLinkedWord(List.of(1));

    assertThat(deliver(live.lectureId(), live.problemId(), this.instructor).statusCode()).isEqualTo(200);
    assertThat(deliver(live.lectureId(), live.problemId(), this.instructor).statusCode()).isEqualTo(200);

    assertThat(metadataWrites(live)).isEqualTo(2);
  }

  @Test
  void 연결하지_않은_문제는_출제할_수_없다() {
    Live live = openLectureWithLinkedWord(List.of(1));

    assertError(
        deliver(live.lectureId(), seedWord(List.of(2)).getId(), this.instructor),
        404,
        "PROBLEM_NOT_FOUND");
  }

  @Test
  void 시작_전_수업에서는_출제할_수_없다() {
    String lectureId = createLecture();
    Problem word = seedWord(List.of(1));
    register(lectureId, word.getTargetText(), this.instructor);

    assertError(deliver(lectureId, word.getId(), this.instructor), 409, "INVALID_LECTURE_STATE");
  }

  @Test
  void 담당이_아닌_강사에게는_출제_대상_수업을_숨긴다() {
    Live live = openLectureWithLinkedWord(List.of(1));

    assertError(
        deliver(live.lectureId(), live.problemId(), accountFor(UserRole.INSTRUCTOR)),
        404,
        "LECTURE_NOT_FOUND");
  }

  @Test
  void metadata_쓰기가_실패하면_503이지만_출제는_저장되고_다시_누르면_복구된다() {
    Live live = openLectureWithLinkedWord(List.of(1));
    this.gateway.failRoomOperations = true;
    try {
      assertError(
          deliver(live.lectureId(), live.problemId(), this.instructor),
          503,
          "MEDIA_SERVER_UNAVAILABLE");
    } finally {
      this.gateway.failRoomOperations = false;
    }

    Map<String, Object> view =
        HttpTestClient.json(send("GET", "/lectures/" + live.lectureId(), null, this.instructor));
    assertThat(map(view.get("currentProblem")))
        .containsEntry("problemId", String.valueOf(live.problemId()));

    assertThat(deliver(live.lectureId(), live.problemId(), this.instructor).statusCode()).isEqualTo(200);
    assertThat(metadataWrites(live)).isEqualTo(1);
  }

  @Test
  void 강사가_음성_토큰을_받으면_현재_문제를_metadata에_다시_싣는다() {
    Live live = openLectureWithLinkedWord(List.of(1));
    deliver(live.lectureId(), live.problemId(), this.instructor);

    assertThat(
            send("POST", "/lectures/" + live.lectureId() + "/media-tokens", null, this.instructor)
                .statusCode())
        .isEqualTo(201);

    assertThat(metadataWrites(live)).isEqualTo(2);
  }
```

- [ ] **Step 7: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.repository.LectureRepositoryTest" --tests "com.itda.lecture.controller.LectureProblemApiIntegrationTest"`
Expected: FAIL — `findByIdForUpdate` 없음, `/current-problem` 경로 없음

- [ ] **Step 8: 잠금 조회·출제 서비스·컨트롤러·보호 경로**

`LectureRepository.java` — import `jakarta.persistence.LockModeType;`, `org.springframework.data.jpa.repository.Lock;`, `org.springframework.data.jpa.repository.Query;`, `org.springframework.data.repository.query.Param;` 추가 후 메서드 추가:

```java
  /**
   * 행을 잠그고 읽는다. 종료·출제·LIVE 답안 저장을 같은 lectures 행 잠금으로 직렬화한다 (API 명세 8.1절). 트랜잭션 안에서만 부른다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from Lecture l where l.id = :id")
  Optional<Lecture> findByIdForUpdate(@Param("id") Long id);
```

`lecture/service/LectureDeliveryService.java`:

```java
package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.LectureProblem;
import com.itda.lecture.media.LectureRoom;
import com.itda.lecture.media.LiveRoomGateway;
import com.itda.lecture.repository.LectureProblemRepository;
import com.itda.lecture.repository.LectureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** 현재 문제 출제 (API 명세 8.2·8.5.2절). */
@Service
public class LectureDeliveryService {

  private final LectureRepository lectureRepository;
  private final LectureProblemRepository lectureProblemRepository;
  private final LectureProblemQuery problemQuery;
  private final LiveRoomGateway liveRoomGateway;
  private final TransactionOperations transactionOperations;

  public LectureDeliveryService(
      LectureRepository lectureRepository,
      LectureProblemRepository lectureProblemRepository,
      LectureProblemQuery problemQuery,
      LiveRoomGateway liveRoomGateway,
      PlatformTransactionManager transactionManager) {
    this.lectureRepository = lectureRepository;
    this.lectureProblemRepository = lectureProblemRepository;
    this.problemQuery = problemQuery;
    this.liveRoomGateway = liveRoomGateway;
    this.transactionOperations = new TransactionTemplate(transactionManager);
  }

  /**
   * 저장을 커밋한 <b>뒤</b> 방 metadata를 쓴다 — {@link LectureService#changeStatus}와 같은 이유다. metadata 쓰기가 실패하면
   * 503이 나가고 저장은 남는다. 강사가 같은 문제로 다시 부르면 DB는 그대로 두고 metadata만 다시 쓴다.
   */
  public LectureSnapshot deliver(AuthenticatedUser user, long lectureId, long problemId) {
    LectureSnapshot snapshot =
        transactionOperations.execute(tx -> deliverInTransaction(user, lectureId, problemId));
    liveRoomGateway.openRoom(LectureRoom.nameOf(lectureId), problemId);
    return snapshot;
  }

  private LectureSnapshot deliverInTransaction(
      AuthenticatedUser user, long lectureId, long problemId) {
    // 종료와 겹치면 CLOSED 수업에 현재 문제가 남는다 — 행을 잠가 직렬화한다 (8.1절).
    Lecture lecture =
        lectureRepository.findByIdForUpdate(lectureId).orElseThrow(LectureService::notFound);
    if (!LectureService.isOwner(user, lecture)) {
      throw LectureService.notFound();
    }
    if (!lecture.isOpen()) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    LectureProblem link =
        lectureProblemRepository
            .findByLecture_IdAndProblem_Id(lectureId, problemId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PROBLEM_NOT_FOUND));
    if (!link.getId().equals(lecture.getCurrentLectureProblemId())) {
      lecture.deliverProblem(link.getId());
    }
    return problemQuery.instructor(lecture);
  }
}
```

`lecture/dto/DeliverProblemRequest.java`:

```java
package com.itda.lecture.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * @param problemId 10진수 문자열 ID (API 명세 8.2절). long 범위를 넘지 않게 18자리까지 받는다.
 */
public record DeliverProblemRequest(@NotNull @Pattern(regexp = "[1-9][0-9]{0,17}") String problemId) {

  public long problemIdValue() {
    return Long.parseLong(problemId);
  }
}
```

`lecture/controller/LectureProblemController.java`:

```java
package com.itda.lecture.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.lecture.dto.DeliverProblemRequest;
import com.itda.lecture.dto.InstructorLectureResponse;
import com.itda.lecture.service.LectureDeliveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수업의 현재 문제와 LIVE 답안 (API 명세 8.2·8.2.2절). 문제 연결(POST /problems)은 문제 모듈의 {@code ProblemController}가 맡는다.
 *
 * <p>클래스에 {@code @Validated}를 붙이지 않는다 — 파라미터 제약 검증은 Spring MVC 기본 메서드 검증이 400으로 처리한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/lectures/{lectureId}")
public class LectureProblemController {

  private final LectureDeliveryService deliveryService;

  /** 같은 문제 재요청도 200 — metadata만 다시 쓴다. */
  @PutMapping("/current-problem")
  public InstructorLectureResponse deliver(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @Valid @RequestBody DeliverProblemRequest request) {
    return InstructorLectureResponse.from(
        deliveryService.deliver(user, lectureId, request.problemIdValue()));
  }
}
```

`SecurityConfig.java` — `LECTURE_PROBLEMS_PATH` 아래에 추가:

```java
  private static final String LECTURE_CURRENT_PROBLEM_PATH =
      "/lectures/{lectureId}/current-problem";
```

matcher 목록의 `LECTURE_PROBLEMS_PATH` 줄 아래에 추가:

```java
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.PUT, LECTURE_CURRENT_PROBLEM_PATH),
```

- [ ] **Step 9: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.*"`
Expected: PASS

- [ ] **Step 10: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/lecture apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java apps/backend/src/test/java/com/itda/lecture
git commit -m "feat(lecture): 강사가 현재 문제를 출제하고 방 metadata로 알린다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: LIVE 답안 제출 (채점·잠금·UNIQUE 멱등·강사 알림)

**Files:**
- Modify: `lecture/media/LiveRoomGateway.java`, `lecture/media/LiveKitRoomGateway.java`
- Create: `lecture/service/LectureAnswerService.java`, `lecture/service/SubmittedAnswer.java`, `lecture/dto/SubmitAnswerRequest.java`
- Modify: `lecture/controller/LectureProblemController.java`, `global/security/SecurityConfig.java`
- Test: `lecture/media/LiveKitRoomGatewayTest.java`, `lecture/service/RecordingLiveRoomGateway.java`, `lecture/controller/LectureAuthenticatedApiIntegrationTest.java`(대역), `lecture/controller/LectureProblemApiIntegrationTest.java`

**Interfaces:**
- Consumes: `LiveAnswerGrader`, `LiveAnswer`, `AnswerCell`, `LectureProblemQuery.currentProblemId/findAnswer`, `LiveAnswerResponse`(Task 4), `LectureRepository.findByIdForUpdate`(Task 5), `ProblemCellRepository.findByProblemIdOrderByCellIndexAsc(Long)`(기존)
- Produces: `void LiveRoomGateway.notifyAnswerSubmitted(String roomName, String identity, long problemId)`
- Produces: `SubmittedAnswer LectureAnswerService.submit(AuthenticatedUser, long lectureId, long problemId, List<Integer> cells)`; `record SubmittedAnswer(LiveAnswer answer, boolean created)`
- `LectureAnswerService` 생성자 인자 순서(Task 7이 두 개를 더한다): `(LectureRepository, ParticipantRepository, ProblemAttemptRepository, AttemptCellRepository, ProblemCellRepository, LectureProblemQuery, LiveRoomGateway, PlatformTransactionManager)`
- Produces (test): `FakeLiveRoomGateway.answerSignals : List<String>`(`"{room}/{identity}/{problemId}"`), `FakeLiveRoomGateway.failSignals : boolean`, `RecordingLiveRoomGateway.answerSignals : List<String>`

- [ ] **Step 1: 게이트웨이 테스트 (RED)**

`LiveKitRoomGatewayTest.java` — import 추가 `livekit.LivekitModels.DataPacket;`. 클래스 끝에 추가:

```java
  // ---------- 제출 알림 (API 명세 8.5.2절) ----------

  @SuppressWarnings("unchecked")
  private void dataCall(Response<Void> response) throws IOException {
    Call<Void> call = mock(Call.class);
    given(call.execute()).willReturn(response);
    given(roomClient.sendData(any(), any(), any(), any(), any(), any())).willReturn(call);
  }

  @Test
  void 제출_알림은_강사_identity에게만_lecture_topic으로_보낸다() throws Exception {
    dataCall(Response.<Void>success(null));

    speakingGateway.notifyAnswerSubmitted("lecture-42", "user-7", 26578L);

    ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
    verify(roomClient)
        .sendData(
            eq("lecture-42"),
            payload.capture(),
            eq(DataPacket.Kind.RELIABLE),
            eq(List.of()),
            eq(List.of("user-7")),
            eq("lecture"));
    assertThat(new String(payload.getValue(), StandardCharsets.UTF_8))
        .isEqualTo("{\"type\":\"ANSWER_SUBMITTED\",\"problemId\":\"26578\"}");
  }

  @Test
  void 제출_알림이_실패하면_503을_던진다() throws Exception {
    dataCall(Response.error(500, ResponseBody.create("", null)));

    assertThatThrownBy(() -> speakingGateway.notifyAnswerSubmitted("lecture-42", "user-7", 1L))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.media.LiveKitRoomGatewayTest"`
Expected: FAIL — `notifyAnswerSubmitted` 없음

- [ ] **Step 3: 게이트웨이 구현·대역 갱신**

`LiveRoomGateway.java` — `setSpeaking` 선언 아래에 추가:

```java
  /**
   * 담당 강사 한 명에게 "답안이 들어왔다"는 데이터 메시지를 보낸다(topic {@code lecture}, reliable, payload {@code
   * {"type":"ANSWER_SUBMITTED","problemId":"<id>"}}). 답 내용은 싣지 않는다. 실패하면 MEDIA_SERVER_UNAVAILABLE을 던지며 호출자가
   * 로그만 남긴다 (API 명세 8.5.2절).
   */
  void notifyAnswerSubmitted(String roomName, String identity, long problemId);
```

`LiveKitRoomGateway.java` — import `java.nio.charset.StandardCharsets;`, `livekit.LivekitModels.DataPacket;` 추가. 상수 영역에 추가:

```java
  /** 데이터 메시지 topic (API 명세 8.5.2절). 웹이 이 값으로 걸러 받는다. */
  static final String DATA_TOPIC = "lecture";
```

`setSpeaking` 아래에 추가:

```java
  @Override
  public void notifyAnswerSubmitted(String roomName, String identity, long problemId) {
    byte[] payload =
        ("{\"type\":\"ANSWER_SUBMITTED\",\"problemId\":\"" + problemId + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    // destinationSids는 null을 받지 않는다(SDK non-null 파라미터) — 비워 두고 identity로만 고른다.
    execute(
        roomServiceClient.sendData(
            roomName, payload, DataPacket.Kind.RELIABLE, List.of(), List.of(identity), DATA_TOPIC),
        "sendData",
        false);
  }
```

`RecordingLiveRoomGateway.java` — 필드와 메서드 추가:

```java
  /** {@code "{room}/{identity}/{problemId}"}. */
  final List<String> answerSignals = new ArrayList<>();
```

```java
  @Override
  public void notifyAnswerSubmitted(String roomName, String identity, long problemId) {
    failIfRequested();
    answerSignals.add(roomName + "/" + identity + "/" + problemId);
  }
```

`LectureAuthenticatedApiIntegrationTest.FakeLiveRoomGateway` — 필드와 메서드 추가:

```java
    /** {@code "{room}/{identity}/{problemId}"}. */
    final List<String> answerSignals = new CopyOnWriteArrayList<>();

    /** true면 제출 알림이 503으로 실패한다. 쓴 테스트가 finally에서 되돌린다. */
    volatile boolean failSignals;
```

```java
    @Override
    public void notifyAnswerSubmitted(String roomName, String identity, long problemId) {
      if (this.failSignals) {
        throw new BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
      }
      this.answerSignals.add(roomName + "/" + identity + "/" + problemId);
    }
```

- [ ] **Step 4: 게이트웨이 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.*"`
Expected: PASS

- [ ] **Step 5: 제출 통합 테스트 (RED)**

`LectureProblemApiIntegrationTest.java` — import 추가 `com.itda.lecture.domain.Participant;`, `com.itda.lecture.domain.ParticipantId;`, `com.itda.lecture.repository.ParticipantRepository;`, `java.util.UUID;`. 필드 추가:

```java
  @Autowired private ParticipantRepository participantRepository;
```

클래스 끝에 추가:

```java
  // ---------- LIVE 답안 (API 명세 8.2.2절) ----------

  private Live deliveredWord(List<Integer> cells) {
    Live live = openLectureWithLinkedWord(cells);
    assertThat(deliver(live.lectureId(), live.problemId(), this.instructor).statusCode()).isEqualTo(200);
    return live;
  }

  private HttpResponse<String> submit(Live live, Account who, String cellsJson) {
    return submitRaw(
        live,
        who,
        "{\"cells\":" + cellsJson + ",\"durationMs\":1900}",
        "Idempotency-Key",
        UUID.randomUUID().toString());
  }

  /** 헤더를 직접 고른다 — 멱등 키 누락·형식 오류 사례용. */
  private HttpResponse<String> submitRaw(Live live, Account who, String body, String... headers) {
    return send(
        "POST",
        "/lectures/" + live.lectureId() + "/problems/" + live.problemId() + "/answers",
        body,
        who,
        headers);
  }

  private long signals(Live live) {
    String entry =
        "lecture-" + live.lectureId() + "/user-" + this.instructor.userId() + "/" + live.problemId();
    return this.gateway.answerSignals.stream().filter(entry::equals).count();
  }

  @Test
  void 학습자가_정답을_내면_201과_채점_결과를_받고_강사에게_알림이_간다() {
    Live live = deliveredWord(List.of(8, 3));

    HttpResponse<String> response = submit(live, this.learner, "[8,3]");

    assertThat(response.statusCode()).isEqualTo(201);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body)
        .containsEntry("lectureId", live.lectureId())
        .containsEntry("problemId", String.valueOf(live.problemId()))
        .containsEntry("isCorrect", true)
        .containsEntry("score", 100)
        .containsKey("attemptId")
        .containsKey("attemptedAt");
    assertThat(list(body.get("cells")))
        .extracting(cell -> map(cell).get("cellIndex"))
        .containsExactly(1, 2);
    assertThat(signals(live)).isEqualTo(1);
  }

  @Test
  void 오답은_칸별_diffMask와_부분_점수를_받는다() {
    Live live = deliveredWord(List.of(8, 3));

    Map<String, Object> body = HttpTestClient.json(submit(live, this.learner, "[8]"));

    assertThat(body).containsEntry("isCorrect", false).containsEntry("score", 50);
    assertThat(map(list(body.get("cells")).get(1)))
        .containsEntry("cellIndex", 2)
        .containsEntry("inputPattern", 0)
        .containsEntry("expectedPattern", 3)
        .containsEntry("diffMask", 3)
        .containsEntry("isCorrect", false);
  }

  @Test
  void 같은_cells를_다시_보내면_200과_기존_결과이고_알림은_다시_가지_않는다() {
    Live live = deliveredWord(List.of(8));
    HttpResponse<String> first = submit(live, this.learner, "[8]");

    HttpResponse<String> again = submit(live, this.learner, "[8]");

    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(HttpTestClient.json(again).get("attemptId"))
        .isEqualTo(HttpTestClient.json(first).get("attemptId"));
    assertThat(signals(live)).isEqualTo(1);
  }

  @Test
  void 다른_cells를_다시_보내면_409다() {
    Live live = deliveredWord(List.of(8));
    submit(live, this.learner, "[8]");

    assertError(submit(live, this.learner, "[9]"), 409, "LECTURE_PROBLEM_ALREADY_ANSWERED");
  }

  @Test
  void 제출하면_학습자_Snapshot에_submitted와_myResult가_담긴다() {
    Live live = deliveredWord(List.of(8));
    Object attemptId = HttpTestClient.json(submit(live, this.learner, "[9]")).get("attemptId");

    Map<String, Object> view =
        HttpTestClient.json(send("GET", "/lectures/" + live.lectureId(), null, this.learner));

    assertThat(map(view.get("currentProblem"))).containsEntry("submitted", true);
    assertThat(map(view.get("myResult")))
        .containsEntry("attemptId", attemptId)
        .containsEntry("isCorrect", false);
  }

  @Test
  void 현재_문제가_아니면_409다() {
    Live live = deliveredWord(List.of(8));
    Problem next = seedWord(List.of(1));
    register(live.lectureId(), next.getTargetText(), this.instructor);
    deliver(live.lectureId(), next.getId(), this.instructor);

    assertError(submit(live, this.learner, "[8]"), 409, "LECTURE_PROBLEM_NOT_CURRENT");
  }

  @Test
  void 퇴장한_학습자는_403이다() {
    Live live = deliveredWord(List.of(8));
    Participant participant =
        this.participantRepository
            .findById(ParticipantId.of(Long.parseLong(live.lectureId()), this.learner.userId()))
            .orElseThrow();
    participant.leave(LocalDateTime.now(ZoneOffset.UTC));
    this.participantRepository.save(participant);

    assertError(submit(live, this.learner, "[8]"), 403, "LECTURE_PARTICIPATION_REQUIRED");
  }

  @Test
  void 종료된_수업에는_제출할_수_없다() {
    Live live = deliveredWord(List.of(8));
    changeStatus(live.lectureId(), "CLOSED");

    assertError(submit(live, this.learner, "[8]"), 409, "INVALID_LECTURE_STATE");
  }

  @Test
  void 강사는_답안을_낼_수_없다() {
    Live live = deliveredWord(List.of(8));

    assertError(submit(live, this.instructor, "[8]"), 403, "LEARNER_ACCESS_REQUIRED");
  }

  @Test
  void 참여하지_않은_학습자에게는_수업을_숨긴다() {
    Live live = deliveredWord(List.of(8));

    assertError(submit(live, this.otherLearner, "[8]"), 404, "LECTURE_NOT_FOUND");
  }

  @Test
  void 비었거나_범위를_벗어나거나_12칸을_넘는_cells는_422다() {
    Live live = deliveredWord(List.of(8));

    assertError(submit(live, this.learner, "[]"), 422, "INVALID_ANSWER_CELLS");
    assertError(submit(live, this.learner, "[64]"), 422, "INVALID_ANSWER_CELLS");
    assertError(
        submit(live, this.learner, "[1,1,1,1,1,1,1,1,1,1,1,1,1]"), 422, "INVALID_ANSWER_CELLS");
  }

  @Test
  void Idempotency_Key가_없거나_UUID_v4가_아니면_400이다() {
    Live live = deliveredWord(List.of(8));
    String body = "{\"cells\":[8],\"durationMs\":0}";

    assertError(submitRaw(live, this.learner, body), 400, "INVALID_REQUEST_PARAMETER");
    assertError(
        submitRaw(live, this.learner, body, "Idempotency-Key", "not-a-uuid"),
        400,
        "INVALID_REQUEST_PARAMETER");
    assertError(
        submitRaw(live, this.learner, body, "Idempotency-Key", "123e4567-e89b-12d3-a456-426614174000"),
        400,
        "INVALID_REQUEST_PARAMETER");
  }

  @Test
  void durationMs가_없거나_음수면_400이다() {
    Live live = deliveredWord(List.of(8));

    assertError(
        submitRaw(live, this.learner, "{\"cells\":[8],\"durationMs\":-1}", "Idempotency-Key", UUID.randomUUID().toString()),
        400,
        "INVALID_REQUEST_PARAMETER");
    assertError(
        submitRaw(live, this.learner, "{\"cells\":[8]}", "Idempotency-Key", UUID.randomUUID().toString()),
        400,
        "INVALID_REQUEST_PARAMETER");
  }

  @Test
  void 알림이_실패해도_제출은_201이다() {
    Live live = deliveredWord(List.of(8));
    this.gateway.failSignals = true;
    try {
      assertThat(submit(live, this.learner, "[8]").statusCode()).isEqualTo(201);
    } finally {
      this.gateway.failSignals = false;
    }
  }
```

- [ ] **Step 6: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.controller.LectureProblemApiIntegrationTest"`
Expected: FAIL — answers 경로 없음(보호 경로 밖이라 500 또는 404 `ENDPOINT_NOT_FOUND`)

- [ ] **Step 7: 제출 서비스·요청 DTO·컨트롤러·보호 경로**

`lecture/service/SubmittedAnswer.java`:

```java
package com.itda.lecture.service;

/** @param created 이번 요청이 저장했으면 true(201), 같은 답이 이미 있었으면 false(200) */
public record SubmittedAnswer(LiveAnswer answer, boolean created) {}
```

`lecture/service/LectureAnswerService.java`:

```java
package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.learning.domain.AttemptCell;
import com.itda.learning.domain.AttemptCellRepository;
import com.itda.learning.domain.ProblemAttempt;
import com.itda.learning.domain.ProblemAttemptRepository;
import com.itda.lecture.domain.Lecture;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.media.LectureRoom;
import com.itda.lecture.media.LiveRoomGateway;
import com.itda.lecture.repository.LectureRepository;
import com.itda.lecture.repository.ParticipantRepository;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** LIVE 답안 제출·조회 (API 명세 8.2.2절). AI 분석·BKT·user_skill_memory는 건드리지 않는다 (8.1절). */
@Slf4j
@Service
public class LectureAnswerService {

  private final LectureRepository lectureRepository;
  private final ParticipantRepository participantRepository;
  private final ProblemAttemptRepository problemAttemptRepository;
  private final AttemptCellRepository attemptCellRepository;
  private final ProblemCellRepository problemCellRepository;
  private final LectureProblemQuery problemQuery;
  private final LiveRoomGateway liveRoomGateway;
  private final TransactionOperations transactionOperations;

  public LectureAnswerService(
      LectureRepository lectureRepository,
      ParticipantRepository participantRepository,
      ProblemAttemptRepository problemAttemptRepository,
      AttemptCellRepository attemptCellRepository,
      ProblemCellRepository problemCellRepository,
      LectureProblemQuery problemQuery,
      LiveRoomGateway liveRoomGateway,
      PlatformTransactionManager transactionManager) {
    this.lectureRepository = lectureRepository;
    this.participantRepository = participantRepository;
    this.problemAttemptRepository = problemAttemptRepository;
    this.attemptCellRepository = attemptCellRepository;
    this.problemCellRepository = problemCellRepository;
    this.problemQuery = problemQuery;
    this.liveRoomGateway = liveRoomGateway;
    this.transactionOperations = new TransactionTemplate(transactionManager);
  }

  /**
   * 채점·저장을 커밋한 <b>뒤</b> 강사에게 알린다. 알림 실패는 제출 결과를 바꾸지 않는다 (API 명세 8.5.2절).
   *
   * <p>멱등 키는 저장하지 않는다. (lecture_id, user_id, problem_id) UNIQUE가 한 번만 저장하게 하고, 이미 있으면 같은 cells면 기존
   * 결과(200), 다르면 409다.
   */
  public SubmittedAnswer submit(
      AuthenticatedUser user, long lectureId, long problemId, List<Integer> cells) {
    if (user.role() != UserRole.LEARNER) {
      throw new BusinessException(ErrorCode.LEARNER_ACCESS_REQUIRED);
    }
    Submission submission;
    try {
      submission =
          transactionOperations.execute(
              tx -> submitInTransaction(user.userId(), lectureId, problemId, cells));
    } catch (DataIntegrityViolationException e) {
      // 수업 행 잠금으로 같은 학습자의 동시 제출은 이미 직렬화되지만, 잠금 밖 경로(격리 수준 차이 등)로 UNIQUE에 걸리면
      // 앞 트랜잭션은 롤백됐으므로 다시 읽어 같은 규칙(200/409)을 적용한다.
      submission =
          transactionOperations.execute(
              tx -> submitInTransaction(user.userId(), lectureId, problemId, cells));
    }
    if (submission.created()) {
      notifyInstructor(lectureId, submission.instructorUserId(), problemId);
    }
    return new SubmittedAnswer(submission.answer(), submission.created());
  }

  private Submission submitInTransaction(
      long userId, long lectureId, long problemId, List<Integer> cells) {
    // 종료 트랜잭션과 같은 행 잠금으로 직렬화한다 (API 명세 8.1절).
    Lecture lecture =
        lectureRepository.findByIdForUpdate(lectureId).orElseThrow(LectureService::notFound);
    Participant participant =
        participantRepository
            .findById(ParticipantId.of(lectureId, userId))
            .orElseThrow(LectureService::notFound);
    // 종료하면 모든 참여자의 left_at이 채워지므로 상태를 먼저 본다 (LectureMediaService와 같은 순서).
    if (!lecture.isOpen()) {
      throw new BusinessException(ErrorCode.INVALID_LECTURE_STATE);
    }
    if (!participant.isParticipating()) {
      throw new BusinessException(ErrorCode.LECTURE_PARTICIPATION_REQUIRED);
    }
    if (!Long.valueOf(problemId).equals(problemQuery.currentProblemId(lecture))) {
      throw new BusinessException(ErrorCode.LECTURE_PROBLEM_NOT_CURRENT);
    }
    LiveAnswerGrader.validate(cells);

    Optional<LiveAnswer> existing = problemQuery.findAnswer(lectureId, userId, problemId);
    if (existing.isPresent()) {
      if (!LiveAnswerGrader.sameInput(cells, existing.get().cells())) {
        throw new BusinessException(ErrorCode.LECTURE_PROBLEM_ALREADY_ANSWERED);
      }
      return new Submission(existing.get(), false, lecture.getInstructorUserId());
    }

    List<Integer> expected =
        problemCellRepository.findByProblemIdOrderByCellIndexAsc(problemId).stream()
            .map(ProblemCell::getExpectedPattern)
            .toList();
    List<AnswerCell> graded = LiveAnswerGrader.grade(cells, expected);
    boolean correct = LiveAnswerGrader.isCorrect(cells, expected);
    LocalDateTime now = LectureService.now();
    ProblemAttempt attempt =
        problemAttemptRepository.save(
            ProblemAttempt.forLiveLecture(userId, problemId, lectureId, correct, now));
    attemptCellRepository.saveAll(
        graded.stream()
            .map(
                cell ->
                    new AttemptCell(
                        attempt, cell.cellIndex(), cell.expectedPattern(), cell.inputPattern(), now))
            .toList());
    LiveAnswer answer =
        new LiveAnswer(
            attempt.getId(),
            lectureId,
            problemId,
            correct,
            LiveAnswerGrader.score(correct, graded),
            now,
            graded);
    return new Submission(answer, true, lecture.getInstructorUserId());
  }

  private void notifyInstructor(long lectureId, long instructorUserId, long problemId) {
    try {
      liveRoomGateway.notifyAnswerSubmitted(
          LectureRoom.nameOf(lectureId), LectureRoom.identityOf(instructorUserId), problemId);
    } catch (RuntimeException e) {
      // 강사 웹은 출제·새로고침 때 답안 목록을 다시 조회한다. 제출은 이미 커밋됐다.
      log.warn(
          "제출 알림 실패: lecture={} problem={} ({})",
          lectureId,
          problemId,
          e.getClass().getSimpleName());
    }
  }

  private record Submission(LiveAnswer answer, boolean created, long instructorUserId) {}
}
```

`lecture/dto/SubmitAnswerRequest.java`:

```java
package com.itda.lecture.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * LIVE 답안 body (API 명세 8.2.2절).
 *
 * <p>cells는 여기서 검증하지 않는다 — 업무 code {@code INVALID_ANSWER_CELLS}(422)로 답해야 하므로 서비스(LiveAnswerGrader)가
 * 검사한다. durationMs는 0 이상인지만 보고 저장하지 않는다.
 */
public record SubmitAnswerRequest(List<Integer> cells, @NotNull @PositiveOrZero Long durationMs) {}
```

`LectureProblemController.java` — import 추가:

```java
import com.itda.lecture.dto.LiveAnswerResponse;
import com.itda.lecture.dto.SubmitAnswerRequest;
import com.itda.lecture.service.LectureAnswerService;
import com.itda.lecture.service.SubmittedAnswer;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
```

필드·상수·메서드 추가:

```java
  /** UUID v4. 형식만 검사하고 저장하지 않는다 (API 명세 8.2.2절). */
  private static final String UUID_V4 =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

  private final LectureAnswerService answerService;
```

```java
  /** 201 새 풀이 / 200 같은 cells 재전송. 헤더 누락·형식 오류와 durationMs 오류는 400 (API 명세 8.4절). */
  @PostMapping("/problems/{problemId}/answers")
  public ResponseEntity<LiveAnswerResponse> submitAnswer(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @PathVariable @Positive Long problemId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = UUID_V4) String idempotencyKey,
      @Valid @RequestBody SubmitAnswerRequest request) {
    SubmittedAnswer submitted = answerService.submit(user, lectureId, problemId, request.cells());
    return ResponseEntity.status(submitted.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(LiveAnswerResponse.from(submitted.answer()));
  }
```

`SecurityConfig.java` — 상수 추가:

```java
  private static final String LECTURE_PROBLEM_ANSWERS_PATH =
      "/lectures/{lectureId}/problems/{problemId}/answers";
```

matcher 추가(`LECTURE_CURRENT_PROBLEM_PATH` 줄 아래):

```java
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, LECTURE_PROBLEM_ANSWERS_PATH),
```

- [ ] **Step 8: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.*"`
Expected: PASS. `Idempotency_Key...` 의 헤더 누락 사례가 400이 아니면 `MissingRequestHeaderException` 처리기(400)를 확인한다 — 이미 `GlobalExceptionHandler.handleInvalidParameter` 에 있다.

- [ ] **Step 9: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/lecture apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java apps/backend/src/test/java/com/itda/lecture
git commit -m "feat(lecture): 학습자 LIVE 답안을 채점·저장하고 강사에게 알린다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 강사 답안 목록

**Files:**
- Create: `lecture/service/LectureAnswers.java`, `lecture/service/LearnerAnswer.java`, `lecture/dto/LectureAnswersResponse.java`
- Modify: `lecture/service/LectureAnswerService.java`, `lecture/controller/LectureProblemController.java`, `learning/domain/ProblemAttemptRepository.java`, `global/security/SecurityConfig.java`
- Test: `lecture/controller/LectureProblemApiIntegrationTest.java`

**Interfaces:**
- Consumes: `LiveAnswer.of`, `AnswerCellResponse`(Task 4), `LectureProblemRepository.findByLecture_IdAndProblem_Id`(Task 3), Task 6의 `LectureAnswerService`
- Produces: `LectureAnswers LectureAnswerService.listAnswers(AuthenticatedUser, long lectureId, long problemId)`; `record LectureAnswers(long problemId, List<LearnerAnswer> answers)`; `record LearnerAnswer(long userId, String username, LiveAnswer answer)`
- Produces: `List<ProblemAttempt> ProblemAttemptRepository.findAllByLectureIdAndProblemIdOrderByAttemptedAtAscIdAsc(Long, Long)`
- `LectureAnswerService` 생성자 인자 순서가 `(LectureRepository, ParticipantRepository, ProblemAttemptRepository, AttemptCellRepository, ProblemCellRepository, LectureProblemRepository, UserJpaRepository, LectureProblemQuery, LiveRoomGateway, PlatformTransactionManager)` 로 바뀐다(스프링 주입만 쓰므로 테스트 영향 없음)

- [ ] **Step 1: 통합 테스트 (RED)**

`LectureProblemApiIntegrationTest.java` 클래스 끝에 추가:

```java
  // ---------- 강사 답안 목록 (API 명세 8.2.2절) ----------

  private HttpResponse<String> answers(String lectureId, long problemId, Account who) {
    return send("GET", "/lectures/" + lectureId + "/problems/" + problemId + "/answers", null, who);
  }

  @Test
  void 강사는_제출한_학습자의_답안만_제출_순서대로_본다() {
    Live live = openLectureWithLinkedWord(List.of(8, 3));
    join(live.lectureId(), this.otherLearner);
    Account absent = accountFor(UserRole.LEARNER);
    join(live.lectureId(), absent);
    deliver(live.lectureId(), live.problemId(), this.instructor);
    submit(live, this.learner, "[9,3]");
    submit(live, this.otherLearner, "[8,3]");

    HttpResponse<String> response = answers(live.lectureId(), live.problemId(), this.instructor);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body).containsEntry("problemId", String.valueOf(live.problemId()));
    List<Object> items = list(body.get("answers"));
    assertThat(items).hasSize(2);
    assertThat(map(items.get(0)))
        .containsEntry("userId", String.valueOf(this.learner.userId()))
        .containsEntry("username", this.learner.username())
        .containsEntry("isCorrect", false)
        .containsKey("attemptedAt");
    assertThat(map(list(map(items.get(0)).get("cells")).get(0))).containsEntry("diffMask", 1);
    assertThat(map(items.get(1)))
        .containsEntry("userId", String.valueOf(this.otherLearner.userId()))
        .containsEntry("isCorrect", true);
  }

  @Test
  void 제출이_없으면_빈_목록이다() {
    Live live = deliveredWord(List.of(8));

    assertThat(list(HttpTestClient.json(answers(live.lectureId(), live.problemId(), this.instructor)).get("answers")))
        .isEmpty();
  }

  @Test
  void 연결되지_않은_문제의_답안_목록은_404다() {
    Live live = deliveredWord(List.of(8));

    assertError(
        answers(live.lectureId(), seedWord(List.of(1)).getId(), this.instructor),
        404,
        "PROBLEM_NOT_FOUND");
  }

  @Test
  void 학습자에게는_답안_목록_대상_수업을_숨긴다() {
    Live live = deliveredWord(List.of(8));

    assertError(answers(live.lectureId(), live.problemId(), this.learner), 404, "LECTURE_NOT_FOUND");
  }

  @Test
  void 종료_뒤에도_답안_목록을_본다() {
    Live live = deliveredWord(List.of(8));
    submit(live, this.learner, "[8]");
    changeStatus(live.lectureId(), "CLOSED");

    HttpResponse<String> response = answers(live.lectureId(), live.problemId(), this.instructor);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(list(HttpTestClient.json(response).get("answers"))).hasSize(1);
  }
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.controller.LectureProblemApiIntegrationTest"`
Expected: FAIL — GET answers 경로 없음

- [ ] **Step 3: 조회·서비스·DTO·컨트롤러·보호 경로**

`ProblemAttemptRepository.java` 에 추가:

```java
  /** 강사 답안 목록. 제출 시각 오름차순, 같은 시각이면 id 오름차순 (API 명세 8.2.2절). */
  List<ProblemAttempt> findAllByLectureIdAndProblemIdOrderByAttemptedAtAscIdAsc(
      Long lectureId, Long problemId);
```

`lecture/service/LearnerAnswer.java`:

```java
package com.itda.lecture.service;

/** 강사 답안 목록의 한 줄 — 제출한 학습자와 그 답안. */
public record LearnerAnswer(long userId, String username, LiveAnswer answer) {}
```

`lecture/service/LectureAnswers.java`:

```java
package com.itda.lecture.service;

import java.util.List;

/** @param answers 제출한 학습자만, 제출 시각 오름차순 */
public record LectureAnswers(long problemId, List<LearnerAnswer> answers) {}
```

`LectureAnswerService.java`:
- import 추가: `com.itda.lecture.repository.LectureProblemRepository;`, `com.itda.user.domain.User;`, `com.itda.user.repository.UserJpaRepository;`, `java.util.Map;`, `java.util.stream.Collectors;`, `org.springframework.transaction.annotation.Transactional;`
- 필드 `private final LectureProblemRepository lectureProblemRepository;` 와 `private final UserJpaRepository userJpaRepository;` 를 `problemCellRepository` 아래에 추가하고, 생성자에 같은 순서(`ProblemCellRepository problemCellRepository` 뒤)로 인자·대입을 추가한다.
- 메서드 추가:

```java
  /** 담당 강사만, 수업 상태와 관계없이 본다. 연결되지 않은 문제면 404 (API 명세 8.2.2절). */
  @Transactional(readOnly = true)
  public LectureAnswers listAnswers(AuthenticatedUser user, long lectureId, long problemId) {
    Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(LectureService::notFound);
    if (!LectureService.isOwner(user, lecture)) {
      throw LectureService.notFound();
    }
    if (lectureProblemRepository.findByLecture_IdAndProblem_Id(lectureId, problemId).isEmpty()) {
      throw new BusinessException(ErrorCode.PROBLEM_NOT_FOUND);
    }
    List<ProblemAttempt> attempts =
        problemAttemptRepository.findAllByLectureIdAndProblemIdOrderByAttemptedAtAscIdAsc(
            lectureId, problemId);
    if (attempts.isEmpty()) {
      return new LectureAnswers(problemId, List.of());
    }
    Map<Long, List<AttemptCell>> cellsByAttempt =
        attemptCellRepository
            .findAllByAttemptIds(attempts.stream().map(ProblemAttempt::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(cell -> cell.getAttempt().getId()));
    Map<Long, String> usernames =
        userJpaRepository
            .findAllById(attempts.stream().map(ProblemAttempt::getUserId).toList())
            .stream()
            .collect(Collectors.toMap(User::getId, User::getUsername));
    List<LearnerAnswer> answers =
        attempts.stream()
            .map(
                attempt ->
                    new LearnerAnswer(
                        attempt.getUserId(),
                        usernames.get(attempt.getUserId()),
                        LiveAnswer.of(
                            attempt, cellsByAttempt.getOrDefault(attempt.getId(), List.of()))))
            .toList();
    return new LectureAnswers(problemId, answers);
  }
```

`lecture/dto/LectureAnswersResponse.java`:

```java
package com.itda.lecture.dto;

import com.itda.lecture.service.LearnerAnswer;
import com.itda.lecture.service.LectureAnswers;
import java.util.List;

/** 강사 답안 목록 (API 명세 8.2.2절). ID는 10진수 문자열이다. */
public record LectureAnswersResponse(String problemId, List<Item> answers) {

  public record Item(
      String userId,
      String username,
      boolean isCorrect,
      String attemptedAt,
      List<AnswerCellResponse> cells) {

    static Item from(LearnerAnswer answer) {
      return new Item(
          String.valueOf(answer.userId()),
          answer.username(),
          answer.answer().correct(),
          UtcTimes.format(answer.answer().attemptedAt()),
          answer.answer().cells().stream().map(AnswerCellResponse::from).toList());
    }
  }

  public static LectureAnswersResponse from(LectureAnswers answers) {
    return new LectureAnswersResponse(
        String.valueOf(answers.problemId()), answers.answers().stream().map(Item::from).toList());
  }
}
```

`LectureProblemController.java` — import `com.itda.lecture.dto.LectureAnswersResponse;`, `org.springframework.web.bind.annotation.GetMapping;` 추가 후 메서드 추가:

```java
  @GetMapping("/problems/{problemId}/answers")
  public LectureAnswersResponse listAnswers(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable @Positive Long lectureId,
      @PathVariable @Positive Long problemId) {
    return LectureAnswersResponse.from(answerService.listAnswers(user, lectureId, problemId));
  }
```

`SecurityConfig.java` — matcher 추가(POST answers 줄 아래):

```java
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.GET, LECTURE_PROBLEM_ANSWERS_PATH),
```

- [ ] **Step 4: 통과 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.*"`
Expected: PASS. `Item.isCorrect` 가 JSON에서 `correct` 로 나오면 Task 4 Step 11과 같이 `@JsonProperty("isCorrect")` 를 붙인다.

- [ ] **Step 5: 전체 테스트·포맷 후 Commit**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test && cd ../..
git status --short
git add apps/backend/src/main/java/com/itda/lecture apps/backend/src/main/java/com/itda/learning/domain/ProblemAttemptRepository.java apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java apps/backend/src/test/java/com/itda/lecture/controller/LectureProblemApiIntegrationTest.java
git commit -m "feat(lecture): 강사가 문제별 LIVE 답안 목록을 조회한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 보호 경로 회귀 테스트와 최종 검증

**Files:**
- Test: `lecture/controller/LectureApiIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3·5·6·7이 `SecurityConfig` 에 넣은 4개 경로

- [ ] **Step 1: 토큰 없는 요청 401 테스트 추가**

`LectureApiIntegrationTest.java` 클래스 끝에 추가:

```java
  @Test
  void 토큰_없이_문제를_연결하면_401이다() {
    assertThat(status("POST", "/lectures/1/problems", "{\"targetText\":\"가\"}")).isEqualTo(401);
  }

  @Test
  void 토큰_없이_출제하면_401이다() {
    assertThat(status("PUT", "/lectures/1/current-problem", "{\"problemId\":\"1\"}"))
        .isEqualTo(401);
  }

  @Test
  void 토큰_없이_답안을_내면_401이다() {
    assertThat(status("POST", "/lectures/1/problems/1/answers", "{\"cells\":[1],\"durationMs\":0}"))
        .isEqualTo(401);
  }

  @Test
  void 토큰_없이_답안_목록을_보면_401이다() {
    assertThat(status("GET", "/lectures/1/problems/1/answers", null)).isEqualTo(401);
  }
```

- [ ] **Step 2: 실행**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.lecture.controller.LectureApiIntegrationTest"`
Expected: PASS (경로는 앞 Task들이 넣었다). 어느 하나가 500이면 그 경로가 `SecurityConfig` matcher에 빠진 것이다 — 해당 Task의 matcher를 넣고 다시 돌린다.

- [ ] **Step 3: 전체 검증**

```bash
cd apps/backend && ./gradlew spotlessApply && ./gradlew test spotlessCheck && cd ../..
git status --short
docker build --target test apps/backend
```

Expected: BUILD SUCCESSFUL, `git status` 는 LectureApiIntegrationTest 한 파일, docker build 성공(Docker가 없으면 "미실행"으로 보고 — MR verify가 같은 것을 돌린다).

- [ ] **Step 4: Commit**

```bash
git add apps/backend/src/test/java/com/itda/lecture/controller/LectureApiIntegrationTest.java
git commit -m "test(lecture): 문제 출제·답안 경로가 인증을 요구하는지 확인한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 완료 확인 (MR 전)

- [ ] `cd apps/backend && ./gradlew test` BUILD SUCCESSFUL 을 확인했다
- [ ] `./gradlew spotlessCheck` 성공, `git status --short` 가 비어 있음을 확인했다
- [ ] `docker build --target test apps/backend` 성공을 확인했다(또는 미실행으로 보고)
- [ ] `git log --oneline feat/lecture-problem..HEAD` 가 커밋 8개(Task 1~8)이고 모두 `type(scope): 한글 제목` + 마지막 줄 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 임을 확인했다(`git log --format=%B feat/lecture-problem..HEAD`)
- [ ] `git diff --stat feat/lecture-problem..HEAD` 에 File Structure 표 밖의 파일이 없고, `src/main/resources`(스키마·설정) 변경이 없음을 확인했다
- [ ] `grep -rn "findByTargetText(\|OPEN_METADATA\|TODO:수강생" apps/backend/src/main` 출력이 없음을 확인했다
- [ ] `grep -n "@Validated" apps/backend/src/main/java/com/itda/lecture/controller/*.java apps/backend/src/main/java/com/itda/problem/controller/*.java` 출력이 없음을 확인했다
- [ ] api-spec §8.2.1·8.2.2·8.5.2의 경로·필드·JSON·오류 code·metadata·신호 payload가 교차 계약(`problem-contract.md`)과 글자 그대로 같음을 확인했다
- [ ] push하지 않았다. mr-draft 스킬로 MR 초안(대상 `feat/lecture-problem`, 문제 등록 API 담당 팀원 리뷰어)을 만들어 사람에게 넘긴다
- [ ] 실서버 스모크(선택, 사람이 로컬 DB·`.env` 를 준비한 경우): `초성 ㄱ` 201 + displayText `초성 ㄱ`·expectedPattern, `ㄱ` 409 + 후보 2개, 같은 글자 재등록 200, 출제 후 https://meet.livekit.io 에서 방 metadata에 `currentProblemId` 확인, 학습자 제출 후 강사 탭에서 topic `lecture` 데이터 수신 확인

## 알려진 한계 (후속 이슈 후보)

- **후보에 구분어 없는 문구가 섞이는 경우:** 구분어 없는 낱자의 후보 중 SKILL_01·SKILL_03 어느 쪽에도 연결되지 않은 CONSONANT(예: 쌍자음 `ㅆ`)가 다른 후보와 함께 있으면 그 후보의 `input` 은 글자 그대로라 다시 보내도 같은 409가 난다. 현재 데이터에서 그런 글자는 후보가 하나뿐이라(바로 연결) 일어나지 않는다.
- **공백 없는 구분어(`초성ㄱ`):** 문법상 구분어가 아니므로 낱말로 보고 AI 생성 경로로 간다. 강사 화면의 입력 안내가 막는다. 필요하면 후속에서 거부 규칙을 더한다.
- **구분어 없는 글자의 폴백:** 계약 "WORD·CONTRACTION_WORD·SENTENCE 우선, 없으면 AI 생성"에서 "우선"을 폴백으로 해석해, 그 셋이 없으면 다른 level의 같은 글자(예: 약자만 있는 `억`)를 먼저 연결한다 — 같은 글자의 중복 문제를 AI로 또 만들지 않기 위해서다. 표시 문구는 `음절 억` 이 된다.
- **같은 cells 판정:** 저장된 입력은 0으로 채워져 있어 원래 길이를 모른다. 정답보다 짧게 낸 답과 끝에 0을 붙인 답은 같은 답으로 본다(점수·정오답도 같다).
- **동시성 테스트:** 행 잠금(`PESSIMISTIC_WRITE`)과 UNIQUE 재시도는 단위·통합 테스트가 경합을 재현하지 않는다(잠금 조회 동작만 확인). 운영 MariaDB에서 종료와 제출의 직렬화는 코드 리뷰로 확인한다.
- **수업 목록 응답:** `GET /lectures` 항목은 문제 정보를 채우지 않는다(`currentProblem` null, `linkedProblems` 빈 배열) — 항목마다 조회가 늘어나는 것을 피한다. api-spec §8.2에 적었다.
- **권한 없는 요청의 code:** 출제·답안 목록은 기존 `PUT /status` 와 같이 담당 강사가 아니면(학습자 포함) 404 `LECTURE_NOT_FOUND` 다. 문제 연결은 기존 코드대로 비강사 403 `INSTRUCTOR_ACCESS_REQUIRED` 를 유지한다.
