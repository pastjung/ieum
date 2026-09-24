# 학습 세션 API 연동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학습자 앱의 자기주도 학습 한 바퀴(학습 시작 → 문항 풀이 → 일괄 제출 → 결과)를 실제 백엔드 API로 동작시킨다.

**Architecture:** 백엔드는 연동을 막고 있는 버그 3건만 고친다(인증 누락·경로 중복·`problemSessionId` 누락). 새 엔드포인트는 만들지 않는다. 앱은 문항마다 로컬 XOR로 즉시 피드백을 주고 답안을 메모리에 쌓았다가, 마지막 문항 뒤 `analyzing` 화면에서 12개를 한 번에 제출한다. 결과 화면은 로컬 값이 아니라 서버 응답만 읽는다.

**Tech Stack:** Spring Boot 4 / Java 25 / JUnit 5 + AssertJ, React Native (Expo) / TypeScript / zod / Zustand / TanStack Query / Jest

**Spec:** `docs/superpowers/specs/2026-09-24-learning-session-integration-design.md`

## Global Constraints

- 브랜치는 `feat/learning-session-integration` 이다. `dev`·`main` 에 직접 push 하지 않는다.
- 커밋 메시지는 `type(scope): 한글 제목` 형식이다. `git push --force`·`--no-verify` 금지.
- 점자 셀은 전 구간에서 **6비트 정수**(0~63)로 표현한다. 백엔드가 `@Min(0) @Max(63)` 으로 검증한다.
- 백엔드 검증: `cd apps/backend && ./gradlew test spotlessCheck`
- 앱 검증: `cd front && pnpm -r typecheck && pnpm -r test`
- CI 동일 검증: `docker build --target test front`
- 실패하는 모든 사용자 경로에 `announce()` + `hapticError()` 를 붙인다. 화면 표시만으로는 스크린리더 사용자에게 아무 일도 일어나지 않은 것과 같다.
- `.env` 계열·`*.pem` 은 읽지도 수정하지도 않는다.
- 요청 범위 밖의 파일을 함께 고치지 않는다. 특히 `ProblemService.registerProblem` 의 `// TODO:수강생들에게 문제 전달` 은 건드리지 않는다.

## File Structure

**백엔드 (3파일 수정, 1파일 생성)**

| 파일 | 책임 |
|---|---|
| `apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java` | 보호 체인에 학습 세션 세 경로 추가 |
| `apps/backend/src/main/java/com/itda/learning/controller/LearningSessionCompletionController.java` | 중복 프리픽스 제거 |
| `apps/backend/src/main/java/com/itda/problem/dto/ProblemDetail.java` | `problemSessionId` 추가 |
| `apps/backend/src/main/java/com/itda/problem/service/ProblemService.java` | `ProblemDetail` 생성 지점 4곳 맞춤 |
| `apps/backend/src/test/java/com/itda/learning/controller/LearningSessionApiIntegrationTest.java` | **생성** — 세 경로의 HTTP 계약 |

**앱 (4파일 수정, 2파일 생성)**

| 파일 | 책임 |
|---|---|
| `front/packages/contracts/src/index.ts` | 엔드포인트 3개의 zod 스키마 |
| `front/apps/mobile/src/services/learningService.ts` | HTTP 호출 + 도메인 변환 |
| `front/apps/mobile/src/stores/sessionStore.ts` | `sessionId`·`problemSessionId` 보관, 완료 페이로드 생성 |
| `front/apps/mobile/src/app/session/intro.tsx` | 실제 세션 시작 |
| `front/apps/mobile/src/app/session/analyzing.tsx` | 일괄 제출 |
| `front/apps/mobile/src/app/session/summary.tsx` | 서버 결과 표시 |
| `front/apps/mobile/__tests__/learningContracts.test.ts` | **생성** — 스키마 계약 |
| `front/apps/mobile/__tests__/sessionStore.test.ts` | **생성** — 완료 페이로드 |

---

### Task 1: 학습 세션 세 경로를 인증 보호 아래로 옮긴다

지금 `SecurityConfig` 의 보호 체인이 매칭하는 경로는 `GET /users/me` 와 `POST /users/me/withdrawals` 둘뿐이다. 학습 세션 경로는 `permitAll` 체인으로 가서 `@AuthenticationPrincipal` 이 null이 되고, 토큰 없는 요청이 서비스 계층까지 들어가 500으로 터진다.

**Files:**
- Create: `apps/backend/src/test/java/com/itda/learning/controller/LearningSessionApiIntegrationTest.java`
- Modify: `apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java:60-62` (상수), `:92-95` (matcher)

**Interfaces:**
- Consumes: `com.itda.support.web.WebApiTest`, `com.itda.support.web.HttpTestClient`
- Produces: `LearningSessionApiIntegrationTest` — Task 2·3이 이 파일에 테스트를 더한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/backend/src/test/java/com/itda/learning/controller/LearningSessionApiIntegrationTest.java` 를 새로 만든다.

```java
package com.itda.learning.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 학습 세션 API의 HTTP 계약을 실제 Tomcat으로 검증한다.
 *
 * <p>세 경로 모두 본인의 세션을 다루므로 Access Token 없이는 들어올 수 없어야 한다. 토큰이 없을 때
 * {@code 500}이 나간다면 인증 필터가 붙지 않아 {@code @AuthenticationPrincipal}이 null인 채로 서비스까지
 * 들어갔다는 뜻이다 — 계약 위반이자 정보 노출이다.
 */
@WebApiTest
class LearningSessionApiIntegrationTest {

  private static final String SESSIONS = "/api/v1/learning-sessions";

  @LocalServerPort private int port;

