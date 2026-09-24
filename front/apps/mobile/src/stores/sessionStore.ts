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
    // 백엔드는 같은 problemSessionId가 두 번 있으면 제출 전체를 거부한다
    // (LearningSessionCompletionService.validateAndIndexAttempts) — 재제출 시 마지막 답만 남긴다
    const latestByProblemSessionId = new Map<number, Cell[]>();
    for (const a of get().answers) {
      if (a.problemSessionId === null) continue;
      latestByProblemSessionId.set(a.problemSessionId, a.cells);
    }
    return Array.from(latestByProblemSessionId, ([problemSessionId, inputPatterns]) => ({
      problemSessionId,
      inputPatterns,
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
