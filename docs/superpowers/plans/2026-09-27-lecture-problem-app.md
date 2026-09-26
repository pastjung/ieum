# 실시간 수업 문제 출제·답안 — 학습자 앱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수업이 시작되면 풀이 화면이 "선생님 문제를 기다리는 중이에요" 대기 상태로 시작하고, 강사가 출제하면(방 metadata `currentProblemId`) 서버의 현재 문제로 풀이를 시작하며, 제출은 서버(`POST …/answers`)가 채점한 결과를 채점 화면에서 읽어 준 뒤 다시 대기로 돌아간다.

**Architecture:** LiveKit 방은 지금처럼 `liveLecture.ts`가 소유하고, 방 metadata의 `currentProblemId`가 **바뀔 때만** 새 핸들러 `onCurrentProblemChanged(problemId | null)`을 부른다(세대 규칙 유지, 입장 시점 값은 알리지 않고 기준값으로만 둔다). 문제 상태는 순수 상태 기계(`lectureProblemFlow.ts` — `waiting`/`solving`/`submitted`/`graded`)가 정하고, 서비스 모듈 `lectureProblemSync.ts`가 `GET /lectures/{id}` 조회·`POST …/answers` 제출을 순번(세대)으로 보호해 zustand 스토어 `lectureProblemStore`에 쓰고 전환 안내를 낭독한다. 화면(풀이·채점)은 스토어를 구독해 그리고 이동만 한다 — 대기 화면이 등록한 `liveLecture` 핸들러가 화면을 옮겨도 살아 있으므로(음성 기능과 같은 구조) 채점 화면에 있는 동안 온 새 문제도 같은 경로로 처리된다. 수업 풀이의 로컬 문제 세트(`useSessionStore` + `classService.getClassSession` 목)는 걷어낸다.

**Tech Stack:** Expo 57 (CNG), React Native 0.86.3, expo-router, zustand 5, zod 3, `livekit-client@2.22.3`, `@livekit/react-native@3.0.0`, jest-expo, pnpm 워크스페이스(`node-linker=hoisted`)

**Spec:** `docs/superpowers/specs/2026-09-27-lecture-problem-delivery-design.md` §2, §5, §8 / 교차 계약 `C:/Users/SSAFY/Desktop/S15P21B201/.superpowers/sdd/problem-contract.md` (읽기 전용 — 이 파일은 git에 넣지 않는다)

## Global Constraints

- 브랜치: `feat/lecture-problem-app` (`feat/lecture-problem`에서 분기), 작업 폴더 `C:/Users/SSAFY/Desktop/wt-problem-app`, MR 대상 `feat/lecture-problem`. **push하지 않는다** (MR은 사람이 올린다)
- 작업 위치: `front/apps/mobile`, 그리고 앱이 쓰는 공유 패키지 `front/packages/contracts`(학습자 스키마만)·`front/packages/api-client`(요청별 헤더 옵션 **추가만** — 웹이 같은 패키지를 쓰므로 기존 동작을 바꾸지 않는다). backend·web은 건드리지 않는다. 요청과 무관한 파일을 고치지 않는다
- 커밋: `type(lecture): 한글 제목` + 빈 줄 + 정확히 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `git add`는 **경로를 하나하나 지정**한다(`git add .`/`-A` 금지) — 추적되지 않는 `.superpowers/`·`seed_remaining.sql`·`total_init.sql`을 절대 넣지 않는다. `--no-verify`, `push --force` 금지
- `.env*` 파일은 읽지도 고치지도 않는다. 이 작업은 환경변수를 추가하지 않는다
- 프로세스를 끌 때는 PID로만 끈다(`taskkill /PID <pid> /F`). 이름으로 일괄 종료 금지
- **의존성을 추가하지 않는다.** UUID v4는 `expo`가 이미 끌어오는 `expo-modules-core`의 `uuid.v4()`(네이티브 `globalThis.expo.uuidv4`, Android `CoreModule.kt`)를 쓴다. `expo-crypto`·`uuid`·`react-native-get-random-values`는 설치돼 있지 않거나(앞의 둘) RN에서 난수원이 없다. `expo-modules-core`는 이미 `src/services/itdaVoiceEngine.ts`가 import하고 있고 hoisted 링커라 해석된다
- 네이티브 의존성·`app.json`을 바꾸지 않는다 → 개발 빌드 재생성(prebuild) 불필요
- 검증: `cd front && pnpm --filter @ieum/mobile typecheck`, `pnpm --filter @ieum/mobile exec jest` 전체 통과, 공유 패키지를 고쳤으므로 `pnpm --filter @ieum/web build`도 통과. MR 전 `docker build --target test front`. 실패 상태로 완료를 선언하지 않는다
- 계약(verbatim, problem-contract.md):
  - 모든 ID는 JSON·경로에서 10진수 문자열. Base `/api/v1`. 오류 body `{"code","message"}`
  - 학습자 `GET /lectures/{lectureId}`: `currentProblem` = `{problemId, problemType, targetText, displayText, cellCount, submitted}` | null (**expectedPattern 없음**), `myResult` = LiveAnswer | null (현재 문제를 제출했을 때만)
  - `POST /lectures/{lectureId}/problems/{problemId}/answers` header `Idempotency-Key: <UUID v4>` body `{"cells":[8],"durationMs":1900}` → 201 새로 저장 / 200 같은 cells 이미 저장 → LiveAnswer `{ "attemptId","lectureId","problemId","isCorrect","score","attemptedAt","cells":[AnswerCell] }`
  - AnswerCell `{ "cellIndex": 1, "inputPattern": 8, "expectedPattern": 8, "diffMask": 0, "isCorrect": true }` — cellIndex 1부터, 칸 수 = max(입력, 정답), 없는 쪽은 0, diffMask = input XOR expected
  - 제출 오류: 409 `LECTURE_PROBLEM_ALREADY_ANSWERED`(다른 cells 이미 저장), 409 `LECTURE_PROBLEM_NOT_CURRENT`, 409 `INVALID_LECTURE_STATE`, 403 `LEARNER_ACCESS_REQUIRED`, 403 `LECTURE_PARTICIPATION_REQUIRED`, 422 `INVALID_ANSWER_CELLS`(빈 배열, 12칸 초과, 0..63 밖), 400 키 없음·형식 오류 또는 durationMs < 0. 키와 durationMs는 서버에 저장되지 않는다
  - 방 metadata(JSON 문자열) `{"status":"OPEN","currentProblemId":"26578"}` — 문제가 없으면 `currentProblemId` 생략/null. 클라이언트는 try/catch로 파싱하고 `status` 규칙(`isLectureOpen`)은 그대로
  - 앱은 `RoomEvent.RoomMetadataChanged (metadata)`를 듣는다
- 설치본에서 확인한 API (livekit-client 2.22.3 `dist/src/room/events.d.ts:195`, `Room.d.ts:339`):
  - `RoomEvent.RoomMetadataChanged = "roomMetadataChanged"`, `RoomEventCallbacks.roomMetadataChanged: (metadata: string) => void`
  - `Room.metadata: string | undefined` — 접속 뒤 최신 값. `liveLecture.join`이 이미 `isLectureOpen(room.metadata)`로 읽고 있다
  - `expo-modules-core/src/index.ts:13` `export { default as uuid } from './uuid'` — `uuid.v4(): string`. jest-expo는 `globalThis.expo.uuidv4`를 채우지 않으므로 테스트는 `@/services/idempotencyKey`를 목으로 바꾼다
- 접근성(시각장애 학습자, `docs/conventions/code/mobile.md`): 모든 버튼에 라벨·역할(`AppButton`이 부여), 결과가 자명하지 않으면 힌트. 상태 변화는 `@/a11y`의 `announce`/`speak`/`hapticSuccess`/`hapticError`로만 알린다(컴포넌트·서비스에서 `expo-speech` 직접 호출 금지). 색만으로 정오를 전하지 않는다. 손들기·마이크 버튼은 풀이·대기 어느 상태에서도 **같은 자리**에 그대로 둔다
- 문구(정확히 이대로):
  - 대기: `선생님 문제를 기다리는 중이에요`
  - 출제: `선생님 문제예요. '{displayText}'을 입력하세요` (설계서 §5 그대로 — 받침 유무와 관계없이 `을`)
  - 제출한 문제 대기: `제출했어요. 다음 문제를 기다려요`
  - 문제 바뀜(409 NOT_CURRENT): `문제가 바뀌었어요`
  - 제출 실패(네트워크 등, 같은 키로 재시도): `제출하지 못했어요. 다시 길게 눌러 주세요.`
  - 수업 닫힘(409 INVALID_LECTURE_STATE): `수업이 끝나 제출하지 못했어요.`

## File Structure

| 파일 | 책임 |
| --- | --- |
| `front/packages/contracts/src/index.ts` (수정) | `answerCellSchema`, `liveAnswerSchema`, `liveAnswerRequestSchema`, `learnerCurrentProblemSchema`, 학습자 스냅샷의 `currentProblem`·`myResult` |
| `front/packages/api-client/src/types.ts`, `client.ts` (수정) | `RequestOptions.headers` — 요청별 헤더(Idempotency-Key). 인증·플랫폼 헤더는 덮지 못한다 |
| `front/apps/mobile/src/services/classService.ts` (수정) | `getLectureProblem`, `submitLiveAnswer` 추가, 목 `getClassSession` 제거 |
| `front/apps/mobile/src/services/lectureSignals.ts` (수정) | `currentProblemIdFrom(metadata)` 순수 함수 |
| `front/apps/mobile/src/services/liveLecture.ts` (수정) | `onCurrentProblemChanged` 핸들러, 값이 바뀔 때만·살아 있는 세대만 |
| `front/apps/mobile/src/services/lectureProblemFlow.ts` (신규) | 문제 상태 기계와 안내 문구 (순수) |
| `front/apps/mobile/src/services/idempotencyKey.ts` (신규) | `newIdempotencyKey()` — UUID v4 |
| `front/apps/mobile/src/stores/lectureProblemStore.ts` (신규) | 화면용 상태: `lectureId`·`active`·`phase`·`results` |
| `front/apps/mobile/src/services/lectureProblemSync.ts` (신규) | 조회·제출·채점 확인 오케스트레이션, 순번 보호, 전환 낭독 |
| `front/apps/mobile/src/services/liveGradeFeedback.ts` (신규) | 서버 LiveAnswer → 채점 화면 단계(`GradeFeedback`) (순수, `describeCells` 재사용) |
| `front/apps/mobile/src/app/class/waiting.tsx` (수정) | `begin`/`stop`, `onCurrentProblemChanged` → 조회, 시작 시 목 문제 대신 풀이 화면으로 |
| `front/apps/mobile/src/app/class/play.tsx` (수정) | 대기·풀이 두 상태, 서버 문제 표시, 서버 제출, 채점 화면 이동 |
| `front/apps/mobile/src/app/class/grade.tsx` (신규) | 수업 채점 화면 — 서버 결과 낭독, 확인 → 대기, 새 문제 → 풀이 |
| `front/apps/mobile/src/app/class/end.tsx`, `disconnected.tsx` (수정) | 세션 스토어 대신 문제 스토어(집계·정리) |
| `front/apps/mobile/__tests__/` `lectureContracts`, `apiClient`, `classService`, `lectureSignals`, `liveLecture` (수정), `lectureProblemFlow`, `lectureProblemSync`, `liveGradeFeedback` (신규) | 테스트 |

`src/app/session/grade.tsx`의 `kind === "class"` 분기는 수업이 더 이상 세션 스토어를 쓰지 않아 닿지 않는 코드가 되지만, 자기주도 학습 화면이라 이 작업에서 고치지 않는다(MR 본문에 후속으로 적는다).

---

### Task 0: 작업 폴더 준비

**Files:** 없음

- [ ] **Step 1: 워크트리와 브랜치 만들기**

```bash
cd C:/Users/SSAFY/Desktop/wt-lecture-problem
git status --short          # 상위 워크트리 상태 확인만 한다 (여기서 커밋하지 않는다)
git worktree add -b feat/lecture-problem-app C:/Users/SSAFY/Desktop/wt-problem-app feat/lecture-problem
cd C:/Users/SSAFY/Desktop/wt-problem-app
git log --oneline -1        # feat/lecture-problem의 최신 커밋과 같아야 한다
```

이 계획 문서가 `feat/lecture-problem`에 아직 커밋돼 있지 않으면 원본 경로(`C:/Users/SSAFY/Desktop/wt-lecture-problem/docs/superpowers/plans/2026-09-27-lecture-problem-app.md`)를 읽기만 하고, 이 브랜치에 복사·커밋하지 않는다.

- [ ] **Step 2: 의존성 설치와 기준선 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-problem-app/front
pnpm install --frozen-lockfile
pnpm --filter @ieum/mobile typecheck
pnpm --filter @ieum/mobile exec jest
```

Expected: 타입 오류 없음, 전체 PASS (기준선). 실패하면 작업을 시작하기 전에 원인을 보고한다. `pnpm install`이 lockfile 변경을 요구하면 멈추고 보고한다(이 작업은 의존성을 바꾸지 않는다).

---

### Task 1: 계약 스키마, 요청별 헤더, 수업 문제 API 호출

**Files:**
- Modify: `front/packages/contracts/src/index.ts` (`learnerLectureSchema` 정의 바로 위에 스키마 추가, `learnerLectureSchema`의 `currentProblem` 줄 교체와 `myResult` 추가)
- Modify: `front/packages/api-client/src/types.ts` (`RequestOptions`), `front/packages/api-client/src/client.ts` (`buildHeaders`)
- Modify: `front/apps/mobile/src/services/classService.ts` (import, 두 메서드 추가)
- Test: `front/apps/mobile/__tests__/lectureContracts.test.ts`, `apiClient.test.ts`, `classService.test.ts` (각 파일 끝에 추가)

**Interfaces:**
- Produces:
  - `@ieum/contracts`: `answerCellSchema`/`AnswerCell`, `liveAnswerSchema`/`LiveAnswer`, `liveAnswerRequestSchema`/`LiveAnswerRequest`, `learnerCurrentProblemSchema`/`LearnerCurrentProblem`; `LearnerLecture.currentProblem: LearnerCurrentProblem | null`, `LearnerLecture.myResult: LiveAnswer | null`
  - `@ieum/api-client`: `RequestOptions.headers?: Readonly<Record<string, string>>`
  - `classService.getLectureProblem(lectureId: string): Promise<LectureProblemSnapshot>` — `interface LectureProblemSnapshot { status: LectureStatus; currentProblem: LearnerCurrentProblem | null; myResult: LiveAnswer | null }`
  - `classService.submitLiveAnswer(lectureId: string, problemId: string, answer: LiveAnswerRequest, idempotencyKey: string): Promise<LiveAnswer>`

- [ ] **Step 1: 실패하는 테스트 작성 — 계약**

`__tests__/lectureContracts.test.ts` 첫 줄을 바꾼다:

```ts
import { learnerLectureSchema, liveAnswerRequestSchema, liveAnswerSchema, mediaTokenSchema } from "@ieum/contracts";
```

파일 끝에 추가:

```ts
const currentProblem = {
  problemId: "26578",
  problemType: "CONSONANT",
  targetText: "ㄱ",
  displayText: "초성 ㄱ",
  cellCount: 1,
  submitted: false,
};