  private HttpTestClient client;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
  }

  @Test
  void 토큰_없이_세션을_만들려_하면_401이다() {
    HttpResponse<String> response =
        this.client.send("POST", SESSIONS, "{\"curriculumId\":1}", "Content-Type", "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 토큰_없이_세션을_조회하면_401이다() {
    HttpResponse<String> response = this.client.get(SESSIONS + "/1");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void 토큰_없이_완료를_제출하면_401이다() {
    HttpResponse<String> response =
        this.client.send(
            "POST", SESSIONS + "/1/completion", "{\"attempts\":[]}", "Content-Type", "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd apps/backend && ./gradlew test --tests 'com.itda.learning.controller.LearningSessionApiIntegrationTest'
```

기대: 3개 모두 FAIL. 첫 두 개는 500(또는 200), 완료는 404가 나온다. **이 값을 기록해 둔다** — Task 2가 404를 없앤다.

- [ ] **Step 3: 경로 상수를 추가한다**

`SecurityConfig.java` 의 `WITHDRAWALS_PATH` 선언(62행) 바로 아래에 넣는다.

```java
  /**
   * 학습 세션 경로. 모두 본인 세션만 다루므로 인증이 필요하다.
   *
   * <p>{@code context-path}가 {@code /api/v1}을 붙이므로 여기에는 그 뒤만 적는다.
   */
  private static final String LEARNING_SESSIONS_PATH = "/learning-sessions";

  private static final String LEARNING_SESSION_PATH = "/learning-sessions/{sessionId}";

  private static final String LEARNING_SESSION_COMPLETION_PATH =
      "/learning-sessions/{sessionId}/completion";
```

- [ ] **Step 4: matcher에 세 경로를 더한다**

`SecurityConfig.java:92-95` 의 `securityMatcher` 블록을 이렇게 바꾼다. 기존 두 줄은 그대로 두고 세 줄을 더하는 것이다.

```java
        .securityMatcher(
            new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, ME_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, WITHDRAWALS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LEARNING_SESSIONS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, LEARNING_SESSION_PATH),
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, LEARNING_SESSION_COMPLETION_PATH)))
```

**method까지 지정하는 이유**는 `SecurityConfig` 의 기존 클래스 주석(76행)에 있다 — 경로만 지정하면 같은 경로의 다른 method가 이 체인에 들어와, 계약상 405여야 할 요청이 401이 된다.

- [ ] **Step 5: 테스트를 다시 돌린다**

```bash
cd apps/backend && ./gradlew test --tests 'com.itda.learning.controller.LearningSessionApiIntegrationTest'
```

기대: 앞 두 개 PASS. 완료 테스트는 여전히 FAIL(404) — Task 2에서 고친다. 이 상태가 맞다.

- [ ] **Step 6: 전체 테스트가 깨지지 않았는지 본다**

```bash
cd apps/backend && ./gradlew test
```

기대: `LearningSessionApiIntegrationTest` 의 완료 테스트 1건만 실패한다. **`ProblemJpaMappingTest.정답_Cell은_problem_id로_문제를_참조해_저장된다` 도 실패하지만 이 브랜치와 무관하다** — `origin/dev` 를 그대로 받아도 실패하는 기존 문제다. 다른 테스트가 새로 깨졌다면 이 변경 때문이므로 멈추고 원인을 찾는다.

- [ ] **Step 7: 커밋**

```bash
git add apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java \
        apps/backend/src/test/java/com/itda/learning/controller/LearningSessionApiIntegrationTest.java
git commit -m "fix(learning): 학습 세션 경로를 인증 보호 아래로 옮긴다"
```

커밋 본문에 쓸 내용: 보호 체인이 `/users/me` 계열 둘만 매칭해서 학습 세션 경로가 `permitAll` 로 갔고, 토큰 없는 요청이 `@AuthenticationPrincipal` null 인 채 서비스까지 들어가 500으로 터졌다. 401이어야 한다.

---

### Task 2: 완료 엔드포인트의 중복 프리픽스를 없앤다

`application.yml:35` 가 `context-path: /api/v1` 을 붙이는데 `LearningSessionCompletionController` 가 클래스에 `@RequestMapping("/api/v1/learning-sessions")` 를 또 붙였다. 실제로 이 엔드포인트는 `/api/v1/api/v1/learning-sessions/{id}/completion` 에 있고, 문서에 적힌 경로로는 404다.

**Files:**
- Modify: `apps/backend/src/main/java/com/itda/learning/controller/LearningSessionCompletionController.java:25`
- Test: `apps/backend/src/test/java/com/itda/learning/controller/LearningSessionApiIntegrationTest.java` (Task 1에서 만든 파일)

**Interfaces:**
- Consumes: Task 1의 `LearningSessionApiIntegrationTest`
- Produces: 없음

- [ ] **Step 1: 잘못된 경로가 사라지는지 보는 테스트를 더한다**

Task 1 파일의 마지막 테스트 아래에 추가한다.

```java
  @Test
  void 완료_경로가_context_path를_두_번_붙이지_않는다() {
    // context-path(/api/v1)와 클래스 @RequestMapping이 겹쳐 /api/v1/api/v1/... 에 붙어 있었다.
    // 그 경로는 존재하지 않아야 한다 — 남아 있으면 문서와 다른 경로가 계속 살아 있다는 뜻이다.
    HttpResponse<String> response =
        this.client.send(
            "POST",
            "/api/v1/api/v1/learning-sessions/1/completion",
            "{\"attempts\":[]}",
            "Content-Type",
            "application/json");

    assertThat(response.statusCode()).isEqualTo(404);
  }
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd apps/backend && ./gradlew test --tests 'com.itda.learning.controller.LearningSessionApiIntegrationTest'
```

기대: 새 테스트가 FAIL(404가 아니라 401 또는 422가 나온다). Task 1의 완료 테스트도 여전히 FAIL(404).

- [ ] **Step 3: 프리픽스를 고친다**

`LearningSessionCompletionController.java:25` 한 줄이다.

```java
@RequestMapping("/learning-sessions")
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
cd apps/backend && ./gradlew test --tests 'com.itda.learning.controller.LearningSessionApiIntegrationTest'
```

기대: 4개 모두 PASS. Task 1에서 남겨 둔 완료 401 테스트가 이제 통과한다.

- [ ] **Step 5: 커밋**

```bash
git add apps/backend/src/main/java/com/itda/learning/controller/LearningSessionCompletionController.java \
        apps/backend/src/test/java/com/itda/learning/controller/LearningSessionApiIntegrationTest.java
git commit -m "fix(learning): 완료 엔드포인트의 중복 경로 프리픽스를 없앤다"
```

---

### Task 3: 세션 응답에 `problemSessionId` 를 넣는다

세션 생성 응답의 문항에 `problemSessionId` 가 없는데 완료 제출은 문항마다 그것을 요구한다. 앱이 받은 값만으로 제출 요청을 만들 수 없다. 같은 문제가 한 세션에 두 번 배정될 수 있으므로 `problemId` 로 대신할 수 없다.

**Files:**
- Modify: `apps/backend/src/main/java/com/itda/problem/dto/ProblemDetail.java`
- Modify: `apps/backend/src/main/java/com/itda/problem/service/ProblemService.java` (생성 지점 4곳: `:64`, `:194`, `:226`, `:333` 부근)
- Test: `apps/backend/src/test/java/com/itda/problem/service/ProblemServiceTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `ProblemDetail(Long problemSessionId, Long problemId, Long sequence, String targetText, List<Integer> expectedPatterns, Boolean completed)` — Task 4의 zod 스키마가 이 필드 순서·이름에 맞춘다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ProblemServiceTest.java` 에 더한다. 이 파일은 이미 Mockito 단위 테스트이고 `@Mock` 으로
`learningSessionRepository`·`problemSessionRepository`·`problemCellRepository` 를 모두 갖고 있다.

`studyWithOldSession` 을 고른 이유는 스텁이 셋뿐이라서다. `learningWithNewSession` 은 난수 출제와
오답 조회까지 얽혀 있어 같은 것을 확인하는 데 스텁이 훨씬 많이 필요하다. 배정 식별자를 응답에
싣는지는 두 경로가 같은 문제이므로 싼 쪽으로 검증한다.

```java
  @Test
  void 세션_조회_응답의_각_문항이_problemSessionId를_갖는다() {
    // 앱은 이 값으로만 완료 제출을 만들 수 있다. 같은 문제가 한 세션에 두 번 배정될 수 있어
    // problemId로는 어느 배정에 대한 답인지 가릴 수 없다.
    long sessionId = 55L;
    long learnerId = 9L;
    AuthenticatedUser learner = new AuthenticatedUser(learnerId, UserRole.LEARNER);

    LearningSession session = new LearningSession(learnerId, null);
    given(learningSessionRepository.findById(sessionId)).willReturn(Optional.of(session));

    Problem problem = new Problem(Level.WORD, "가방");
    ReflectionTestUtils.setField(problem, "id", 31L);
    ProblemSession assigned = new ProblemSession(problem, session, 1L);
    ReflectionTestUtils.setField(assigned, "id", 101L);
    given(problemSessionRepository.findAllBySession_IdOrderBySequence(sessionId))
        .willReturn(List.of(assigned));

    given(
            problemCellRepository.findAllByProblem_IdInOrderByProblem_IdAscCellIndexAsc(
                List.of(31L)))
        .willReturn(List.of());

    ProblemsResponse response = problemService.studyWithOldSession(learner, sessionId);

    assertThat(response.problems())
        .singleElement()
        .satisfies(p -> assertThat(p.problemSessionId()).isEqualTo(101L));
  }
```

`LearningSession` 과 `AuthenticatedUser` 의 생성자 인자가 이 코드와 다르면 실제 정의에 맞춘다.
`ReflectionTestUtils.setField` 로 ID를 넣는 방식은 `LearningSessionCompletionServiceTest` 가 이미
쓰는 패턴이다.

- [ ] **Step 2: 실패를 확인한다**

```bash
cd apps/backend && ./gradlew test --tests 'com.itda.problem.service.ProblemServiceTest'
```

기대: 컴파일 실패 — `ProblemDetail` 에 `problemSessionId()` 접근자가 없다. 이것이 올바른 빨간불이다.

- [ ] **Step 3: DTO에 필드를 더한다**

```java
package com.itda.problem.dto;

import java.util.List;

/**
 * 세션에 배정된 문항 하나.
 *
 * <p>{@code problemSessionId}는 이 문항 배정의 식별자다. 완료 제출이 이 값을 요구한다 —
 * 같은 문제가 한 세션에 두 번 배정될 수 있어 {@code problemId}로는 어느 배정인지 가릴 수 없다.
 */
public record ProblemDetail(
    Long problemSessionId,
    Long problemId,
    Long sequence,
    String targetText,
    List<Integer> expectedPatterns,
    Boolean completed) {}
```

- [ ] **Step 4: 생성 지점 4곳을 맞춘다**

`ProblemService.java` 에서 `new ProblemDetail(` 을 모두 찾아 고친다.

`learningWithNewSession` (194행 부근) — `problemSessions` 는 `saveAll` 뒤라 ID가 있다. 순회 인덱스가 같으므로 그대로 꺼낸다.

```java
      problemDetails.add(
          new ProblemDetail(
              problemSessions.get(i).getId(),
              p.getId(),
              (long) i + 1,
              p.getTargetText(),
              cellMap.getOrDefault(p.getId(), List.of()),
              false));
```

`studyWithOldSession` (226행 부근) — 이미 `ProblemSession s` 를 순회 중이다.

```java
      problemDetails.add(
          new ProblemDetail(
              s.getId(),
              p.getId(),
              s.getSequence(),
              p.getTargetText(),
              cellMap.getOrDefault(p.getId(), List.of()),
              s.isCompleted()));
```

`reviewWithNewSession` (333행 부근) — `learningWithNewSession` 과 같은 모양이다. 그 자리의 `problemSessions` 리스트에서 같은 인덱스를 꺼낸다.

`registerProblem` (64행) — 여기서 만든 `problemDetail` 은 지역 변수에 담기고 쓰이지 않는다(`// TODO:수강생들에게 문제 전달`). 컴파일을 위해 첫 인자에 `null` 을 넘긴다.

```java
    ProblemDetail problemDetail =
        new ProblemDetail(
            null, problem.getId(), 1L, registerProblemRequest.targetText(), expectedPatterns, false);
```

**TODO 주석과 그 아래 미완성 코드는 건드리지 않는다.** 남의 작업 범위다.

- [ ] **Step 5: 테스트를 돌린다**

```bash
cd apps/backend && ./gradlew test spotlessCheck
```

기대: 새 테스트 PASS. `ProblemJpaMappingTest` 1건만 실패(기존 문제). spotless 통과. 포맷 위반이 나오면 `./gradlew spotlessApply` 로 고친다.

- [ ] **Step 6: 실제 서버로 확인한다**

`docker compose up -d --build backend` 로 올린 뒤, 로그인해 받은 Access Token으로 세션을 만들어 응답을 눈으로 본다.

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/learning-sessions \
  -H "Content-Type: application/json" -H "Authorization: Bearer <token>" \
  -d '{"curriculumId":1}' | head -c 400
```

기대: 각 `problems[]` 항목에 `problemSessionId` 가 있다. 토큰 없이 같은 요청을 보내면 401이다.

- [ ] **Step 7: 커밋**

```bash
git add apps/backend/src/main/java/com/itda/problem/
git commit -m "feat(learning): 세션 응답에 problemSessionId를 담는다"
```

---

### Task 4: 학습 세션 계약(zod 스키마)을 정의한다

앱과 백엔드의 계약을 한 곳에 둔다. 화면마다 응답 모양을 다시 적으면 언젠가 갈라진다.

**Files:**
- Modify: `front/packages/contracts/src/index.ts` (파일 끝에 추가)
- Create: `front/apps/mobile/__tests__/learningContracts.test.ts`

**Interfaces:**
- Consumes: Task 3의 `ProblemDetail` 필드 이름
- Produces:
  - `problemDetailSchema`, `problemsResponseSchema`, `type ProblemsResponse`
  - `completionRequestSchema`, `completionResponseSchema`, `type CompletionResponse`, `type AttemptInput`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`front/apps/mobile/__tests__/learningContracts.test.ts` 를 새로 만든다.

```ts
import {
  problemsResponseSchema,
  completionResponseSchema,
  completionRequestSchema,
} from "@ieum/contracts";

const serverProblems = {
  sessionId: 12,
  problems: [
    {
      problemSessionId: 101,
      problemId: 7,
      sequence: 1,
      targetText: "가방",
      expectedPatterns: [1, 32],
      completed: false,
    },
  ],
};

describe("학습 세션 응답 계약", () => {
  it("백엔드가 보내는 모양을 그대로 받는다", () => {
    const parsed = problemsResponseSchema.parse(serverProblems);
    expect(parsed.problems[0].problemSessionId).toBe(101);
  });

  it("problemSessionId가 없으면 거부한다", () => {
    // 이 값이 없으면 완료 제출을 만들 수 없다. 화면까지 흘려보내면 제출 직전에 터진다
    const { problemSessionId: _omit, ...rest } = serverProblems.problems[0];
    expect(() =>
      problemsResponseSchema.parse({ sessionId: 12, problems: [rest] }),
    ).toThrow();
  });

  it("6비트 범위를 벗어난 패턴을 거부한다", () => {
    expect(() =>
      problemsResponseSchema.parse({
        sessionId: 12,
        problems: [{ ...serverProblems.problems[0], expectedPatterns: [64] }],
      }),
    ).toThrow();
  });

  it("문항이 없는 세션을 거부한다", () => {
    expect(() => problemsResponseSchema.parse({ sessionId: 12, problems: [] })).toThrow();
  });
});

describe("완료 제출 계약", () => {
  it("백엔드 검증과 같은 범위를 강제한다", () => {
    // 백엔드가 @NotEmpty @Min(0) @Max(63)으로 막는다. 여기서 먼저 막아 네트워크까지 가지 않게 한다
    expect(() =>
      completionRequestSchema.parse({ attempts: [] }),
    ).toThrow();
    expect(() =>
      completionRequestSchema.parse({
        attempts: [{ problemSessionId: 101, inputPatterns: [64] }],
      }),
    ).toThrow();
    expect(() =>
      completionRequestSchema.parse({
        attempts: [{ problemSessionId: 101, inputPatterns: [1, 32] }],
      }),
    ).not.toThrow();
  });

  it("완료 응답의 셀 진단을 읽는다", () => {
    const parsed = completionResponseSchema.parse({
      sessionId: 12,
      completedAt: "2026-09-24T11:00:00Z",
      totalProblemCount: 1,
      correctProblemCount: 0,
      problemResults: [
        {
          problemSessionId: 101,
          problemId: 7,
          attemptId: 5,
          correct: false,
          cells: [
            {
              cellIndex: 1,
              expectedPattern: 1,
              inputPattern: 3,
              diffMask: 2,
              missingMask: 0,
              extraMask: 2,
              missingDots: [],
              extraDots: [2],
            },
          ],
        },
      ],
    });
    expect(parsed.problemResults[0].cells[0].extraDots).toEqual([2]);
  });
});
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd front && pnpm --filter @ieum/mobile test learningContracts
```

기대: FAIL — `problemsResponseSchema` 를 `@ieum/contracts` 에서 못 찾는다.

- [ ] **Step 3: 스키마를 쓴다**

`front/packages/contracts/src/index.ts` 끝에 추가한다. `cellSchema`(4행, `z.number().int().min(0).max(63)`)가 이미 있으니 그것을 쓴다.

```ts
/* ─── 학습 세션 (구현된 백엔드 계약 기준) ──────────────────────────────────
 *
 * API 명세(api-spec.md 3.6절)와 구현이 갈라져 있다. 명세는 curriculumStageId·문항별 제출·
 * 분석/결과 분리를 정의하지만 구현은 curriculumId·일괄 제출 하나다. 여기서는 **구현**에 맞춘다.
 * 배경과 되돌릴 지점은 docs/superpowers/specs/2026-09-24-learning-session-integration-design.md
 * 2.4절에 있다.
 *
 * 식별자를 숫자로 받는 이유: 백엔드가 Long을 JSON 숫자로 보낸다. 명세는 10진수 문자열을
 * 요구하지만 구현이 그렇지 않다. JS 안전 정수 한계(2^53)를 넘으면 조용히 깨진다 — 식별자가
 * 그 크기에 다다를 일이 생기면 백엔드를 문자열로 바꾸고 이 스키마도 함께 옮겨야 한다.
 */
const identifierSchema = z.number().int().nonnegative();

export const problemDetailSchema = z.object({
  /** 이 문항 "배정"의 식별자. 완료 제출이 요구한다 — problemId로는 대신할 수 없다 */
  problemSessionId: identifierSchema,
  problemId: identifierSchema,
  sequence: z.number().int().positive(),
  targetText: z.string().min(1),
  expectedPatterns: z.array(cellSchema).min(1),
  completed: z.boolean(),
});
export type ProblemDetail = z.infer<typeof problemDetailSchema>;

export const problemsResponseSchema = z.object({
  sessionId: identifierSchema,
  problems: z.array(problemDetailSchema).min(1),
});
export type ProblemsResponse = z.infer<typeof problemsResponseSchema>;

/** 일괄 제출. 백엔드가 @NotEmpty @Size(max=100) @Min(0) @Max(63)으로 검증한다 */
export const attemptInputSchema = z.object({
  problemSessionId: identifierSchema,
  inputPatterns: z.array(cellSchema).min(1).max(100),
});
export type AttemptInput = z.infer<typeof attemptInputSchema>;

export const completionRequestSchema = z.object({
  attempts: z.array(attemptInputSchema).min(1).max(100),
});
export type CompletionRequest = z.infer<typeof completionRequestSchema>;

export const cellCompletionSchema = z.object({
  cellIndex: z.number().int(),
  expectedPattern: cellSchema,
  inputPattern: cellSchema,
  diffMask: cellSchema,
  missingMask: cellSchema,
  extraMask: cellSchema,
  missingDots: z.array(z.number().int()),
  extraDots: z.array(z.number().int()),
});

export const problemCompletionSchema = z.object({
  problemSessionId: identifierSchema,
  problemId: identifierSchema,
  attemptId: identifierSchema,
  correct: z.boolean(),
  cells: z.array(cellCompletionSchema),
});

export const completionResponseSchema = z.object({
  sessionId: identifierSchema,
  completedAt: z.string(),
  totalProblemCount: z.number().int().nonnegative(),
  correctProblemCount: z.number().int().nonnegative(),
  problemResults: z.array(problemCompletionSchema),
});
export type CompletionResponse = z.infer<typeof completionResponseSchema>;
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
cd front && pnpm --filter @ieum/mobile test learningContracts && pnpm -r typecheck
```

기대: 전부 PASS.

- [ ] **Step 5: 커밋**

```bash
git add front/packages/contracts/src/index.ts front/apps/mobile/__tests__/learningContracts.test.ts
git commit -m "feat(learning): 학습 세션 API 계약을 스키마로 정의한다"
```

---

### Task 5: `sessionStore` 가 배정 식별자를 들고 제출 페이로드를 만든다

지금 `AnswerRecord` 는 `problemId` 를 들고 있는데, 완료 제출이 요구하는 것은 `problemSessionId` 다. 같은 문제가 한 세션에 두 번 배정될 수 있어 `problemId` 로는 어느 배정에 대한 답인지 가릴 수 없다.

**Files:**
- Modify: `front/apps/mobile/src/stores/sessionStore.ts`
- Create: `front/apps/mobile/__tests__/sessionStore.test.ts`

**Interfaces:**
- Consumes: Task 4의 `completionRequestSchema`, `AttemptInput`
- Produces: `useSessionStore` 에 `serverSessionId: number | null`, `buildAttempts(): AttemptInput[]`, `serverResult: CompletionResponse | null`, `setServerResult(r)`. `start(session, kind?, meta?)` 의 세 번째 인자 `meta` 는 `{ serverSessionId: number; problemSessionIds: number[] }` 이며, 목 세션에서는 넘기지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`front/apps/mobile/__tests__/sessionStore.test.ts` 를 새로 만든다.

```ts
import { useSessionStore } from "@/stores/sessionStore";
import { completionRequestSchema } from "@ieum/contracts";

const session = {
  id: "12",
  title: "자음",
  problems: [
    { id: "7", prompt: "'가'를 점자로 입력하세요", target: "가", expectedCells: [1] },
    // 같은 문제가 두 번 배정된 경우. problemId로는 두 답을 가릴 수 없다
    { id: "7", prompt: "'가'를 점자로 입력하세요", target: "가", expectedCells: [1] },
  ],
};

const meta = { serverSessionId: 12, problemSessionIds: [101, 102] };

describe("세션 스토어의 완료 페이로드", () => {
  beforeEach(() => useSessionStore.getState().reset());

  it("배정 식별자를 문항 순서대로 붙인다", () => {
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().submit([1]);
    useSessionStore.getState().next();
    useSessionStore.getState().submit([3]);

    const attempts = useSessionStore.getState().buildAttempts();

    expect(attempts).toEqual([
      { problemSessionId: 101, inputPatterns: [1] },
      { problemSessionId: 102, inputPatterns: [3] },
    ]);
  });

  it("만든 페이로드가 백엔드 검증을 통과하는 모양이다", () => {
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().submit([1]);

    expect(() =>
      completionRequestSchema.parse({ attempts: useSessionStore.getState().buildAttempts() }),
    ).not.toThrow();
  });

  it("목 세션은 배정 식별자가 없어 제출 페이로드를 만들지 않는다", () => {
    // 목으로 시작한 세션을 실수로 제출하면 서버가 422를 준다. 앞에서 막는다
    const store = useSessionStore.getState();
    store.start(session);
    useSessionStore.getState().submit([1]);

    expect(useSessionStore.getState().buildAttempts()).toEqual([]);
    expect(useSessionStore.getState().serverSessionId).toBeNull();
  });
});
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd front && pnpm --filter @ieum/mobile test sessionStore
```

기대: FAIL — `buildAttempts` 가 없다.

- [ ] **Step 3: 스토어를 고친다**

`sessionStore.ts` 를 바꾼다. 기존 필드·동작은 그대로 두고 더한다.

```ts
import { create } from "zustand";
import type { AttemptInput, CompletionResponse, LearningSession, Problem } from "@ieum/contracts";
import { gradeAnswer, type Cell, type GradeResult } from "@ieum/domain";

export type SessionKind = "free" | "class";

/** 서버 세션의 식별자들. 목 세션으로 시작하면 없다 */
export interface ServerSessionMeta {
  serverSessionId: number;
  /** 문항 순서와 같은 길이·같은 순서 */
  problemSessionIds: number[];
}

interface AnswerRecord {
  problemId: string;
  /** 이 답이 어느 배정에 대한 것인지. 목 세션이면 null */
  problemSessionId: number | null;
  cells: Cell[];
  result: GradeResult;
}
```

`SessionState` 에 다음을 더한다.

```ts
  serverSessionId: number | null;
  problemSessionIds: number[];
  serverResult: CompletionResponse | null;
  start: (session: LearningSession, kind?: SessionKind, meta?: ServerSessionMeta) => void;
  /** 완료 제출 페이로드. 목 세션이면 빈 배열이다 */
  buildAttempts: () => AttemptInput[];
  setServerResult: (result: CompletionResponse) => void;
```

구현은 이렇다.

```ts
  serverSessionId: null,
  problemSessionIds: [],
  serverResult: null,

  start: (session, kind = "free", meta) =>
    set({
      session,
      kind,
      index: 0,
      answers: [],
      lastResult: null,
      disconnectShown: false,
      serverSessionId: meta?.serverSessionId ?? null,
      problemSessionIds: meta?.problemSessionIds ?? [],
      serverResult: null,
    }),

  submit: (cells) => {
    const { index, problemSessionIds } = get();
    const problem = get().current();
    if (!problem) throw new Error("진행 중인 문제가 없어요");
    const result = gradeAnswer(problem.expectedCells, cells);
    set((s) => ({
      answers: [
        ...s.answers,
        {
          problemId: problem.id,
          // 문항 순서로 찾는다. problemId로 찾으면 같은 문제가 두 번 배정된 세션에서 어긋난다
          problemSessionId: problemSessionIds[index] ?? null,
          cells,
          result,
        },
      ],
      lastResult: result,
    }));
    return result;
  },

  buildAttempts: () =>
    get()
      .answers.filter((a): a is AnswerRecord & { problemSessionId: number } =>
        a.problemSessionId !== null,
      )
      .map((a) => ({ problemSessionId: a.problemSessionId, inputPatterns: a.cells })),

  setServerResult: (result) => set({ serverResult: result }),
```

`reset` 에도 새 필드 셋을 더한다.

```ts
  reset: () =>
    set({
      session: null,
      kind: "free",
      index: 0,
      answers: [],
      lastResult: null,
      disconnectShown: false,
      serverSessionId: null,
      problemSessionIds: [],
      serverResult: null,
    }),
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
cd front && pnpm --filter @ieum/mobile test && pnpm -r typecheck
```

기대: 전부 PASS. 기존 테스트가 깨지면 `AnswerRecord` 를 읽는 화면이 있다는 뜻이니 그 화면도 함께 맞춘다.

- [ ] **Step 5: 커밋**

```bash
git add front/apps/mobile/src/stores/sessionStore.ts front/apps/mobile/__tests__/sessionStore.test.ts
git commit -m "feat(learning): 세션 스토어가 배정 식별자로 제출 페이로드를 만든다"
```

---

### Task 6: `learningService` 가 실제 API를 부른다

목 3개 중 세션 관련만 실제 호출로 바꾼다. `getHomeSummary`·`getSkillLevels` 는 대응 엔드포인트가 없어 목으로 남는다.

**Files:**
- Modify: `front/apps/mobile/src/services/learningService.ts`

**Interfaces:**
- Consumes: Task 4의 스키마들, Task 5의 `ServerSessionMeta`, 기존 `./apiClient` 의 `api`
- Produces:
  - `learningService.startSession(theme?: LearningThemeId): Promise<{ session: LearningSession; meta: ServerSessionMeta }>`
  - `learningService.completeSession(sessionId: number, attempts: AttemptInput[]): Promise<CompletionResponse>`
  - `CURRICULUM_ID_BY_THEME: Record<LearningThemeId, number>`

- [ ] **Step 1: 변환 함수와 호출을 쓴다**

`learningService.ts` 를 바꾼다. 목은 지우지 않고 남길 것만 남긴다.

```ts
import {
  problemsResponseSchema,
  completionResponseSchema,
  type AttemptInput,
  type CompletionResponse,
  type LearningSession,
  type ProblemsResponse,
} from "@ieum/contracts";
import { api } from "./apiClient";
import type { ServerSessionMeta } from "@/stores/sessionStore";
import { homeSummary, skillLevels, type HomeSummary, type LearningThemeId } from "./mockData";

const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * 화면의 학습 주제를 백엔드 curriculum_stages.id로 옮긴다.
 *
 * 목록을 내려주는 엔드포인트가 없어 여기에 적어 둔다. DB의 curriculum_stages는 1~6이고
 * 이 순서가 바뀌면 사용자가 엉뚱한 단계를 배우게 된다 — 단계가 늘거나 순서가 바뀌면
 * 조회 API를 먼저 만들고 이 표를 지운다.
 */
export const CURRICULUM_ID_BY_THEME: Record<LearningThemeId, number> = {
  consonant: 1,
  vowel: 2,
  "abbrev-char": 3,
  word: 4,
  "abbrev-word": 5,
  sentence: 6,
};

const THEME_LABEL: Record<LearningThemeId, string> = {
  consonant: "자음",
  vowel: "모음",
  "abbrev-char": "약자",
  word: "단어",
  "abbrev-word": "약어",
  sentence: "문장",
};

/**
 * 서버 응답을 화면이 쓰는 모양으로 옮긴다.
 *
 * 서버는 지시문(prompt)과 제목(title)을 주지 않는다. 화면이 낭독할 문장이 필요하므로
 * 여기서 만든다 — 문구를 화면마다 따로 만들면 갈라진다.
 */
function toSession(
  response: ProblemsResponse,
  theme: LearningThemeId | undefined,
): { session: LearningSession; meta: ServerSessionMeta } {
  return {
    session: {
      id: String(response.sessionId),
      title: theme ? THEME_LABEL[theme] : "학습",
      problems: response.problems.map((p) => ({
        id: String(p.problemId),
        prompt: `'${p.targetText}'을 점자로 입력하세요`,
        target: p.targetText,
        expectedCells: p.expectedPatterns,
      })),
    },
    meta: {
      serverSessionId: response.sessionId,
      problemSessionIds: response.problems.map((p) => p.problemSessionId),
    },
  };
}

export const learningService = {
  /** 새 학습 세션을 만든다. 서버가 문항을 배정해 함께 돌려준다 */
  async startSession(theme?: LearningThemeId) {
    const curriculumId = CURRICULUM_ID_BY_THEME[theme ?? "consonant"];
    const response = problemsResponseSchema.parse(
      await api.post("/learning-sessions", { curriculumId }),
    );
    return toSession(response, theme);
  },

  /** 세션의 모든 답안을 한 번에 제출한다. 이 응답이 최종 채점 결과다 */
  async completeSession(
    sessionId: number,
    attempts: AttemptInput[],
  ): Promise<CompletionResponse> {
    return completionResponseSchema.parse(
      await api.post(`/learning-sessions/${sessionId}/completion`, { attempts }),
    );
  },

  /* ─── 아래는 아직 목이다. 대응 엔드포인트가 없다 ─── */

  async getHomeSummary(): Promise<HomeSummary> {
    await delay(250);
    return homeSummary;
  },

  async getSkillLevels() {
    await delay(200);
    return skillLevels;
  },
};
```

`freeSessionVowel` import가 더는 쓰이지 않으면 지운다. `mockData.ts` 의 정의 자체는 **지우지 않는다** — 다른 화면이 쓸 수 있다. 쓰는 곳이 없는지 먼저 확인한다.

```bash
cd front && grep -rn "freeSessionVowel\|getFreeSession" apps/mobile/src
```

- [ ] **Step 2: 타입체크와 테스트**

```bash
cd front && pnpm -r typecheck && pnpm -r test
```

기대: `intro.tsx` 가 `getFreeSession` 을 부르고 있어 타입 오류가 난다. 그것은 Task 7에서 고친다. 다른 오류가 있으면 여기서 고친다.

- [ ] **Step 3: 커밋**

Task 7과 함께 커밋한다. `intro.tsx` 를 고치기 전에는 타입체크가 통과하지 않으므로 여기서 커밋하지 않는다.

---

### Task 7: `intro` 화면이 실제 세션을 시작한다

**Files:**
- Modify: `front/apps/mobile/src/app/session/intro.tsx`

**Interfaces:**
- Consumes: Task 6의 `learningService.startSession`, Task 5의 `start(session, kind, meta)`
- Produces: 없음

- [ ] **Step 1: 화면을 고친다**

`intro.tsx` 의 쿼리와 시작 버튼을 바꾼다. 나머지(실패 화면, 규칙 카드, 낭독 문구)는 그대로 둔다.

```ts
  const { data, isError, refetch } = useQuery({
    queryKey: ["learningSession", themeId ?? "default"],
    queryFn: () => learningService.startSession(themeId),
    // 세션 생성은 서버에 행을 만든다. 화면에 다시 들어올 때마다 새 세션이 생기면 안 된다
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
  });
  const session = data?.session;
```

시작 버튼:

```tsx
          onPress={() => {
            if (!data) return;
            start(data.session, "free", data.meta);
            router.replace("/session/play");
          }}
```

낭독 `useEffect` 의 의존성은 `session` 그대로 둔다.

- [ ] **Step 2: 실패 화면의 문구를 실제 원인에 맞춘다**

지금 실패 화면은 "학습 준비가 안 됐어요 / 잠시 후 다시 해 보세요"다. 원인을 가리지 않는 문구라 그대로 둔다. 다만 낭독에 실패 사실이 반드시 실리도록 `announce` 옆에 `hapticError` 를 더한다.

```ts
    if (isError) {
      void hapticError();
      announce("학습 준비가 안 됐어요. 잠시 후 다시 해 보세요.");
      return;
    }
```

`@/a11y` 의 import에 `hapticError` 를 더한다.

- [ ] **Step 3: 타입체크와 테스트**

```bash
cd front && pnpm -r typecheck && pnpm -r test
```

기대: 전부 PASS.

- [ ] **Step 4: 커밋**

```bash
git add front/apps/mobile/src/services/learningService.ts front/apps/mobile/src/app/session/intro.tsx
git commit -m "feat(learning): 학습 시작이 실제 세션 API를 부른다"
```

---

### Task 8: `analyzing` 이 일괄 제출하고 `summary` 가 서버 결과를 읽는다

지금 `analyzing` 은 1.8초 가짜 지연 뒤 무조건 `summary` 로 간다. 제출이 실패해도 결과가 뜬다.

**Files:**
- Modify: `front/apps/mobile/src/app/session/analyzing.tsx`
- Modify: `front/apps/mobile/src/app/session/summary.tsx`

**Interfaces:**
- Consumes: Task 5의 `buildAttempts`·`setServerResult`·`serverSessionId`·`serverResult`, Task 6의 `completeSession`
- Produces: 없음

- [ ] **Step 1: `analyzing` 이 제출하게 한다**

```tsx
export default function Analyzing() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const serverSessionId = useSessionStore((s) => s.serverSessionId);
  const buildAttempts = useSessionStore((s) => s.buildAttempts);
  const setServerResult = useSessionStore((s) => s.setServerResult);
  const [failed, setFailed] = useState<string | null>(null);

  const submit = useCallback(async () => {
    setFailed(null);
    // 서버 세션이 아니면(목으로 시작) 제출할 것이 없다. 결과만 보여 준다
    if (serverSessionId === null) {
      router.replace("/session/summary");
      return;
    }
    // 낭독은 제출과 나란히 간다. 낭독이 늦어도 제출을 막지 않는다
    void speakAsync("학습 완료! 결과를 분석 중이에요. 잠시만 기다려 주세요.");
    try {
      const result = await learningService.completeSession(serverSessionId, buildAttempts());
      setServerResult(result);
      router.replace("/session/summary");
    } catch (error) {
      void hapticError();
      const message = describeAuthError(error);
      setFailed(message);
      // 결과 화면으로 넘기지 않는다. 넘기면 사용자가 푼 것이 조용히 사라진다
      announce(`${message} 다시 보내기를 누르면 한 번 더 시도해요.`);
    }
  }, [serverSessionId, buildAttempts, setServerResult, router]);

  useEffect(() => {
    void submit();
  }, [submit]);
```

실패했을 때의 화면을 더한다. 기존 대기 화면은 `failed === null` 일 때만 보여 준다.

```tsx
  if (failed !== null) {
    return (
      <ScreenShell>
        <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: 18 }}>
          <Text
            accessibilityRole="header"
            style={{ fontSize: 28 * fontScale, fontWeight: "900", lineHeight: 40, color: theme.color.text, textAlign: "center" }}
          >
            결과를 보내지{"\n"}못했어요
          </Text>
          <Text
            accessibilityLiveRegion="polite"
            style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}
          >
            {failed}
          </Text>
        </View>
        <View style={{ gap: 12 }}>
          <AppButton label="다시 보내기" onPress={() => void submit()} />
        </View>
      </ScreenShell>
    );
  }
