# 실시간 수업 문제 출제·답안 — 강사 웹 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 강사 웹 03 화면의 목 데이터(지금 문제·문제 세트·제출 현황·학생 답안)를 실제 데이터로 바꾼다 — 강사가 글자를 입력해 문제를 연결하고, 골라서 출제하고, 학습자가 제출한 점자와 정답/오답(틀린 이유)을 학생 카드에서 본다.

**Architecture:** HTTP 호출은 `domains/lecture/api/lectureApi`(등록·출제·답안 목록, 409 후보 해석), 점형 표기·틀린 이유·제출 현황·문제 세트 상태는 React·LiveKit에 의존하지 않는 순수 모듈(`model/braille`, `model/answerView`, `model/problemSet`), LiveKit 데이터 메시지 해석은 순수 모듈 `live/lectureSignal`, 구독은 `live/instructorRoom`이 맡는다. 문제 입력·후보·목록 UI는 `domains/lecture/ui/ProblemSetPanel`, 페이지는 조합과 갱신 규칙(출제 직후·`ANSWER_SUBMITTED` 신호·진입 때 답안 재조회, 세대 번호로 마지막 응답만 반영)만 맡는다. 신호는 "다시 조회하라"는 뜻일 뿐이며 답 내용은 권한 검사를 거친 HTTP로만 받는다.

**Tech Stack:** React 19, Vite 6, TypeScript 5.8, `@ieum/api-client`(workspace), `livekit-client@2.22.3`, vitest(node 환경, `src/**/__tests__/**/*.test.ts`)

**Spec:** `docs/superpowers/specs/2026-09-27-lecture-problem-delivery-design.md` §2, §4, §8 / 계약(원문 그대로 사용): `C:/Users/SSAFY/Desktop/S15P21B201/.superpowers/sdd/problem-contract.md` / 화면 원본: 와이어프레임 `C:/Users/SSAFY/Desktop/이음 강사웹.html` 03

## Global Constraints

- 브랜치: `feat/lecture-problem-web` (`feat/lecture-problem`에서 분기), 작업 위치 `C:/Users/SSAFY/Desktop/wt-problem-web`, MR 대상 `feat/lecture-problem`. **push하지 않는다** (사람이 한다)
- 작업 범위: `front/apps/web` + `front/packages/api-client/src/{errors.ts,client.ts}`의 **추가형 변경 1건**(`ApiError.body`, Task 1). backend·mobile·lockfile은 건드리지 않는다. 의존성 추가 없음
- `packages/api-client`는 학습자 앱도 쓴다. 변경은 선택 인자·새 필드 추가만 한다(기존 3인자 생성자·필드 불변). 앱 계획(`feat/lecture-problem-app`)이 같은 파일을 고치면 병합 때 이 추가만 남기면 된다
- 커밋: `feat(lecture): 한글 제목` + 빈 줄 + 정확히 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `--no-verify`·`--force` 금지
- ID는 전부 10진수 문자열. 경로에 넣을 때 `encodeURIComponent`. baseUrl `/api/v1`은 `apiClient`에 이미 있다
- API (계약 원문 그대로):
  - `POST /lectures/{lectureId}/problems` body `{"targetText": "초성 ㄱ"}` → 201 새 연결 / 200 이미 연결 → LinkedProblem `{ "problemId": "26578", "targetText": "ㄱ", "displayText": "초성 ㄱ", "problemType": "CONSONANT", "cellCount": 1, "expectedPattern": [8] }`
    - 409 `PROBLEM_TEXT_AMBIGUOUS` body `{"code","message","candidates":[{"input":"초성 ㄱ","displayText":"초성 ㄱ"},{"input":"받침 ㄱ","displayText":"받침 ㄱ"}]}` — 구분어 없는 낱자, 후보 ≥2
    - 404 `PROBLEM_NOT_FOUND`(구분어 입력에 맞는 문제 없음), 409 `INVALID_LECTURE_STATE`(CLOSED), 403 `INSTRUCTOR_ACCESS_REQUIRED`, 404 `LECTURE_NOT_FOUND`, 400 빈 targetText
  - `PUT /lectures/{lectureId}/current-problem` body `{"problemId": "26578"}` (OPEN 전용) → 200 강사 Snapshot. 404 `PROBLEM_NOT_FOUND`(미연결), 503 `MEDIA_SERVER_UNAVAILABLE`(DB는 저장됨, 같은 요청 재시도로 metadata 복구)
  - `GET /lectures/{lectureId}/problems/{problemId}/answers` (상태 무관) → 200 `{ "problemId", "answers": [ { "userId","username","isCorrect","attemptedAt","cells":[AnswerCell] } ] }` — 제출한 학습자만, attemptedAt 오름차순
  - AnswerCell `{ "cellIndex": 1, "inputPattern": 8, "expectedPattern": 8, "diffMask": 0, "isCorrect": true }` — cellIndex 1부터, 칸은 max(입력 길이, 정답 길이), 없는 쪽은 0, diffMask = input XOR expected
  - 강사 `GET /lectures/{lectureId}`: `currentProblem` = LinkedProblem | null, `linkedProblems` = LinkedProblem[] (연결 순서)
  - 점형은 6비트 정수, bit0 = 1점 … bit5 = 6점. 유니코드 점자 = U+2800 + 점형
- LiveKit 답안 신호 (계약 원문 그대로): 백엔드 → 강사 identity에게만 데이터 메시지, topic `lecture`, reliable, payload UTF-8 JSON `{"type":"ANSWER_SUBMITTED","problemId":"26578"}`. 답 내용 없음
- livekit-client 2.22.3 확인 결과 (`node_modules/livekit-client/dist/src/room/Room.d.ts:340`): `dataReceived: (payload: NonSharedUint8Array, participant?: RemoteParticipant, kind?: DataPacket_Kind, topic?: string, encryptionType?: Encryption_Type) => void`, `RoomEvent.DataReceived = "dataReceived"`. **서버가 보낸 메시지는 `participant`가 undefined**이므로 보낸 사람으로 거르지 않는다(신호는 재조회 트리거일 뿐이라 위조돼도 무해)
- 화면 문구 (설계 §4 원문):
  - 입력칸 위 안내: `초성 ㄱ은 '초성'을 입력하고 한 칸 띄운 뒤 ㄱ을 입력하세요. 받침 ㄱ, 모음 ㅏ, 음절 가도 같은 방식이에요.`
  - 목록 상태: `출제됨` / `지금 문제` / `대기`. SCHEDULED 안내: `수업을 시작하면 출제할 수 있어요`
  - 현재 문제 없음: `오른쪽에서 문제를 골라 출제하세요`
  - 학생 답안: 정답 = 제출 점자 + `정답`, 오답 = 제출 점자 + 틀린 이유, 미제출 = `미착수`. **걸린 시간은 표시하지 않는다**
  - 틀린 이유: `칸 수 다름` / `첫 칸 6점 누락` / `둘째 칸 3점 추가` / `첫 칸 1점 누락, 4점 추가`
- 제거: `liveMock.ts`, 장치 연결 점과 범례, 미입장 학생, 출제됨 문제의 반 정답률, 문제 은행 검색 목 카드(설계 §9 범위 밖 + 목 파일 삭제)
- 기존 동작 유지: `joinSeqRef` 세대 번호와 매 await 뒤 확인, `joining`·`busy` 잠금, `releaseRoom`, 종료 503 처리, 발언 허용·손들기·학생 소리 켜기, instructorRoom의 `isStale` 규칙
- 디자인: 인라인 `style` + `theme`/`styles` 상수. UI 라이브러리 추가 없음
- ESLint `@typescript-eslint/no-explicit-any: error` — `any` 금지. lint **경고 0개**(`react-hooks/exhaustive-deps`, `react-refresh/only-export-components` 포함)
- 검증: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
- 서버를 띄웠으면 **PID로만** 멈춘다 (`netstat -ano | findstr :5173` → `taskkill //PID <pid> //F`). 이름 단위 종료 금지
- `.env*`·`*.pem` 파일은 읽지도, 고치지도 않는다

## File Structure (`front/` 아래)

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `packages/api-client/src/errors.ts`, `client.ts` | `ApiError.body` — 오류 응답 본문 원문(409 `candidates` 읽기용) | Modify |
| `apps/web/src/domains/lecture/model/types.ts` | `LinkedProblem`, `ProblemType`, `AnswerCell`, `StudentAnswer`, `ProblemAnswers`, `ProblemCandidate`, Snapshot 필드 | Modify |
| `apps/web/src/domains/lecture/api/lectureApi.ts` | `registerProblem`, `deliverProblem`, `listAnswers`, `ambiguousCandidates(error)` | Modify |
| `apps/web/src/shared/ui/errorMessage.ts` | `PROBLEM_NOT_FOUND`, `PROBLEM_TEXT_AMBIGUOUS` 문구 | Modify |
| `apps/web/src/domains/lecture/model/braille.ts` | `dotsOf`, `dotLabel`, `brailleGlyph` | Create |
| `apps/web/src/domains/lecture/model/answerView.ts` | `submittedPatterns`, `wrongReason`, `studentAnswerView`, `progressCounts` | Create |
| `apps/web/src/domains/lecture/live/lectureSignal.ts` | topic `lecture` JSON 안전 해석 (LiveKit import 없음) | Create |
| `apps/web/src/domains/lecture/live/instructorRoom.ts` | `onAnswerSubmitted` 리스너 (`RoomEvent.DataReceived`) | Modify |
| `apps/web/src/domains/lecture/model/problemSet.ts` | `problemSetItems`(출제됨/지금 문제/대기), `mergeLinkedProblem` | Create |
| `apps/web/src/domains/lecture/ui/ProblemSetPanel.tsx` | 입력·안내·후보 버튼·목록·출제 버튼 | Create |
| `apps/web/src/pages/LiveLecturePage.tsx` | 지금 문제 카드·제출 현황·학생 답안·갱신 규칙, 목 제거 | Modify |
| `apps/web/src/domains/lecture/mock/liveMock.ts` | — | Delete |
| 테스트: `shared/api/__tests__/apiErrorBody.test.ts`(Create), `domains/lecture/api/__tests__/lectureApi.test.ts`, `shared/ui/__tests__/errorMessage.test.ts`, `domains/lecture/model/__tests__/{braille,answerView,problemSet}.test.ts`(Create), `domains/lecture/live/__tests__/{lectureSignal(Create),instructorRoom}.test.ts` | vitest | |

---

### Task 0: 작업 브랜치 준비

**Files:** 없음 (git 작업)

- [ ] **Step 1: `feat/lecture-problem`에서 워크트리 분기**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git rev-parse --verify feat/lecture-problem
git worktree add -b feat/lecture-problem-web C:/Users/SSAFY/Desktop/wt-problem-web feat/lecture-problem
cd C:/Users/SSAFY/Desktop/wt-problem-web
git ls-files docs/superpowers/specs/2026-09-27-lecture-problem-delivery-design.md
cd front && pnpm install --frozen-lockfile
```

`git ls-files`가 설계서 경로를 출력하지 않으면 중단하고 사람에게 알린다. 이 계획 문서는 상위 워크트리(`C:/Users/SSAFY/Desktop/wt-lecture-problem/docs/superpowers/plans/`)에 있다 — 이 브랜치로 복사·커밋하지 않는다. push하지 않는다.

- [ ] **Step 2: 출발점 확인**

Run: `cd C:/Users/SSAFY/Desktop/wt-problem-web/front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: 모두 성공, lint 경고 0. 실패하면 시작하지 말고 사람에게 알린다.

이후 모든 경로는 `C:/Users/SSAFY/Desktop/wt-problem-web` 기준이다.

---

### Task 1: 타입·API 호출·409 후보 해석

