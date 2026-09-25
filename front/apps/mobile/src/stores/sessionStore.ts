import { create } from "zustand";
import type {
  AttemptAnalysisResponse,
  AttemptInput,
  CompletionResponse,
  LearningSession,
  Problem,
} from "@ieum/contracts";
import { EMPTY_CELL, gradeAnswer, type Cell, type GradeResult } from "@ieum/domain";

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

interface SessionState {
  session: LearningSession | null;
  kind: SessionKind;
  index: number;
  answers: AnswerRecord[];
  lastResult: GradeResult | null;
  /** 문항별 서버 분석 결과. 실시간 수업(serverSessionId === null)은 채우지 않는다 */
  lastAnalysis: AttemptAnalysisResponse | null;
  /** 수업 중 연결 끊김 데모를 이미 보여줬는지 (V29) */
  disconnectShown: boolean;
  serverSessionId: number | null;
  problemSessionIds: number[];
  serverResult: CompletionResponse | null;
  start: (session: LearningSession, kind?: SessionKind, meta?: ServerSessionMeta) => void;
  current: () => Problem | null;
  submit: (cells: Cell[]) => GradeResult;
  /** 다음 문항으로. 마지막이었으면 false */
  next: () => boolean;
  correctCount: () => number;
  markDisconnectShown: () => void;
  /** 완료 제출 페이로드. 목 세션이면 빈 배열이다 */
  buildAttempts: () => AttemptInput[];
  setServerResult: (result: CompletionResponse) => void;
  setLastAnalysis: (result: AttemptAnalysisResponse | null) => void;
  reset: () => void;
}

/**
 * 답안을 그 문항이 요구하는 칸 수에 맞춘다 — 모자라면 빈 셀로 채우고, 넘치면 자른다.
 *
 * **왜 필요한가**: 백엔드는 문항 하나라도 `expectedCells.size() != inputPatterns.size()` 이면
 * 세션 전체를 거부한다(`LearningSessionCompletionService.validateCellInputs`). `complete()`는
 * `@Transactional`이라 12문항 중 하나만 칸 수가 어긋나도 전부 롤백된다. 그런데 칸 수가 모자란
 * 답은 예외가 아니라 초보 학습자의 가장 흔한 오답이다 — 앱은 이것을 막지 않고(`play.tsx`의
 * 제출 가드는 "한 칸도 없음"만 본다) 정상적인 오답으로 채점해 왔다. 정규화하지 않으면 그 오답
 * 하나가 세션 전체를 영구 실패시킨다.
 *
 * **모자란 답을 채우는 것은 (한 가지 예외를 빼면) 채점을 바꾸지 않는다**: `gradeAnswer`
 * (`@ieum/domain`)가 모자란 칸을 `?? EMPTY_CELL`로 읽으므로, 빈 셀로 채워 보내면 서버도 같은
 * 값을 보고 같은 판정을 한다.
 *
 * 예외는 **기대 셀의 뒤쪽이 빈 칸(`0`)인 문항**이다. 그때는 로컬이 길이 미달을 오답으로 보지만
 * (`correct: firstDiff === null && actual.length >= total`) 서버는 셀 값만 비교하므로 정답으로
 * 센다. 지금 시드에는 `expected_pattern = 0` 인 행이 없어 발현하지 않는다(확인함). 빈 칸이
 * 섞이기 쉬운 **문장 단계 시드가 들어오면 발현한다.** 근본 수정은 `gradeAnswer`의 길이 규칙을
 * 바꾸는 것인데 그 함수는 실시간 수업도 쓰므로 함께 다뤄야 한다 — 이번 작업 범위 밖이다
 * (설계 9절에도 적어 두었다).
 *
 * **넘치는 답은 여기까지 오면 안 된다**: `gradeAnswer`는 앞 칸이 다 맞아도 칸이 남으면 오답으로
 * 판정하므로(`actual.length > expected.length` 분기), 잘라 보내면 화면은 "틀렸어요"인데 서버는
 * 정답으로 센다. 그래서 `play.tsx`의 제출 가드(`describeTooManyCells`)가 넘치는 입력을 애초에
 * 막는다. 여기의 자르기는 그 가드를 우회하는 경로가 생겼을 때를 위한 방어선이다 — 채점이 한
 * 문항에서 어긋나는 편이 세션 12문항을 통째로 잃는 것보다 낫다는 판단이며, 여기에 걸리는 답이
 * 있다면 그것은 `play.tsx` 쪽의 버그다.
 *
 * 기대 길이를 모르면(목 세션 등) 손대지 않는다.
 */