const liveAnswer = {
  attemptId: "900",
  lectureId: "42",
  problemId: "26578",
  isCorrect: false,
  score: 0,
  attemptedAt: "2026-09-27T01:00:00.000Z",
  cells: [{ cellIndex: 1, inputPattern: 0, expectedPattern: 8, diffMask: 8, isCorrect: false }],
};

describe("수업 문제 계약", () => {
  it("현재 문제와 내 결과가 있는 학습자 스냅샷을 파싱한다", () => {
    const parsed = learnerLectureSchema.parse({ ...learner, status: "OPEN", currentProblem, myResult: liveAnswer });
    expect(parsed.currentProblem?.displayText).toBe("초성 ㄱ");
    expect(parsed.currentProblem?.submitted).toBe(false);
    expect(parsed.myResult?.cells[0]?.diffMask).toBe(8);
  });

  it("myResult가 없는 응답은 null로 채운다 (API 브랜치가 합쳐지기 전 서버와도 붙는다)", () => {
    const parsed = learnerLectureSchema.parse(learner);
    expect(parsed.currentProblem).toBeNull();
    expect(parsed.myResult).toBeNull();
  });

  it("학습자 현재 문제에 정답 점형이 섞여 와도 앱으로 넘기지 않는다", () => {
    const parsed = learnerLectureSchema.parse({ ...learner, currentProblem: { ...currentProblem, expectedPattern: [8] } });
    expect(parsed.currentProblem).not.toBeNull();
    expect("expectedPattern" in parsed.currentProblem!).toBe(false);
  });

  it("답안 칸 값이 6비트를 벗어나면 거부한다", () => {
    expect(() =>
      liveAnswerSchema.parse({ ...liveAnswer, cells: [{ ...liveAnswer.cells[0], inputPattern: 64 }] }),
    ).toThrow();
  });

  it("제출 요청은 1~12칸, 칸 값 0~63, durationMs 0 이상이다", () => {
    expect(liveAnswerRequestSchema.parse({ cells: [8], durationMs: 0 })).toEqual({ cells: [8], durationMs: 0 });
    expect(() => liveAnswerRequestSchema.parse({ cells: [], durationMs: 0 })).toThrow();
    expect(() => liveAnswerRequestSchema.parse({ cells: Array(13).fill(1), durationMs: 0 })).toThrow();
    expect(() => liveAnswerRequestSchema.parse({ cells: [64], durationMs: 0 })).toThrow();
    expect(() => liveAnswerRequestSchema.parse({ cells: [8], durationMs: -1 })).toThrow();
  });
});
```

- [ ] **Step 2: 실패하는 테스트 작성 — 요청별 헤더**

`__tests__/apiClient.test.ts` 파일 끝에 추가:

```ts
describe("api-client 요청별 헤더", () => {
  function clientWith(fetchFn: jest.Mock) {
    const { store } = memoryTokenStore("access-1", "refresh-1");
    return createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });
  }

  it("요청별 헤더를 인증·플랫폼 헤더와 함께 보낸다 (Idempotency-Key)", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(201, { ok: true }));

    await clientWith(fetchFn).post(
      "/lectures/42/problems/7/answers",
      { cells: [8], durationMs: 0 },
      { headers: { "Idempotency-Key": "k-1" } },
    );

    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers["Idempotency-Key"]).toBe("k-1");
    expect(headers.Authorization).toBe("Bearer access-1");
    expect(headers["X-Client-Platform"]).toBe("MOBILE");
    expect(headers["Content-Type"]).toBe("application/json");
  });

  it("요청별 헤더가 인증·플랫폼 헤더를 덮지 못한다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(200, {}));

    await clientWith(fetchFn).get("/users/me", {
      headers: { Authorization: "Bearer other", "X-Client-Platform": "WEB" },
    });

    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer access-1");
    expect(headers["X-Client-Platform"]).toBe("MOBILE");
  });
});
```

- [ ] **Step 3: 실패하는 테스트 작성 — classService**

`__tests__/classService.test.ts`의 `describe("classService", …)` 블록 끝(마지막 `it` 뒤, 닫는 `});` 앞)에 추가:

```ts
  it("현재 문제와 내 결과를 조회한다", async () => {
    const currentProblem = {
      problemId: "26578",
      problemType: "CONSONANT",
      targetText: "ㄱ",
      displayText: "초성 ㄱ",
      cellCount: 1,
      submitted: false,
    };
    mockedApi.get.mockResolvedValue({ ...snapshot("OPEN"), currentProblem, myResult: null });

    const state = await classService.getLectureProblem("42");

    expect(mockedApi.get).toHaveBeenCalledWith("/lectures/42");
    expect(state).toEqual({ status: "OPEN", currentProblem, myResult: null });
  });

  it("답안을 멱등 키와 함께 제출하고 서버 채점 결과를 돌려준다", async () => {
    const answer = {
      attemptId: "900",
      lectureId: "42",
      problemId: "26578",
      isCorrect: true,
      score: 100,
      attemptedAt: "2026-09-27T01:00:00.000Z",
      cells: [{ cellIndex: 1, inputPattern: 8, expectedPattern: 8, diffMask: 0, isCorrect: true }],
    };
    mockedApi.post.mockResolvedValue(answer);

    const result = await classService.submitLiveAnswer("42", "26578", { cells: [8], durationMs: 1900 }, "key-1");

    expect(mockedApi.post).toHaveBeenCalledWith(
      "/lectures/42/problems/26578/answers",
      { cells: [8], durationMs: 1900 },
      { headers: { "Idempotency-Key": "key-1" } },
    );
    expect(result).toEqual(answer);
  });

  it("계약을 벗어난 답안은 보내지 않는다", async () => {
    await expect(
      classService.submitLiveAnswer("42", "26578", { cells: [], durationMs: 0 }, "key-1"),
    ).rejects.toThrow();
    expect(mockedApi.post).not.toHaveBeenCalled();
  });
```

- [ ] **Step 4: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureContracts.test.ts __tests__/apiClient.test.ts __tests__/classService.test.ts`
Expected: FAIL — `liveAnswerSchema` undefined, `Idempotency-Key` undefined, `classService.getLectureProblem is not a function` (타입 오류로 실패해도 된다)

- [ ] **Step 5: 구현 — 계약**

`front/packages/contracts/src/index.ts`에서 `/** 참여 학습자용 수업 Snapshot. …` 주석 바로 위에 추가:

```ts
/** LIVE 답안 칸 하나의 서버 채점. cellIndex는 1부터, 칸 수는 max(입력, 정답)이고 없는 쪽은 0이다 */
export const answerCellSchema = z.object({
  cellIndex: z.number().int().positive(),
  inputPattern: cellSchema,
  expectedPattern: cellSchema,
  diffMask: cellSchema,
  isCorrect: z.boolean(),
});
export type AnswerCell = z.infer<typeof answerCellSchema>;

/** 수업 답안 제출 결과 (POST /lectures/{id}/problems/{problemId}/answers, 학습자 스냅샷의 myResult) */
export const liveAnswerSchema = z.object({
  attemptId: z.string(),
  lectureId: z.string(),
  problemId: z.string(),
  isCorrect: z.boolean(),
  score: z.number().int().min(0).max(100),
  attemptedAt: z.string(),
  cells: z.array(answerCellSchema),
});
export type LiveAnswer = z.infer<typeof liveAnswerSchema>;

/** 수업 답안 제출 body. 서버가 422로 막는 범위(빈 배열·12칸 초과·0..63 밖)를 앱이 먼저 막는다 */
export const liveAnswerRequestSchema = z.object({
  cells: z.array(cellSchema).min(1).max(MAX_INPUT_CELLS),
  durationMs: z.number().int().nonnegative(),
});
export type LiveAnswerRequest = z.infer<typeof liveAnswerRequestSchema>;

/**
 * 학습자에게 보이는 현재 문제. 제출 전에는 정답 점형을 주지 않는다(명세 8.2) — 섞여 와도 zod가 버린다.
 * problemType은 표시·분기에 쓰지 않으므로 문자열로 받는다 — 서버가 level을 늘려도 스냅샷 전체가 깨지지 않게.
 */
export const learnerCurrentProblemSchema = z.object({
  problemId: z.string(),
  problemType: z.string(),
  targetText: z.string(),
  displayText: z.string(),
  cellCount: z.number().int().positive(),
  submitted: z.boolean(),
});
export type LearnerCurrentProblem = z.infer<typeof learnerCurrentProblemSchema>;
```

`learnerLectureSchema` 안의 줄

```ts
  currentProblem: z.unknown().nullable(),
```

을 다음 두 줄로 바꾼다:

```ts
  currentProblem: learnerCurrentProblemSchema.nullable(),
  /** 현재 문제를 제출했을 때만 온다. 필드가 없던 서버와도 붙도록 없으면 null */
  myResult: liveAnswerSchema.nullable().default(null),
```

- [ ] **Step 6: 구현 — 요청별 헤더**

`front/packages/api-client/src/types.ts`의 `RequestOptions`에 필드를 추가한다:

```ts
export interface RequestOptions {
  body?: unknown;
  /** 기본 true. false면 Authorization을 붙이지 않고 401 자동 갱신도 하지 않는다 */
  auth?: boolean;
  /** 요청별 헤더(예: Idempotency-Key). 플랫폼·Content-Type·Authorization은 덮지 못한다 */
  headers?: Readonly<Record<string, string>>;
}
```

`front/packages/api-client/src/client.ts`의 `buildHeaders` 첫 문장을 바꾼다:

```ts
    const headers: Record<string, string> = {
      ...options.headers,
      "X-Client-Platform": config.platform,
    };
```

(이후의 Content-Type·Authorization 대입이 뒤에서 덮으므로 요청별 헤더가 이들을 바꾸지 못한다. 401 재시도는 같은 `options`로 다시 보내므로 멱등 키도 그대로 실린다.)

- [ ] **Step 7: 구현 — classService**

`src/services/classService.ts`의 contracts import를 바꾼다:

```ts
import {
  learnerLectureSchema,
  liveAnswerRequestSchema,
  liveAnswerSchema,
  mediaTokenSchema,
  type LearnerCurrentProblem,
  type LearnerLecture,
  type LearningSession,
  type LectureStatus,
  type LiveAnswer,
  type LiveAnswerRequest,
  type MediaToken,
} from "@ieum/contracts";
```

`ClassInfo` 인터페이스 바로 아래에 추가:

```ts
/** 문제 상태 조회 결과 — 화면이 대기·풀이·제출 대기를 정하는 데 쓴다 */
export interface LectureProblemSnapshot {
  status: LectureStatus;
  currentProblem: LearnerCurrentProblem | null;
  myResult: LiveAnswer | null;
}
```

`issueMediaToken` 메서드 바로 뒤에 추가:

```ts
  /** 현재 문제와 내 결과. 출제 신호(방 metadata)·입장·재접속 때 부른다. 정답 점형은 오지 않는다 */
  async getLectureProblem(lectureId: string): Promise<LectureProblemSnapshot> {
    const lecture = learnerLectureSchema.parse(await api.get<unknown>(`/lectures/${lectureId}`));
    return { status: lecture.status, currentProblem: lecture.currentProblem, myResult: lecture.myResult };
  },

  /**
   * 현재 문제의 답안을 낸다. 서버가 채점해 결과를 돌려준다.
   * idempotencyKey는 문제마다 새로 만들고, 같은 문제를 다시 보낼 때(응답 유실·재시도)는 같은 값을 쓴다.
   */
  async submitLiveAnswer(
    lectureId: string,
    problemId: string,
    answer: LiveAnswerRequest,
    idempotencyKey: string,
  ): Promise<LiveAnswer> {
    const body = liveAnswerRequestSchema.parse(answer);
    const payload = await api.post<unknown>(`/lectures/${lectureId}/problems/${problemId}/answers`, body, {
      headers: { "Idempotency-Key": idempotencyKey },
    });
    return liveAnswerSchema.parse(payload);
  },
```

- [ ] **Step 8: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureContracts.test.ts __tests__/apiClient.test.ts __tests__/classService.test.ts && pnpm --filter @ieum/mobile typecheck && pnpm --filter @ieum/web build`
Expected: PASS (기존 테스트 포함), 타입 오류 없음, 웹 빌드 성공(공유 패키지 변경이 웹을 깨지 않음)

- [ ] **Step 9: Commit**

```bash
git add front/packages/contracts/src/index.ts front/packages/api-client/src/types.ts front/packages/api-client/src/client.ts front/apps/mobile/src/services/classService.ts front/apps/mobile/__tests__/lectureContracts.test.ts front/apps/mobile/__tests__/apiClient.test.ts front/apps/mobile/__tests__/classService.test.ts
git commit -m "feat(lecture): 수업 현재 문제 조회와 답안 제출 계약을 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 방 metadata의 현재 문제 감지

**Files:**
- Modify: `front/apps/mobile/src/services/lectureSignals.ts` (`isLectureOpen` 바로 아래에 함수 추가)
- Modify: `front/apps/mobile/src/services/liveLecture.ts` (import, `LiveLectureHandlers`, `Session`, 새 함수, metadata 리스너, 세션 초기값, 접속 직후 기준값)
- Modify: `front/apps/mobile/src/app/class/waiting.tsx` (타입을 맞추려고 핸들러만 추가 — 조회 연결은 Task 4)
- Test: `front/apps/mobile/__tests__/lectureSignals.test.ts`, `front/apps/mobile/__tests__/liveLecture.test.ts`

**Interfaces:**
- Produces:
  - `currentProblemIdFrom(metadata: string | undefined | null): string | null` — JSON이 아니거나 값이 빈 문자열·문자열이 아니면 null
  - `LiveLectureHandlers.onCurrentProblemChanged: (problemId: string | null) => void` — **필수** 필드. 살아 있는 세션에서 metadata의 현재 문제가 **바뀔 때만** 부른다. 접속 시점의 값은 알리지 않는다(화면이 입장·재접속 때 직접 조회한다)

- [ ] **Step 1: 실패하는 테스트 작성 — 해석 함수**

`__tests__/lectureSignals.test.ts` 첫 import에 `currentProblemIdFrom`을 넣는다:

```ts
import {
  canSpeak,
  classifyDisconnect,
  currentProblemIdFrom,
  handFromAttributes,
  isLectureOpen,
  isUserBackAction,
  MICROPHONE_SOURCE,
  ROOM_DELETED_REASON,
} from "@/services/lectureSignals";
```

파일 끝에 추가:

```ts
describe("currentProblemIdFrom", () => {
  it("metadata의 현재 문제 ID를 읽는다", () => {
    expect(currentProblemIdFrom('{"status":"OPEN","currentProblemId":"26578"}')).toBe("26578");
  });

  it("현재 문제가 없거나 null이면 null이다", () => {
    expect(currentProblemIdFrom('{"status":"OPEN"}')).toBeNull();
    expect(currentProblemIdFrom('{"status":"OPEN","currentProblemId":null}')).toBeNull();
    expect(currentProblemIdFrom('{"status":"OPEN","currentProblemId":""}')).toBeNull();
  });

  it("문자열이 아닌 ID·JSON이 아닌 값·빈 값은 null이다", () => {
    expect(currentProblemIdFrom('{"currentProblemId":26578}')).toBeNull();
    expect(currentProblemIdFrom("not-json")).toBeNull();
    expect(currentProblemIdFrom('"26578"')).toBeNull();
    expect(currentProblemIdFrom("")).toBeNull();
    expect(currentProblemIdFrom(undefined)).toBeNull();
    expect(currentProblemIdFrom(null)).toBeNull();
  });
});
```

