import { create } from "zustand";
import type { AttemptInput, CompletionResponse, LearningSession, Problem } from "@ieum/contracts";
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
 * **왜 이 규칙인가**: `gradeAnswer`(`@ieum/domain`)가 정확히 이렇게 동작하기 때문이다 — 모자란
 * 칸은 `?? EMPTY_CELL`로 읽고, 기대 길이까지만 순회해 그 뒤는 채점에 넣지 않는다. 같은 규칙을
 * 쓰면 사용자가 화면에서 들은 로컬 채점과 서버가 저장하는 채점이 갈라지지 않는다.
 *
 * 다만 **넘치는 입력 한 경우만은 완전히 같지 않다**: 앞 칸이 모두 맞고 뒤에 군더더기 칸이 있으면
 * 로컬은 오답으로 말하지만(`gradeAnswer`의 `actual.length >= total` 검사) 잘라 보낸 답을 서버는
 * 정답으로 센다. 칸을 더 보낼 방법이 없으므로(보내면 세션 전체가 실패한다) 이 차이는 감수한다 —
 * 세션 전체를 잃는 것보다 낫다.
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
    }));
    return result;
  },
  next: () => {
    const { session, index } = get();
    if (!session) return false;
    if (index + 1 >= session.problems.length) return false;
    set({ index: index + 1, lastResult: null });
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
}));
