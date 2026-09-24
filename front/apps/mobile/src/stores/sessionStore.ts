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
  next: () => {
    const { session, index } = get();
    if (!session) return false;
    if (index + 1 >= session.problems.length) return false;
    set({ index: index + 1, lastResult: null });
    return true;
  },
  correctCount: () => get().answers.filter((a) => a.result.correct).length,
  markDisconnectShown: () => set({ disconnectShown: true }),
  buildAttempts: () =>
    get()
      .answers.filter((a): a is AnswerRecord & { problemSessionId: number } =>
        a.problemSessionId !== null,
      )
      .map((a) => ({ problemSessionId: a.problemSessionId, inputPatterns: a.cells })),
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