- [ ] **Step 2: 실패하는 테스트 작성 — liveLecture**

`__tests__/liveLecture.test.ts`의 `handlers()`를 바꾼다:

```ts
function handlers() {
  return { onOpen: jest.fn(), onExit: jest.fn(), onSpeakingChanged: jest.fn(), onCurrentProblemChanged: jest.fn() };
}
```

파일 끝에 추가:

```ts
describe("liveLecture 현재 문제", () => {
  const withProblem = (id: string) => JSON.stringify({ status: "OPEN", currentProblemId: id });

  it("방 metadata의 현재 문제가 바뀔 때만 알린다", async () => {
    const { h, room } = await joinOpen();

    room.emit("roomMetadataChanged", withProblem("26578"));
    room.emit("roomMetadataChanged", withProblem("26578")); // 같은 값의 재기록(강사 재시도)은 되풀이하지 않는다

    expect(h.onCurrentProblemChanged).toHaveBeenCalledTimes(1);
    expect(h.onCurrentProblemChanged).toHaveBeenCalledWith("26578");

    room.emit("roomMetadataChanged", withProblem("26579"));

    expect(h.onCurrentProblemChanged).toHaveBeenCalledTimes(2);
    expect(h.onCurrentProblemChanged).toHaveBeenLastCalledWith("26579");
  });

  it("현재 문제가 사라지면 null로 알린다", async () => {
    const { h, room } = await joinOpen();
    room.emit("roomMetadataChanged", withProblem("7"));

    room.emit("roomMetadataChanged", '{"status":"OPEN"}');

    expect(h.onCurrentProblemChanged).toHaveBeenLastCalledWith(null);
  });

  it("입장할 때 이미 있던 현재 문제는 알리지 않고 기준값으로 둔다", async () => {
    mockConnectReached = () => {
      mockRooms[0]!.metadata = withProblem("7");
    };
    const { h, room } = await joinOpen();

    expect(h.onCurrentProblemChanged).not.toHaveBeenCalled();

    room.emit("roomMetadataChanged", withProblem("7"));
    expect(h.onCurrentProblemChanged).not.toHaveBeenCalled();

    room.emit("roomMetadataChanged", withProblem("8"));
    expect(h.onCurrentProblemChanged).toHaveBeenCalledWith("8");
  });

  it("JSON이 아닌 metadata는 문제 없음으로 보고, 시작 판정도 그대로 둔다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const h = handlers();
    await liveLecture.join("42", h);
    const room = mockRooms[0]!;

    room.emit("roomMetadataChanged", "not-json");
    expect(h.onCurrentProblemChanged).not.toHaveBeenCalled();
    expect(h.onOpen).not.toHaveBeenCalled();

    room.emit("roomMetadataChanged", withProblem("3"));
    expect(h.onOpen).toHaveBeenCalledTimes(1);
    expect(h.onCurrentProblemChanged).toHaveBeenCalledWith("3");
  });

  it("다시 입장하면 이전 방의 metadata 변경은 누구에게도 알리지 않는다", async () => {
    const { h: first, room: oldRoom } = await joinOpen();
    // discardSilently의 removeAllListeners()가 목 방의 리스너도 지우므로, 그 부수효과가 아니라
    // 코드 안의 isLive 가드가 막는지 보려고 해제 전에 리스너를 붙잡아 두었다가 직접 부른다
    const staleListener = oldRoom.listeners.get("roomMetadataChanged")![0]!;
    const second = handlers();
    await liveLecture.join("42", second);

    staleListener(withProblem("99"));

    expect(first.onCurrentProblemChanged).not.toHaveBeenCalled();
    expect(second.onCurrentProblemChanged).not.toHaveBeenCalled();
  });

  it("나간 뒤 늦게 닿은 metadata 변경은 알리지 않는다", async () => {
    const { h, room } = await joinOpen();
    const staleListener = room.listeners.get("roomMetadataChanged")![0]!;

    await liveLecture.leave();
    staleListener(withProblem("99"));

    expect(h.onCurrentProblemChanged).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 3: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureSignals.test.ts __tests__/liveLecture.test.ts`
Expected: FAIL — `currentProblemIdFrom is not a function`, `onCurrentProblemChanged` 미호출

- [ ] **Step 4: 구현 — 해석 함수**

`src/services/lectureSignals.ts`에서 `isLectureOpen` 함수 바로 아래에 추가:

```ts
/**
 * 방 metadata의 현재 문제 ID. 강사가 출제하면 서버가 {"status":"OPEN","currentProblemId":"26578"}로 쓴다.
 * 없거나 null·빈 문자열·문자열이 아니면 null — ID는 계약상 10진수 문자열이다.
 * 이 값은 "다시 조회하라"는 신호일 뿐이다. 문제 내용은 GET /lectures/{id}로 받는다.
 */
export function currentProblemIdFrom(metadata: string | undefined | null): string | null {
  if (!metadata) return null;
  try {
    const parsed: unknown = JSON.parse(metadata);
    if (typeof parsed !== "object" || parsed === null) return null;
    const id = (parsed as { currentProblemId?: unknown }).currentProblemId;
    return typeof id === "string" && id !== "" ? id : null;
  } catch {
    return null;
  }
}
```

- [ ] **Step 5: 구현 — liveLecture**

`src/services/liveLecture.ts`:

import 줄을 바꾼다:

```ts
import {
  canSpeak,
  classifyDisconnect,
  currentProblemIdFrom,
  handFromAttributes,
  isLectureOpen,
  type RoomExit,
} from "./lectureSignals";
```

`LiveLectureHandlers`에 필드를 추가한다(`onSpeakingChanged` 아래):

```ts
  /** 강사가 출제해 방 metadata의 현재 문제가 바뀌었다(없어지면 null). 값이 바뀔 때만 부른다 — 화면을 옮겨도 불린다 */
  onCurrentProblemChanged: (problemId: string | null) => void;
```

`Session`에 필드를 추가한다(`micBusy` 아래):

```ts
  /** 마지막으로 본 metadata의 현재 문제 — 같은 값의 재기록을 걸러낸다 */
  currentProblemId: string | null;
```

`applySpeaking` 함수 바로 아래에 추가:

```ts
/** metadata의 현재 문제가 실제로 바뀔 때만 알린다. 낡은 세대·퇴장한 세션은 알리지 않는다 */
function applyCurrentProblem(current: Session, problemId: string | null): void {
  if (!isLive(current) || current.currentProblemId === problemId) return;
  current.currentProblemId = problemId;
  current.handlers.onCurrentProblemChanged(problemId);
}
```

`join()`의 세션 객체 리터럴에 초기값을 추가한다(`micBusy: false,` 아래):

```ts
      currentProblemId: null,
```

metadata 리스너를 바꾼다:

```ts
    room.on(RoomEvent.RoomMetadataChanged, (metadata: string) => {
      if (isLectureOpen(metadata)) markOpen(current);
      applyCurrentProblem(current, currentProblemIdFrom(metadata));
    });
```

접속 뒤 `applySpeaking(current, canSpeak(room.localParticipant.permissions));` 줄 바로 앞에 추가:

```ts
    // 접속 시점의 현재 문제는 알리지 않고 기준값으로만 둔다 — 화면이 입장·재접속 때 스냅샷을 직접 조회한다.
    // 이후 값이 달라질 때만 onCurrentProblemChanged가 불린다
    current.currentProblemId = currentProblemIdFrom(room.metadata);
```

- [ ] **Step 6: 구현 — 대기 화면 타입 맞추기**

`src/app/class/waiting.tsx`의 `liveLecture.join(validLectureId, { … })` 객체에서 `onSpeakingChanged` 핸들러 바로 뒤에 추가:

```tsx
        // 조회·풀이 전환은 Task 4에서 lectureProblemSync.refresh()로 잇는다
        onCurrentProblemChanged: () => undefined,
```

- [ ] **Step 7: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureSignals.test.ts __tests__/liveLecture.test.ts && pnpm --filter @ieum/mobile typecheck`
Expected: PASS (기존 발언·세대 테스트 포함), 타입 오류 없음

- [ ] **Step 8: Commit**

```bash
git add front/apps/mobile/src/services/lectureSignals.ts front/apps/mobile/src/services/liveLecture.ts front/apps/mobile/src/app/class/waiting.tsx front/apps/mobile/__tests__/lectureSignals.test.ts front/apps/mobile/__tests__/liveLecture.test.ts
git commit -m "feat(lecture): 방 metadata로 강사의 출제를 감지한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 문제 상태 기계, 스토어, 조회·제출 오케스트레이션

**Files:**
- Create: `front/apps/mobile/src/services/lectureProblemFlow.ts`
- Create: `front/apps/mobile/src/services/idempotencyKey.ts`
- Create: `front/apps/mobile/src/stores/lectureProblemStore.ts`
- Create: `front/apps/mobile/src/services/lectureProblemSync.ts`
- Test: `front/apps/mobile/__tests__/lectureProblemFlow.test.ts` (신규), `front/apps/mobile/__tests__/lectureProblemSync.test.ts` (신규)

**Interfaces:**
- Consumes: `classService.getLectureProblem`, `classService.submitLiveAnswer`, `LearnerCurrentProblem`, `LiveAnswer` (Task 1)
- Produces:
  - 문구 상수 `WAITING_MESSAGE`, `SUBMITTED_WAITING_MESSAGE`, `PROBLEM_CHANGED_MESSAGE`, `SUBMIT_FAILED_MESSAGE`, `SUBMIT_CLOSED_MESSAGE`, `problemAnnouncement(displayText: string): string`
  - `type LectureProblem = Omit<LearnerCurrentProblem, "submitted">`
  - `type ProblemPhase = { kind: "waiting" } | { kind: "solving"; problem: LectureProblem; idempotencyKey: string; startedAt: number } | { kind: "submitted"; problemId: string } | { kind: "graded"; problem: LectureProblem; result: LiveAnswer }`
  - `interface Transition { phase: ProblemPhase; announcement: string | null }`, `interface FlowDeps { newKey: () => string; now: () => number }`
  - `applySnapshot(phase: ProblemPhase | null, currentProblem: LearnerCurrentProblem | null, deps: FlowDeps): Transition`
  - `acceptResult(phase: ProblemPhase | null, result: LiveAnswer): Transition | null` (null = 낡은 결과)
  - `acknowledgeGrade(phase: ProblemPhase | null): Transition | null`
  - `waitingText(phase: ProblemPhase | null): string`, `tallyResults(results: Readonly<Record<string, boolean>>): { total: number; correct: number }`
  - `newIdempotencyKey(): string`
  - `useLectureProblemStore` — `{ lectureId: string | null; active: boolean; phase: ProblemPhase | null; results: Record<string, boolean>; set(patch); reset() }`. `lectureProblemSync`만 쓴다. `phase === null`은 "아직 조회 전"
  - `lectureProblemSync.begin(lectureId: string): void`, `.stop(): void`, `.reset(): void`, `.refresh(): Promise<void>`, `.submit(cells: readonly Cell[]): Promise<SubmitOutcome>`, `.acknowledgeGrade(): void`; `type SubmitOutcome = "graded" | "changed" | "closed" | "failed" | "ignored"`

상태 규칙(설계서 §5):
- 첫 조회에서 문제가 없으면 `waiting` + "선생님 문제를 기다리는 중이에요". 이미 `waiting`이면 다시 말하지 않는다
- 제출 안 한 새 문제 → `solving`(새 UUID v4 키, 시작 시각) + "선생님 문제예요. '{displayText}'을 입력하세요". 채점 화면(`graded`)에서도 같다(안내 후 풀이로 전환)
- 같은 문제를 다시 조회하면 `solving`을 그대로 둔다 — 키가 유지되므로 재시도는 같은 키
- `submitted=true`인 현재 문제(재접속 등) → `submitted` + "제출했어요. 다음 문제를 기다려요"
- 한 번 제출한 문제(`submitted`·`graded`)는 늦게 온 스냅샷으로 되돌리지 않는다. 채점 화면은 같은 문제의 스냅샷이 와도 유지한다
- 제출 성공 → `graded`(낭독은 채점 화면이 한다). 채점 확인 → `submitted` + "제출했어요. 다음 문제를 기다려요"
- 조회는 순번으로 보호한다 — 겹치면 마지막 요청만, `stop()`/`reset()`/다른 수업 `begin()` 뒤에 온 응답과 **제출을 시작하기 전에 떠난 조회 응답**은 버린다

- [ ] **Step 1: 실패하는 테스트 작성 — 상태 기계**

`__tests__/lectureProblemFlow.test.ts`:

```ts
import type { LearnerCurrentProblem, LiveAnswer } from "@ieum/contracts";
import {
  acceptResult,
  acknowledgeGrade,
  applySnapshot,
  problemAnnouncement,
  SUBMITTED_WAITING_MESSAGE,
  tallyResults,
  WAITING_MESSAGE,
  waitingText,
  type LectureProblem,
  type ProblemPhase,
} from "@/services/lectureProblemFlow";

const deps = { newKey: jest.fn(() => "key-new"), now: jest.fn(() => 1000) };

function problemOf(id: string): LectureProblem {
  return { problemId: id, problemType: "CONSONANT", targetText: "ㄱ", displayText: "초성 ㄱ", cellCount: 1 };
}

function current(id: string, submitted = false): LearnerCurrentProblem {
  return { ...problemOf(id), submitted };
}

function answer(id: string, isCorrect = true): LiveAnswer {
  return {
    attemptId: "900",
    lectureId: "42",
    problemId: id,
    isCorrect,
    score: isCorrect ? 100 : 0,
    attemptedAt: "2026-09-27T01:00:00.000Z",
    cells: [{ cellIndex: 1, inputPattern: 8, expectedPattern: 8, diffMask: 0, isCorrect: true }],
  };
}

function solving(id: string, key = "key-old"): ProblemPhase {
  return { kind: "solving", problem: problemOf(id), idempotencyKey: key, startedAt: 0 };
}

function graded(id: string): ProblemPhase {
  return { kind: "graded", problem: problemOf(id), result: answer(id) };
}

beforeEach(() => jest.clearAllMocks());

describe("problemAnnouncement", () => {
  it("설계서 문구 그대로 문제를 읽는다", () => {
    expect(problemAnnouncement("초성 ㄱ")).toBe("선생님 문제예요. '초성 ㄱ'을 입력하세요");
  });
});

describe("applySnapshot", () => {
  it("처음 조회에서 문제가 없으면 대기로 가고 기다린다고 알린다", () => {
    expect(applySnapshot(null, null, deps)).toEqual({ phase: { kind: "waiting" }, announcement: WAITING_MESSAGE });
  });

  it("이미 대기 중이면 다시 알리지 않는다", () => {
    const phase: ProblemPhase = { kind: "waiting" };
    expect(applySnapshot(phase, null, deps)).toEqual({ phase, announcement: null });
  });

  it("새 문제가 오면 새 멱등 키와 시작 시각으로 풀이를 시작하고 문제를 읽는다", () => {
    const t = applySnapshot({ kind: "waiting" }, current("7"), deps);

    expect(t.phase).toEqual({ kind: "solving", problem: problemOf("7"), idempotencyKey: "key-new", startedAt: 1000 });
    expect(t.announcement).toBe("선생님 문제예요. '초성 ㄱ'을 입력하세요");
  });

  it("같은 문제를 다시 조회하면 키와 시작 시각을 그대로 둔다 (재시도는 같은 키)", () => {
    const phase = solving("7", "key-old");

    const t = applySnapshot(phase, current("7"), deps);

    expect(t.phase).toBe(phase);
    expect(t.announcement).toBeNull();
    expect(deps.newKey).not.toHaveBeenCalled();
  });

  it("풀던 중 다른 문제가 오면 새 키로 바꾼다", () => {
    const t = applySnapshot(solving("7", "key-old"), current("8"), deps);

    expect(t.phase).toMatchObject({ kind: "solving", problem: { problemId: "8" }, idempotencyKey: "key-new" });
  });

  it("채점 화면에 있는 동안 새 문제가 오면 풀이로 바꾸고 알린다", () => {
    const t = applySnapshot(graded("7"), current("8"), deps);

    expect(t.phase).toMatchObject({ kind: "solving", problem: { problemId: "8" } });
    expect(t.announcement).toBe(problemAnnouncement("초성 ㄱ"));
  });

  it("이미 제출한 현재 문제(재접속)는 제출 대기로 두고 알린다", () => {
    expect(applySnapshot(null, current("7", true), deps)).toEqual({
      phase: { kind: "submitted", problemId: "7" },
      announcement: SUBMITTED_WAITING_MESSAGE,
    });
  });

  it("채점 결과를 보는 중에 같은 문제가 제출됨으로 와도 채점 화면을 유지한다", () => {
    const phase = graded("7");
    expect(applySnapshot(phase, current("7", true), deps)).toEqual({ phase, announcement: null });
  });

  it("한 번 제출한 문제는 늦게 온 미제출 스냅샷으로 되돌리지 않는다", () => {
    const phase: ProblemPhase = { kind: "submitted", problemId: "7" };
    expect(applySnapshot(phase, current("7", false), deps)).toEqual({ phase, announcement: null });
    const shown = graded("7");
    expect(applySnapshot(shown, current("7", false), deps).phase).toBe(shown);
  });

  it("채점 화면에서 문제가 없다는 스냅샷이 와도 결과를 지우지 않는다", () => {
    const phase = graded("7");
    expect(applySnapshot(phase, null, deps).phase).toBe(phase);
  });
});

describe("acceptResult", () => {
  it("풀던 문제의 결과면 채점 상태로 바꾼다 (낭독은 채점 화면이 한다)", () => {
    const result = answer("7", false);
    expect(acceptResult(solving("7"), result)).toEqual({
      phase: { kind: "graded", problem: problemOf("7"), result },
      announcement: null,
    });
  });

  it("그 사이 다른 문제로 바뀌었거나 풀이 상태가 아니면 null이다", () => {
    expect(acceptResult(solving("8"), answer("7"))).toBeNull();
    expect(acceptResult({ kind: "waiting" }, answer("7"))).toBeNull();
    expect(acceptResult(null, answer("7"))).toBeNull();
  });
});

describe("acknowledgeGrade", () => {
  it("채점을 확인하면 제출 대기로 돌아가고 알린다", () => {
    expect(acknowledgeGrade(graded("7"))).toEqual({
      phase: { kind: "submitted", problemId: "7" },
      announcement: SUBMITTED_WAITING_MESSAGE,
    });
  });

  it("채점 상태가 아니면 null이다", () => {
    expect(acknowledgeGrade(solving("7"))).toBeNull();
    expect(acknowledgeGrade(null)).toBeNull();
  });
});

describe("waitingText", () => {
  it("제출 대기면 제출 문구, 그 밖에는 대기 문구다", () => {
    expect(waitingText({ kind: "submitted", problemId: "7" })).toBe(SUBMITTED_WAITING_MESSAGE);
    expect(waitingText({ kind: "waiting" })).toBe(WAITING_MESSAGE);
    expect(waitingText(null)).toBe(WAITING_MESSAGE);
  });
});

describe("tallyResults", () => {
  it("낸 문제 수와 맞힌 수를 센다", () => {
    expect(tallyResults({})).toEqual({ total: 0, correct: 0 });
    expect(tallyResults({ "7": true, "8": false, "9": true })).toEqual({ total: 3, correct: 2 });
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureProblemFlow.test.ts`
Expected: FAIL — `Cannot find module '@/services/lectureProblemFlow'`

- [ ] **Step 3: 구현 — 상태 기계**

`src/services/lectureProblemFlow.ts`:

```ts
import type { LearnerCurrentProblem, LiveAnswer } from "@ieum/contracts";

/** 수업 문제 상태와 안내 문구 — 화면·네트워크 없이 테스트하려고 순수 함수로 둔다 (설계서 §5) */

export const WAITING_MESSAGE = "선생님 문제를 기다리는 중이에요";
export const SUBMITTED_WAITING_MESSAGE = "제출했어요. 다음 문제를 기다려요";
export const PROBLEM_CHANGED_MESSAGE = "문제가 바뀌었어요";
export const SUBMIT_FAILED_MESSAGE = "제출하지 못했어요. 다시 길게 눌러 주세요.";
export const SUBMIT_CLOSED_MESSAGE = "수업이 끝나 제출하지 못했어요.";

export function problemAnnouncement(displayText: string): string {
  return `선생님 문제예요. '${displayText}'을 입력하세요`;
}

export type LectureProblem = Omit<LearnerCurrentProblem, "submitted">;

export type ProblemPhase =
  /** 출제 전 */
  | { kind: "waiting" }
  /** 풀이 중. 멱등 키는 문제마다 새로 만들고 재시도 때 그대로 쓴다 */
  | { kind: "solving"; problem: LectureProblem; idempotencyKey: string; startedAt: number }
  /** 현재 문제를 이미 냈다 — 다음 출제를 기다린다 */
  | { kind: "submitted"; problemId: string }
  /** 방금 낸 답의 서버 채점 결과를 보고 있다 */
  | { kind: "graded"; problem: LectureProblem; result: LiveAnswer };

export interface Transition {
  phase: ProblemPhase;
  /** 전환하며 읽을 문장. 없으면 null */
  announcement: string | null;
}

export interface FlowDeps {
  newKey: () => string;
  now: () => number;
}

function answeredProblemId(phase: ProblemPhase | null): string | null {
  if (phase?.kind === "submitted") return phase.problemId;
  if (phase?.kind === "graded") return phase.problem.problemId;
  return null;
}

/**
 * 조회한 현재 문제로 다음 상태를 정한다. phase가 null이면 아직 한 번도 조회하지 않은 것이다.
 * 한 번 낸 문제는 서버가 되돌릴 수 없으므로, 늦게 온 스냅샷이 "미제출"이라 해도 되돌리지 않는다.
 */
export function applySnapshot(
  phase: ProblemPhase | null,
  currentProblem: LearnerCurrentProblem | null,
  deps: FlowDeps,
): Transition {
  if (currentProblem === null) {
    if (phase !== null && (phase.kind === "waiting" || phase.kind === "graded")) return { phase, announcement: null };
    return { phase: { kind: "waiting" }, announcement: WAITING_MESSAGE };
  }

  if (phase !== null && answeredProblemId(phase) === currentProblem.problemId) return { phase, announcement: null };

  if (currentProblem.submitted) {
    return { phase: { kind: "submitted", problemId: currentProblem.problemId }, announcement: SUBMITTED_WAITING_MESSAGE };
  }

  if (phase?.kind === "solving" && phase.problem.problemId === currentProblem.problemId) {
    return { phase, announcement: null };
  }

  const problem: LectureProblem = {
    problemId: currentProblem.problemId,
    problemType: currentProblem.problemType,
    targetText: currentProblem.targetText,
    displayText: currentProblem.displayText,
    cellCount: currentProblem.cellCount,
  };
  return {
    phase: { kind: "solving", problem, idempotencyKey: deps.newKey(), startedAt: deps.now() },
    announcement: problemAnnouncement(problem.displayText),
  };
}

/** 제출 응답을 반영한다. 그 사이 다른 문제로 넘어갔으면 null — 화면은 새 문제를 유지한다 */
export function acceptResult(phase: ProblemPhase | null, result: LiveAnswer): Transition | null {
  if (phase?.kind !== "solving" || phase.problem.problemId !== result.problemId) return null;
  return { phase: { kind: "graded", problem: phase.problem, result }, announcement: null };
}

/** 채점 화면의 확인. 채점 상태가 아니면 null */
export function acknowledgeGrade(phase: ProblemPhase | null): Transition | null {
  if (phase?.kind !== "graded") return null;
  return { phase: { kind: "submitted", problemId: phase.problem.problemId }, announcement: SUBMITTED_WAITING_MESSAGE };
}

/** 대기 화면 제목 — 낭독 문구와 같은 문자열이다 */
export function waitingText(phase: ProblemPhase | null): string {
  return phase?.kind === "submitted" ? SUBMITTED_WAITING_MESSAGE : WAITING_MESSAGE;
}

/** 이번 수업에서 낸 문제 수와 맞힌 수 (종료 화면) */
export function tallyResults(results: Readonly<Record<string, boolean>>): { total: number; correct: number } {
  const values = Object.values(results);
  return { total: values.length, correct: values.filter(Boolean).length };
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureProblemFlow.test.ts`
Expected: PASS

- [ ] **Step 5: 멱등 키 생성기와 스토어 작성**

`src/services/idempotencyKey.ts`:

```ts
import { uuid } from "expo-modules-core";

/**
 * 수업 답안의 Idempotency-Key(UUID v4). 문제마다 새로 만든다.
 * expo가 이미 싣고 있는 네이티브 생성기(globalThis.expo.uuidv4)를 쓴다 — 의존성을 더하지 않는다.
 * jest에는 네이티브가 없으므로 테스트는 이 모듈을 목으로 바꾼다.
 */
export function newIdempotencyKey(): string {
  return uuid.v4();
}
```

`src/stores/lectureProblemStore.ts`:

```ts
import { create } from "zustand";
import type { ProblemPhase } from "@/services/lectureProblemFlow";

/**
 * 수업 문제 상태 — 풀이·채점·종료 화면이 구독한다.
 *
 * 값은 `services/lectureProblemSync.ts`만 바꾼다. 문제 내용의 권위는 서버 스냅샷이고 여기는 그 사본이다.
 */
interface LectureProblemState {
  /** 문제를 따라가는 수업. 재접속해도 같은 수업이면 결과를 유지한다 */
  lectureId: string | null;
  /** 수업 방에 있는 동안 true. 퇴장 뒤 늦게 온 조회·제출 응답을 버리는 데 쓴다 */
  active: boolean;
  /** null이면 아직 조회 전 (화면은 대기처럼 그리되 낭독하지 않는다) */
  phase: ProblemPhase | null;
  /** 이번 수업에서 낸 문제별 정오 — 종료 화면 집계 */
  results: Record<string, boolean>;
  set: (patch: Partial<Pick<LectureProblemState, "lectureId" | "active" | "phase" | "results">>) => void;
  reset: () => void;
}

const INITIAL: Pick<LectureProblemState, "lectureId" | "active" | "phase" | "results"> = {
  lectureId: null,
  active: false,
  phase: null,
  results: {},
};

export const useLectureProblemStore = create<LectureProblemState>()((set) => ({
  ...INITIAL,
  set: (patch) => set(patch),
  reset: () => set(INITIAL),
}));
```

- [ ] **Step 6: 실패하는 테스트 작성 — 오케스트레이션**

`__tests__/lectureProblemSync.test.ts`:

```ts
import { ApiError } from "@ieum/api-client";
import type { LearnerCurrentProblem, LiveAnswer } from "@ieum/contracts";
import { announce } from "@/a11y";
import { classService } from "@/services/classService";
import { newIdempotencyKey } from "@/services/idempotencyKey";
import {
  PROBLEM_CHANGED_MESSAGE,
  problemAnnouncement,
  SUBMIT_CLOSED_MESSAGE,
  SUBMIT_FAILED_MESSAGE,
  SUBMITTED_WAITING_MESSAGE,
  WAITING_MESSAGE,
} from "@/services/lectureProblemFlow";
import { lectureProblemSync } from "@/services/lectureProblemSync";
import { useLectureProblemStore } from "@/stores/lectureProblemStore";

jest.mock("@/a11y", () => ({ announce: jest.fn() }));
jest.mock("@/services/classService", () => ({
  classService: { getLectureProblem: jest.fn(), submitLiveAnswer: jest.fn() },
}));
jest.mock("@/services/idempotencyKey", () => ({ newIdempotencyKey: jest.fn() }));

const mockedClass = classService as unknown as { getLectureProblem: jest.Mock; submitLiveAnswer: jest.Mock };
const mockedAnnounce = announce as unknown as jest.Mock;
const mockedKey = newIdempotencyKey as unknown as jest.Mock;

function current(id: string, submitted = false): LearnerCurrentProblem {
  return { problemId: id, problemType: "CONSONANT", targetText: "ㄱ", displayText: "초성 ㄱ", cellCount: 1, submitted };
}

function snapshot(currentProblem: LearnerCurrentProblem | null, myResult: LiveAnswer | null = null) {
  return { status: "OPEN", currentProblem, myResult };
}

function answer(id: string, isCorrect = true): LiveAnswer {
  return {
    attemptId: "900",
    lectureId: "42",
    problemId: id,
    isCorrect,
    score: isCorrect ? 100 : 0,
    attemptedAt: "2026-09-27T01:00:00.000Z",
    cells: [{ cellIndex: 1, inputPattern: 8, expectedPattern: 8, diffMask: 0, isCorrect: true }],
  };
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void } {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function flushMicrotasks(times = 10): Promise<void> {
  for (let i = 0; i < times; i += 1) await Promise.resolve();
}

const phase = () => useLectureProblemStore.getState().phase;

/** 풀이 상태로 만든 뒤 낭독 기록을 비운다 */
async function startSolving(id = "7"): Promise<void> {
  mockedClass.getLectureProblem.mockResolvedValueOnce(snapshot(current(id)));
  await lectureProblemSync.refresh();
  mockedAnnounce.mockClear();
}

let nowSpy: jest.SpyInstance | null = null;

beforeEach(() => {
  jest.resetAllMocks();
  let n = 0;
  mockedKey.mockImplementation(() => `key-${++n}`);
  lectureProblemSync.reset();
  lectureProblemSync.begin("42");
});

afterEach(() => {
  nowSpy?.mockRestore();
  nowSpy = null;
});

describe("lectureProblemSync 조회", () => {
  it("문제가 없으면 대기로 두고 기다린다고 알린다", async () => {
    mockedClass.getLectureProblem.mockResolvedValue(snapshot(null));

    await lectureProblemSync.refresh();

    expect(mockedClass.getLectureProblem).toHaveBeenCalledWith("42");
    expect(phase()).toEqual({ kind: "waiting" });
    expect(mockedAnnounce).toHaveBeenCalledWith(WAITING_MESSAGE);
  });

  it("문제가 있으면 풀이로 바꾸고 문제를 읽는다", async () => {
    mockedClass.getLectureProblem.mockResolvedValue(snapshot(current("7")));

    await lectureProblemSync.refresh();

    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "7" }, idempotencyKey: "key-1" });
    expect(mockedAnnounce).toHaveBeenCalledWith(problemAnnouncement("초성 ㄱ"));
  });

  it("겹친 조회는 마지막 요청의 응답만 반영한다", async () => {
    const first = deferred<ReturnType<typeof snapshot>>();
    mockedClass.getLectureProblem
      .mockImplementationOnce(() => first.promise)
      .mockResolvedValueOnce(snapshot(current("8")));

    const r1 = lectureProblemSync.refresh();
    await lectureProblemSync.refresh();
    first.resolve(snapshot(current("7")));
    await r1;

    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "8" } });
    expect(mockedAnnounce).toHaveBeenCalledTimes(1);
  });

  it("퇴장(stop) 뒤에 도착한 조회 응답은 버린다", async () => {
    const gate = deferred<ReturnType<typeof snapshot>>();
    mockedClass.getLectureProblem.mockImplementationOnce(() => gate.promise);

    const r = lectureProblemSync.refresh();
    lectureProblemSync.stop();
    gate.resolve(snapshot(current("7")));
    await r;

    expect(phase()).toBeNull();
    expect(mockedAnnounce).not.toHaveBeenCalled();
  });

  it("퇴장한 뒤에는 조회하지 않는다", async () => {
    lectureProblemSync.stop();

    await lectureProblemSync.refresh();

    expect(mockedClass.getLectureProblem).not.toHaveBeenCalled();
  });

  it("첫 조회가 실패하면 대기로 두고 안내한다", async () => {
    mockedClass.getLectureProblem.mockRejectedValue(new ApiError(0, "NETWORK_UNAVAILABLE", "x"));

    await lectureProblemSync.refresh();

    expect(phase()).toEqual({ kind: "waiting" });
    expect(mockedAnnounce).toHaveBeenCalledWith(WAITING_MESSAGE);
  });

  it("풀던 중 조회가 실패하면 상태를 그대로 둔다", async () => {
    await startSolving("7");
    mockedClass.getLectureProblem.mockRejectedValueOnce(new ApiError(0, "NETWORK_UNAVAILABLE", "x"));

    await lectureProblemSync.refresh();

    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "7" } });
    expect(mockedAnnounce).not.toHaveBeenCalled();
  });

  it("이미 낸 현재 문제면 제출 대기로 두고 내 결과를 집계에 넣는다 (재접속)", async () => {
    mockedClass.getLectureProblem.mockResolvedValue(snapshot(current("7", true), answer("7", false)));

    await lectureProblemSync.refresh();

    expect(phase()).toEqual({ kind: "submitted", problemId: "7" });
    expect(mockedAnnounce).toHaveBeenCalledWith(SUBMITTED_WAITING_MESSAGE);
    expect(useLectureProblemStore.getState().results).toEqual({ "7": false });
  });

  it("같은 수업에 다시 들어오면 결과를 두고, 다른 수업이면 비운다", () => {
    useLectureProblemStore.getState().set({ results: { "7": true } });

    lectureProblemSync.begin("42");
    expect(useLectureProblemStore.getState()).toMatchObject({ lectureId: "42", active: true, phase: null, results: { "7": true } });

    lectureProblemSync.begin("43");
    expect(useLectureProblemStore.getState()).toMatchObject({ lectureId: "43", active: true, phase: null, results: {} });
  });
});

describe("lectureProblemSync 제출", () => {
  it("풀던 문제에 멱등 키·칸·걸린 시간을 보내고 채점 상태로 바꾼다", async () => {
    nowSpy = jest.spyOn(Date, "now").mockReturnValue(1000);
    await startSolving("7");
    nowSpy.mockReturnValue(2900);
    mockedClass.submitLiveAnswer.mockResolvedValue(answer("7", true));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("graded");

    expect(mockedClass.submitLiveAnswer).toHaveBeenCalledWith("42", "7", { cells: [8], durationMs: 1900 }, "key-1");
    expect(phase()).toMatchObject({ kind: "graded", problem: { problemId: "7" }, result: { isCorrect: true } });
    expect(useLectureProblemStore.getState().results).toEqual({ "7": true });
  });

  it("실패 뒤 다시 내면 같은 멱등 키를 쓴다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer
      .mockRejectedValueOnce(new ApiError(0, "NETWORK_UNAVAILABLE", "x"))
      .mockResolvedValueOnce(answer("7"));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("failed");
    expect(mockedAnnounce).toHaveBeenCalledWith(SUBMIT_FAILED_MESSAGE);
    expect(phase()).toMatchObject({ kind: "solving" });

    await expect(lectureProblemSync.submit([8])).resolves.toBe("graded");
    expect(mockedClass.submitLiveAnswer.mock.calls[0]![3]).toBe("key-1");
    expect(mockedClass.submitLiveAnswer.mock.calls[1]![3]).toBe("key-1");
  });

  it("새 문제는 새 멱등 키를 받는다", async () => {
    await startSolving("7");
    await startSolving("8");

    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "8" }, idempotencyKey: "key-2" });
  });

  it("문제가 바뀌었으면(409 NOT_CURRENT) 알리고 다시 조회해 새 문제로 간다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer.mockRejectedValue(new ApiError(409, "LECTURE_PROBLEM_NOT_CURRENT", "x"));
    mockedClass.getLectureProblem.mockResolvedValueOnce(snapshot(current("8")));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("changed");
    await flushMicrotasks();

    expect(mockedAnnounce.mock.calls[0]![0]).toBe(PROBLEM_CHANGED_MESSAGE);
    expect(mockedAnnounce).toHaveBeenLastCalledWith(problemAnnouncement("초성 ㄱ"));
    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "8" } });
  });

  it("다른 답을 이미 냈으면(409 ALREADY_ANSWERED) 다시 조회해 제출 대기로 둔다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer.mockRejectedValue(new ApiError(409, "LECTURE_PROBLEM_ALREADY_ANSWERED", "x"));
    mockedClass.getLectureProblem.mockResolvedValueOnce(snapshot(current("7", true), answer("7", false)));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("changed");
    await flushMicrotasks();

    expect(phase()).toEqual({ kind: "submitted", problemId: "7" });
    expect(mockedAnnounce).toHaveBeenLastCalledWith(SUBMITTED_WAITING_MESSAGE);
  });

  it("수업이 닫혔으면(409 INVALID_LECTURE_STATE) 알리고 끝낸다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer.mockRejectedValue(new ApiError(409, "INVALID_LECTURE_STATE", "x"));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("closed");

    expect(mockedAnnounce).toHaveBeenCalledWith(SUBMIT_CLOSED_MESSAGE);
  });

  it("제출 중에 겹쳐 누르면 나중 요청은 무시한다", async () => {
    await startSolving("7");
    const gate = deferred<LiveAnswer>();
    mockedClass.submitLiveAnswer.mockImplementationOnce(() => gate.promise);

    const first = lectureProblemSync.submit([8]);
    await expect(lectureProblemSync.submit([8])).resolves.toBe("ignored");
    gate.resolve(answer("7"));
    await first;

    expect(mockedClass.submitLiveAnswer).toHaveBeenCalledTimes(1);
  });

  it("풀이 상태가 아니면 보내지 않는다", async () => {
    await expect(lectureProblemSync.submit([8])).resolves.toBe("ignored");
    expect(mockedClass.submitLiveAnswer).not.toHaveBeenCalled();
  });

  it("제출 전에 떠난 조회 응답이 채점 결과를 덮지 않는다", async () => {
    await startSolving("7");
    const gate = deferred<ReturnType<typeof snapshot>>();
    mockedClass.getLectureProblem.mockImplementationOnce(() => gate.promise);
    mockedClass.submitLiveAnswer.mockResolvedValue(answer("7"));

    const r = lectureProblemSync.refresh();
    await lectureProblemSync.submit([8]);
    gate.resolve(snapshot(current("7", false)));
    await r;

    expect(phase()).toMatchObject({ kind: "graded" });
  });

  it("채점을 기다리는 사이 다음 문제가 오면 결과는 집계만 하고 새 문제를 유지한다", async () => {
    await startSolving("7");
    const gate = deferred<LiveAnswer>();
    mockedClass.submitLiveAnswer.mockImplementationOnce(() => gate.promise);
    mockedClass.getLectureProblem.mockResolvedValueOnce(snapshot(current("8")));

    const submitting = lectureProblemSync.submit([8]);
    await lectureProblemSync.refresh(); // 출제 신호로 온 조회 — 제출 뒤에 떠났으니 유효하다
    gate.resolve(answer("7", false));

    await expect(submitting).resolves.toBe("ignored");
    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "8" } });
    expect(useLectureProblemStore.getState().results).toEqual({ "7": false });
  });

  it("퇴장한 뒤 도착한 제출 응답은 반영하지 않는다", async () => {
    await startSolving("7");
    const gate = deferred<LiveAnswer>();
    mockedClass.submitLiveAnswer.mockImplementationOnce(() => gate.promise);

    const submitting = lectureProblemSync.submit([8]);
    lectureProblemSync.stop();
    gate.resolve(answer("7"));

    await expect(submitting).resolves.toBe("ignored");
    expect(phase()).toMatchObject({ kind: "solving" });
  });

  it("채점을 확인하면 제출 대기로 돌아가고 알린다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer.mockResolvedValue(answer("7"));
    await lectureProblemSync.submit([8]);
    mockedAnnounce.mockClear();

    lectureProblemSync.acknowledgeGrade();

    expect(phase()).toEqual({ kind: "submitted", problemId: "7" });
    expect(mockedAnnounce).toHaveBeenCalledWith(SUBMITTED_WAITING_MESSAGE);
  });
});
```

- [ ] **Step 7: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureProblemSync.test.ts`
Expected: FAIL — `Cannot find module '@/services/lectureProblemSync'`

- [ ] **Step 8: 구현 — 오케스트레이션**

`src/services/lectureProblemSync.ts`:

```ts
import { ApiError } from "@ieum/api-client";
import type { LiveAnswer } from "@ieum/contracts";
import type { Cell } from "@ieum/domain";
import { announce } from "@/a11y";
import { useLectureProblemStore } from "@/stores/lectureProblemStore";
import { classService } from "./classService";
import { newIdempotencyKey } from "./idempotencyKey";
import {
  acceptResult,
  acknowledgeGrade,
  applySnapshot,
  PROBLEM_CHANGED_MESSAGE,
  SUBMIT_CLOSED_MESSAGE,
  SUBMIT_FAILED_MESSAGE,
  WAITING_MESSAGE,
  type FlowDeps,
  type Transition,
} from "./lectureProblemFlow";

/**
 * 수업 문제 조회·제출. 방 metadata 신호는 "다시 조회하라"는 뜻일 뿐이고 문제·결과는 HTTP로만 받는다.
 * 화면을 옮겨도 이어져야 하므로(채점 화면에서 새 문제 수신) 컴포넌트가 아니라 이 모듈이 맡는다.
 */

const deps: FlowDeps = { newKey: () => newIdempotencyKey(), now: () => Date.now() };

// 조회가 겹치면 마지막 요청만 유효하다. 제출을 시작할 때·퇴장할 때도 하나 올려,
// 그 전에 떠난 조회 응답(제출 전 상태)이 결과를 덮거나 퇴장 뒤에 화면을 바꾸지 않게 한다
let requestSeq = 0;
let submitting = false;

export type SubmitOutcome = "graded" | "changed" | "closed" | "failed" | "ignored";

function store() {
  return useLectureProblemStore.getState();
}

function commit(transition: Transition): void {
  store().set({ phase: transition.phase });
  if (transition.announcement) announce(transition.announcement);
}

function isActiveFor(lectureId: string): boolean {
  const s = store();
  return s.active && s.lectureId === lectureId;
}

function record(result: LiveAnswer): void {
  store().set({ results: { ...store().results, [result.problemId]: result.isCorrect } });
}

export const lectureProblemSync = {
  /** 대기 화면이 입장을 시도할 때. 같은 수업의 재접속이면 집계는 두고 상태만 조회 전으로 되돌린다 */
  begin(lectureId: string): void {
    requestSeq++;
    if (store().lectureId !== lectureId) store().reset();
    store().set({ lectureId, active: true, phase: null });
  },

  /** 방을 나왔다(종료·끊김·퇴장). 집계는 종료 화면이 쓰므로 남긴다 */
  stop(): void {
    requestSeq++;
    store().set({ active: false });
  },

  /** 수업을 완전히 떠날 때(홈으로·수업 나가기) */
  reset(): void {
    requestSeq++;
    store().reset();
  },

  /** 현재 문제를 조회해 상태를 바꾸고 전환을 알린다. 입장·재접속·출제 신호·"문제 다시 확인" 때 부른다 */
  async refresh(): Promise<void> {
    const { lectureId, active } = store();
    if (!active || lectureId === null) return;
    const seq = ++requestSeq;
    try {
      const snapshot = await classService.getLectureProblem(lectureId);
      if (seq !== requestSeq || !isActiveFor(lectureId)) return;
      if (snapshot.myResult) record(snapshot.myResult);
      commit(applySnapshot(store().phase, snapshot.currentProblem, deps));
    } catch {
      if (seq !== requestSeq || !isActiveFor(lectureId)) return;
      // 처음 조회가 실패해도 화면은 대기로 둔다. 다음 출제 신호나 "문제 다시 확인"이 다시 조회한다
      if (store().phase === null) commit({ phase: { kind: "waiting" }, announcement: WAITING_MESSAGE });
    }
  },

  /**
   * 풀던 문제의 답을 낸다. 멱등 키는 그 문제의 키를 그대로 쓴다 — 실패 뒤 다시 눌러도 같은 키.
   * 성공하면 상태가 graded가 되고 화면이 채점 화면으로 옮긴다(낭독은 채점 화면).
   */
  async submit(cells: readonly Cell[]): Promise<SubmitOutcome> {
    const { lectureId, active, phase } = store();
    if (!active || lectureId === null || phase?.kind !== "solving" || submitting) return "ignored";
    submitting = true;
    requestSeq++;
    const durationMs = Math.max(0, Math.round(deps.now() - phase.startedAt));
    try {
      const result = await classService.submitLiveAnswer(
        lectureId,
        phase.problem.problemId,
        { cells: [...cells], durationMs },
        phase.idempotencyKey,
      );
      if (!isActiveFor(lectureId)) return "ignored";
      record(result);
      const transition = acceptResult(store().phase, result);
      if (transition === null) return "ignored"; // 그 사이 다음 문제로 넘어갔다 — 결과는 집계만 한다
      commit(transition);
      return "graded";
    } catch (error) {
      if (!isActiveFor(lectureId)) return "ignored";
      const code = error instanceof ApiError ? error.code : null;
      if (code === "LECTURE_PROBLEM_NOT_CURRENT") {
        announce(PROBLEM_CHANGED_MESSAGE);
        void lectureProblemSync.refresh();
        return "changed";
      }
      if (code === "LECTURE_PROBLEM_ALREADY_ANSWERED") {
        // 앞선 제출이 저장됐는데 응답을 못 받았다 — 조회하면 제출 대기로 바뀌며 안내된다
        void lectureProblemSync.refresh();
        return "changed";
      }
      if (code === "INVALID_LECTURE_STATE") {
        announce(SUBMIT_CLOSED_MESSAGE);
        return "closed";
      }
      announce(SUBMIT_FAILED_MESSAGE);
      return "failed";
    } finally {
      submitting = false;
    }
  },

  /** 채점 화면의 확인 — 제출 대기로 돌아가며 안내한다 */
  acknowledgeGrade(): void {
    const transition = acknowledgeGrade(store().phase);
    if (transition) commit(transition);
  },
};
```

- [ ] **Step 9: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureProblemFlow.test.ts __tests__/lectureProblemSync.test.ts && pnpm --filter @ieum/mobile typecheck`
Expected: PASS, 타입 오류 없음 (`expo-modules-core`의 `uuid` 타입도 여기서 확인된다)