```

**"홈으로" 버튼은 두지 않는다.** 나가면 푼 답안이 사라지는데 그 사실을 알리지 않는 버튼이 되기 때문이다. 재시도만 남긴다.

필요한 import: `useCallback`·`useState` (react), `View`·`Text` (react-native), `AppButton`, `ScreenShell`, `useSessionStore`, `learningService`, `describeAuthError` (`@/services/authErrors`), `announce`·`hapticError` (`@/a11y`).

- [ ] **Step 2: `summary` 가 서버 값을 읽게 한다**

```ts
  const serverResult = useSessionStore((s) => s.serverResult);
  const localCorrect = useSessionStore((s) => s.correctCount)();
  // 서버 채점이 최종이다. 로컬 채점은 문항별 즉시 반응에만 쓴 잠정 값이라 결과에 쓰지 않는다
  const correct = serverResult?.correctProblemCount ?? localCorrect;
  const total = serverResult?.totalProblemCount ?? session?.problems.length ?? 0;
```

화면에서 `session.problems.length` 를 쓰던 자리를 `total` 로, 정답 수를 쓰던 자리를 `correct` 로 바꾼다.

- [ ] **Step 3: 타입체크와 테스트**

```bash
cd front && pnpm -r typecheck && pnpm -r test
```

- [ ] **Step 4: CI와 같은 검증**

```bash
docker build --target test front
```

기대: 통과. 내부에서 jest가 실제로 실행된 건수가 출력된다.

- [ ] **Step 5: 커밋**

```bash
git add front/apps/mobile/src/app/session/analyzing.tsx front/apps/mobile/src/app/session/summary.tsx
git commit -m "feat(learning): 세션 완료를 일괄 제출하고 서버 결과를 표시한다"
```

---

### Task 9: 실기기로 한 바퀴를 확인한다

코드가 아니라 확인이다. 앞의 모든 테스트가 통과해도 이 단계에서 처음 드러나는 것들이 있다 — 이전 인증 연동에서 비밀번호 정책 불일치가 여기서만 잡혔다.

**Files:** 없음 (문서 갱신만)

- [ ] **Step 1: 환경을 올린다**

```bash
docker compose up -d --build backend
adb reverse tcp:8080 tcp:8080
```

`front/apps/mobile` 에서 Metro를 띄운다. `EXPO_PUBLIC_API_URL` 값은 Metro 시작 시점에 번들에 박히므로, `.env` 를 고쳤다면 **Metro를 다시 시작한다**.

- [ ] **Step 2: 한 바퀴를 돈다**

앱에서 학습 주제를 골라 시작 → 문항을 전부 풀고 → 결과 화면까지 간다. 확인할 것:

1. 시작 시 문항이 서버에서 온다 (목의 고정 문항이 아니다).
2. 문항마다 즉시 "정답/오답"이 들린다.
3. 결과 화면의 정답 수가 서버 응답과 같다. 백엔드 로그에서 `POST /api/v1/learning-sessions/{id}/completion status=200` 을 확인한다.

```bash
docker compose logs backend --since 5m | grep http_request | tail -5
```

- [ ] **Step 3: 실패 경로를 본다**

마지막 문항을 풀기 직전에 기기를 비행기 모드로 바꾼 뒤 제출한다. 확인할 것:

1. 결과 화면으로 **넘어가지 않는다**.
2. "결과를 보내지 못했어요"와 원인이 화면에 보이고 낭독된다.
3. 비행기 모드를 끄고 "다시 보내기"를 누르면 제출된다.

- [ ] **Step 4: 문제 부족 경로를 기록한다**

`curriculum_stages` 중 문제가 12개 미만인 단계를 고르면 서버가 500을 준다(`ProblemService.java:151` 의 `RuntimeException`). 앱은 "일시적인 오류"로만 말한다. **이번 범위 밖이므로 고치지 않는다.** 어느 단계에서 재현되는지만 확인해 MR 본문에 적는다.

- [ ] **Step 5: 설계 문서의 완료 기준을 대조한다**

`docs/superpowers/specs/2026-09-24-learning-session-integration-design.md` 10절을 열어 항목마다 확인한 값을 적는다. 확인하지 못한 항목은 **확인하지 못했다고 적는다.**

- [ ] **Step 6: 커밋**

```bash
git add docs/superpowers/specs/2026-09-24-learning-session-integration-design.md
git commit -m "docs(learning): 학습 세션 연동 검증 결과를 기록한다"
```
