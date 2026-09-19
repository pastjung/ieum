import { create } from "zustand";
import type { LearningSession, Problem } from "@ieum/contracts";
import { gradeAnswer, type Cell, type GradeResult } from "@ieum/domain";

export type SessionKind = "free" | "class";

interface AnswerRecord {
  problemId: string;
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
  start: (session: LearningSession, kind?: SessionKind) => void;
  current: () => Problem | null;
  submit: (cells: Cell[]) => GradeResult;
  /** 다음 문항으로. 마지막이었으면 false */
  next: () => boolean;
  correctCount: () => number;
  markDisconnectShown: () => void;
  reset: () => void;
}

export const useSessionStore = create<SessionState>()((set, get) => ({
  session: null,
  kind: "free",
  index: 0,
  answers: [],
  lastResult: null,
  disconnectShown: false,
  start: (session, kind = "free") =>
    set({ session, kind, index: 0, answers: [], lastResult: null, disconnectShown: false }),
  current: () => {
    const { session, index } = get();
    return session?.problems[index] ?? null;
  },
  submit: (cells) => {
    const problem = get().current();
    if (!problem) throw new Error("진행 중인 문제가 없어요");
    const result = gradeAnswer(problem.expectedCells, cells);
    set((s) => ({
      answers: [...s.answers, { problemId: problem.id, cells, result }],
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
  reset: () =>
    set({ session: null, kind: "free", index: 0, answers: [], lastResult: null, disconnectShown: false }),
}));