- [ ] **Step 10: Commit**

```bash
git add front/apps/mobile/src/services/lectureProblemFlow.ts front/apps/mobile/src/services/idempotencyKey.ts front/apps/mobile/src/stores/lectureProblemStore.ts front/apps/mobile/src/services/lectureProblemSync.ts front/apps/mobile/__tests__/lectureProblemFlow.test.ts front/apps/mobile/__tests__/lectureProblemSync.test.ts
git commit -m "feat(lecture): 수업 문제 상태와 조회·제출 흐름을 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 대기·풀이 화면을 서버 현재 문제로 잇는다

**Files:**
- Modify: `front/apps/mobile/src/app/class/waiting.tsx` (전체 교체)
- Modify: `front/apps/mobile/src/app/class/play.tsx` (전체 교체)
- Modify: `front/apps/mobile/src/app/class/end.tsx` (전체 교체)
- Modify: `front/apps/mobile/src/app/class/disconnected.tsx` (import 한 줄, `reset` 두 곳)
- Modify: `front/apps/mobile/src/services/classService.ts` (목 `getClassSession` 제거)

**Interfaces:**
- Consumes: `lectureProblemSync.begin/stop/reset/refresh/submit` (Task 3), `useLectureProblemStore` (Task 3), `problemAnnouncement`, `waitingText`, `tallyResults` (Task 3), `LiveLectureHandlers.onCurrentProblemChanged` (Task 2), `describeTooManyCells` (기존 `answerLengthGuard.ts`)
- Produces: 화면 동작만. 채점 화면 이동은 Task 5가 `play.tsx`에 effect 하나를 더한다

화면 테스트는 두지 않는다 — 화면 파일은 AsyncStorage·expo-router 의존 사슬 때문에 jest에서 import하지 않는 것이 이 앱의 관례다(`answerLengthGuard.ts` 주석). 판단은 Task 3의 순수 함수·서비스가 테스트로 보증하고, 화면은 typecheck와 Task 6 실기기로 확인한다.

- [ ] **Step 1: 대기 화면 교체**

`src/app/class/waiting.tsx` 전체:

```tsx
import { useCallback, useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { router, useLocalSearchParams, useNavigation } from "expo-router";
import { ApiError } from "@ieum/api-client";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { liveLecture } from "@/services/liveLecture";
import { lectureProblemSync } from "@/services/lectureProblemSync";
import { isUserBackAction } from "@/services/lectureSignals";
import { announce, hapticSuccess, speakAsync } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";
import { speakingAllowedAnnouncement, SPEAKING_REVOKED_MESSAGE } from "@/services/lectureVoiceControls";

/** V22 수업 대기 — 선생님이 시작하면 자동 입장 */
export default function ClassWaiting() {
  const theme = useTheme();
  const fontScale = useFontScale();
  const { lectureId, title } = useLocalSearchParams<{ lectureId: string; title?: string }>();
  const className = title ?? "수업";
  // 파라미터가 없거나 비어 있으면 입장할 방을 알 수 없다 — undefined로 join하지 않는다
  const validLectureId = typeof lectureId === "string" && lectureId.trim() !== "" ? lectureId : null;
  // retryable이 false면 다시 시도해도 같은 결과라 "다시 시도" 대신 나가기만 권한다
  const [failed, setFailed] = useState<{ message: string; retryable: boolean } | null>(null);
  // 언마운트(풀이 화면으로 이동 등) 뒤에는 상태를 바꾸거나 화면을 옮기지 않는다. onExit·onSpeakingChanged·
  // onCurrentProblemChanged는 그대로 살려 둔다 — 대기 화면을 떠난 뒤에도 liveLecture 쪽 handlers는 계속 불린다.
  const mountedRef = useRef(true);
  // "입장 완료" 안내는 화면에 들어올 때 한 번만 읽는다. 다시 시도할 때마다 되풀이하지 않는다.
  // 실패 안내는 이 낭독이 끝난 뒤에 읽는다 — 겹치면 announce가 입장 안내를 중간에 끊는다
  const introRef = useRef<Promise<void> | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // 하드웨어 뒤로 가기·제스처로 떠나도 "수업 나가기"처럼 음성 방을 정리한다. 풀이·종료·끊김
  // 화면으로의 이동은 앱이 router.replace(REPLACE)로 하므로 방을 그대로 둔다
  const navigation = useNavigation();
  useEffect(
    () =>
      navigation.addListener("beforeRemove", (event) => {
        if (isUserBackAction(event.data.action)) void liveLecture.leave();
      }),
    [navigation],
  );

  const attempt = useCallback(() => {
    if (validLectureId === null) {
      const message = "수업 정보를 찾지 못했어요. 수업 나가기를 눌러 주세요.";
      setFailed({ message, retryable: false });
      announce(message);
      return;
    }
    setFailed(null);
    if (introRef.current === null) {
      void hapticSuccess();
      introRef.current = speakAsync(`입장 완료. ${className}, 곧 시작해요. 선생님이 시작하면 알려 드릴게요.`);
    }
    const intro = introRef.current;
    const fail = (message: string, retryable: boolean) => {
      setFailed({ message, retryable });
      void intro.then(() => {
        if (mountedRef.current) announce(message);
      });
    };
    // 문제 상태를 이 수업으로 맞춘다. 같은 수업의 재접속이면 지금까지의 정오 집계는 남는다
    lectureProblemSync.begin(validLectureId);
    liveLecture
      .join(validLectureId, {
        onOpen: () => {
          // 수업이 시작되면 풀이 화면으로 간다. 그 화면은 "문제를 기다리는 중" 대기 상태로 시작해
          // 현재 문제를 한 번 조회한다. 그 사이 방이 끊겨 이미 다른 화면으로 옮겼으면 버린다
          if (!mountedRef.current || liveLecture.currentLectureId() !== validLectureId) return;
          router.replace("/class/play");
        },
        onExit: (exit) => {
          // 방을 나온 뒤 늦게 온 조회·제출 응답이 종료·끊김 화면 위에서 문제를 읽지 않게 멈춘다
          lectureProblemSync.stop();
          if (exit === "ended") router.replace("/class/end");
          else if (exit === "lost") router.replace({ pathname: "/class/disconnected", params: { lectureId: validLectureId, title } });
        },
        onSpeakingChanged: (allowed) => {
          // 풀이·채점 화면에 있어도 알린다 — 이 핸들러는 대기 화면이 사라진 뒤에도 불린다.
          // 마이크 버튼은 풀이 화면에만 있으므로, 그 화면이 아니면 누르라는 말은 하지 않는다 (M5)
          if (allowed) void hapticSuccess();
          announce(allowed ? speakingAllowedAnnouncement() : SPEAKING_REVOKED_MESSAGE);
        },
        onCurrentProblemChanged: () => {
          // 신호는 "다시 조회하라"는 뜻일 뿐이다. 문제 내용·제출 여부는 스냅샷으로 받는다.
          // 풀이·채점 어느 화면에 있어도 같은 경로로 상태가 바뀌고 안내된다
          void lectureProblemSync.refresh();
        },
      })
      .catch((error: unknown) => {
        if (!mountedRef.current) return;
        // 참여 기록이 없어 토큰이 거부됐다 — 다시 시도해도 같으니 코드 재입장을 안내한다
        if (error instanceof ApiError && error.code === "LECTURE_PARTICIPATION_REQUIRED") {
          fail("코드로 다시 입장해 주세요.", false);
          return;
        }
        fail("음성 수업에 연결하지 못했어요. 다시 시도해 주세요.", true);
      });
  }, [validLectureId, title, className]);

  useEffect(() => {
    attempt();
  }, [attempt]);

  const leaveAndBack = async () => {
    await liveLecture.leave();
    lectureProblemSync.reset();
    router.back();
  };

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} expression={failed ? "worried" : undefined} accessibilityLabel={failed ? "연결 실패 캐릭터" : "대기 중 캐릭터"} />
        {!failed && <Text style={{ fontSize: 15, fontWeight: "700", color: theme.color.primary }}>입장 완료</Text>}
        {failed ? (
          <>
            <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text, textAlign: "center" }}>
              연결하지{"\n"}못했어요
            </Text>
            <Text accessibilityLiveRegion="polite" style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
              {failed.message}
            </Text>
          </>
        ) : (
          <>
            <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
              {className}{"\n"}곧 시작해요
            </Text>
            <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29 }}>
              선생님이 시작하면 알려 드릴게요.
            </Text>
          </>
        )}
      </View>
      <View style={{ gap: 12 }}>
        {failed?.retryable && <AppButton label="다시 시도" onPress={attempt} />}
        <AppButton
          label="수업 나가기"
          variant={failed ? "secondary" : "primary"}
          onPress={() => void leaveAndBack()}
          accessibilityHint="대기를 취소하고 수업 참여 화면으로 돌아가요"
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
```

- [ ] **Step 2: 풀이 화면 교체**

`src/app/class/play.tsx` 전체:

```tsx
import { useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useNavigation, useRouter } from "expo-router";
import type { Cell } from "@ieum/domain";
import { cellToDots, cellsToUnicode } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { DotPad } from "@/components/DotPad";
import { AppButton } from "@/components/AppButton";
import { describeTooManyCells } from "@/services/answerLengthGuard";
import { liveLecture } from "@/services/liveLecture";
import { problemAnnouncement, waitingText } from "@/services/lectureProblemFlow";
import { lectureProblemSync } from "@/services/lectureProblemSync";
import { isUserBackAction } from "@/services/lectureSignals";
import { ensureMicPermission } from "@/services/micPermission";
import {
  HAND_DOWN_MESSAGE,
  HAND_FAILED_MESSAGE,
  HAND_UP_MESSAGE,
  lectureVoiceControls,
  MIC_FAILED_MESSAGE,
  MIC_OFF_MESSAGE,
  MIC_ON_MESSAGE,
  MIC_PERMISSION_DENIED_MESSAGE,
  setLecturePlayScreenActive,
} from "@/services/lectureVoiceControls";
import { useLectureProblemStore } from "@/stores/lectureProblemStore";
import { useLectureVoiceStore } from "@/stores/lectureVoiceStore";
import { announce, hapticSuccess, speak, speakAsync } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** TalkBack의 실제 낭독 속도가 추정보다 느릴 때를 대비한 여유 (M4) */
const MIC_ON_ANNOUNCE_MARGIN_MS = 400;