**Files:**
- Modify: `front/packages/api-client/src/errors.ts`, `front/packages/api-client/src/client.ts`
- Modify: `front/apps/web/src/domains/lecture/model/types.ts`
- Modify: `front/apps/web/src/domains/lecture/api/lectureApi.ts`
- Modify: `front/apps/web/src/shared/ui/errorMessage.ts`
- Test: `front/apps/web/src/shared/api/__tests__/apiErrorBody.test.ts` (Create), `front/apps/web/src/domains/lecture/api/__tests__/lectureApi.test.ts`, `front/apps/web/src/shared/ui/__tests__/errorMessage.test.ts`

**Interfaces:**
- Produces:
  - `ApiError.body: unknown` — 오류 응답 본문(JSON 파싱 결과). 본문이 없으면 `undefined`. 생성자 `new ApiError(status, code, message, body?)`
  - `type ProblemType = 'CONSONANT' | 'VOWEL' | 'CONTRACTION' | 'CONTRACTION_WORD' | 'WORD' | 'SENTENCE'`
  - `interface LinkedProblem { problemId: string; targetText: string; displayText: string; problemType: ProblemType; cellCount: number; expectedPattern: number[] }`
  - `InstructorLecture`에 `currentProblem: LinkedProblem | null`, `linkedProblems: LinkedProblem[]` 추가
  - `interface AnswerCell { cellIndex: number; inputPattern: number; expectedPattern: number; diffMask: number; isCorrect: boolean }`
  - `interface StudentAnswer { userId: string; username: string; isCorrect: boolean; attemptedAt: string; cells: AnswerCell[] }`
  - `interface ProblemAnswers { problemId: string; answers: StudentAnswer[] }`
  - `interface ProblemCandidate { input: string; displayText: string }`
  - `lectureApi.registerProblem(lectureId: string, targetText: string): Promise<LinkedProblem>` — 앞뒤 공백 제거 후 `POST /lectures/{id}/problems`
  - `lectureApi.deliverProblem(lectureId: string, problemId: string): Promise<InstructorLecture>` — `PUT /lectures/{id}/current-problem`
  - `lectureApi.listAnswers(lectureId: string, problemId: string): Promise<ProblemAnswers>` — `GET /lectures/{id}/problems/{problemId}/answers`
  - `ambiguousCandidates(error: unknown): ProblemCandidate[] | null` (`lectureApi.ts`에서 export) — 409 `PROBLEM_TEXT_AMBIGUOUS`이고 본문 `candidates`에 올바른 후보가 하나 이상이면 그 목록, 아니면 null
  - `errorMessage`: `PROBLEM_NOT_FOUND` → `'해당하는 문제를 찾지 못했어요. 구분어와 글자를 확인해 주세요.'`, `PROBLEM_TEXT_AMBIGUOUS` → `'어떤 문제인지 아래에서 골라 주세요.'`

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/web/src/shared/api/__tests__/apiErrorBody.test.ts` (새 파일):

```ts
import { ApiError, createClient, type TokenStore } from '@ieum/api-client'
import { describe, expect, it } from 'vitest'

/** 클라이언트가 실제로 읽는 필드만 가진 가짜 응답 */
function fakeResponse(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body === undefined ? '' : JSON.stringify(body)),
  } as unknown as Response
}

const tokens: TokenStore = {
  getAccess: () => 'access',
  getRefresh: async () => null,
  save: async () => {},
  clear: async () => {},
}

function clientReturning(status: number, body?: unknown) {
  return createClient({
    baseUrl: '/api/v1',
    platform: 'WEB',
    tokens,
    onSessionExpired: () => {},
    fetchFn: (async () => fakeResponse(status, body)) as unknown as typeof fetch,
  })
}

describe('ApiError.body', () => {
  it('오류 응답 본문 전체를 남겨 공통 필드 밖의 값(candidates)을 읽을 수 있다', async () => {
    const body = {
      code: 'PROBLEM_TEXT_AMBIGUOUS',
      message: '어떤 문제인지 골라 주세요',
      candidates: [{ input: '초성 ㄱ', displayText: '초성 ㄱ' }],
    }
    const error = await clientReturning(409, body)
      .post('/lectures/1/problems', { targetText: 'ㄱ' })
      .catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('PROBLEM_TEXT_AMBIGUOUS')
    expect((error as ApiError).body).toEqual(body)
  })

  it('본문이 없는 오류는 body가 undefined다', async () => {
    const error = await clientReturning(503).get('/x').catch((e: unknown) => e)

    expect((error as ApiError).code).toBe('UNKNOWN_ERROR')
    expect((error as ApiError).body).toBeUndefined()
  })

  it('기존 3인자 생성도 그대로 동작한다', () => {
    const error = new ApiError(400, 'INVALID_REQUEST_INPUT', 'm')
    expect(error.body).toBeUndefined()
    expect(error.code).toBe('INVALID_REQUEST_INPUT')
  })
})
```

`lectureApi.test.ts` — 첫 줄 import 두 줄을 다음으로 바꾼다:

```ts
import { ApiError, type ApiClient } from '@ieum/api-client'
import { describe, expect, it, vi } from 'vitest'
import { ambiguousCandidates, createLectureApi, toScheduledAtUtc } from '../lectureApi'
```

`'datetime-local 값을 UTC ISO로 바꾼다'` 테스트 **앞에** 넣는다:

```ts
  it('문제 등록은 앞뒤 공백을 지운 targetText를 보낸다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).registerProblem('42', '  초성 ㄱ  ')
    expect(client.post).toHaveBeenCalledWith('/lectures/42/problems', { targetText: '초성 ㄱ' })
  })

  it('출제와 답안 목록 경로', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    await lectures.deliverProblem('42', '26578')
    await lectures.listAnswers('42', '26578')

    expect(client.put).toHaveBeenCalledWith('/lectures/42/current-problem', { problemId: '26578' })
    expect(client.get).toHaveBeenCalledWith('/lectures/42/problems/26578/answers')
  })

  it('문제 경로의 lectureId와 problemId를 인코딩한다', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    const id = '42/../etc'
    const problemId = '7/../1'
    await lectures.registerProblem(id, 'ㄱ')
    await lectures.deliverProblem(id, problemId)
    await lectures.listAnswers(id, problemId)

    const encoded = encodeURIComponent(id)
    expect(client.post).toHaveBeenCalledWith(`/lectures/${encoded}/problems`, { targetText: 'ㄱ' })
    expect(client.put).toHaveBeenCalledWith(`/lectures/${encoded}/current-problem`, { problemId })
    expect(client.get).toHaveBeenCalledWith(`/lectures/${encoded}/problems/${encodeURIComponent(problemId)}/answers`)
  })
```

같은 파일 **끝**(마지막 `})` 뒤)에 새 describe를 넣는다:

```ts

describe('ambiguousCandidates', () => {
  const candidates = [
    { input: '초성 ㄱ', displayText: '초성 ㄱ' },
    { input: '받침 ㄱ', displayText: '받침 ㄱ' },
  ]
  const ambiguous = (body: unknown) => new ApiError(409, 'PROBLEM_TEXT_AMBIGUOUS', 'm', body)

  it('409 PROBLEM_TEXT_AMBIGUOUS 본문의 후보를 돌려준다', () => {
    expect(ambiguousCandidates(ambiguous({ code: 'PROBLEM_TEXT_AMBIGUOUS', message: 'm', candidates }))).toEqual(candidates)
  })

  it('다른 오류·ApiError가 아닌 값이면 null', () => {
    expect(ambiguousCandidates(new ApiError(404, 'PROBLEM_NOT_FOUND', 'm', { candidates }))).toBeNull()
    expect(ambiguousCandidates(new Error('boom'))).toBeNull()
    expect(ambiguousCandidates(undefined)).toBeNull()
  })

  it('본문이 없거나 candidates가 배열이 아니면 null', () => {
    expect(ambiguousCandidates(ambiguous(undefined))).toBeNull()
    expect(ambiguousCandidates(ambiguous(null))).toBeNull()
    expect(ambiguousCandidates(ambiguous({ candidates: 'x' }))).toBeNull()
  })

  it('형식이 어긋난 후보는 빼고, 남는 것이 없으면 null', () => {
    expect(
      ambiguousCandidates(ambiguous({ candidates: [null, { input: 3, displayText: 'x' }, { input: ' ', displayText: 'x' }, candidates[1]] })),
    ).toEqual([candidates[1]])
    expect(ambiguousCandidates(ambiguous({ candidates: [{ input: '초성 ㄱ' }] }))).toBeNull()
  })
})
```

`errorMessage.test.ts` — `it.each` 표의 `['LECTURE_PARTICIPANT_NOT_FOUND', …]` 줄 **뒤에** 넣는다:

```ts
    ['PROBLEM_NOT_FOUND', '해당하는 문제를 찾지 못했어요. 구분어와 글자를 확인해 주세요.'],
    ['PROBLEM_TEXT_AMBIGUOUS', '어떤 문제인지 아래에서 골라 주세요.'],
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — `apiErrorBody`의 `body` 비교 실패(undefined), `registerProblem is not a function`, `ambiguousCandidates`가 export되지 않음, errorMessage 2건 기본 문구

- [ ] **Step 3: 구현**

`front/packages/api-client/src/errors.ts`의 `ApiError` 클래스 전체를 다음으로 바꾼다:

```ts
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  /**
   * 오류 응답 본문 원문(JSON). 공통 `{code, message}` 밖의 필드를 읽을 때 쓴다 —
   * 예: 409 `PROBLEM_TEXT_AMBIGUOUS`의 `candidates`. 본문이 없으면 undefined
   */
  readonly body: unknown;

  constructor(status: number, code: string, message: string, body?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.body = body;
  }
}
```

`front/packages/api-client/src/client.ts`에서 찾을 코드:

```ts
      throw new ApiError(
        response.status,
        body.code ?? "UNKNOWN_ERROR",
        body.message ?? "요청을 처리하지 못했어요.",
      );
```

바꿀 코드:

```ts
      throw new ApiError(
        response.status,
        body.code ?? "UNKNOWN_ERROR",
        body.message ?? "요청을 처리하지 못했어요.",
        payload,
      );
```

`front/apps/web/src/domains/lecture/model/types.ts` — `InstructorLecture` 인터페이스 전체를 다음으로 바꾼다:

```ts
export interface InstructorLecture {
  lectureId: string
  title: string
  description: string | null
  status: LectureStatus
  scheduledAt: string | null
  startedAt: string | null
  endedAt: string | null
  createdAt: string
  participationCode: string
  /** 지금 출제된 문제. 없으면 null */
  currentProblem: LinkedProblem | null
  /** 이 수업에 연결된 문제 (연결 순서) */
  linkedProblems: LinkedProblem[]
}
```

파일 끝(`SpeakingResult` 뒤)에 추가한다:

```ts

/** problems.level */
export type ProblemType = 'CONSONANT' | 'VOWEL' | 'CONTRACTION' | 'CONTRACTION_WORD' | 'WORD' | 'SENTENCE'

/** 수업에 연결된 문제. 문제 등록 응답·강사 Snapshot에 쓴다 */
export interface LinkedProblem {
  problemId: string
  targetText: string
  /** 구분어가 필요한 문제(초성·받침·모음·음절)는 구분어를 붙인 표시용 문자열 */
  displayText: string
  problemType: ProblemType
  cellCount: number
  /** 칸별 정답 점형 (6비트, bit0 = 1점) */
  expectedPattern: number[]
}

/** 칸별 채점. 칸은 max(입력, 정답) 길이이고 없는 쪽은 0 */
export interface AnswerCell {
  cellIndex: number
  inputPattern: number
  expectedPattern: number
  diffMask: number
  isCorrect: boolean
}

export interface StudentAnswer {
  userId: string
  username: string
  isCorrect: boolean
  attemptedAt: string
  cells: AnswerCell[]
}

/** 강사 답안 목록. 제출한 학습자만 담는다 */
export interface ProblemAnswers {
  problemId: string
  answers: StudentAnswer[]
}

/** 409 PROBLEM_TEXT_AMBIGUOUS 후보. input으로 다시 등록한다 */
export interface ProblemCandidate {
  input: string
  displayText: string
}
```

`front/apps/web/src/domains/lecture/api/lectureApi.ts` — 1~3행 import를 다음으로 바꾼다:

