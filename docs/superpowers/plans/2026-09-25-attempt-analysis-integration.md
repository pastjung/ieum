# 문항별 분석 연동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학습자 앱이 문항을 하나 풀 때마다 서버로 보내 AI 규칙 분석을 받고, 마지막에 세션을 완료하게 한다.

**Architecture:** 채점 권위가 앱의 로컬 XOR에서 서버로 옮겨간다. `play` 화면이 제출 후 화면을 떠나지 않고 응답을 기다리며, 실패하면 그 자리에서 재시도한다. `grade` 화면은 서버 응답의 셀별 진단으로 점 수준 문장을 만들고 서버의 규칙 설명을 덧붙인다. 실시간 수업은 서버 세션이 없으므로 기존 로컬 채점 경로를 그대로 유지한다.

**Tech Stack:** React Native (Expo) / TypeScript / zod / Zustand / Jest, Spring Boot 4 / Java 25 / JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-09-25-attempt-analysis-integration-design.md`

## Global Constraints

- 브랜치는 `feat/learning-session-integration` 이다. `dev`·`main` 에 직접 push 하지 않는다. **push 자체를 하지 않는다 — 커밋만 한다.**
- 커밋 메시지는 `type(scope): 한글 제목` 형식, scope는 `learning`.
- `git push --force`·`--no-verify` 금지.
- `.env` 계열·`*.pem` 은 읽지도 수정하지도 않는다.
- 워킹트리의 `total_init.sql`·`seed_remaining.sql` 은 건드리지 않는다. `git add -A` 를 쓰지 않는다 — 그 파일들이 딸려 들어가 훅에 막힌다.
- 점자 셀은 전 구간에서 **6비트 정수**(0~63)로 표현한다.
- **입력은 12칸까지다.** FastAPI가 `input_patterns: Field(max_length=12)` 로 막는다(`apps/ai/app/presentation/api/backend/router.py:121`).
- **이 앱은 시각장애인용이다.** 실패하는 모든 사용자 경로에 `announce()` + `hapticError()` 를 붙이고, 낭독 문구와 화면 문구를 같은 문자열로 만든다. `announce()` 만 쓰면 음성 모드가 꺼진 사용자에게 아무것도 전달되지 않는다.
- `a11y/speech.ts` 는 같은 문구를 1.5초 안에 중복 제거한다. 재시도 문구에는 시도 횟수를 담아 구분한다.
- 백엔드 검증: `cd apps/backend && ./gradlew test spotlessCheck`
- 앱 검증: `cd front && pnpm -r typecheck && pnpm -r test` (현재 125개)
- CI 동일 검증: `docker build --target test front`
- `ProblemJpaMappingTest` 는 가끔 실패하는 기존 플레이키 테스트다. 고치지 않는다.

## 반드시 지켜야 할 경계

**`grade.tsx` 와 `sessionStore` 는 실시간 수업(`class/play.tsx`)도 쓴다.** 수업은 서버 세션이 없어 `serverSessionId === null` 이고 `attempt-analysis` 를 부르지 않는다. 기존 로컬 채점 경로(`lastResult` + `describeDiff`)가 **그대로 동작해야 한다**. 새 경로는 더하는 방식으로 넣는다.

**`packages/domain` 의 `gradeAnswer` 는 바꾸지 않는다.** 수업이 쓰는 공유 로직이다.

## File Structure

**백엔드 (2파일 수정)**

| 파일 | 책임 |
|---|---|
| `apps/backend/src/main/java/com/itda/problem/repository/ProblemRepository.java` | 출제 후보 조회에 셀 수 상한 조건 추가 |
| `apps/backend/src/main/java/com/itda/problem/service/ProblemService.java` | 새 메서드 호출, 상한 상수 |
| `apps/backend/src/test/java/com/itda/problem/repository/ProblemRepositoryTest.java` | **생성** — 쿼리가 13칸 이상을 거르는지 |

**앱 (6파일 수정, 4파일 생성)**

| 파일 | 책임 |
|---|---|
| `front/packages/contracts/src/index.ts` | `attempt-analysis` 요청·응답 스키마, `completion` 의 `summary` |
| `front/apps/mobile/src/services/cellFeedback.ts` | **생성** — `cells[]` → 점 수준 문장 |
| `front/apps/mobile/src/services/attemptSubmission.ts` | **생성** — 제출 오케스트레이션(재시도·ALREADY_ANALYZED·소진) |
| `front/apps/mobile/src/services/learningService.ts` | `analyzeAttempt` 호출 추가 |
| `front/apps/mobile/src/stores/sessionStore.ts` | 문항별 서버 분석 결과 보관 |
| `front/apps/mobile/src/app/session/play.tsx` | 제출·대기·재시도, 12칸 가드 |
| `front/apps/mobile/src/app/session/grade.tsx` | 서버 분석 우선, 수업은 로컬 유지 |
| `front/apps/mobile/src/app/session/summary.tsx` | 서버 세션 요약 표시 |
| `front/apps/mobile/__tests__/cellFeedback.test.ts` | **생성** |
| `front/apps/mobile/__tests__/attemptSubmission.test.ts` | **생성** |

---

### Task 1: 출제에서 13칸 이상 문제를 제외한다

FastAPI가 입력을 12칸까지만 받는데(`router.py:121`) 단어 단계에 13칸 이상이 필요한 문제가 486개 있다("특정경제범죄가중처벌법상" 27칸 등). 그 문항은 정답을 넣어도 `attempt-analysis` 가 실패하고, 그러면 `ProblemSession.completed` 가 안 찍혀 **세션 전체를 완료할 수 없다.**

**Files:**
- Modify: `apps/backend/src/main/java/com/itda/problem/repository/ProblemRepository.java`
- Modify: `apps/backend/src/main/java/com/itda/problem/service/ProblemService.java`
- Create: `apps/backend/src/test/java/com/itda/problem/repository/ProblemRepositoryTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `ProblemRepository.findAssignableByLevel(Level level, Collection<Long> excludedIds, long maxCells)` — Task 2 이후는 쓰지 않는다(백엔드 안에서 끝난다).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/backend/src/test/java/com/itda/problem/repository/ProblemRepositoryTest.java` 를 새로 만든다. 같은 저장소의 `ProblemJpaMappingTest` 가 쓰는 테스트 구성(어노테이션·주입 방식)을 먼저 읽고 그대로 따른다.

```java
  @Test
  void 셀이_상한을_넘는_문제는_출제_후보에서_빠진다() {
    // FastAPI가 입력을 12칸까지만 받는다(router.py:121). 13칸 이상이 필요한 문제는
    // 정답을 넣어도 attempt-analysis가 거부하고, 그러면 그 세션을 완료할 수 없다.
    Problem shortOne = persistWithCells(Level.WORD, "가방", List.of(0b000001, 0b100000));
    Problem longOne =
        persistWithCells(
            Level.WORD, "긴문제", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13));

    List<Problem> assignable = problemRepository.findAssignableByLevel(Level.WORD, List.of(), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(shortOne.getId());
    assertThat(assignable).extracting(Problem::getId).doesNotContain(longOne.getId());
  }

  @Test
  void 제외할_문제가_없어도_조회된다() {
    // 오답 문제가 하나도 없으면 빈 목록이 넘어온다. JPQL의 NOT IN은 빈 목록에서
    // 깨지기 쉬우므로 이 경우를 반드시 덮는다.
    Problem only = persistWithCells(Level.WORD, "가방", List.of(0b000001));

    List<Problem> assignable = problemRepository.findAssignableByLevel(Level.WORD, List.of(), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(only.getId());
  }

  @Test
  void 제외_목록에_있는_문제는_빠진다() {
    Problem kept = persistWithCells(Level.WORD, "가방", List.of(0b000001));
    Problem excluded = persistWithCells(Level.WORD, "신발", List.of(0b000010));

    List<Problem> assignable =
        problemRepository.findAssignableByLevel(Level.WORD, List.of(excluded.getId()), 12L);

    assertThat(assignable).extracting(Problem::getId).contains(kept.getId());
    assertThat(assignable).extracting(Problem::getId).doesNotContain(excluded.getId());
  }
```

`persistWithCells` 는 `ProblemJpaMappingTest` 에 같은 이름의 헬퍼가 있다. 그 구현을 보고 이 테스트 클래스에도 맞게 두거나, 공용으로 뺄 수 있으면 뺀다. **셀이 없는 문제는 만들지 않는다** — 그건 이 쿼리의 관심사가 아니다.

- [ ] **Step 2: 실패를 확인한다**

```bash
cd apps/backend && ./gradlew test --tests 'com.itda.problem.repository.ProblemRepositoryTest'
```

기대: 컴파일 실패 — `findAssignableByLevel` 이 없다.

- [ ] **Step 3: 쿼리를 추가한다**

`ProblemRepository` 에 더한다. 기존 `findAllByLevelAndIdNotIn` 은 **지우지 말고 그대로 둔다** — 다른 곳에서 쓰는지 확인한 뒤 판단한다(`grep -rn "findAllByLevelAndIdNotIn" apps/backend/src`).

```java
  /**
   * 출제 후보를 고른다.
   *
   * <p>{@code maxCells}로 셀 수를 제한하는 이유: AI가 입력을 12칸까지만 받는다
   * ({@code apps/ai/app/presentation/api/backend/router.py} 의 {@code input_patterns}).
   * 13칸 이상이 필요한 문제는 정답을 넣어도 분석이 거부되고, 그러면 그 문항의
   * {@code ProblemSession}이 완료 처리되지 않아 세션 전체를 끝낼 수 없다.
   */
  @Query(
      """
      SELECT p
      FROM Problem p
      WHERE p.level = :level
        AND p.id NOT IN :excludedIds
        AND (SELECT COUNT(c) FROM ProblemCell c WHERE c.problem = p) <= :maxCells
      """)
  List<Problem> findAssignableByLevel(
      @Param("level") Level level,
      @Param("excludedIds") Collection<Long> excludedIds,
      @Param("maxCells") long maxCells);
```

**빈 목록 주의**: `NOT IN ()` 은 SQL에서 문법 오류다. Hibernate 버전에 따라 처리가 다르므로 Step 2의 "제외할 문제가 없어도 조회된다" 테스트로 확인한다. 실패하면 호출부에서 빈 목록일 때 실재하지 않는 ID(`List.of(-1L)`)를 넣는 방식으로 우회하고, **왜 그렇게 했는지 주석으로 남긴다.**

- [ ] **Step 4: 서비스가 새 메서드를 쓰게 한다**

`ProblemService` 에 상수를 둔다.

```java
  /**
   * 한 문항에 허용하는 최대 셀 수.
   *
   * <p>AI의 {@code input_patterns} 상한과 같은 값이다. 여기가 더 크면 풀 수 없는 문제가 출제되고,
   * 더 작으면 풀 수 있는 문제를 버린다. 둘은 함께 움직여야 한다.
   */
  private static final long MAX_ASSIGNABLE_CELLS = 12L;
```

`learningWithNewSession` 에서 `problemRepository.findAllByLevelAndIdNotIn(level, wrongProblemIds)` 를 찾아 새 메서드로 바꾼다.

```java
    List<Problem> problems =
        problemRepository.findAssignableByLevel(level, wrongProblemIds, MAX_ASSIGNABLE_CELLS);
```

`reviewWithNewSession` 에도 같은 종류의 조회가 있는지 확인하고, 있으면 같이 바꾼다. **확인만 하고 없으면 건드리지 않는다.**

- [ ] **Step 5: 테스트를 돌린다**

```bash
cd apps/backend && ./gradlew test spotlessCheck
```

기대: 새 테스트 3개 PASS. 기존 테스트가 깨지면 `ProblemServiceTest` 의 스텁이 옛 메서드를 가리키고 있을 수 있으니 새 메서드에 맞춘다. `ProblemJpaMappingTest` 실패는 기존 플레이키이므로 무시한다.

- [ ] **Step 6: 실제 DB로 확인한다**

```bash
docker compose exec -T db sh -c 'mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" -D"$MARIADB_DATABASE" -N -B -e "select count(*) from (select p.id, count(c.id) n from problems p join problem_cells c on c.problem_id=p.id where p.level=\"WORD\" group by p.id having n > 12) t;"'
```

기대: `486`. 이 값이 출제에서 빠지는 문제 수다. 보고서에 적는다.

- [ ] **Step 7: 커밋**

```bash
git add apps/backend/src/main/java/com/itda/problem/ apps/backend/src/test/java/com/itda/problem/
git commit -m "fix(learning): AI가 받을 수 없는 긴 문제를 출제에서 제외한다"
```

커밋 본문에 쓸 내용: FastAPI 입력 상한이 12칸인데 단어 단계에 13칸 이상이 486개 있다. 그 문항은 정답을 넣어도 분석이 거부되고 세션을 완료할 수 없다. 상한을 FastAPI에 맞춘다.

---

### Task 2: `attempt-analysis` 계약을 스키마로 정의한다

**Files:**
- Modify: `front/packages/contracts/src/index.ts` (파일 끝에 추가)
- Modify: `front/apps/mobile/__tests__/learningContracts.test.ts`

**Interfaces:**
- Consumes: 기존 `cellSchema`(0~63), `identifierSchema`, `requestIdentifierSchema`, `cellCompletionSchema`
- Produces:
  - `attemptAnalysisRequestSchema`, `type AttemptAnalysisRequest`
  - `attemptAnalysisResponseSchema`, `type AttemptAnalysisResponse` — `{ problemSessionId, problemId, attemptId, correct, attemptedAt, cells, feedback }`
  - `type AttemptCellAnalysis` — `cells[]` 한 항목
  - `MAX_INPUT_CELLS = 12`
  - `completionResponseSchema` 에 `summary` 추가, `type SessionSummary`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`learningContracts.test.ts` 에 추가한다.

```ts
import {
  attemptAnalysisRequestSchema,
  attemptAnalysisResponseSchema,
  completionResponseSchema,
  MAX_INPUT_CELLS,
} from "@ieum/contracts";

const serverAttempt = {
  problemSessionId: 101,
  problemId: 7,
  attemptId: 55,
  correct: false,
  attemptedAt: "2026-09-25T02:00:00Z",
  cells: [
    {
      cellIndex: 1,
      expectedPattern: 5,
      inputPattern: 1,
      diffMask: 4,
      missingMask: 4,
      extraMask: 0,
      missingDots: [3],
      extraDots: [],
    },
  ],
  feedback: { tts: "첫소리 이응은 점자로 적지 않아요.", detail: "제13항" },
};

describe("문항별 분석 계약", () => {
  it("서버가 보내는 모양을 그대로 받는다", () => {
    const parsed = attemptAnalysisResponseSchema.parse(serverAttempt);
    expect(parsed.feedback.tts).toContain("이응");
    expect(parsed.cells[0].missingDots).toEqual([3]);
  });

  it("피드백이 없으면 거부한다", () => {
    // 낭독할 문장이 없으면 오답 화면이 아무 말도 못 한다 — 화면까지 흘려보내지 않는다
    const { feedback: _omit, ...rest } = serverAttempt;
    expect(() => attemptAnalysisResponseSchema.parse(rest)).toThrow();
  });

  it("요청은 12칸을 넘길 수 없다", () => {
    // FastAPI가 input_patterns를 12개까지만 받는다(router.py:121).
    // 여기서 막지 않으면 사용자는 이유를 알 수 없는 실패를 본다
    const thirteen = Array.from({ length: 13 }, () => 1);
    expect(() =>
      attemptAnalysisRequestSchema.parse({ problemSessionId: 101, inputPatterns: thirteen }),
    ).toThrow();
    expect(MAX_INPUT_CELLS).toBe(12);
  });

  it("요청 식별자로 0을 거부한다", () => {
    expect(() =>
      attemptAnalysisRequestSchema.parse({ problemSessionId: 0, inputPatterns: [1] }),
    ).toThrow();
  });

  it("완료 응답의 세션 요약을 읽는다", () => {
    const parsed = completionResponseSchema.parse({
      sessionId: 12,
      completedAt: "2026-09-25T02:00:00Z",
      totalProblemCount: 1,
      correctProblemCount: 0,
      problemResults: [],
      summary: {
        summary: "받침에서 자주 틀렸어요.",
        ttsText: "받침 규칙을 더 연습해요.",
        errorTypeCounts: { missing_dot: 3 },
        weakSkills: [],
      },
    });
    expect(parsed.ttsText).toContain("받침");
  });
});
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd front && pnpm --filter @ieum/mobile test learningContracts
```

기대: FAIL — `attemptAnalysisRequestSchema` 를 `@ieum/contracts` 에서 못 찾는다.

- [ ] **Step 3: 스키마를 쓴다**

`front/packages/contracts/src/index.ts` 끝에 추가한다. 기존 `cellCompletionSchema`(완료 응답의 셀)와 모양이 같으므로 **재사용한다** — 백엔드의 `CellAnalysisResponse` 와 `CellCompletionResponse` 가 같은 필드다.

```ts
/**
 * 한 문항에 보낼 수 있는 최대 칸 수.
 *
 * AI가 `input_patterns`를 12개까지만 받는다
 * (`apps/ai/app/presentation/api/backend/router.py`). 기대 칸 수와 무관하게 이 상한이
 * 걸리므로 — 5칸 문제에 13칸을 넣어도 거부된다 — 앱이 먼저 막는다.
 */
export const MAX_INPUT_CELLS = 12;

export const attemptAnalysisRequestSchema = z.object({
  problemSessionId: requestIdentifierSchema,
  inputPatterns: z.array(cellSchema).min(1).max(MAX_INPUT_CELLS),
});
export type AttemptAnalysisRequest = z.infer<typeof attemptAnalysisRequestSchema>;

/** 셀 하나의 진단. 완료 응답의 셀과 같은 모양이라 스키마를 공유한다 */
export const attemptCellAnalysisSchema = cellCompletionSchema;
export type AttemptCellAnalysis = z.infer<typeof attemptCellAnalysisSchema>;

export const attemptAnalysisResponseSchema = z.object({
  problemSessionId: identifierSchema,
  problemId: identifierSchema,
  attemptId: identifierSchema,
  correct: z.boolean(),
  attemptedAt: z.string(),
  cells: z.array(attemptCellAnalysisSchema),
  /** 서버가 AI로 만든 규칙 설명. 낭독할 문장이 없으면 오답 화면이 말할 것이 없다 */
  feedback: z.object({ tts: z.string(), detail: z.string() }),
});
export type AttemptAnalysisResponse = z.infer<typeof attemptAnalysisResponseSchema>;

/* 요약은 응답의 **최상위에 평평하게** 온다. 서비스 계층(`SessionSummaryResult`)은 중첩
 * 레코드지만 `LearningSessionCompletionResponse.from()` 이 풀어서 넣는다. 중첩 객체로 받으면
 * 모든 정상 응답이 파싱에 실패한다. */
```

`completionResponseSchema` 에 `summary` 를 더한다. **기존 필드는 건드리지 않는다.**

```ts
  summary: z.string().nullable(),
  ttsText: z.string().nullable(),
  errorTypeCounts: z.record(z.string(), z.number().int().nonnegative()),
  weakSkills: z.array(z.unknown()),
```

`optional()` 로 두는 이유: 이 필드는 최근에 생겼고, 서버가 요약을 만들지 못한 경우
(`SessionSummaryResult.notAvailable()`)에도 응답이 와야 한다. 받는 값에 엄격하면 서버의 정상
응답을 앱이 거부하는 더 나쁜 실패가 생긴다.

`weakSkills` 를 `z.unknown()` 으로 둔 이유: 앱이 이번에 그 내용을 쓰지 않는다. 모양을 지금
못 박으면 서버가 바꿀 때 앱이 먼저 깨진다. 쓰게 될 때 구체화한다.

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
cd front && pnpm --filter @ieum/mobile test learningContracts && pnpm -r typecheck && pnpm -r test
```

기대: 전부 PASS. 기존 125개도 계속 통과해야 한다.

- [ ] **Step 5: 커밋**

```bash
git add front/packages/contracts/src/index.ts front/apps/mobile/__tests__/learningContracts.test.ts
git commit -m "feat(learning): 문항별 분석 API 계약을 스키마로 정의한다"
```

---

### Task 3: 셀 진단을 점 수준 문장으로 옮긴다

서버는 규칙 수준 설명(`feedback.tts`)을 주지만 "몇 번 칸의 몇 번 점이 틀렸는지"는 말하지 않는다. 그 문장은 앱이 `cells[]` 로 만든다.

**Files:**
- Create: `front/apps/mobile/src/services/cellFeedback.ts`
- Create: `front/apps/mobile/__tests__/cellFeedback.test.ts`

**Interfaces:**
- Consumes: Task 2의 `AttemptCellAnalysis`
- Produces: `describeCells(cells: AttemptCellAnalysis[], correct: boolean, ruleText: string): { headline: string; detail: string; speech: string }`
  - `speech` 는 `headline` 과 `detail` 을 이어 붙인 것이다. 화면과 낭독이 같은 내용을 쓰도록 한 곳에서 만든다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`front/apps/mobile/__tests__/cellFeedback.test.ts` 를 새로 만든다.

```ts
import { describeCells } from "@/services/cellFeedback";
import type { AttemptCellAnalysis } from "@ieum/contracts";

function cell(
  cellIndex: number,
  expectedPattern: number,
  inputPattern: number,
  missingDots: number[],
  extraDots: number[],
): AttemptCellAnalysis {
  return {
    cellIndex,
    expectedPattern,
    inputPattern,
    diffMask: expectedPattern ^ inputPattern,
    missingMask: 0,
    extraMask: 0,
    missingDots,
    extraDots,
  };
}

describe("셀 진단을 점 수준 문장으로", () => {
  it("정답이면 규칙 설명만 붙인다", () => {
    const r = describeCells([cell(1, 5, 5, [], [])], true, "이 단어는 두 칸이에요.");
    expect(r.headline).toBe("정답이에요!");
    expect(r.speech).toContain("이 단어는 두 칸이에요.");
  });

  it("틀린 칸의 정답 점과 틀린 점을 모두 말한다", () => {
    // 1번 칸: 정답은 1,3번인데 1번만 눌러 3번이 빠졌다
    const r = describeCells([cell(1, 0b000101, 0b000001, [3], [])], false, "제13항");

    expect(r.speech).toContain("첫 번째 칸의 정답은 1번, 3번이에요");
    expect(r.speech).toContain("3번 점이 빠졌어요");
  });

  it("더 눌린 점도 말한다", () => {
    const r = describeCells([cell(1, 0b000001, 0b000101, [], [3])], false, "제13항");
    expect(r.speech).toContain("3번 점이 더 눌렸어요");
  });

  it("여러 칸이 틀리면 칸마다 말한다", () => {
    const r = describeCells(
      [cell(1, 0b000101, 0b000001, [3], []), cell(2, 0b000010, 0b000110, [], [3])],
      false,
      "제13항",
    );
    expect(r.speech).toContain("첫 번째 칸");
    expect(r.speech).toContain("두 번째 칸");
  });

  it("맞은 칸은 말하지 않는다", () => {
    const r = describeCells(
      [cell(1, 5, 5, [], []), cell(2, 0b000010, 0b000110, [], [3])],
      false,
      "제13항",
    );
    expect(r.speech).not.toContain("첫 번째 칸");
    expect(r.speech).toContain("두 번째 칸");
  });

  it("틀린 칸이 많으면 앞 세 칸만 자세히 말하고 나머지는 개수로 줄인다", () => {
    // 낭독이 지나치게 길어지면 사용자가 끝까지 듣지 못한다. 다만 몇 칸이 틀렸는지는 항상 말한다
    const cells = [1, 2, 3, 4, 5].map((i) => cell(i, 0b000101, 0b000001, [3], []));
    const r = describeCells(cells, false, "제13항");

    expect(r.speech).toContain("모두 5칸이 틀렸어요");
    expect(r.speech).toContain("세 번째 칸");
    expect(r.speech).not.toContain("네 번째 칸");
    expect(r.speech).toContain("그 외 2칸");
  });

  it("규칙 설명이 비어 있어도 문장이 만들어진다", () => {
    // 서버가 규칙을 못 찾는 경우가 있다. 그때도 점 수준 안내는 나와야 한다
    const r = describeCells([cell(1, 0b000101, 0b000001, [3], [])], false, "");
    expect(r.speech).toContain("첫 번째 칸");
    expect(r.speech.trim()).not.toBe("");
  });

  it("화면 문구와 낭독 문구가 같은 내용이다", () => {
    const r = describeCells([cell(1, 0b000101, 0b000001, [3], [])], false, "제13항");
    expect(r.speech).toBe(`${r.headline} ${r.detail}`.trim());
  });
});
```

- [ ] **Step 2: 실패를 확인한다**

```bash
cd front && pnpm --filter @ieum/mobile test cellFeedback
```

기대: FAIL — `cellFeedback` 모듈이 없다.

- [ ] **Step 3: 구현한다**

`front/apps/mobile/src/services/cellFeedback.ts` 를 만든다.

`ordinalKo`(첫 번째, 두 번째…)가 `@ieum/domain` 에 이미 있다. **재사용한다.** 배열 길이를 넘는 인덱스에서 어떻게 동작하는지 확인하고 쓴다.

정답 점 번호는 `expectedPattern` 에서 뽑는다. `cellToDots`(`@ieum/domain`)가 6비트 정수를 점 번호 배열로 바꾼다.

구조는 이렇게 한다.

```ts
/**
 * 서버의 셀 진단을 사용자가 들을 문장으로 옮긴다.
 *
 * 점 수준을 먼저, 규칙 설명을 뒤에 둔다 — 방금 틀린 것을 바로 고칠 수 있게 하고 이유는
 * 그 뒤에 설명하는 순서다 (설계 4.2절).
 *
 * `speech`는 `headline`과 `detail`을 이어 붙인 것이다. 화면과 낭독이 갈라지지 않도록
 * 한 곳에서 만든다 — 둘이 다르면 사용자가 어느 쪽을 믿어야 할지 알 수 없다.
 */
export function describeCells(
  cells: AttemptCellAnalysis[],
  correct: boolean,
  ruleText: string,
): { headline: string; detail: string; speech: string }
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
cd front && pnpm --filter @ieum/mobile test cellFeedback && pnpm -r typecheck
```

- [ ] **Step 5: 커밋**

```bash
git add front/apps/mobile/src/services/cellFeedback.ts front/apps/mobile/__tests__/cellFeedback.test.ts
git commit -m "feat(learning): 셀 진단을 점 수준 낭독 문장으로 옮긴다"
```

---

### Task 4: 문항 제출 오케스트레이션

재시도·`ALREADY_ANALYZED`·시도 소진을 화면 밖의 순수 모듈로 뺀다. 화면 파일은 AsyncStorage 체인 때문에 테스트에서 import할 수 없다 — `learningSessionCache.ts` 가 같은 방식의 전례다.

**Files:**
- Create: `front/apps/mobile/src/services/attemptSubmission.ts`
- Create: `front/apps/mobile/__tests__/attemptSubmission.test.ts`
- Modify: `front/apps/mobile/src/services/learningService.ts`

**Interfaces:**
- Consumes: Task 2의 `AttemptAnalysisResponse`, 기존 `describeAuthError`(`@/services/authErrors`), 기존 `api`(`./apiClient`)
- Produces:
  - `learningService.analyzeAttempt(sessionId: number, problemSessionId: number, inputPatterns: number[]): Promise<AttemptAnalysisResponse>`
  - `submitAttempt(params): Promise<AttemptOutcome>` where
    `AttemptOutcome = { kind: "analyzed"; result: AttemptAnalysisResponse } | { kind: "failed"; message: string; attempt: number } | { kind: "exhausted"; message: string }`
  - `MAX_ATTEMPT_TRIES = 3`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`front/apps/mobile/__tests__/attemptSubmission.test.ts` 를 새로 만든다.

```ts
import { submitAttempt, MAX_ATTEMPT_TRIES } from "@/services/attemptSubmission";
import { ApiError } from "@ieum/api-client";

const analysis = {
  problemSessionId: 101,
  problemId: 7,
  attemptId: 55,
  correct: true,
  attemptedAt: "2026-09-25T02:00:00Z",
  cells: [],
  feedback: { tts: "정답이에요.", detail: "" },
};

describe("문항 제출", () => {
  it("성공하면 분석 결과를 돌려준다", async () => {
    const analyze = jest.fn(async () => analysis);
    const outcome = await submitAttempt({
      analyze,
      sessionId: 12,
      problemSessionId: 101,
      inputPatterns: [1],
      attempt: 1,
    });

    expect(outcome).toEqual({ kind: "analyzed", result: analysis });
    expect(analyze).toHaveBeenCalledTimes(1);
  });

  it("이미 분석된 문항은 오류로 보지 않는다", async () => {
    // 앱이 응답을 못 받았지만 서버는 성공한 경우다. 오류로 다루면 이미 끝난 문항에서 막힌다
    const analyze = jest.fn(async () => {
      throw new ApiError(409, "LEARNING_PROBLEM_ALREADY_ANALYZED", "이미 분석했어요");
    });

    const outcome = await submitAttempt({
      analyze,
      sessionId: 12,
      problemSessionId: 101,
      inputPatterns: [1],
      attempt: 1,
    });

    expect(outcome.kind).toBe("already");
  });

  it("실패하면 시도 횟수를 담은 문구를 돌려준다", async () => {
    // a11y/speech.ts 가 같은 문구를 1.5초 안에 중복 제거한다. 시도마다 문구가 달라야
    // 재시도 실패가 묵음이 되지 않는다
    const analyze = jest.fn(async () => {
      throw new ApiError(500, "INTERNAL_SERVER_ERROR", "일시적인 오류");
    });

    const first = await submitAttempt({
      analyze, sessionId: 12, problemSessionId: 101, inputPatterns: [1], attempt: 1,
    });
    const second = await submitAttempt({
      analyze, sessionId: 12, problemSessionId: 101, inputPatterns: [1], attempt: 2,
    });

    expect(first.kind).toBe("failed");
    expect(second.kind).toBe("failed");
    expect(first).not.toEqual(second);
  });

  it("시도를 소진하면 다시 시작하라고 알린다", async () => {
    // 12칸 초과 문제처럼 영구 실패가 실재한다. 무한 재시도는 사용자를 가둔다
    const analyze = jest.fn(async () => {
      throw new ApiError(500, "INTERNAL_SERVER_ERROR", "일시적인 오류");
    });

    const outcome = await submitAttempt({
      analyze,
      sessionId: 12,
      problemSessionId: 101,
      inputPatterns: [1],
      attempt: MAX_ATTEMPT_TRIES,
    });

    expect(outcome.kind).toBe("exhausted");
    expect(outcome.message).toContain("처음부터");
  });
});
```

`ApiError` 의 실제 생성자 시그니처를 `front/packages/api-client/src/errors.ts` 에서 확인하고 맞춘다.

- [ ] **Step 2: 실패를 확인한다**

```bash
cd front && pnpm --filter @ieum/mobile test attemptSubmission
```

기대: FAIL — 모듈이 없다.

- [ ] **Step 3: `learningService` 에 호출을 더한다**

```ts
  /** 문항 하나를 제출해 서버 채점과 규칙 분석을 받는다 */
  async analyzeAttempt(
    sessionId: number,
    problemSessionId: number,
    inputPatterns: number[],
  ): Promise<AttemptAnalysisResponse> {
    const body = attemptAnalysisRequestSchema.parse({ problemSessionId, inputPatterns });
    return attemptAnalysisResponseSchema.parse(
      await api.post(`/learning-sessions/${sessionId}/attempt-analysis`, body),
    );
  },
```

요청도 `parse` 하는 이유: 12칸 상한을 네트워크에 나가기 전에 막는다.

- [ ] **Step 4: 오케스트레이션을 구현한다**

`attemptSubmission.ts` 를 만든다. `analyze` 를 인자로 받는 이유는 테스트에서 네트워크 없이 부르기 위해서다(`learningSessionSubmission.ts` 가 같은 방식이다).

`AttemptOutcome` 의 갈래는 넷이다: `analyzed` · `already` · `failed` · `exhausted`.

`already` 를 별도로 둔 이유: 화면이 이것을 오류로 다루면 안 된다. 다만 **서버가 분석 결과를 돌려주지 않으므로** 그 문항의 피드백은 만들 수 없다. 화면은 "이미 확인한 문제예요"라고 알리고 다음으로 넘어가게 한다.

- [ ] **Step 5: 테스트가 통과하는지 본다**

```bash
cd front && pnpm --filter @ieum/mobile test && pnpm -r typecheck
```

- [ ] **Step 6: 커밋**

```bash
git add front/apps/mobile/src/services/attemptSubmission.ts front/apps/mobile/src/services/learningService.ts front/apps/mobile/__tests__/attemptSubmission.test.ts
git commit -m "feat(learning): 문항 제출 오케스트레이션을 분리한다"
```

---

### Task 5: 스토어가 문항별 분석 결과를 보관한다

**Files:**
- Modify: `front/apps/mobile/src/stores/sessionStore.ts`
- Modify: `front/apps/mobile/__tests__/sessionStore.test.ts`

**Interfaces:**
- Consumes: Task 2의 `AttemptAnalysisResponse`
- Produces: `lastAnalysis: AttemptAnalysisResponse | null`, `setLastAnalysis(result: AttemptAnalysisResponse | null): void`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`sessionStore.test.ts` 에 추가한다.

```ts
  it("문항 분석 결과를 보관하고 다음 문항에서 비운다", () => {
    // 이전 문항의 피드백이 남아 있으면 다음 문항에서 엉뚱한 설명을 읽는다
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().setLastAnalysis(analysis);
    expect(useSessionStore.getState().lastAnalysis).toEqual(analysis);

    useSessionStore.getState().next();
    expect(useSessionStore.getState().lastAnalysis).toBeNull();
  });

  it("reset이 분석 결과도 지운다", () => {
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().setLastAnalysis(analysis);

    useSessionStore.getState().reset();

    expect(useSessionStore.getState().lastAnalysis).toBeNull();
  });
```

`analysis` 픽스처는 Task 4의 테스트에 있는 것과 같은 모양으로 파일 위쪽에 둔다.

- [ ] **Step 2: 실패를 확인한다**

```bash
cd front && pnpm --filter @ieum/mobile test sessionStore
```

- [ ] **Step 3: 스토어를 고친다**

`lastAnalysis` 와 `setLastAnalysis` 를 더한다. **`next()`·`start()`·`reset()` 에서 모두 비운다** — 하나라도 빠지면 이전 문항의 피드백이 다음 문항에 새어 나온다.

기존 `lastResult`(로컬 `GradeResult`)는 **그대로 둔다.** 실시간 수업이 쓴다.

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
cd front && pnpm --filter @ieum/mobile test && pnpm -r typecheck
```

- [ ] **Step 5: 커밋**

```bash
git add front/apps/mobile/src/stores/sessionStore.ts front/apps/mobile/__tests__/sessionStore.test.ts
git commit -m "feat(learning): 세션 스토어가 문항별 분석 결과를 보관한다"
```

---

### Task 6: `play` 가 제출하고 기다린다

**Files:**
- Modify: `front/apps/mobile/src/app/session/play.tsx`
- Modify: `front/apps/mobile/src/services/answerLengthGuard.ts` (또는 삭제)
- Modify: `front/apps/mobile/__tests__/answerLengthGuard.test.ts`

**Interfaces:**
- Consumes: Task 4의 `submitAttempt`·`MAX_ATTEMPT_TRIES`, Task 5의 `setLastAnalysis`, 기존 `learningService.analyzeAttempt`
- Produces: 없음

- [ ] **Step 1: 입력 가드를 바꾼다**

지금 `answerLengthGuard.ts` 의 `describeTooManyCells(cellCount, expectedLength)` 는 **기대 칸 수 초과**를 막는다. 그 제약이 사라졌다 — dev가 `validateExpectedCells` 에서 길이 동등 검사를 뺐고, 칸 수가 다른 답은 이제 오답으로 채점된다.

대신 **12칸 상한**을 막는다. 기대 칸 수와 무관하다.

```ts
/**
 * 한 문항에 보낼 수 있는 칸 수를 넘었는지 본다.
 *
 * 기대 칸 수를 넘는 것은 막지 않는다 — 서버가 오답으로 채점한다. 막는 것은 AI의 입력
 * 상한(12칸)뿐이다. 5칸 문제에 13칸을 넣어도 거부되므로 기대 칸 수와 무관한 제약이다.
 */
export function describeTooManyCells(cellCount: number): string | null
```

함수 이름을 바꿀지, 파일을 새로 만들지는 판단해서 정한다. **기존 테스트가 기대 칸 수 기준으로 쓰여 있으므로 함께 고친다** — 계약이 바뀐 것이지 테스트를 통과시키려 지우는 것이 아니다. 무엇을 왜 바꿨는지 보고서에 적는다.

- [ ] **Step 2: 제출 흐름을 바꾼다**

지금은 `submit(cells)` 후 바로 `router.replace("/session/grade")` 로 간다. 이제 **화면을 떠나지 않고 기다린다.**

```
제출 →  submit(cells)로 로컬 기록(수업 경로와 correctCount가 쓴다)
     →  "확인하고 있어요" 낭독 + 화면 표시, 입력·제출 잠금
     →  submitAttempt(...)
        · analyzed  → setLastAnalysis(result), grade 로
        · already   → "이미 확인한 문제예요" 안내 후 grade 로
        · failed    → 이 화면에 머물며 재시도 버튼, 시도 횟수 +1
        · exhausted → "처음부터 다시 해주세요" + 홈으로
```

**목 세션(`serverSessionId === null`)은 지금처럼 바로 `grade` 로 간다.** 실시간 수업과 목 데이터 경로가 깨지면 안 된다.

대기·실패 문구는 **화면에도 표시**하고 `accessibilityLiveRegion="polite"` 를 붙인다. 이 화면의 기존 `blockedReason` 표시가 같은 방식이니 그 자리를 재사용한다.

실패 시 `hapticError()` 를 부른다.

- [ ] **Step 3: 검증**

```bash
cd front && pnpm -r typecheck && pnpm -r test
```

화면 파일은 테스트에서 import할 수 없다. **판정 로직이 순수 함수로 표현되면 빼서 덮고, 아니면 무엇을 테스트하지 못했는지 보고서에 적는다.** 억지로 만들지 않는다.

- [ ] **Step 4: 커밋**

```bash
git add front/apps/mobile/src/app/session/play.tsx front/apps/mobile/src/services/answerLengthGuard.ts front/apps/mobile/__tests__/answerLengthGuard.test.ts
git commit -m "feat(learning): 문항을 풀 때마다 서버 분석을 받는다"
```

---

### Task 7: `grade` 가 서버 분석을 읽는다

**Files:**
- Modify: `front/apps/mobile/src/app/session/grade.tsx`

**Interfaces:**
- Consumes: Task 3의 `describeCells`, Task 5의 `lastAnalysis`
- Produces: 없음

- [ ] **Step 1: 두 경로를 가른다**

`lastAnalysis` 가 있으면 서버 분석을 쓰고, 없으면 지금처럼 로컬 `describeDiff` 를 쓴다.

```ts
const analysis = useSessionStore((s) => s.lastAnalysis);
const local = useSessionStore((s) => s.lastResult);

const feedback = analysis
  ? describeCells(analysis.cells, analysis.correct, analysis.feedback.tts)
  : local
    ? /* 기존 describeDiff 경로 */
    : null;
```

**로컬 경로를 지우지 않는다.** 실시간 수업(`kind === "class"`)은 서버 세션이 없어 `lastAnalysis` 가 항상 `null` 이다. 지금 동작이 그대로여야 한다.

정오 판정도 `analysis?.correct ?? local?.correct` 로 서버를 우선한다.

- [ ] **Step 2: 정답 셀 표시를 맞춘다**

지금 `grade.tsx:93-107` 이 `result.firstDiff` 로 정답/내 입력 점자 셀 두 개를 그린다. 서버 경로에서는 `analysis.cells` 에서 **첫 번째 틀린 칸**을 찾아 같은 자리에 쓴다. 여러 칸을 다 그리면 화면이 넘친다.

- [ ] **Step 3: 낭독을 맞춘다**

`useEffect` 에서 `feedback.speech` 를 낭독한다. 화면 문구와 같은 문자열이어야 한다(Task 3이 그렇게 만든다).

정답이면 `hapticSuccess()`, 오답이면 `hapticError()` — 지금 동작 그대로다.

- [ ] **Step 4: 검증**

```bash
cd front && pnpm -r typecheck && pnpm -r test && cd .. && docker build --target test front
```

- [ ] **Step 5: 커밋**

```bash
git add front/apps/mobile/src/app/session/grade.tsx
git commit -m "feat(learning): 오답 화면이 서버 분석으로 칸마다 설명한다"
```

---

### Task 8: `summary` 가 세션 요약을 보여준다

**Files:**
- Modify: `front/apps/mobile/src/app/session/summary.tsx`

**Interfaces:**
- Consumes: Task 2가 `completionResponseSchema` 최상위에 더한 `summary`·`ttsText`·`errorTypeCounts`·`weakSkills`, 기존 `serverResult`
- Produces: 없음

- [ ] **Step 1: 요약을 표시하고 낭독한다**

`serverResult?.ttsText` 가 있으면 (최상위 필드다 — 중첩이 아니다) 낭독하고 화면에도 표시한다. 없으면 지금 문구를 그대로 쓴다 — 서버가 요약을 못 만든 경우(`notAvailable()`)가 있다.

`summary.summary`(화면용 설명)도 있으면 표시한다. `weakSkills` 는 이번에 쓰지 않는다(설계 7절).

정답 수는 지금처럼 `correctProblemCount`·`totalProblemCount` 를 쓴다.

- [ ] **Step 2: 검증**

```bash
cd front && pnpm -r typecheck && pnpm -r test
```

- [ ] **Step 3: 커밋**

```bash
git add front/apps/mobile/src/app/session/summary.tsx
git commit -m "feat(learning): 결과 화면이 서버 세션 요약을 들려준다"
```

---

### Task 9: 실기기로 한 바퀴를 확인한다

코드가 아니라 확인이다. 앞의 모든 테스트가 통과해도 이 단계에서 처음 드러나는 것들이 있다 — 이 브랜치에서 이미 두 번 그랬다(칸 수 불일치로 세션 전체 실패, `idempotency_key` 잔재).

**Files:** 없음 (설계 문서 갱신만)

- [ ] **Step 1: 환경을 올린다**

```bash
docker compose up -d --build backend ai
adb reverse tcp:8080 tcp:8080 && adb reverse tcp:8081 tcp:8081
```

`front/apps/mobile` 에서 Metro를 띄운다. `adb reverse` 는 USB를 뽑을 때마다 사라지므로 다시 건다.

- [ ] **Step 2: 한 바퀴를 돈다**

**단어 단계**로 시작한다. 자음·모음은 1칸 문제가 섞여 있어 이 작업이 바꾼 것이 드러나지 않는다.

1. 12문항을 풀어 **결과 화면까지 도달한다.** 지금은 422로 막힌다 — 이것이 뚫리는지가 이 작업의 핵심이다.
2. 여러 칸을 틀린 문항에서 **칸마다 점 번호가 낭독되고 이어서 규칙 설명이 낭독된다.**
3. 기대보다 **적은 칸**, **많은 칸**을 넣어도 세션이 끝까지 간다.
4. 결과 화면에서 세션 요약이 들린다.

백엔드 로그로 대조한다.

```bash
docker compose logs backend --since 10m | grep http_request | tail -20
```

`POST /{id}/attempt-analysis` 가 문항마다 200이고, 마지막 `POST /{id}/completion` 이 **200** 이어야 한다.

- [ ] **Step 3: 실패 경로를 본다**

문항을 푼 뒤 기기를 비행기 모드로 바꾸고 제출한다. 확인할 것:

1. `grade` 로 **넘어가지 않는다**.
2. "확인하지 못했어요"류 문구가 화면에 보이고 낭독된다.
3. 비행기 모드를 끄고 재시도하면 통과한다.
4. 세 번 실패시키면 "처음부터 다시 해주세요"와 홈으로 버튼이 나온다.

- [ ] **Step 4: 긴 문제가 안 나오는지 본다**

세션을 몇 번 만들어 배정된 문항의 글자 수를 본다. "특정경제범죄가중처벌법상" 같은 긴 기관명이 나오면 Task 1이 동작하지 않은 것이다.

```bash
docker compose exec -T db sh -c 'mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" -D"$MARIADB_DATABASE" -N -B -e "select p.target_text, count(c.id) n from learning_sessions s join problem_sessions ps on ps.session_id=s.id join problems p on p.id=ps.problem_id join problem_cells c on c.problem_id=p.id where s.id=(select max(id) from learning_sessions) group by p.id, p.target_text order by n desc limit 5;"'
```

기대: 가장 긴 것도 12 이하.

- [ ] **Step 5: 설계 문서의 완료 기준을 대조한다**

`docs/superpowers/specs/2026-09-25-attempt-analysis-integration-design.md` 9절을 열어 항목마다 확인한 값을 적는다. **확인하지 못한 항목은 확인하지 못했다고 적는다.**

- [ ] **Step 6: 커밋**

```bash
git add docs/superpowers/specs/2026-09-25-attempt-analysis-integration-design.md
git commit -m "docs(learning): 문항별 분석 연동 검증 결과를 기록한다"
```