/** V23 수업 진행 — 선생님 문제 대기 · 풀이 */
export default function ClassPlay() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const lectureId = useLectureProblemStore((s) => s.lectureId);
  const phase = useLectureProblemStore((s) => s.phase);
  const solving = phase?.kind === "solving" ? phase : null;
  const problemId = solving?.problem.problemId ?? null;
  const [cells, setCells] = useState<Cell[]>([]);
  // 발언 상태는 liveLecture가 스토어에 쓴다. 객체로 한 번에 고르면 매번 새 참조라 값별로 고른다
  const speakingAllowed = useLectureVoiceStore((s) => s.speakingAllowed);
  const micOn = useLectureVoiceStore((s) => s.micOn);
  const handUp = useLectureVoiceStore((s) => s.handUp);
  const voice = lectureVoiceControls({ speakingAllowed, micOn, handUp });
  // 손들기·마이크 요청이 진행 중이다 — 겹쳐 누른 것을 무시한다 (버튼을 비활성으로 바꾸면 TalkBack이 "사용할 수 없음"을 끼워 읽는다)
  const voiceBusyRef = useRef(false);
  // 제출이 진행 중이다 — 길게 누르기가 겹쳐도 한 번만 보낸다 (lectureProblemSync도 막지만 마이크 끄기 대기 중의 겹침을 여기서 막는다)
  const submittingRef = useRef(false);

  // 하드웨어 뒤로 가기·제스처로 떠나면 음성 방을 먼저 정리한다. 채점 화면으로의 이동은
  // router.replace(REPLACE)라 방을 그대로 둔다
  const navigation = useNavigation();
  useEffect(
    () =>
      navigation.addListener("beforeRemove", (event) => {
        if (isUserBackAction(event.data.action)) void liveLecture.leave();
      }),
    [navigation],
  );

  // 입장·재접속·채점 확인으로 이 화면에 올 때 한 번 조회한다. 이후 출제는 방 metadata 신호가
  // (대기 화면이 등록한 onCurrentProblemChanged) 조회를 부른다. 전환 안내는 lectureProblemSync가 읽는다
  useEffect(() => {
    void lectureProblemSync.refresh();
  }, []);

  // 새 문제가 오면 입력을 비운다
  useEffect(() => {
    setCells([]);
  }, [problemId]);

  // 마이크 버튼이 있는 유일한 화면이다 — 떠 있는 동안만 onSpeakingChanged가 "버튼을 눌러 켜세요"라고 말한다 (M5)
  useEffect(() => {
    setLecturePlayScreenActive(true);
    return () => setLecturePlayScreenActive(false);
  }, []);

  if (lectureId === null) {
    return (
      <ScreenShell style={styles.center}>
        <Text style={{ color: theme.color.text, fontSize: 20, fontWeight: "700" }}>진행 중인 수업이 없어요</Text>
        <AppButton
          label="수업 참여로"
          onPress={() => {
            void liveLecture.leave();
            lectureProblemSync.reset();
            router.replace("/(tabs)/class");
          }}
        />
      </ScreenShell>
    );
  }

  const dot = (color: string) => (
    <View style={{ width: 9, height: 9, borderRadius: 5, backgroundColor: color }} />
  );

  /** 제출 버튼 짧은 터치: 지금까지 입력한 점을 읽어 준다 */
  const readInput = () => {
    if (cells.length === 0) {
      announce("아직 입력한 칸이 없어요. 점을 짚고 손을 떼면 한 칸이 입력돼요.");
      return;
    }
    const last = cellToDots(cells[cells.length - 1]!);
    announce(
      `지금까지 ${cells.length}칸 입력했어요. 마지막 칸은 ${last.join("번, ")}번 점이에요. 길게 누르면 제출돼요.`,
    );
  };

  const submitAnswer = async () => {
    if (submittingRef.current) return;
    if (cells.length === 0) {
      announce("아직 입력한 칸이 없어요.");
      return;
    }
    const tooMany = describeTooManyCells(cells.length);
    if (tooMany) {
      announce(tooMany);
      return;
    }
    submittingRef.current = true;
    try {
      // 채점 화면에는 마이크 버튼이 없다 — 켠 채로 넘어가면 끌 방법이 없어
      // 채점 결과 낭독이 그대로 반 전체에 전해진다
      if (micOn) await liveLecture.setMicrophone(false);
      // 성공하면 상태가 graded가 되어 채점 화면으로 옮긴다. 실패 안내는 lectureProblemSync가 읽는다
      await lectureProblemSync.submit(cells);
    } finally {
      submittingRef.current = false;
    }
  };

  /** 대기 중 "문제 다시 확인" — 바뀐 것이 없으면 지금 상태를 다시 읽어 준다 */
  const recheck = async () => {
    const before = useLectureProblemStore.getState().phase;
    await lectureProblemSync.refresh();
    const after = useLectureProblemStore.getState().phase;
    if (after === before) announce(waitingText(after));
  };

  const toggleHand = async () => {
    if (voiceBusyRef.current) return;
    voiceBusyRef.current = true;
    const up = !handUp;
    const ok = await liveLecture.raiseHand(up);
    voiceBusyRef.current = false;
    announce(ok ? (up ? HAND_UP_MESSAGE : HAND_DOWN_MESSAGE) : HAND_FAILED_MESSAGE);
  };

  const toggleMic = async () => {
    if (voiceBusyRef.current) return;
    voiceBusyRef.current = true;
    try {
      if (micOn) {
        const ok = await liveLecture.setMicrophone(false);
        announce(ok ? MIC_OFF_MESSAGE : MIC_FAILED_MESSAGE);
        return;
      }
      if (!(await ensureMicPermission())) {
        announce(MIC_PERMISSION_DENIED_MESSAGE);
        return;
      }
      // 켠 뒤에 읽으면 안내 음성이 수업에 그대로 전해진다 — 켜기 전에 말하고, 켜진 것은 진동으로 알린다.
      // TalkBack이 켜져 있으면 speakAsync는 글자 수로 낭독 시간을 어림잡을 뿐이라 TalkBack 자신의
      // 말하기 속도가 느리면 추정보다 낭독이 늦게 끝난다 — 여유 400ms를 더 기다린 뒤에 연다
      await speakAsync(MIC_ON_MESSAGE);
      await new Promise((resolve) => setTimeout(resolve, MIC_ON_ANNOUNCE_MARGIN_MS));
      const ok = await liveLecture.setMicrophone(true);
      if (ok) void hapticSuccess();
      // 그 사이 선생님이 거뒀다면 회수 안내가 이미 나갔다 — 실패 안내를 겹쳐 읽지 않는다
      else if (useLectureVoiceStore.getState().speakingAllowed) announce(MIC_FAILED_MESSAGE);
    } finally {
      voiceBusyRef.current = false;
    }
  };

  return (
    <ScreenShell>
      <View style={styles.statusRow}>
        <View
          style={styles.statusItem}
          accessibilityLabel={`수업 중, 선생님 음성 연결됨${voice.status ? `, ${voice.status}` : ""}`}
        >
          {dot(theme.color.primary)}
          <Text style={{ fontSize: 16, fontWeight: "800", color: theme.color.text }}>수업 중</Text>
          <Text style={{ fontSize: 14, color: theme.color.textMuted, marginLeft: 6 }}>
            {voice.status || "선생님 음성"}
          </Text>
        </View>
        <View
          style={[styles.chip, { borderColor: theme.color.border }]}
          accessibilityLabel="점자 장치 연결됨"
        >
          {dot(theme.color.primary)}
          <Text style={{ fontSize: 14, fontWeight: "700", color: theme.color.text }}>장치 연결됨</Text>
        </View>
      </View>
      <View style={{ flexDirection: "row", gap: 10 }}>
        {/* 두 버튼은 대기·풀이 어느 상태에서도 같은 자리·같은 높이다 — 상태가 바뀌어도 글자만 바뀐다 */}
        <View style={{ flex: 1 }}>
          <AppButton
            label={voice.hand.label}
            variant="compact"
            accessibilityHint={voice.hand.hint}
            disabled={voice.hand.disabled}
            onPress={() => void toggleHand()}
          />
        </View>
        <View style={{ flex: 1 }}>
          <AppButton
            label={voice.mic.label}
            variant="compact"
            accessibilityHint={voice.mic.hint}
            disabled={voice.mic.disabled}
            onPress={() => void toggleMic()}
          />
        </View>
      </View>
      {solving ? (
        <>
          <Text accessibilityRole="header" style={{ fontSize: 28 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
            {solving.problem.displayText}
          </Text>
          <View style={{ flexDirection: "row", gap: 10 }}>
            <View style={{ flex: 1 }}>
              <AppButton
                label="문제 다시 듣기"
                variant="compact"
                onPress={() => speak(problemAnnouncement(solving.problem.displayText), { force: true })}
              />
            </View>
            <View style={{ flex: 1 }}>
              <AppButton
                label="한 칸 지우기"
                variant="compact"
                onPress={() =>
                  setCells((prev) => {
                    if (prev.length === 0) {
                      announce("지울 칸이 없어요.");
                      return prev;
                    }
                    announce("한 칸 지웠어요.");
                    return prev.slice(0, -1);
                  })
                }
              />
            </View>
          </View>
          <Text
            accessibilityLabel={`지금까지 ${cells.length}칸 입력`}
            style={{ fontSize: 16, color: theme.color.textMuted, textAlign: "center" }}
          >
            {cells.length > 0 ? `${cellsToUnicode(cells)} · ${cells.length}칸` : "입력 전"}
          </Text>
          <DotPad onCommitCell={(cell) => setCells((prev) => [...prev, cell])} />
          <AppButton label="길게 눌러 제출" requireLongPress onPress={() => void submitAnswer()} onShortPress={readInput} />
        </>
      ) : (
        <View style={styles.waiting}>
          <Text
            accessibilityRole="header"
            style={{ fontSize: 26 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text, textAlign: "center" }}
          >
            {waitingText(phase)}
          </Text>
          <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
            선생님이 문제를 내면 알려 드릴게요.
          </Text>
          <AppButton
            label="문제 다시 확인"
            variant="secondary"
            accessibilityHint="선생님이 낸 문제가 있는지 다시 확인해요"
            onPress={() => void recheck()}
          />
        </View>
      )}
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 16 },
  waiting: { flex: 1, alignItems: "center", justifyContent: "center", gap: 16 },
  statusRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  statusItem: { flexDirection: "row", alignItems: "center", gap: 6 },
  chip: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    borderWidth: 2,
    borderRadius: 99,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
});
```

- [ ] **Step 3: 종료 화면 교체**

`src/app/class/end.tsx` 전체:

```tsx
import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { liveLecture } from "@/services/liveLecture";
import { tallyResults } from "@/services/lectureProblemFlow";
import { lectureProblemSync } from "@/services/lectureProblemSync";
import { useLectureProblemStore } from "@/stores/lectureProblemStore";
import { announce, hapticSuccess } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V23c 수업 종료 */
export default function ClassEnd() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  // 이번 수업에서 이 기기로 낸 답의 서버 채점 결과 (앱을 다시 켜면 비어 있다 — 학습자용 수업 결과 API는 없다)
  const results = useLectureProblemStore((s) => s.results);
  const { total, correct } = tallyResults(results);
  const hasWrong = total - correct > 0;
  const summary = total === 0 ? "" : ` ${total}문제 중 ${correct}문제를 맞혔어요.`;

  useEffect(() => {
    void hapticSuccess();
    announce(`수업이 끝났어요. 고생했어요.${summary}` + (hasWrong ? " 틀린 문제로 복습해 볼까요?" : ""));
  }, [summary, hasWrong]);

  const leaveTo = (path: "/class/review" | "/(tabs)") =>
    void (async () => {
      await liveLecture.leave();
      lectureProblemSync.reset();
      router.replace(path);
    })();

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} accessibilityLabel="수업 종료 캐릭터" />
        <Text accessibilityRole="header" style={{ fontSize: 32 * fontScale, fontWeight: "900", color: theme.color.text }}>
          고생했어요
        </Text>
        {total > 0 && (
          <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
            {total}문제 중 {correct}문제를 맞혔어요.{hasWrong ? " 틀린 문제로 복습해 볼까요?" : ""}
          </Text>
        )}
      </View>
      <View style={{ gap: 12 }}>
        {hasWrong && <AppButton label="오답 복습하기" onPress={() => leaveTo("/class/review")} />}
        <AppButton label="홈으로" variant={hasWrong ? "secondary" : "primary"} onPress={() => leaveTo("/(tabs)")} />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
```

- [ ] **Step 4: 끊김 화면 수정**

`src/app/class/disconnected.tsx`:

import 줄

```tsx
import { useSessionStore } from "@/stores/sessionStore";
```

을 다음으로 바꾼다:

```tsx
import { lectureProblemSync } from "@/services/lectureProblemSync";
```

줄 `  const reset = useSessionStore((s) => s.reset);`을 지우고, "수업 나가기" onPress 안의 `reset();`을 `lectureProblemSync.reset();`으로 바꾼다. ("다시 연결"은 그대로 — 같은 수업으로 다시 들어가면 `begin`이 집계를 유지한다.)

- [ ] **Step 5: 목 문제 세트 제거**

`src/services/classService.ts`:
- `/* ─── 아래는 아직 목이다. 대응 엔드포인트(문제 출제·복습 연결)가 없다 ─── */` 주석과 `getClassSession()` 메서드 전체를 지우고, 그 자리에 다음 주석을 둔다:

```ts
  /* ─── 아래는 아직 목이다. 대응 엔드포인트(최근 참여·복습 연결)가 없다 ─── */
```

- 쓰이지 않게 된 `const delay = …` 줄, `import { dotsToCell } from "@ieum/domain";` 줄, contracts import의 `type LearningSession,` 줄을 지운다
- 파일 위 서비스 주석을 `/** 수업(LIVE) 서비스 — 입장·스냅샷·음성 토큰·현재 문제·답안 제출은 실제 API, 최근 참여와 복습 후보는 아직 목이다 */`로 바꾼다

- [ ] **Step 6: 확인**

Run: `cd front && pnpm --filter @ieum/mobile typecheck && pnpm --filter @ieum/mobile exec jest`
Expected: 타입 오류 없음(`getClassSession`·`useSessionStore`를 참조하는 수업 화면이 남아 있지 않음), 전체 PASS

Run: `cd front/apps/mobile && git grep -n "getClassSession\|useSessionStore" -- src/app/class src/services/classService.ts`
Expected: 출력 없음

- [ ] **Step 7: Commit**

```bash
git add front/apps/mobile/src/app/class/waiting.tsx front/apps/mobile/src/app/class/play.tsx front/apps/mobile/src/app/class/end.tsx front/apps/mobile/src/app/class/disconnected.tsx front/apps/mobile/src/services/classService.ts
git commit -m "feat(lecture): 수업 풀이를 선생님이 낸 서버 문제로 바꾼다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 서버 채점 결과 화면과 풀이 화면의 넘김

**Files:**
- Create: `front/apps/mobile/src/services/liveGradeFeedback.ts`
- Create: `front/apps/mobile/src/app/class/grade.tsx`
- Modify: `front/apps/mobile/src/app/class/play.tsx` ("새 문제가 오면 입력을 비운다" effect 바로 아래에 effect 추가)
- Test: `front/apps/mobile/__tests__/liveGradeFeedback.test.ts` (신규)

**Interfaces:**
- Consumes: `LiveAnswer`, `AnswerCell` (Task 1), `describeCells` (기존 `cellFeedback.ts`), `GradeFeedback` 타입 (기존 `gradeFeedback.ts`), `useLectureProblemStore`, `lectureProblemSync.acknowledgeGrade` (Task 3)
- Produces: `liveGradeFeedback(result: LiveAnswer): GradeFeedback` — 서버 칸 채점을 채점 화면 단계로 옮긴다. 규칙 설명(AI)은 LIVE에 없으므로 점 수준 설명만

- [ ] **Step 1: 실패하는 테스트 작성**

`__tests__/liveGradeFeedback.test.ts`:

```ts
import type { LiveAnswer } from "@ieum/contracts";
import { liveGradeFeedback } from "@/services/liveGradeFeedback";

function result(isCorrect: boolean, cells: LiveAnswer["cells"]): LiveAnswer {
  return {
    attemptId: "900",
    lectureId: "42",
    problemId: "26578",
    isCorrect,
    score: isCorrect ? 100 : 0,
    attemptedAt: "2026-09-27T01:00:00.000Z",
    cells,
  };
}

describe("liveGradeFeedback", () => {
  it("정답이면 헤드라인 한 단계만 읽는다", () => {
    const feedback = liveGradeFeedback(
      result(true, [{ cellIndex: 1, inputPattern: 8, expectedPattern: 8, diffMask: 0, isCorrect: true }]),
    );

    expect(feedback).toEqual({
      correct: true,
      headline: "정답이에요!",
      steps: [{ detail: "", speech: "정답이에요!", compare: null }],
    });
  });

  it("빠진 점을 칸 위치와 함께 설명하고 그 칸을 나란히 그린다", () => {
    const feedback = liveGradeFeedback(
      result(false, [{ cellIndex: 1, inputPattern: 0, expectedPattern: 8, diffMask: 8, isCorrect: false }]),
    );

    expect(feedback.correct).toBe(false);
    expect(feedback.headline).toBe("오답이에요.");
    expect(feedback.steps).toEqual([
      {
        detail: "1칸이 틀렸어요. 첫 번째 칸의 정답은 4번이에요. 4번 점이 빠졌어요.",
        speech: "오답이에요. 1칸이 틀렸어요. 첫 번째 칸의 정답은 4번이에요. 4번 점이 빠졌어요.",
        compare: { expected: 8, actual: 0, missingDots: [4], extraDots: [], index: 0 },
      },
    ]);
  });

  it("칸 수가 다르면 넘친 칸을 빈 칸 정답과 더 눌린 점으로 설명한다", () => {
    const feedback = liveGradeFeedback(
      result(false, [
        { cellIndex: 1, inputPattern: 8, expectedPattern: 8, diffMask: 0, isCorrect: true },
        { cellIndex: 2, inputPattern: 1, expectedPattern: 0, diffMask: 1, isCorrect: false },
      ]),
    );

    expect(feedback.steps).toHaveLength(1);
    expect(feedback.steps[0]!.detail).toBe("1칸이 틀렸어요. 두 번째 칸의 정답은 빈 칸이에요. 1번 점이 더 눌렸어요.");
    expect(feedback.steps[0]!.compare).toEqual({ expected: 0, actual: 1, missingDots: [], extraDots: [1], index: 1 });
  });

  it("틀린 칸이 여럿이면 칸마다 한 단계다", () => {
    const feedback = liveGradeFeedback(
      result(false, [
        { cellIndex: 1, inputPattern: 1, expectedPattern: 3, diffMask: 2, isCorrect: false },
        { cellIndex: 2, inputPattern: 5, expectedPattern: 1, diffMask: 4, isCorrect: false },
      ]),
    );

    expect(feedback.steps.map((s) => s.compare?.index)).toEqual([0, 1]);
    expect(feedback.steps[0]!.speech.startsWith("오답이에요. 모두 2칸이 틀렸어요.")).toBe(true);
    expect(feedback.steps[1]!.speech).toBe("두 번째 칸의 정답은 1번이에요. 3번 점이 더 눌렸어요.");
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/liveGradeFeedback.test.ts`
Expected: FAIL — `Cannot find module '@/services/liveGradeFeedback'`

- [ ] **Step 3: 구현 — 채점 단계 변환**

`src/services/liveGradeFeedback.ts`:

```ts
import type { AnswerCell, AttemptCellAnalysis, LiveAnswer } from "@ieum/contracts";
import { cellToDots } from "@ieum/domain";
import { describeCells } from "./cellFeedback";
import type { GradeFeedback } from "./gradeFeedback";

const SIX_DOTS = 0b111111;

/** LIVE 칸 채점(AnswerCell)에는 빠진·더 눌린 점이 없다 — 두 점형에서 계산해 기존 진단 모양으로 맞춘다 */
function toCellAnalysis(cell: AnswerCell): AttemptCellAnalysis {
  const missingMask = cell.expectedPattern & ~cell.inputPattern & SIX_DOTS;
  const extraMask = cell.inputPattern & ~cell.expectedPattern & SIX_DOTS;
  return {
    cellIndex: cell.cellIndex,
    expectedPattern: cell.expectedPattern,
    inputPattern: cell.inputPattern,
    diffMask: cell.diffMask,
    missingMask,
    extraMask,
    missingDots: cellToDots(missingMask),
    extraDots: cellToDots(extraMask),
  };
}

/**
 * 수업 답안의 서버 채점을 채점 화면 단계로 옮긴다. 정오는 서버 값(isCorrect)만 따른다.
 * LIVE는 AI 분석을 하지 않으므로 규칙 설명 없이 점 수준 설명만 한다 (설계서 §3.3).
 * 문장은 자기주도 학습과 같은 `describeCells`로 만든다 — 두 채점 화면의 말투가 갈리지 않게.
 */
export function liveGradeFeedback(result: LiveAnswer): GradeFeedback {
  const cells = result.cells.map(toCellAnalysis);
  const { headline, steps } = describeCells(cells, result.isCorrect, "");
  const byIndex = new Map(cells.map((c) => [c.cellIndex, c]));
  return {
    correct: result.isCorrect,
    headline,
    steps: steps.map((step) => {
      const wrong = step.cellIndex === null ? undefined : byIndex.get(step.cellIndex);
      return {
        detail: step.detail,
        speech: step.speech,
        compare: wrong
          ? {
              expected: wrong.expectedPattern,
              actual: wrong.inputPattern,
              missingDots: cellToDots(wrong.missingMask),
              extraDots: cellToDots(wrong.extraMask),
              index: wrong.cellIndex - 1,
            }
          : null,
      };
    }),
  };
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/liveGradeFeedback.test.ts`
Expected: PASS

- [ ] **Step 5: 수업 채점 화면 작성**

`src/app/class/grade.tsx`:

```tsx
import { useEffect, useMemo, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useNavigation, useRouter } from "expo-router";
import { ordinalKo } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { BrailleCellView } from "@/components/BrailleCellView";
import { liveGradeFeedback } from "@/services/liveGradeFeedback";
import { liveLecture } from "@/services/liveLecture";
import { lectureProblemSync } from "@/services/lectureProblemSync";
import { isUserBackAction } from "@/services/lectureSignals";
import { useLectureProblemStore } from "@/stores/lectureProblemStore";
import { announce, hapticError, hapticSuccess } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** V23b 수업 채점 — 서버가 채점한 결과를 읽어 주고, 확인하면 다음 문제를 기다린다 */
export default function ClassGrade() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const phase = useLectureProblemStore((s) => s.phase);
  const graded = phase?.kind === "graded" ? phase : null;
  const feedback = useMemo(() => (graded ? liveGradeFeedback(graded.result) : null), [graded]);
  /** 지금 보고 있는 틀린 칸의 순서 — 칸이 여럿이면 한 칸씩 넘겨 본다 */
  const [step, setStep] = useState(0);
  const attemptId = graded?.result.attemptId ?? null;

  useEffect(() => {
    setStep(0);
  }, [attemptId]);

  // 뒤로 가기로 떠나면 풀이 화면과 같이 음성 방을 정리한다
  const navigation = useNavigation();
  useEffect(
    () =>
      navigation.addListener("beforeRemove", (event) => {
        if (isUserBackAction(event.data.action)) void liveLecture.leave();
      }),
    [navigation],
  );

  // 채점 상태가 끝나면(확인 → 제출 대기, 새 문제 도착 → 풀이) 풀이 화면으로 돌아간다.
  // 새 문제 안내·대기 안내는 lectureProblemSync가 상태를 바꾸며 이미 읽었다
  useEffect(() => {
    if (!graded) router.replace("/class/play");
  }, [graded, router]);

  const steps = feedback?.steps ?? [];
  const current = steps[step] ?? steps[0];
  const isLastStep = step + 1 >= steps.length;

  useEffect(() => {
    if (!feedback || !current) return;
    // 정오 진동은 문항당 한 번이다. 칸을 넘길 때마다 울리면 칸마다 새로 채점된 것처럼 느껴진다
    if (step === 0) void (feedback.correct ? hapticSuccess() : hapticError());
    announce(current.speech);
  }, [current, feedback, step]);

  if (!graded || !feedback || !current) {
    return <ScreenShell style={styles.center} />;
  }

  return (
    <ScreenShell>
      <Text style={{ fontSize: 18, fontWeight: "700", color: theme.color.textMuted }}>
        {`선생님 문제 · ${graded.problem.displayText}`}
        {steps.length > 1 ? ` · 틀린 칸 ${step + 1} / ${steps.length}` : ""}
      </Text>
      {feedback.correct ? (
        <View style={styles.center}>
          <IeumFace size={150} accessibilityLabel="정답 캐릭터" />
          <Text accessibilityRole="header" style={{ fontSize: 40 * fontScale, fontWeight: "900", color: theme.color.primary }}>
            {feedback.headline}
          </Text>
        </View>
      ) : (
        <>
          <View style={{ flexDirection: "row", alignItems: "flex-start", justifyContent: "space-between", gap: 12 }}>
            <Text accessibilityRole="header" style={{ flex: 1, fontSize: 32 * fontScale, fontWeight: "900", lineHeight: 44, color: theme.color.primary }}>
              {feedback.headline}
            </Text>
            <IeumFace size={84} expression="frown" accessibilityLabel="아쉬워하는 이음 캐릭터" />
          </View>
          <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 28 }}>{current.detail}</Text>
          {current.compare && (
            <View style={[styles.compare, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
              <BrailleCellView
                cell={current.compare.expected}
                caption={`정답 · ${ordinalKo(current.compare.index)} 칸`}
                accessibilityLabel={`정답 점자, ${ordinalKo(current.compare.index)} 칸`}
              />
              <BrailleCellView
                cell={current.compare.actual}
                emphasized
                markDots={current.compare.missingDots}
                caption={
                  current.compare.missingDots.length > 0
                    ? `내 입력 · ${current.compare.missingDots.join("·")}번 누락`
                    : `내 입력 · ${current.compare.extraDots.join("·")}번 초과`
                }
                accessibilityLabel="내가 입력한 점자"
              />
            </View>
          )}
        </>
      )}
      <View style={{ marginTop: "auto" }}>
        {/* 틀린 칸이 남아 있으면 먼저 한 칸씩 보여 주고, 마지막 칸에서 확인한다 — 버튼은 하나다 */}
        <AppButton
          label={isLastStep ? "확인" : "다음 점자"}
          accessibilityHint={isLastStep ? "선생님의 다음 문제를 기다려요" : "다음 틀린 칸을 보여 줘요"}
          onPress={isLastStep ? () => lectureProblemSync.acknowledgeGrade() : () => setStep((s) => s + 1)}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 28 },
  compare: { borderWidth: 1, padding: 18, flexDirection: "row", justifyContent: "space-around" },
});
```

- [ ] **Step 6: 풀이 화면에서 채점 화면으로 넘기기**

`src/app/class/play.tsx`에서 "새 문제가 오면 입력을 비운다" effect 바로 아래에 추가:

```tsx
  // 제출이 채점되면(서버 응답) 채점 화면으로 간다. 결과 낭독은 채점 화면이 한다
  useEffect(() => {
    if (phase?.kind === "graded") router.replace("/class/grade");
  }, [phase, router]);
```

- [ ] **Step 7: 확인**

Run: `cd front && pnpm --filter @ieum/mobile typecheck && pnpm --filter @ieum/mobile exec jest && pnpm --filter @ieum/web build`
Expected: 타입 오류 없음, 전체 PASS, 웹 빌드 성공

- [ ] **Step 8: Commit**

```bash
git add front/apps/mobile/src/services/liveGradeFeedback.ts front/apps/mobile/src/app/class/grade.tsx front/apps/mobile/src/app/class/play.tsx front/apps/mobile/__tests__/liveGradeFeedback.test.ts
git commit -m "feat(lecture): 서버 채점 결과를 읽어 주는 수업 채점 화면을 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 실기기 확인 (사람과 함께)

**Files:** 없음 (확인 절차)

전제: `feat/lecture-problem-api`(백엔드: 문제 등록 수정·출제·답안 제출·스냅샷 채우기·metadata `currentProblemId`)가 `feat/lecture-problem`에 병합돼 있고, 이 브랜치를 그 위로 로컬에서만 병합해 둔다(`git merge feat/lecture-problem`, push 금지). 강사 웹(`feat/lecture-problem-web`)이 없으면 강사 역할은 curl로 대신한다: `POST /api/v1/lectures/{id}/problems {"targetText":"초성 ㄱ"}`, `PUT /api/v1/lectures/{id}/current-problem {"problemId":"…"}`.

- [ ] **Step 1: 개발 빌드**

네이티브 의존성·`app.json`을 바꾸지 않았으므로 prebuild 재생성은 필요 없다. 기존 개발 빌드가 설치된 기기에서 Metro만 띄운다:

```bash
cd C:/Users/SSAFY/Desktop/wt-problem-app/front/apps/mobile
npx expo start --dev-client
```

(기기에 개발 빌드가 없을 때만 `npx expo run:android`.) Metro를 끌 때는 그 PID로만 끈다.

- [ ] **Step 2: 로컬 backend**

`C:/Users/SSAFY/Desktop/wt-problem-api`(또는 병합한 작업 폴더)에서 `docker compose up -d --build backend`. 실기기면 앱의 API 주소를 PC LAN IP로 — `.env`는 사람이 고친다(에이전트는 열지 않는다).

- [ ] **Step 3: E2E (설계서 §8 수동 항목 2~6, TalkBack 켠 상태)**

1. 학습자 앱 입장 → 강사 수업 시작 → 풀이 화면이 **"선생님 문제를 기다리는 중이에요"**를 읽고, 손들기·마이크 버튼이 그대로 있으며, "문제 다시 확인"을 누르면 같은 문구를 다시 읽는다
2. 강사가 `초성 ㄱ`을 출제 → 앱이 **"선생님 문제예요. '초성 ㄱ'을 입력하세요"**를 읽고 풀이 화면(제목 `초성 ㄱ`)으로 바뀐다. "문제 다시 듣기"가 같은 문장을 읽는다
3. 4번 점을 입력하고 길게 눌러 제출 → 채점 화면이 "정답이에요!"를 읽는다. backend 로그/DB에서 `Idempotency-Key`가 UUID v4 형식으로 왔고 201이 났음을 확인한다(네이티브 `uuid.v4()` 동작 확인). 강사 웹이 있으면 학생 카드가 갱신된다
4. "확인" → **"제출했어요. 다음 문제를 기다려요"** 낭독, 대기 화면
5. 강사가 `그리고` 출제 → 대기 화면에서 풀이로 전환·낭독. 이번에는 틀리게 제출 → 채점 화면이 틀린 칸을 칸별로 읽는다("다음 점자"로 넘김). **채점 화면에 머문 채** 강사가 다음 문제를 출제 → 새 문제 안내가 나오고 풀이 화면으로 바뀐다
6. 문제를 푼 뒤 제출하지 않은 채 비행기 모드로 제출 → "제출하지 못했어요. 다시 길게 눌러 주세요." → 네트워크 복구 후 다시 길게 눌러 제출 → 채점된다(같은 키 재시도)
7. 풀이 중에 강사가 다음 문제로 바꾸고 metadata 이벤트 전에 제출하는 경합은 재현이 어렵다 — 가능하면 curl로 `PUT current-problem` 직후 바로 제출해 "문제가 바뀌었어요" 뒤 새 문제 안내가 나오는지 확인하고, 안 되면 기록만 한다
8. 제출한 뒤 앱을 강제 종료하고 다시 입장(또는 비행기 모드로 끊김 → "다시 연결") → **"제출했어요. 다음 문제를 기다려요"** 대기 상태로 복원된다. 제출 전 재접속이면 같은 문제로 풀이가 복원된다
9. 강사가 수업 종료 → 종료 화면이 "N문제 중 M문제를 맞혔어요"를 읽는다(이 기기에서 낸 답 기준)
10. 발언 기능 회귀: 풀이·대기 상태에서 손들기, 허용 → 마이크 켜기, 마이크를 켠 채 제출하면 꺼진 뒤 채점 화면으로 간다

Expected: 1~6, 8~10 통과. 7은 결과를 MR 본문에 기록한다.

---

## 완료 확인 (MR 전)

- [ ] `cd front && pnpm --filter @ieum/mobile exec jest` 전체 PASS
- [ ] `pnpm --filter @ieum/mobile typecheck` 통과, `pnpm --filter @ieum/web build` 통과 (공유 패키지 변경)
- [ ] `docker build --target test front` 성공 (MR verify와 동일)
- [ ] `git status --short`에 `.superpowers/`·`.env*`·`*.sql`이 스테이징되지 않았고, 변경 파일이 File Structure 표의 파일뿐이다 (`git diff --stat feat/lecture-problem...HEAD`)
- [ ] `git diff feat/lecture-problem...HEAD -- front/apps/mobile/package.json front/pnpm-lock.yaml` 출력 없음 (의존성 추가 없음)
- [ ] Task 6 E2E 결과와 후속 항목(`session/grade.tsx`의 닿지 않는 `kind === "class"` 분기, 종료 화면 집계가 기기 로컬인 점)을 MR 본문에 기록
- [ ] push하지 않는다 — MR은 사람이 `feat/lecture-problem-app` → `feat/lecture-problem`으로 올린다

## Self-Review

- 설계서 §5 대응: 대기 시작·낭독(Task 3 `applySnapshot` null→waiting, Task 4 play 대기 UI) / 손들기·마이크 유지(Task 4 play) / 로컬 문제 세트 교체(Task 4 waiting·play·classService) / metadata 감지·세대 규칙(Task 2) / 입장·재접속·변경 시 조회(Task 4 play mount, waiting `onCurrentProblemChanged`) / 풀이 전환 낭독(Task 3) / 제출: 마이크 끄기·문제별 UUID v4·재시도 같은 키·cells+durationMs(Task 3 submit, Task 4 play) / 채점 화면 서버 결과·확인 후 대기·채점 중 새 문제(Task 3 acknowledge, Task 5) / `submitted=true` 재접속(Task 3) / 409 NOT_CURRENT(Task 3). §8: mobile jest·typecheck·docker(완료 확인), 수동 E2E 2~6(Task 6)
- 타입 일관성: `ProblemPhase`·`Transition`·`FlowDeps`·`SubmitOutcome`·`LectureProblemSnapshot`·`onCurrentProblemChanged` 이름이 Task 1~5에서 같다. `acceptResult`/`acknowledgeGrade`는 낡으면 null을 돌려주고 `lectureProblemSync`가 그 null을 처리한다
- 기존 규칙 유지: `joinGeneration`·`audioOwner`·`releaseMic`·`pauseItdaForLecture` 경로는 건드리지 않고, 새 핸들러도 `isLive` 가드를 지난다. 낡은 세대 테스트는 기존 관례대로 해제 전에 붙잡은 리스너를 직접 부른다