```ts
import { ApiError, type ApiClient } from '@ieum/api-client'
import { api } from '../../../shared/api/apiClient'
import type {
  CreateLectureInput,
  InstructorLecture,
  LecturePage,
  LinkedProblem,
  MediaToken,
  ProblemAnswers,
  ProblemCandidate,
  SpeakingResult,
} from '../model/types'
```

`setSpeaking` 항목 **뒤에**(객체 닫는 `}` 앞) 추가한다:

```ts

    /**
     * 글자 입력으로 문제를 이 수업에 연결한다. 이미 연결된 문제면 서버가 200으로 같은 문제를 돌려준다.
     * 구분어 없는 낱자가 여러 문제에 해당하면 409 PROBLEM_TEXT_AMBIGUOUS — ambiguousCandidates로 후보를 꺼낸다
     */
    registerProblem: (lectureId: string, targetText: string) =>
      client.post<LinkedProblem>(`/lectures/${encodeURIComponent(lectureId)}/problems`, { targetText: targetText.trim() }),

    /** 출제(OPEN 전용). 503이면 DB는 저장됐고 같은 요청으로 방 metadata만 다시 쓴다 */
    deliverProblem: (lectureId: string, problemId: string) =>
      client.put<InstructorLecture>(`/lectures/${encodeURIComponent(lectureId)}/current-problem`, { problemId }),

    /** 문제별 제출 답안. 제출한 학습자만 온다 — 미착수는 접속자 목록과 맞춰 화면이 판단한다 */
    listAnswers: (lectureId: string, problemId: string) =>
      client.get<ProblemAnswers>(
        `/lectures/${encodeURIComponent(lectureId)}/problems/${encodeURIComponent(problemId)}/answers`,
      ),
```

`toScheduledAtUtc` 함수 **앞에** 추가한다:

```ts
function isCandidate(value: unknown): value is ProblemCandidate {
  if (typeof value !== 'object' || value === null) return false
  const { input, displayText } = value as Record<string, unknown>
  return typeof input === 'string' && input.trim() !== '' && typeof displayText === 'string'
}

/** 409 PROBLEM_TEXT_AMBIGUOUS 오류 본문의 후보. 다른 오류이거나 올바른 후보가 없으면 null */
export function ambiguousCandidates(error: unknown): ProblemCandidate[] | null {
  if (!(error instanceof ApiError) || error.code !== 'PROBLEM_TEXT_AMBIGUOUS') return null
  const raw = (error.body as { candidates?: unknown } | null | undefined)?.candidates
  if (!Array.isArray(raw)) return null
  const candidates = raw.filter(isCandidate)
  return candidates.length > 0 ? candidates : null
}

```

`front/apps/web/src/shared/ui/errorMessage.ts` — `MESSAGES`의 `LECTURE_PARTICIPANT_NOT_FOUND` 줄 **뒤에** 추가한다:

```ts
  PROBLEM_NOT_FOUND: '해당하는 문제를 찾지 못했어요. 구분어와 글자를 확인해 주세요.',
  PROBLEM_TEXT_AMBIGUOUS: '어떤 문제인지 아래에서 골라 주세요.',
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build && pnpm -r typecheck`
Expected: PASS(apiErrorBody 3 + lectureApi 3 + ambiguousCandidates 4 + errorMessage 2 신규), lint 경고 0, build 성공, 모든 패키지 typecheck 성공(학습자 앱이 `ApiError`를 3인자로 만드는 곳이 그대로 컴파일돼야 한다)

`pnpm -r typecheck`가 mobile에서 이 변경과 무관한 이유로 실패하면 Task 0 출발점에서도 실패하는지 `git stash`로 확인하고 사람에게 알린다.

- [ ] **Step 5: 학습자 앱 회귀 확인**

Run: `cd front && pnpm --filter @ieum/mobile test`
Expected: PASS (api-client 테스트는 `toMatchObject`라 새 필드에 영향 없다)

- [ ] **Step 6: Commit**

```bash
git add front/packages/api-client/src/errors.ts front/packages/api-client/src/client.ts front/apps/web/src/domains/lecture/model/types.ts front/apps/web/src/domains/lecture/api front/apps/web/src/shared/ui/errorMessage.ts front/apps/web/src/shared/ui/__tests__/errorMessage.test.ts front/apps/web/src/shared/api/__tests__/apiErrorBody.test.ts
git commit -m "feat(lecture): 강사 웹에 수업 문제 등록·출제·답안 목록 API 호출을 추가한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 점형 표기·틀린 이유·제출 현황 순수 함수

**Files:**
- Create: `front/apps/web/src/domains/lecture/model/braille.ts`
- Create: `front/apps/web/src/domains/lecture/model/answerView.ts`
- Test: `front/apps/web/src/domains/lecture/model/__tests__/braille.test.ts`, `front/apps/web/src/domains/lecture/model/__tests__/answerView.test.ts`

**Interfaces:**
- Consumes: `AnswerCell`, `StudentAnswer` (Task 1)
- Produces:
  - `dotsOf(pattern: number): number[]` — 볼록한 점 번호 오름차순. `8` → `[4]`
  - `dotLabel(patterns: readonly number[]): string` — `[21, 42]` → `'1·3·5 / 2·4·6'`, 빈 칸은 `'빈칸'`
  - `brailleGlyph(patterns: readonly number[]): string` — `String.fromCharCode(0x2800 + (p & 0x3f))`를 이어 붙임
  - `submittedPatterns(cells: readonly AnswerCell[]): number[]` — cellIndex 순서의 입력 점형에서 뒤쪽 0(=입력 없는 칸)을 뺀 것
  - `wrongReason(cells: readonly AnswerCell[]): string`
  - `type AnswerState = 'correct' | 'wrong' | 'idle'`, `interface StudentAnswerView { state: AnswerState; glyph: string | null; detail: string }`
  - `studentAnswerView(answer: StudentAnswer | undefined): StudentAnswerView`
  - `interface ProgressCounts { connected: number; submitted: number; correct: number; wrong: number; notStarted: number }`
  - `progressCounts(connectedUserIds: readonly string[], answers: readonly StudentAnswer[]): ProgressCounts` — **접속 학생 기준**으로만 센다(접속하지 않은 제출자는 세지 않는다 → 미착수가 음수가 되지 않는다)

칸 수 판정 규칙: 계약상 답안 칸은 max(입력, 정답) 길이이고 없는 쪽은 0이라 원래 길이가 사라진다. 그래서 입력·정답 각각 **뒤쪽 0을 뺀 길이**를 실제 칸 수로 본다(빈 칸 0을 끝에 일부러 제출한 경우만 구분 못 한다 — 점자 입력에서 사실상 없다). 틀린 칸은 `diffMask`가 아니라 `inputPattern ^ expectedPattern`으로 다시 계산한다(표시가 서버 필드 하나에 기대지 않게).

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/web/src/domains/lecture/model/__tests__/braille.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { brailleGlyph, dotLabel, dotsOf } from '../braille'

describe('braille', () => {
  it('6비트 점형을 점 번호로 바꾼다 (bit0 = 1점)', () => {
    expect(dotsOf(8)).toEqual([4])
    expect(dotsOf(21)).toEqual([1, 3, 5])
    expect(dotsOf(63)).toEqual([1, 2, 3, 4, 5, 6])
    expect(dotsOf(0)).toEqual([])
    expect(dotsOf(64 + 1)).toEqual([1])
  })

  it('칸별 점 번호 문자열을 만든다', () => {
    expect(dotLabel([21, 42])).toBe('1·3·5 / 2·4·6')
    expect(dotLabel([8])).toBe('4')
    expect(dotLabel([0, 1])).toBe('빈칸 / 1')
    expect(dotLabel([])).toBe('')
  })

  it('유니코드 점자 문자로 바꾼다', () => {
    expect(brailleGlyph([8])).toBe('\u2808')
    expect(brailleGlyph([21, 42])).toBe('\u2815\u282A')
    expect(brailleGlyph([0])).toBe('\u2800')
    expect(brailleGlyph([63 + 64])).toBe('\u283F')
  })
})
```

`front/apps/web/src/domains/lecture/model/__tests__/answerView.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { progressCounts, studentAnswerView, submittedPatterns, wrongReason } from '../answerView'
import type { AnswerCell, StudentAnswer } from '../types'

function cell(cellIndex: number, inputPattern: number, expectedPattern: number): AnswerCell {
  const diffMask = inputPattern ^ expectedPattern
  return { cellIndex, inputPattern, expectedPattern, diffMask, isCorrect: diffMask === 0 }
}

function answer(userId: string, isCorrect: boolean, cells: AnswerCell[]): StudentAnswer {
  return { userId, username: `u${userId}`, isCorrect, attemptedAt: '2026-09-27T01:00:00Z', cells }
}

describe('wrongReason', () => {
  it('첫 칸에서 빠진 점', () => {
    // 정답 2·4·6(42), 입력 2·4(10)
    expect(wrongReason([cell(1, 10, 42)])).toBe('첫 칸 6점 누락')
  })

  it('둘째 칸에 더한 점', () => {
    // 첫 칸 맞음, 둘째 칸 정답 1점(1) 입력 1·3점(5)
    expect(wrongReason([cell(1, 8, 8), cell(2, 5, 1)])).toBe('둘째 칸 3점 추가')
  })

  it('누락과 추가가 함께 있으면 쉼표로 잇는다', () => {
    expect(wrongReason([cell(1, 8, 1)])).toBe('첫 칸 1점 누락, 4점 추가')
  })

  it('여러 점은 가운뎃점으로 잇는다', () => {
    expect(wrongReason([cell(1, 1, 7)])).toBe('첫 칸 2·3점 누락')
  })

  it('틀린 칸이 여럿이면 첫 번째 칸만 말한다', () => {
    expect(wrongReason([cell(1, 8, 8), cell(2, 8, 8), cell(3, 0 + 2, 3), cell(4, 1, 2)])).toBe('셋째 칸 1점 누락')
  })

  it('cellIndex 순서로 본다', () => {
    expect(wrongReason([cell(2, 5, 1), cell(1, 10, 42)])).toBe('첫 칸 6점 누락')
  })

  it('입력이 길거나 짧으면 칸 수 다름', () => {
    expect(wrongReason([cell(1, 8, 8), cell(2, 5, 0)])).toBe('칸 수 다름')
    expect(wrongReason([cell(1, 8, 8), cell(2, 0, 5)])).toBe('칸 수 다름')
  })

  it('다른 점이 없으면 오답이라고만 한다', () => {
    expect(wrongReason([cell(1, 8, 8)])).toBe('오답')
  })
})

describe('submittedPatterns', () => {
  it('입력이 없는 뒤쪽 칸을 뺀다', () => {
    expect(submittedPatterns([cell(2, 0, 5), cell(1, 8, 8)])).toEqual([8])
    expect(submittedPatterns([cell(1, 8, 8), cell(2, 5, 0)])).toEqual([8, 5])
    expect(submittedPatterns([cell(1, 0, 8)])).toEqual([])
  })
})

describe('studentAnswerView', () => {
  it('답안이 없으면 미착수', () => {
    expect(studentAnswerView(undefined)).toEqual({ state: 'idle', glyph: null, detail: '미착수' })
  })

  it('정답은 제출 점자와 "정답"', () => {
    expect(studentAnswerView(answer('1', true, [cell(1, 8, 8)]))).toEqual({ state: 'correct', glyph: '\u2808', detail: '정답' })
  })

  it('오답은 제출 점자와 틀린 이유', () => {
    expect(studentAnswerView(answer('1', false, [cell(1, 10, 42)]))).toEqual({
      state: 'wrong',
      glyph: '\u280A',
      detail: '첫 칸 6점 누락',
    })
  })

  it('입력 칸이 하나도 없으면 점자 없이 이유만', () => {
    expect(studentAnswerView(answer('1', false, [cell(1, 0, 8)]))).toEqual({ state: 'wrong', glyph: null, detail: '칸 수 다름' })
  })
})

describe('progressCounts', () => {
  it('접속 학생 기준으로 제출·정답·오답·미착수를 센다', () => {
    const answers = [answer('1', true, [cell(1, 8, 8)]), answer('2', false, [cell(1, 1, 8)]), answer('9', true, [cell(1, 8, 8)])]
    expect(progressCounts(['1', '2', '3'], answers)).toEqual({ connected: 3, submitted: 2, correct: 1, wrong: 1, notStarted: 1 })
  })

  it('접속 학생이 없으면 모두 0', () => {
    expect(progressCounts([], [answer('1', true, [])])).toEqual({ connected: 0, submitted: 0, correct: 0, wrong: 0, notStarted: 0 })
  })

  it('같은 userId가 두 번 와도 한 명으로 센다', () => {
    expect(progressCounts(['1', '1'], [])).toEqual({ connected: 1, submitted: 0, correct: 0, wrong: 0, notStarted: 1 })
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — `../braille`, `../answerView` 모듈이 없다

- [ ] **Step 3: 구현**

`front/apps/web/src/domains/lecture/model/braille.ts`:

```ts
/** 점자 셀은 6비트 정수다 (bit0 = 1점 … bit5 = 6점, 유니코드 U+2800 + 값) */