function normalizeToExpectedLength(cells: Cell[], expectedLength: number | undefined): Cell[] {
  if (expectedLength === undefined || cells.length === expectedLength) return cells;
  if (cells.length > expectedLength) return cells.slice(0, expectedLength);
  return [...cells, ...Array<Cell>(expectedLength - cells.length).fill(EMPTY_CELL)];
}

export const useSessionStore = create<SessionState>()((set, get) => ({
  session: null,
  kind: "free",
  index: 0,
  answers: [],
  lastResult: null,
  lastAnalysis: null,
  disconnectShown: false,
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
      lastAnalysis: null,
      disconnectShown: false,
      serverSessionId: meta?.serverSessionId ?? null,
      problemSessionIds: meta?.problemSessionIds ?? [],
      serverResult: null,
    }),
  current: () => {
    const { session, index } = get();
    return session?.problems[index] ?? null;
  },
  submit: (cells) => {
    const { index, problemSessionIds, current } = get();
    const problem = current();
    if (!problem) throw new Error("진행 중인 문제가 없어요");
    const result = gradeAnswer(problem.expectedCells, cells);
    set((s) => ({
      answers: [
        ...s.answers,
        {
          problemId: problem.id,
          // 문항 순서(index)로 찾는다. 앱은 problemId → problemSessionId 대응을 모른다(서버만
          // 안다) — 서버가 준 배열의 순서가 유일한 단서다. 순서가 어긋나면 답이 다른 문항에
          // 기록되고, 화면에는 이상이 없이 점수만 틀린다
          problemSessionId: problemSessionIds[index] ?? null,
          cells,
          result,
        },
      ],
      lastResult: result,
      // 새 답은 이전 분석을 무효로 만든다 — 서버 응답을 기다리는 동안(setLastAnalysis 호출
      // 전까지) 이전 문항·이전 시도의 분석이 남아 있으면 그 사이 화면이 무관한 설명을 읽는다
      lastAnalysis: null,
    }));
    return result;
  },
  next: () => {
    const { session, index } = get();
    if (!session) return false;
    if (index + 1 >= session.problems.length) return false;
    set({ index: index + 1, lastResult: null, lastAnalysis: null });
    return true;
  },
  correctCount: () => get().answers.filter((a) => a.result.correct).length,
  markDisconnectShown: () => set({ disconnectShown: true }),
  buildAttempts: () => {
    const { answers, session, problemSessionIds } = get();
    // 배정 식별자 → 그 문항이 요구하는 칸 수. 문항 순서와 problemSessionIds는 같은 인덱스다
    // (`submit`이 답을 붙일 때 쓰는 대응과 같다)
    const expectedLengthById = new Map<number, number>();
    session?.problems.forEach((problem, index) => {
      const problemSessionId = problemSessionIds[index];
      if (problemSessionId !== undefined) {
        expectedLengthById.set(problemSessionId, problem.expectedCells.length);
      }
    });

    // 백엔드는 같은 problemSessionId가 두 번 있으면 제출 전체를 거부한다
    // (LearningSessionCompletionService.validateAndIndexAttempts) — 재제출 시 마지막 답만 남긴다
    const latestByProblemSessionId = new Map<number, Cell[]>();
    for (const a of answers) {
      if (a.problemSessionId === null) continue;
      latestByProblemSessionId.set(a.problemSessionId, a.cells);
    }
    return Array.from(latestByProblemSessionId, ([problemSessionId, cells]) => ({
      problemSessionId,
      inputPatterns: normalizeToExpectedLength(cells, expectedLengthById.get(problemSessionId)),
    }));
  },
  setServerResult: (result) => set({ serverResult: result }),
  setLastAnalysis: (result) => set({ lastAnalysis: result }),
  reset: () =>
    set({
      session: null,
      kind: "free",
      index: 0,
      answers: [],
      lastResult: null,
      lastAnalysis: null,
      disconnectShown: false,
      serverSessionId: null,
      problemSessionIds: [],
      serverResult: null,
    }),
}));