/** 볼록한 점 번호 (오름차순). 6비트 밖의 비트는 무시한다 */
export function dotsOf(pattern: number): number[] {
  const dots: number[] = []
  for (let dot = 1; dot <= 6; dot += 1) {
    if (pattern & (1 << (dot - 1))) dots.push(dot)
  }
  return dots
}

/** 칸별 점 번호 문자열. [21, 42] → "1·3·5 / 2·4·6" */
export function dotLabel(patterns: readonly number[]): string {
  return patterns
    .map((pattern) => {
      const dots = dotsOf(pattern)
      return dots.length > 0 ? dots.join('·') : '빈칸'
    })
    .join(' / ')
}

/** 유니코드 점자 문자열 */
export function brailleGlyph(patterns: readonly number[]): string {
  return patterns.map((pattern) => String.fromCharCode(0x2800 + (pattern & 0x3f))).join('')
}
```

`front/apps/web/src/domains/lecture/model/answerView.ts`:

```ts
import { brailleGlyph, dotsOf } from './braille'
import type { AnswerCell, StudentAnswer } from './types'

/** 서버는 한 문제에 최대 12칸까지 받는다 */
const ORDINALS = ['첫', '둘째', '셋째', '넷째', '다섯째', '여섯째', '일곱째', '여덟째', '아홉째', '열째', '열한째', '열두째']

function ordinal(cellIndex: number): string {
  return ORDINALS[cellIndex - 1] ?? `${cellIndex}번째`
}

function ordered(cells: readonly AnswerCell[]): AnswerCell[] {
  return [...cells].sort((a, b) => a.cellIndex - b.cellIndex)
}

/**
 * 뒤쪽 0을 뺀 길이. 답안 칸은 max(입력, 정답) 길이이고 없는 쪽은 0으로 채워지므로
 * 이것을 원래 칸 수로 본다 (끝에 빈 칸을 일부러 제출한 경우만 구분하지 못한다)
 */
function lengthOf(patterns: readonly number[]): number {
  let length = patterns.length
  while (length > 0 && patterns[length - 1] === 0) length -= 1
  return length
}

/** 학생이 실제로 제출한 칸의 점형 */
export function submittedPatterns(cells: readonly AnswerCell[]): number[] {
  const inputs = ordered(cells).map((c) => c.inputPattern)
  return inputs.slice(0, lengthOf(inputs))
}

/** 오답 카드의 틀린 이유. 칸 수가 다르면 "칸 수 다름", 아니면 첫 틀린 칸의 누락·추가 점 */
export function wrongReason(cells: readonly AnswerCell[]): string {
  const sorted = ordered(cells)
  const inputLength = lengthOf(sorted.map((c) => c.inputPattern))
  const expectedLength = lengthOf(sorted.map((c) => c.expectedPattern))
  if (inputLength !== expectedLength) return '칸 수 다름'

  const first = sorted.find((c) => (c.inputPattern ^ c.expectedPattern) !== 0)
  if (!first) return '오답'
  const missing = dotsOf(first.expectedPattern & ~first.inputPattern)
  const extra = dotsOf(first.inputPattern & ~first.expectedPattern)
  const parts: string[] = []
  if (missing.length > 0) parts.push(`${missing.join('·')}점 누락`)
  if (extra.length > 0) parts.push(`${extra.join('·')}점 추가`)
  return `${ordinal(first.cellIndex)} 칸 ${parts.join(', ')}`
}

export type AnswerState = 'correct' | 'wrong' | 'idle'

export interface StudentAnswerView {
  state: AnswerState
  /** 제출한 점자. 미착수이거나 입력 칸이 없으면 null */
  glyph: string | null
  /** 정답: "정답", 오답: 틀린 이유, 미착수: "미착수". 걸린 시간은 저장하지 않으므로 없다 */
  detail: string
}

export function studentAnswerView(answer: StudentAnswer | undefined): StudentAnswerView {
  if (!answer) return { state: 'idle', glyph: null, detail: '미착수' }
  const patterns = submittedPatterns(answer.cells)
  const glyph = patterns.length > 0 ? brailleGlyph(patterns) : null
  return answer.isCorrect
    ? { state: 'correct', glyph, detail: '정답' }
    : { state: 'wrong', glyph, detail: wrongReason(answer.cells) }
}

export interface ProgressCounts {
  connected: number
  submitted: number
  correct: number
  wrong: number
  notStarted: number
}

/** 제출 현황. 학생 카드와 같은 명단(지금 접속한 학생)으로 센다 */
export function progressCounts(connectedUserIds: readonly string[], answers: readonly StudentAnswer[]): ProgressCounts {
  const byUser = new Map(answers.map((a) => [a.userId, a]))
  const connected = new Set(connectedUserIds)
  let correct = 0
  let wrong = 0
  for (const userId of connected) {
    const found = byUser.get(userId)
    if (!found) continue
    if (found.isCorrect) correct += 1
    else wrong += 1
  }
  return { connected: connected.size, submitted: correct + wrong, correct, wrong, notStarted: connected.size - correct - wrong }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint`
Expected: PASS(braille 3 + answerView 16 신규), lint 경고 0

- [ ] **Step 5: Commit**

```bash
git add front/apps/web/src/domains/lecture/model/braille.ts front/apps/web/src/domains/lecture/model/answerView.ts front/apps/web/src/domains/lecture/model/__tests__
git commit -m "feat(lecture): 답안 점형 표기와 틀린 이유·제출 현황 계산을 추가한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 강사 음성 방 — 답안 제출 신호

**Files:**
- Create: `front/apps/web/src/domains/lecture/live/lectureSignal.ts`
- Modify: `front/apps/web/src/domains/lecture/live/instructorRoom.ts`
- Test: `front/apps/web/src/domains/lecture/live/__tests__/lectureSignal.test.ts` (Create), `front/apps/web/src/domains/lecture/live/__tests__/instructorRoom.test.ts`

**Interfaces:**
- Produces:
  - `LECTURE_TOPIC = 'lecture'`
  - `interface AnswerSubmittedSignal { type: 'ANSWER_SUBMITTED'; problemId: string }`
  - `parseLectureSignal(payload: Uint8Array, topic: string | undefined): AnswerSubmittedSignal | null` — topic이 `lecture`가 아니거나, UTF-8/JSON이 깨졌거나, type이 다르거나, problemId가 10진수 문자열이 아니면 null. throw하지 않는다
  - `RoomListeners.onAnswerSubmitted?: (problemId: string) => void` — `RoomEvent.DataReceived`에서 신호를 해석해 부른다. `isStale()`이 true면 부르지 않는다. 보낸 참가자로 거르지 않는다(서버 메시지는 participant가 undefined)

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/web/src/domains/lecture/live/__tests__/lectureSignal.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { parseLectureSignal } from '../lectureSignal'

const bytes = (value: unknown) => new TextEncoder().encode(typeof value === 'string' ? value : JSON.stringify(value))

describe('parseLectureSignal', () => {
  it('lecture 토픽의 답안 제출 신호를 읽는다', () => {
    expect(parseLectureSignal(bytes({ type: 'ANSWER_SUBMITTED', problemId: '26578' }), 'lecture')).toEqual({
      type: 'ANSWER_SUBMITTED',
      problemId: '26578',
    })
  })

  it('다른 토픽이나 토픽 없는 메시지는 무시한다', () => {
    const payload = bytes({ type: 'ANSWER_SUBMITTED', problemId: '1' })
    expect(parseLectureSignal(payload, 'chat')).toBeNull()
    expect(parseLectureSignal(payload, undefined)).toBeNull()
  })

  it('JSON이 아니거나 객체가 아니면 null', () => {
    expect(parseLectureSignal(bytes('{broken'), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes('3'), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes('null'), 'lecture')).toBeNull()
    expect(parseLectureSignal(new Uint8Array([0xff, 0xfe]), 'lecture')).toBeNull()
  })

  it('type이 다르거나 problemId가 10진수 문자열이 아니면 null', () => {
    expect(parseLectureSignal(bytes({ type: 'OTHER', problemId: '1' }), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes({ type: 'ANSWER_SUBMITTED', problemId: 26578 }), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes({ type: 'ANSWER_SUBMITTED', problemId: '1a' }), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes({ type: 'ANSWER_SUBMITTED' }), 'lecture')).toBeNull()
  })
})
```

`instructorRoom.test.ts` — `vi.mock('livekit-client', …)`의 `RoomEvent` 객체에서 찾을 코드:

```ts
    AudioPlaybackStatusChanged: 'audioPlaybackChanged',
  },
```

바꿀 코드:

```ts
    AudioPlaybackStatusChanged: 'audioPlaybackChanged',
    DataReceived: 'dataReceived',
  },
```

같은 파일의 마지막 테스트 `'소리 재생 상태 변화를 알린다'` **뒤에**(describe 닫는 `})` 앞) 추가한다:

```ts

  it('lecture 토픽의 답안 제출 신호에서 problemId를 알린다', async () => {
    const submitted: string[] = []
    await connectInstructorRoom(token, () => {}, { onAnswerSubmitted: (id) => submitted.push(id) })
    const encode = (value: unknown) => new TextEncoder().encode(JSON.stringify(value))

    // 서버가 보낸 메시지는 participant가 undefined다
    rooms[0].emit('dataReceived', encode({ type: 'ANSWER_SUBMITTED', problemId: '26578' }), undefined, 0, 'lecture')
    rooms[0].emit('dataReceived', encode({ type: 'ANSWER_SUBMITTED', problemId: '1' }), undefined, 0, 'chat')
    rooms[0].emit('dataReceived', new TextEncoder().encode('{broken'), undefined, 0, 'lecture')

    expect(submitted).toEqual(['26578'])
  })

  it('밀려난 접속은 답안 신호를 알리지 않는다', async () => {
    let stale = false
    const submitted: string[] = []
    await connectInstructorRoom(token, () => {}, { onAnswerSubmitted: (id) => submitted.push(id), isStale: () => stale })

    stale = true
    rooms[0].emit(
      'dataReceived',
      new TextEncoder().encode(JSON.stringify({ type: 'ANSWER_SUBMITTED', problemId: '26578' })),
      undefined,
      0,
      'lecture',
    )

    expect(submitted).toEqual([])
  })
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — `../lectureSignal` 모듈이 없다, instructorRoom 신규 1번 테스트가 `[]`를 받는다

- [ ] **Step 3: 구현**

`front/apps/web/src/domains/lecture/live/lectureSignal.ts`:

```ts
/**
 * 백엔드가 강사 identity에게만 보내는 LiveKit 데이터 메시지 (문제 출제 설계 §2·§3.3).
 * 신호는 "다시 조회하라"는 뜻일 뿐 답 내용은 없다. 이 모듈은 LiveKit을 import하지 않는다.
 */
export const LECTURE_TOPIC = 'lecture'

export interface AnswerSubmittedSignal {
  type: 'ANSWER_SUBMITTED'
  problemId: string
}

const decoder = new TextDecoder('utf-8', { fatal: true })

/** 형식이 어긋나면 null. throw하지 않는다 */
export function parseLectureSignal(payload: Uint8Array, topic: string | undefined): AnswerSubmittedSignal | null {
  if (topic !== LECTURE_TOPIC) return null
  let data: unknown
  try {
    data = JSON.parse(decoder.decode(payload))
  } catch {
    return null
  }
  if (typeof data !== 'object' || data === null) return null
  const { type, problemId } = data as Record<string, unknown>
  if (type !== 'ANSWER_SUBMITTED' || typeof problemId !== 'string' || !/^\d+$/.test(problemId)) return null
  return { type: 'ANSWER_SUBMITTED', problemId }
}
```

`front/apps/web/src/domains/lecture/live/instructorRoom.ts`:

3행 `import { buildRoster, type RosterStudent } from './roster'` **뒤에** 추가:

```ts
import { parseLectureSignal } from './lectureSignal'
```

`RoomListeners`에서 찾을 코드:

```ts
  onAudioPlayback?: (playing: boolean) => void
```

바꿀 코드:

```ts
  onAudioPlayback?: (playing: boolean) => void
  /** 학습자가 답안을 제출했다는 서버 신호(topic `lecture`). 화면은 이 problemId의 답안 목록을 다시 조회한다 */
  onAnswerSubmitted?: (problemId: string) => void
```

찾을 코드:

```ts
  room.on(RoomEvent.AudioPlaybackStatusChanged, (playing) => listeners.onAudioPlayback?.(playing))
```

바꿀 코드:

```ts
  room.on(RoomEvent.AudioPlaybackStatusChanged, (playing) => listeners.onAudioPlayback?.(playing))

  // 답안 제출 신호. 서버가 보낸 메시지는 participant가 undefined라 보낸 사람으로 거르지 않는다 —
  // 신호는 재조회 트리거일 뿐이고 답안은 권한 검사를 거친 HTTP로만 받으므로 위조돼도 무해하다
  room.on(RoomEvent.DataReceived, (payload, _participant, _kind, topic) => {
    if (listeners.isStale?.()) return
    const signal = parseLectureSignal(payload, topic)
    if (signal) listeners.onAnswerSubmitted?.(signal.problemId)
  })
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: PASS(lectureSignal 4 + instructorRoom 2 신규, 기존 instructorRoom 테스트 전부 유지), lint 경고 0, build 성공(`payload`가 `NonSharedUint8Array`로 추론돼 `Uint8Array` 인자에 들어가는지 tsc가 확인한다)

- [ ] **Step 5: Commit**

```bash
git add front/apps/web/src/domains/lecture/live
git commit -m "feat(lecture): 강사 음성 방에서 답안 제출 신호를 받는다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 문제 세트 패널

**Files:**
- Create: `front/apps/web/src/domains/lecture/model/problemSet.ts`
- Create: `front/apps/web/src/domains/lecture/ui/ProblemSetPanel.tsx`
- Test: `front/apps/web/src/domains/lecture/model/__tests__/problemSet.test.ts`

**Interfaces:**
- Consumes: `LinkedProblem`, `LectureStatus`, `ProblemCandidate`, `lectureApi.registerProblem`, `ambiguousCandidates` (Task 1), `brailleGlyph` (Task 2), `errorMessage`
- Produces:
  - `type SetItemState = 'current' | 'delivered' | 'waiting'`, `interface SetItem { problem: LinkedProblem; state: SetItemState }`
  - `problemSetItems(problems: readonly LinkedProblem[], currentProblemId: string | null, deliveredIds: ReadonlySet<string>): SetItem[]` — 지금 문제 → `current`, 이번 세션에 출제한 적 있음 → `delivered`, 나머지 → `waiting`. 순서는 연결 순서 그대로
  - `mergeLinkedProblem(problems: LinkedProblem[], problem: LinkedProblem): LinkedProblem[]` — 이미 있으면(200 재연결) **같은 배열을 그대로** 돌려준다, 없으면 끝에 붙인 새 배열
  - `ProblemSetPanel(props: ProblemSetPanelProps)`:
    ```ts
    interface ProblemSetPanelProps {
      lectureId: string
      status: LectureStatus
      problems: LinkedProblem[]
      currentProblemId: string | null
      deliveredIds: ReadonlySet<string>
      deliveringId: string | null
      disabled: boolean
      onRegistered: (problem: LinkedProblem) => void
      onDeliver: (problemId: string) => void
    }
    ```
    등록은 패널이 직접 `lectureApi.registerProblem`을 부르고 결과를 `onRegistered`로 넘긴다(오류·후보는 패널 안에 표시). 출제는 페이지가 한다(Snapshot·답안 갱신이 페이지 상태라서). CLOSED면 입력·추가 비활성, OPEN이 아니면 출제 버튼 비활성

화면 테스트 환경이 없으므로(vitest node, `*.test.ts`만) 상태 판정은 `problemSet.ts`로 빼서 테스트하고, 컴포넌트는 lint·build와 Task 6 E2E로 확인한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/web/src/domains/lecture/model/__tests__/problemSet.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { mergeLinkedProblem, problemSetItems } from '../problemSet'
import type { LinkedProblem } from '../types'

function problem(problemId: string, displayText: string): LinkedProblem {
  return { problemId, targetText: displayText, displayText, problemType: 'WORD', cellCount: 1, expectedPattern: [8] }
}

describe('problemSetItems', () => {
  const problems = [problem('1', '그래서'), problem('2', '그리고'), problem('3', '초성 ㄱ')]

  it('지금 문제·출제됨·대기를 연결 순서대로 표시한다', () => {
    expect(problemSetItems(problems, '2', new Set(['1', '2'])).map((i) => [i.problem.problemId, i.state])).toEqual([
      ['1', 'delivered'],
      ['2', 'current'],
      ['3', 'waiting'],
    ])
  })

  it('현재 문제가 없으면 출제 기록만 본다', () => {
    expect(problemSetItems(problems, null, new Set(['3'])).map((i) => i.state)).toEqual(['waiting', 'waiting', 'delivered'])
  })

  it('출제 기록에 없어도 지금 문제는 current다 (새로고침 직후)', () => {
    expect(problemSetItems(problems, '3', new Set()).map((i) => i.state)).toEqual(['waiting', 'waiting', 'current'])
  })
})

describe('mergeLinkedProblem', () => {
  it('새 문제는 끝에 붙인다', () => {
    const before = [problem('1', '그래서')]
    const after = mergeLinkedProblem(before, problem('2', '그리고'))
    expect(after.map((p) => p.problemId)).toEqual(['1', '2'])
    expect(before).toHaveLength(1)
  })

  it('이미 연결된 문제면 목록을 늘리지 않고 같은 배열을 돌려준다', () => {
    const before = [problem('1', '그래서'), problem('2', '그리고')]
    expect(mergeLinkedProblem(before, problem('1', '그래서'))).toBe(before)
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — `../problemSet` 모듈이 없다

- [ ] **Step 3: 순수 모듈 구현**

`front/apps/web/src/domains/lecture/model/problemSet.ts`:

```ts
import type { LinkedProblem } from './types'

/**
 * 문제 세트 목록의 상태 (문제 출제 설계 §4).
 * 출제됨은 웹 세션 안에서만 기억한다 — 새로고침하면 지금 문제만 알 수 있다.
 */
export type SetItemState = 'current' | 'delivered' | 'waiting'

export interface SetItem {
  problem: LinkedProblem
  state: SetItemState
}

export function problemSetItems(
  problems: readonly LinkedProblem[],
  currentProblemId: string | null,
  deliveredIds: ReadonlySet<string>,
): SetItem[] {
  return problems.map((problem) => {
    if (problem.problemId === currentProblemId) return { problem, state: 'current' }
    return { problem, state: deliveredIds.has(problem.problemId) ? 'delivered' : 'waiting' }
  })
}

/** 등록 응답을 연결 목록에 합친다. 이미 연결된 문제(200)면 같은 배열을 그대로 돌려준다 */
export function mergeLinkedProblem(problems: LinkedProblem[], problem: LinkedProblem): LinkedProblem[] {
  return problems.some((p) => p.problemId === problem.problemId) ? problems : [...problems, problem]
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: PASS (problemSet 5 신규)

- [ ] **Step 5: 패널 컴포넌트 구현**

`front/apps/web/src/domains/lecture/ui/ProblemSetPanel.tsx`:

```tsx
import { useId, useState, type CSSProperties, type FormEvent } from 'react'
import { errorMessage } from '../../../shared/ui/errorMessage'
import { buttonStyle, dimmed, fieldStyle } from '../../../shared/ui/styles'
import { theme } from '../../../shared/ui/theme'
import { ambiguousCandidates, lectureApi } from '../api/lectureApi'
import { brailleGlyph } from '../model/braille'
import { problemSetItems, type SetItem } from '../model/problemSet'
import type { LectureStatus, LinkedProblem, ProblemCandidate } from '../model/types'

const c = theme.color

const INPUT_GUIDE = "초성 ㄱ은 '초성'을 입력하고 한 칸 띄운 뒤 ㄱ을 입력하세요. 받침 ㄱ, 모음 ㅏ, 음절 가도 같은 방식이에요."
const DELIVER_LOCKED = '수업을 시작하면 출제할 수 있어요'

export interface ProblemSetPanelProps {
  lectureId: string
  status: LectureStatus
  problems: LinkedProblem[]
  currentProblemId: string | null
  /** 이번 세션에 한 번이라도 지금 문제였던 problemId */
  deliveredIds: ReadonlySet<string>
  /** 출제 요청 중인 problemId */
  deliveringId: string | null
  /** 페이지의 다른 요청이 진행 중이면 출제를 막는다 */
  disabled: boolean
  onRegistered: (problem: LinkedProblem) => void
  onDeliver: (problemId: string) => void
}

interface SetRowProps {
  item: SetItem
  canDeliver: boolean
  delivering: boolean
  disabled: boolean
  onDeliver: (problemId: string) => void
}

function SetRow({ item, canDeliver, delivering, disabled, onDeliver }: SetRowProps) {
  const { problem, state } = item
  const row: CSSProperties = {
    padding: '10px 13px',
    borderRadius: theme.radius.control,
    display: 'flex',
    alignItems: 'center',
    gap: 10,
  }
  const look: CSSProperties =
    state === 'current'
      ? { background: c.primarySoft, border: `1.5px solid ${c.primaryStrong}` }
      : state === 'delivered'
        ? { background: c.fill, color: c.textMuted }
        : { border: `1.5px solid ${c.border}` }
  const locked = !canDeliver || disabled
  return (
    <li aria-current={state === 'current' ? 'true' : undefined} style={{ ...row, ...look }}>
      <span
        style={{
          flex: 1,
          minWidth: 0,
          fontWeight: state === 'current' ? 900 : 700,
          color: state === 'current' ? c.primaryDark : undefined,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {problem.displayText}
      </span>
      <span aria-hidden="true" style={{ fontSize: 16, lineHeight: 1, flexShrink: 0 }}>
        {brailleGlyph(problem.expectedPattern)}
      </span>
      {state === 'current' && <span style={{ fontSize: 12, fontWeight: 800, color: c.primaryDark, flexShrink: 0 }}>지금 문제</span>}
      {state === 'delivered' && <span style={{ fontSize: 12, fontWeight: 700, flexShrink: 0 }}>출제됨</span>}
      {state === 'waiting' && (
        <>
          <span style={{ fontSize: 12, fontWeight: 700, color: c.textSub, flexShrink: 0 }}>대기</span>
          <button
            type="button"
            disabled={locked}
            onClick={() => onDeliver(problem.problemId)}
            title={canDeliver ? undefined : DELIVER_LOCKED}
            aria-label={delivering ? `${problem.displayText} 출제 중` : `${problem.displayText} 출제`}
            aria-busy={delivering}
            style={{
              ...buttonStyle('outline', 'sm'),
              borderRadius: theme.radius.small,
              padding: '4px 12px',
              fontSize: 12,
              ...(locked ? dimmed : {}),
            }}
          >
            {delivering ? '출제 중…' : '출제'}
          </button>
        </>
      )}
    </li>
  )
}

/** 03 화면 오른쪽 문제 세트: 글자 입력으로 문제를 연결하고, 골라서 출제한다 (문제 출제 설계 §4) */
export function ProblemSetPanel({
  lectureId,
  status,
  problems,
  currentProblemId,
  deliveredIds,
  deliveringId,
  disabled,
  onRegistered,
  onDeliver,
}: ProblemSetPanelProps) {
  const titleId = useId()
  const inputId = useId()
  const guideId = useId()
  const [text, setText] = useState('')
  const [candidates, setCandidates] = useState<ProblemCandidate[] | null>(null)
  const [registering, setRegistering] = useState(false)
  const [registerError, setRegisterError] = useState<string | null>(null)

  const canRegister = status !== 'CLOSED'
  const canDeliver = status === 'OPEN'
  const items = problemSetItems(problems, currentProblemId, deliveredIds)

  async function register(input: string) {
    const targetText = input.trim()
    if (!targetText || registering) return
    setRegistering(true)
    setRegisterError(null)
    try {
      const problem = await lectureApi.registerProblem(lectureId, targetText)
      onRegistered(problem)
      setText('')
      setCandidates(null)
    } catch (e) {
      // 구분어 없는 낱자가 여러 문제에 해당하면 후보를 보여 주고, 누르면 그 input으로 다시 등록한다
      const found = ambiguousCandidates(e)
      setCandidates(found)
      if (!found) setRegisterError(errorMessage(e))
    } finally {
      setRegistering(false)
    }
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void register(text)
  }

  const addLocked = !canRegister || registering || text.trim() === ''

  return (
    <section aria-labelledby={titleId} style={{ display: 'flex', flexDirection: 'column', gap: 10, flex: 1, minHeight: 0 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <h2 id={titleId} style={{ margin: 0, fontSize: 14, fontWeight: 800 }}>
          문제 세트 · {problems.length}
        </h2>
        <span style={{ fontSize: 11.5, color: c.textSub }}>골라서 출제 · 순서 무관</span>
      </div>

      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <label htmlFor={inputId} style={{ fontSize: 12.5, fontWeight: 700 }}>
          문제 추가
        </label>
        <p id={guideId} style={{ margin: 0, fontSize: 11.5, lineHeight: 1.5, color: c.textSub }}>
          {INPUT_GUIDE}
        </p>
        <div style={{ display: 'flex', gap: 8 }}>
          <input
            id={inputId}
            aria-describedby={guideId}
            value={text}
            disabled={!canRegister || registering}
            placeholder="예: 초성 ㄱ, 그리고"
            onChange={(event) => {
              setText(event.target.value)
              setCandidates(null)
            }}
            style={{ ...fieldStyle, flex: 1, minWidth: 0, minHeight: 38, fontSize: 13.5 }}
          />
          <button
            type="submit"
            disabled={addLocked}
            style={{ ...buttonStyle('primary', 'sm'), boxShadow: `0 2px 0 ${c.primaryStrong}`, ...(addLocked ? dimmed : {}) }}
          >
            {registering ? '추가 중…' : '추가'}
          </button>
        </div>
        {candidates && (
          <div role="group" aria-label="어떤 문제인지 고르기" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 12, color: c.textSub }}>어떤 문제인가요?</span>
            {candidates.map((candidate) => (
              <button
                key={candidate.input}
                type="button"
                disabled={registering}
                onClick={() => void register(candidate.input)}
                style={{ ...buttonStyle('mintOutline', 'sm'), padding: '4px 12px', fontSize: 12, ...(registering ? dimmed : {}) }}
              >
                {candidate.displayText}
              </button>
            ))}
          </div>
        )}
        {registerError && (
          <p role="alert" style={{ margin: 0, fontSize: 12.5, fontWeight: 700, color: c.danger }}>
            {registerError}
          </p>
        )}
      </form>

      {status === 'SCHEDULED' && <p style={{ margin: 0, fontSize: 12, color: c.textSub }}>{DELIVER_LOCKED}</p>}

      {items.length === 0 ? (
        <p style={{ margin: 0, fontSize: 13, color: c.textSub }}>아직 추가한 문제가 없어요</p>
      ) : (
        <ul
          style={{
            listStyle: 'none',
            margin: 0,
            padding: 0,
            display: 'flex',
            flexDirection: 'column',
            gap: 7,
            fontSize: 13.5,
            overflowY: 'auto',
            minHeight: 0,
          }}
        >
          {items.map((item) => (
            <SetRow
              key={item.problem.problemId}
              item={item}
              canDeliver={canDeliver}
              delivering={deliveringId === item.problem.problemId}
              disabled={disabled || deliveringId !== null}
              onDeliver={onDeliver}
            />
          ))}
        </ul>
      )}
    </section>
  )
}
```

- [ ] **Step 6: 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: 모두 성공, lint 경고 0 (패널은 아직 어디서도 쓰지 않지만 export된 컴포넌트라 `noUnusedLocals`에 걸리지 않는다. `react-refresh/only-export-components`: 컴포넌트와 타입만 export하므로 경고 없음)

- [ ] **Step 7: Commit**

```bash
git add front/apps/web/src/domains/lecture/model/problemSet.ts front/apps/web/src/domains/lecture/model/__tests__/problemSet.test.ts front/apps/web/src/domains/lecture/ui/ProblemSetPanel.tsx
git commit -m "feat(lecture): 글자 입력으로 문제를 연결하고 출제하는 문제 세트 패널을 추가한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 실시간 수업 화면 연결과 목 제거

**Files:**
- Modify: `front/apps/web/src/pages/LiveLecturePage.tsx`
- Delete: `front/apps/web/src/domains/lecture/mock/liveMock.ts`

**Interfaces:**
- Consumes: `lectureApi.deliverProblem`, `lectureApi.listAnswers`, 타입들 (Task 1), `dotLabel`, `dotsOf`, `progressCounts`, `studentAnswerView`, `AnswerState`, `StudentAnswerView` (Task 2), `RoomListeners.onAnswerSubmitted` (Task 3), `ProblemSetPanel`, `mergeLinkedProblem` (Task 4)
- Produces: 화면 동작
  - 답안 조회 `loadAnswers(problemId | null)` — 세대 번호 `answersSeqRef`로 **마지막 호출의 응답만** 반영. 부르는 곳: 진입 조회 직후, 출제 성공 직후, `ANSWER_SUBMITTED`(problemId가 지금 문제와 같을 때만, 그리고 방 세대 `joinSeqRef`가 최신일 때만)
  - 답안은 `answerSet.problemId === 지금 문제`일 때만 화면에 쓴다(출제 직후 이전 문제 답안이 잠깐 보이지 않게)
  - 학생 카드: 지금 문제가 있으면 답안 칸(정답/오답/미착수), 없으면 답안 칸 없음. 명단은 접속자 기준 그대로

아래 "찾을 코드"는 현재 파일 내용 그대로다. 순서대로 바꾼다.

- [ ] **Step 1: import 교체**

찾을 코드(1~22행):

```tsx
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@ieum/api-client'
import { lectureApi } from '../domains/lecture/api/lectureApi'
import { connectInstructorRoom, type InstructorRoom, type RoomState } from '../domains/lecture/live/instructorRoom'
import type { RosterStudent } from '../domains/lecture/live/roster'
import {
  mockBankResult,
  mockCurrentProblem,
  mockProblemSet,
  mockProblemSetMore,
  mockProblemSetTotal,
  mockProgress,
  mockStudents,
  type MockSetItem,
  type MockStudent,
  type MockStudentState,
} from '../domains/lecture/mock/liveMock'
import type { InstructorLecture, LectureStatus } from '../domains/lecture/model/types'
import { errorMessage } from '../shared/ui/errorMessage'
import { buttonStyle, cardStyle, dimmed, inactive } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'
```

바꿀 코드:

```tsx
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@ieum/api-client'
import { lectureApi } from '../domains/lecture/api/lectureApi'
import { connectInstructorRoom, type InstructorRoom, type RoomState } from '../domains/lecture/live/instructorRoom'
import type { RosterStudent } from '../domains/lecture/live/roster'
import { progressCounts, studentAnswerView, type AnswerState, type StudentAnswerView } from '../domains/lecture/model/answerView'
import { dotLabel, dotsOf } from '../domains/lecture/model/braille'
import { mergeLinkedProblem } from '../domains/lecture/model/problemSet'
import type { InstructorLecture, LectureStatus, LinkedProblem, ProblemAnswers, StudentAnswer } from '../domains/lecture/model/types'
import { ProblemSetPanel } from '../domains/lecture/ui/ProblemSetPanel'
import { errorMessage } from '../shared/ui/errorMessage'
import { buttonStyle, cardStyle, dimmed, inactive } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'
```

- [ ] **Step 2: 학생 답안 모양 상수**

찾을 코드:

```tsx
const STUDENT_LOOK: Record<MockStudentState, { card: CSSProperties; name: string; accent: string }> = {
  correct: { card: { background: c.primarySoft }, name: c.text, accent: c.primaryDark },
  wrong: { card: { background: c.warningSoft }, name: c.text, accent: c.warningText },
  idle: { card: { background: c.bg, border: `1.5px solid ${c.borderSoft}` }, name: c.textSub, accent: c.textSub },
  absent: { card: { border: `1.5px solid ${c.borderStrong}` }, name: c.textMuted, accent: c.textMuted },
}
```

바꿀 코드:

```tsx
/** 학생 카드 안 답안 칸. 정답 초록 · 오답 노랑 · 미착수 회색 */
const ANSWER_LOOK: Record<AnswerState, { box: CSSProperties; accent: string }> = {
  correct: { box: { background: c.primarySoft, border: `1px solid ${c.primaryStrong}` }, accent: c.primaryDark },
  wrong: { box: { background: c.warningSoft, border: `1px solid ${c.warningLine}` }, accent: c.warningText },
  idle: { box: { background: c.bg, border: `1px solid ${c.borderSoft}` }, accent: c.textSub },
}
```

- [ ] **Step 3: 목 답안 함수 삭제**

다음 블록을 통째로 지운다:

```tsx
/** 답안 상태는 아직 목이다(문제 출제·답안 API는 범위 밖). 미입장 예시는 실제 명단과 모순되므로 뺀다 */
const MOCK_ANSWERS = mockStudents.filter((s) => s.state !== 'absent')

/** 같은 학생에게는 정렬 순서가 바뀌어도 같은 예시 답안을 보여 준다 */
function mockAnswerFor(userId: string): MockStudent {
  return MOCK_ANSWERS[Number(userId) % MOCK_ANSWERS.length]
}

```

- [ ] **Step 4: 학생 카드에 실제 답안**

찾을 코드:

```tsx
interface StudentCardProps {
  student: RosterStudent
```

바꿀 코드:

```tsx
interface StudentCardProps {
  student: RosterStudent
  /** 지금 문제에 대한 답안 칸. 지금 문제가 없으면 null(칸을 그리지 않는다) */
  answer: StudentAnswerView | null
```

찾을 코드:

```tsx
function StudentCard({ student, canControl, pending, disabled, onToggleSpeaking }: StudentCardProps) {
  const answer = mockAnswerFor(student.userId)
  const look = STUDENT_LOOK[answer.state]
  const action = student.speakingAllowed ? '발언 끄기' : '발언 허용'
```

바꿀 코드:

```tsx
function StudentCard({ student, answer, canControl, pending, disabled, onToggleSpeaking }: StudentCardProps) {
  const action = student.speakingAllowed ? '발언 끄기' : '발언 허용'
```

찾을 코드(점선 "예시" 칸 전체):

```tsx
      {/* 목: 답안 상태 예시 — 점선 칸으로 실제 정보와 구분한다 */}
      <div
        style={{
          ...look.card,
          border: `1px dashed ${c.borderStrong}`,
          borderRadius: theme.radius.small,
          padding: '6px 8px',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <span style={{ fontSize: 10, fontWeight: 700, color: c.textMuted }}>예시</span>
        {answer.glyph && <span style={{ fontSize: 18, lineHeight: 1, color: look.accent }}>{answer.glyph}</span>}
        <span style={{ fontSize: 11.5, fontWeight: 700, color: look.accent, fontFamily: answer.state === 'correct' ? mono : undefined }}>
          {answer.detail}
        </span>
        <span
          role="img"
          aria-label={answer.deviceConnected ? '장치 연결됨(예시)' : '장치 끊김(예시)'}
          style={{
            marginLeft: 'auto',
            width: 9,
            height: 9,
            borderRadius: '50%',
            flexShrink: 0,
            background: answer.deviceConnected ? c.primaryDark : c.live,
          }}
        />
      </div>
```

바꿀 코드:

```tsx
      {/* 실제: 지금 문제에 제출한 점자와 결과 (걸린 시간은 저장하지 않으므로 표시하지 않는다) */}
      {answer && (
        <div
          style={{
            ...ANSWER_LOOK[answer.state].box,
            borderRadius: theme.radius.small,
            padding: '6px 8px',
            minHeight: 32,
            boxSizing: 'border-box',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          {answer.glyph && (
            <span style={{ fontSize: 18, lineHeight: 1, color: ANSWER_LOOK[answer.state].accent }}>{answer.glyph}</span>
          )}
          <span style={{ fontSize: 11.5, fontWeight: 700, color: ANSWER_LOOK[answer.state].accent }}>{answer.detail}</span>
        </div>
      )}
```

- [ ] **Step 5: 목 문제 세트 행 삭제**

`function SetItem({ item }: { item: MockSetItem }) {`부터 그 함수의 닫는 `}`까지(현재 209~256행, `export function LiveLecturePage()` 바로 앞 빈 줄 전까지) 통째로 지운다. 패널의 `SetRow`가 대신한다.

- [ ] **Step 6: 상태·ref 추가**

찾을 코드:

```tsx
  const [pendingUserId, setPendingUserId] = useState<string | null>(null)
```

바꿀 코드:

```tsx
  const [pendingUserId, setPendingUserId] = useState<string | null>(null)
  /** 마지막으로 받은 답안 목록. problemId가 지금 문제와 같을 때만 화면에 쓴다 */
  const [answerSet, setAnswerSet] = useState<ProblemAnswers | null>(null)
  /** 이번 세션에 한 번이라도 지금 문제였던 problemId (새로고침하면 지금 문제만 남는다) */
  const [deliveredIds, setDeliveredIds] = useState<ReadonlySet<string>>(() => new Set())
  const [deliveringId, setDeliveringId] = useState<string | null>(null)
```

찾을 코드:

```tsx
  const joinSeqRef = useRef(0)
```

바꿀 코드:

```tsx
  const joinSeqRef = useRef(0)
  // 답안 목록 조회의 세대 번호. 진입·출제·제출 신호 조회가 겹치면 마지막 요청의 응답만 반영한다
  const answersSeqRef = useRef(0)
  // 방 콜백(답안 신호) 안에서 지금 문제를 확인하려고 최신 값을 둔다
  const currentProblemIdRef = useRef<string | null>(null)
```

- [ ] **Step 7: 답안 조회 함수와 신호 연결**

찾을 코드:

```tsx
  const joinVoice = useCallback(async () => {
```

바꿀 코드:

```tsx
  const loadAnswers = useCallback(
    async (problemId: string | null) => {
      const seq = ++answersSeqRef.current
      if (problemId === null) {
        setAnswerSet(null)
        return
      }
      try {
        const loaded = await lectureApi.listAnswers(lectureId, problemId)
        if (seq === answersSeqRef.current) setAnswerSet(loaded)
      } catch (e) {
        if (seq === answersSeqRef.current) setError(errorMessage(e))
      }
    },
    [lectureId],
  )

  const joinVoice = useCallback(async () => {
```

찾을 코드:

```tsx
          onAudioPlayback: (playing) => {
            if (seq === joinSeqRef.current) setAudioBlocked(!playing)
          },
```

바꿀 코드:

```tsx
          onAudioPlayback: (playing) => {
            if (seq === joinSeqRef.current) setAudioBlocked(!playing)
          },
          // 지금 문제에 대한 신호만 반영한다 — 이전 문제의 늦은 신호는 화면과 무관하다
          onAnswerSubmitted: (problemId) => {
            if (seq === joinSeqRef.current && problemId === currentProblemIdRef.current) void loadAnswers(problemId)
          },
```

찾을 코드:

```tsx
  }, [lectureId, releaseRoom])
```

바꿀 코드:

```tsx
  }, [lectureId, releaseRoom, loadAnswers])
```

- [ ] **Step 8: 진입 조회에서 출제 기록·답안 복원**

찾을 코드:

```tsx
      .then(async (loaded) => {
        if (cancelled) return
        setLecture(loaded)
        if (loaded.status === 'OPEN') await joinVoice()
      })
      .catch((e) => !cancelled && setError(errorMessage(e)))
    return () => {
      cancelled = true
      joinSeqRef.current += 1 // 진행 중인 joinVoice를 무효화해 언마운트 뒤 roomRef 대입을 막는다
      void releaseRoom()
    }
  }, [lectureId, joinVoice, releaseRoom])
```

바꿀 코드:

```tsx
      .then(async (loaded) => {
        if (cancelled) return
        setLecture(loaded)
        const current = loaded.currentProblem?.problemId ?? null
        currentProblemIdRef.current = current
        setDeliveredIds(new Set(current ? [current] : []))
        // 답안 목록은 수업 상태와 무관하게 조회된다 (종료 뒤에도 복원)
        void loadAnswers(current)
        if (loaded.status === 'OPEN') await joinVoice()
      })
      .catch((e) => !cancelled && setError(errorMessage(e)))
    return () => {
      cancelled = true
      joinSeqRef.current += 1 // 진행 중인 joinVoice를 무효화해 언마운트 뒤 roomRef 대입을 막는다
      answersSeqRef.current += 1 // 진행 중인 답안 조회가 언마운트 뒤 화면을 덮어쓰지 않게 한다
      void releaseRoom()
    }
  }, [lectureId, joinVoice, releaseRoom, loadAnswers])
```

- [ ] **Step 9: 지금 문제 ref 동기화**

찾을 코드:

```tsx
  async function run(action: () => Promise<void>) {
```

바꿀 코드:

```tsx
  // 수업 시작·종료 응답 등 다른 경로로 Snapshot이 바뀌어도 신호 비교 값이 따라가게 한다
  const currentProblemId = lecture?.currentProblem?.problemId ?? null
  useEffect(() => {
    currentProblemIdRef.current = currentProblemId
  }, [currentProblemId])

  async function run(action: () => Promise<void>) {
```

(`run`은 일반 함수이고 이 줄들은 `if (!lecture) return` 조기 반환보다 위에 있으므로 훅 순서 규칙을 지킨다.)

- [ ] **Step 10: 출제와 등록 반영**

찾을 코드:

```tsx
  // 사용자 클릭 안에서 불러야 브라우저가 재생을 허락한다
  const enableAudio = () =>
```

바꿀 코드:

```tsx
  // 출제 → 서버가 방 metadata로 학습자 앱에 알린다. 503이면 DB는 저장됐고 화면은 그대로라
  // 같은 "출제" 버튼을 다시 누르면 서버가 metadata만 다시 쓴다
  const deliver = (problemId: string) =>
    run(async () => {
      setDeliveringId(problemId)
      try {
        const updated = await lectureApi.deliverProblem(lectureId, problemId)
        setLecture(updated)
        const current = updated.currentProblem?.problemId ?? problemId
        // 다음 렌더 전에 도착하는 답안 신호도 새 문제로 비교되게 바로 바꾼다
        currentProblemIdRef.current = current
        setDeliveredIds((prev) => new Set(prev).add(current))
        await loadAnswers(current)
      } finally {
        setDeliveringId(null)
      }
    })

  // 등록 응답(201 새 연결·200 이미 연결)을 목록에 합친다. 이미 있으면 목록이 늘지 않는다
  const addLinkedProblem = (problem: LinkedProblem) =>
    setLecture((prev) => (prev ? { ...prev, linkedProblems: mergeLinkedProblem(prev.linkedProblems, problem) } : prev))

  // 사용자 클릭 안에서 불러야 브라우저가 재생을 허락한다
  const enableAudio = () =>
```

- [ ] **Step 11: 화면 계산 값**

찾을 코드:

```tsx
  const percent = (value: number) => `${(value / mockProgress.total) * 100}%`
```

바꿀 코드:

```tsx
  const currentProblem = lecture.currentProblem
  const currentIndex = currentProblem ? lecture.linkedProblems.findIndex((p) => p.problemId === currentProblem.problemId) : -1
  // 방금 출제해 새 목록이 오기 전이면 이전 문제의 답안을 보여 주지 않는다
  const answers: StudentAnswer[] = currentProblem && answerSet?.problemId === currentProblem.problemId ? answerSet.answers : []
  const answersByUser = new Map(answers.map((a) => [a.userId, a]))
  const progress = progressCounts(
    students.map((s) => s.userId),
    answers,
  )
  const percent = (value: number) => `${progress.connected > 0 ? (value / progress.connected) * 100 : 0}%`
```

- [ ] **Step 12: 지금 문제 카드**

찾을 코드:

```tsx
          {/* 목: 지금 문제 */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 32 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 22 }}>
              <div>
                <div style={{ fontSize: 13, color: c.textSub }}>지금 문제 · {mockCurrentProblem.order}</div>
                <div style={{ fontSize: 42, fontWeight: 900, letterSpacing: '-.02em', lineHeight: 1.1 }}>{mockCurrentProblem.text}</div>
              </div>
```

바꿀 코드:

```tsx
          {/* 실제: 지금 문제 (강사 Snapshot currentProblem) */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 32 }}>
            {currentProblem ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 22 }}>
              <div>
                <div style={{ fontSize: 13, color: c.textSub }}>
                  지금 문제{currentIndex >= 0 ? ` · ${currentIndex + 1} / ${lecture.linkedProblems.length}` : ''}
                </div>
                <div style={{ fontSize: 42, fontWeight: 900, letterSpacing: '-.02em', lineHeight: 1.1 }}>{currentProblem.displayText}</div>
              </div>
```

찾을 코드:

```tsx
                  <span style={{ fontFamily: mono, fontSize: 14, fontWeight: 700 }}>{mockCurrentProblem.answerLabel}</span>
                </div>
                <div style={{ display: 'flex', gap: 10 }}>
                  {mockCurrentProblem.answerCells.map((dots, i) => (
                    <BrailleCell key={i} dots={dots} />
                  ))}
                </div>
              </div>
            </div>
```

바꿀 코드:

```tsx
                  <span style={{ fontFamily: mono, fontSize: 14, fontWeight: 700 }}>{dotLabel(currentProblem.expectedPattern)}</span>
                </div>
                <div style={{ display: 'flex', gap: 10 }}>
                  {currentProblem.expectedPattern.map((pattern, i) => (
                    <BrailleCell key={i} dots={dotsOf(pattern)} />
                  ))}
                </div>
              </div>
            </div>
            ) : (
              <p style={{ margin: 0, fontSize: 16, fontWeight: 700, color: c.textSub }}>오른쪽에서 문제를 골라 출제하세요</p>
            )}
```

(들여쓰기는 prettier가 정리한다: `cd front && pnpm --filter @ieum/web exec prettier --write src/pages/LiveLecturePage.tsx`)

- [ ] **Step 13: 제출 현황 카드**

찾을 코드:

```tsx
          {/* 목: 제출 진행 */}
          <div style={{ ...cardStyle, padding: '18px 24px', display: 'flex', alignItems: 'center', gap: 28, flexShrink: 0 }}>
            <div>
              <div style={{ fontSize: 12, color: c.textSub }}>제출</div>
              <div style={{ fontFamily: mono, fontSize: 24, fontWeight: 700 }}>
                {mockProgress.submitted} / {mockProgress.total}
              </div>
            </div>
            <div style={{ flex: 1, height: 10, borderRadius: theme.radius.pill, background: c.canvas, overflow: 'hidden', display: 'flex' }}>
              <span style={{ width: percent(mockProgress.correct), height: '100%', background: c.primaryTint }} />
              <span style={{ width: percent(mockProgress.wrong), height: '100%', background: c.warningTint }} />
            </div>
            <div style={{ display: 'flex', gap: 22 }}>
              {[
                { label: '정답', value: mockProgress.correct, color: c.primaryDark },
                { label: '오답', value: mockProgress.wrong, color: c.warningText },
                { label: '미착수', value: mockProgress.idle, color: c.textSub },
              ].map((stat) => (
```

바꿀 코드:

```tsx
          {/* 실제: 제출 현황 — 답안 목록과 LiveKit 접속자로 계산 (접속 학생 기준) */}
          <div style={{ ...cardStyle, padding: '18px 24px', display: 'flex', alignItems: 'center', gap: 28, flexShrink: 0 }}>
            <div>
              <div style={{ fontSize: 12, color: c.textSub }}>제출 / 접속 학생</div>
              <div style={{ fontFamily: mono, fontSize: 24, fontWeight: 700 }}>
                {progress.submitted} / {progress.connected}
              </div>
            </div>
            <div style={{ flex: 1, height: 10, borderRadius: theme.radius.pill, background: c.canvas, overflow: 'hidden', display: 'flex' }}>
              <span style={{ width: percent(progress.correct), height: '100%', background: c.primaryTint }} />
              <span style={{ width: percent(progress.wrong), height: '100%', background: c.warningTint }} />
            </div>
            <div style={{ display: 'flex', gap: 22 }}>
              {[
                { label: '정답', value: progress.correct, color: c.primaryDark },
                { label: '오답', value: progress.wrong, color: c.warningText },
                { label: '미착수', value: progress.notStarted, color: c.textSub },
              ].map((stat) => (
```

- [ ] **Step 14: 학생 섹션 범례·카드**

찾을 코드:

```tsx
          {/* 실제: 입장한 학생(LiveKit 참가자)·손들기·발언 허용·마이크 / 목: 카드 안 점선 "예시" 칸의 답안 상태 */}
```

바꿀 코드:

```tsx
          {/* 실제: 입장한 학생(LiveKit 참가자)·손들기·발언 허용·마이크·지금 문제 답안 */}
```

찾을 코드:

```tsx
                <span style={{ fontWeight: 700 }}>답안 상태(예시)</span>
```

바꿀 코드:

```tsx
                <span style={{ fontWeight: 700 }}>답안 상태</span>
```

찾을 코드(장치 범례 삭제):

```tsx
                <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                  <span style={{ width: 9, height: 9, borderRadius: '50%', background: c.primaryDark }} />
                  점 = 장치 연결 여부
                </span>
```

바꿀 코드: (빈 문자열 — 블록을 지운다)

찾을 코드:

```tsx
                    student={student}
                    canControl={canControl}
```

바꿀 코드:

```tsx
                    student={student}
                    answer={currentProblem ? studentAnswerView(answersByUser.get(student.userId)) : null}
                    canControl={canControl}
```

- [ ] **Step 15: 오른쪽 패널 교체**

찾을 코드: `{/* 목: 담아둔 세트 */}` 줄부터 `{/* 목: 문제 은행에서 바로 출제 */}` 섹션의 닫는 `</section>`까지(현재 753~832행, `</aside>` 바로 앞). 두 섹션 전체.

바꿀 코드:

```tsx
          {/* 실제: 문제 세트 — 글자 입력으로 연결하고 골라서 출제 (문제 은행 검색은 범위 밖이라 뺐다) */}
          <ProblemSetPanel
            key={lectureId}
            lectureId={lectureId}
            status={lecture.status}
            problems={lecture.linkedProblems}
            currentProblemId={currentProblemId}
            deliveredIds={deliveredIds}
            deliveringId={deliveringId}
            disabled={busy || joining}
            onRegistered={addLinkedProblem}
            onDeliver={(problemId) => void deliver(problemId)}
          />
```

- [ ] **Step 16: 목 파일 삭제와 남은 참조 확인**

```bash
git rm front/apps/web/src/domains/lecture/mock/liveMock.ts
```

Run: `cd front/apps/web && grep -rn "liveMock\|mockStudents\|mockProgress\|mockCurrentProblem\|mockProblemSet\|mockBankResult\|MockStudent\|MockSetItem\|deviceConnected\|예시\|absent" src`
Expected: 0건

Run: `cd front/apps/web && grep -n "STUDENT_LOOK\|mono" src/pages/LiveLecturePage.tsx`
Expected: `STUDENT_LOOK` 0건. `mono`는 상수 정의와 참여 코드·경과 시간·정답 점형·제출 숫자에서 쓰인다(미사용이면 tsc가 알려 준다)

- [ ] **Step 17: 확인**

Run: `cd front && pnpm --filter @ieum/web exec prettier --write src/pages/LiveLecturePage.tsx src/domains/lecture && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: 모두 성공, lint 경고 0. `react-hooks/exhaustive-deps`: `joinVoice`는 `[lectureId, releaseRoom, loadAnswers]`, 진입 effect는 `[lectureId, joinVoice, releaseRoom, loadAnswers]`, `loadAnswers`는 `[lectureId]`여야 한다(setter·ref는 의존성이 아니다). 경고가 나오면 경고대로 맞추되 위 목록과 다르면 동작 변화가 없는지 따져 본다

- [ ] **Step 18: Commit**

```bash
git add front/apps/web/src/pages/LiveLecturePage.tsx front/apps/web/src/domains/lecture
git commit -m "feat(lecture): 실시간 수업 화면에 문제 출제와 학생 답안을 연결하고 목 데이터를 걷어낸다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 브라우저 E2E (사람과 함께)

**Files:** 없음 (확인 절차)

선행: `feat/lecture-problem-api`(등록·출제·제출·답안 목록·신호)와 `feat/lecture-problem-app`(대기·풀이·제출)이 `feat/lecture-problem`에 병합돼 있어야 한다. API만 먼저 있을 때는 1·6번(웹 단독)을 먼저 본다.

- [ ] **Step 1: 실행**

1. backend: 병합된 `feat/lecture-problem` 워크트리(`C:/Users/SSAFY/Desktop/wt-lecture-problem`)에서 `docker compose up -d --build backend`
2. 웹: `C:/Users/SSAFY/Desktop/wt-problem-web/front`에서 `pnpm dev:web` → `http://localhost:5173`. PID 기록(`netstat -ano | findstr :5173`)
3. 학습자 A·B: 학습자 앱(`feat/lecture-problem-app` 빌드, 실기기 2대. 한 대뿐이면 B는 생략하고 4·5번을 A로만 본다)

- [ ] **Step 2: 시나리오 (설계 §8 수동 E2E 기준)**

1. 강사 로그인 → 예정 수업 진입. 문제 세트 입력칸 위에 안내 문구가 보인다. `초성 ㄱ` 추가 → 목록에 "초성 ㄱ ⠈ 대기", `그리고` 추가 → 목록 2개. 출제 버튼은 비활성이고 "수업을 시작하면 출제할 수 있어요"가 보인다
2. `ㄱ`만 입력 → 입력칸 아래 후보 버튼 `초성 ㄱ` / `받침 ㄱ`. `받침 ㄱ`을 누르면 목록에 추가된다. `초성 ㄱ`을 다시 입력 → 목록 개수가 늘지 않는다. `초성 ㅃㅃ`처럼 없는 문제 → "해당하는 문제를 찾지 못했어요…"
3. 수업 시작 → 지금 문제 칸에 "오른쪽에서 문제를 골라 출제하세요". 학생 A·B 입장 → 카드 2장, 답안 칸 없음, 제출 현황 `0 / 2`
4. `그리고` 출제 → 지금 문제 카드에 "그리고", 정답 점형 문자열과 점자 그림, 목록에서 "지금 문제". 학생 카드마다 회색 "미착수", 제출 현황 `0 / 2 · 미착수 2`. A 앱이 풀이 화면으로 바뀐다
5. A가 정답 제출 → 새로고침 없이 A 카드가 초록 "⠁⠂… 정답"(제출 점자), 현황 `1 / 2 · 정답 1`. B가 틀린 답 제출 → B 카드가 노랑 + 제출 점자 + 이유(예: "첫 칸 6점 누락" 또는 "칸 수 다름"), 현황 `2 / 2 · 오답 1`
6. `초성 ㄱ` 출제 → 이전 답안이 사라지고 두 카드 모두 "미착수", 목록에서 `그리고`는 "출제됨", `초성 ㄱ`은 "지금 문제"
7. 강사 웹 새로고침 → 지금 문제·답안·현황이 복원된다. `그리고`는 "대기"로 돌아온다(출제됨 기억은 세션 한정 — 알려진 한계)
8. (가능하면) 네트워크 탭에서 답안 조회가 제출마다 한 번씩만 나가고, 빠르게 두 번 출제해도 마지막 문제의 답안만 보이는지 확인
9. 수업 종료 → 목록으로 이동. 같은 수업을 다시 열면(CLOSED) 문제 추가 입력이 비활성이고 마지막 문제의 답안이 보인다

Expected: 1~7, 9 통과(8은 관찰). 결과를 MR 본문에 적는다.

- [ ] **Step 3: 정리**

웹 dev 서버는 기록한 PID로만 멈춘다: `taskkill //PID <pid> //F`. backend는 `docker compose stop backend`.

---

## 완료 확인 (MR 전)

- [ ] `cd front && pnpm --filter @ieum/web test` — 기존 + apiErrorBody 3 + lectureApi 3 + ambiguousCandidates 4 + errorMessage 2 + braille 3 + answerView 16 + lectureSignal 4 + instructorRoom 2 + problemSet 5 신규가 모두 PASS임을 확인했다
- [ ] `cd front && pnpm --filter @ieum/web lint` — 오류·경고 0임을 확인했다
- [ ] `cd front && pnpm --filter @ieum/web build` — tsc 포함 성공을 확인했다
- [ ] `cd front && pnpm -r typecheck && pnpm --filter @ieum/mobile test` — api-client 변경이 학습자 앱을 깨지 않음을 확인했다
- [ ] `docker build --target test front` 성공을 확인했다 (MR verify와 같은 검증)
- [ ] `git diff feat/lecture-problem --stat`에 `front/apps/web`와 `front/packages/api-client/src/{errors,client}.ts` 밖의 파일이 없음을 확인했다
- [ ] Task 6 E2E 결과를 MR 본문에 적었다. MR 본문에 `packages/api-client` 추가형 변경(앱 팀 확인 필요)을 명시했다
- [ ] push·MR 등록은 사람이 한다 (`feat/lecture-problem-web` → `feat/lecture-problem`)

## 알려진 한계

- "출제됨"은 웹 세션 메모리다 — 새로고침하면 지금 문제만 알 수 있다(설계 §4).
- 칸 수는 답안 칸에서 뒤쪽 0을 뺀 길이로 판단한다. 끝에 빈 칸(0)을 일부러 제출한 경우 "칸 수 다름" 대신 점 누락으로 보일 수 있다.
- 답안이 있지만 지금 접속하지 않은 학생은 카드·현황에 넣지 않는다(접속자 기준 명단, 설계 §4). 그래서 현황의 제출 수가 서버 답안 수보다 작을 수 있다.
- 답안 신호를 놓치면(LiveKit 끊김 등) 다음 출제·새로고침 때까지 카드가 늦는다. 신호 실패는 서버가 로그만 남긴다.
- 연결 해제·순서 편집, 문제 은행 검색, 반 정답률, 장치 연결 표시, 걸린 시간은 범위 밖(설계 §9).
