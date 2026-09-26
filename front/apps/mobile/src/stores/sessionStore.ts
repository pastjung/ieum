import { create } from "zustand";
import type {
  AttemptAnalysisResponse,
  AttemptInput,
  CompletionResponse,
  LearningSession,
  Problem,
} from "@ieum/contracts";
import { gradeAnswer, type Cell, type GradeResult } from "@ieum/domain";

export type SessionKind = "free" | "class";

/** 서버 세션의 식별자들. 목 세션으로 시작하면 없다 */
export interface ServerSessionMeta {
  serverSessionId: number;
  /** 문항 순서와 같은 길이·같은 순서 */
  problemSessionIds: number[];
  /**
   * 풀기 시작할 문항의 위치. 새 세션은 0이고, 이어받기는 안 푼 첫 문항을 가리킨다.
   *
   * 이미 푼 문항도 목록에 있어야 한다 — 완료 제출은 배정 전부를 대상으로 하므로 빼면
   * 세션이 짧아져 완료가 거부된다.
   */
  startIndex?: number;
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
  /**
   * 이번 문항에 서버 설명이 없는 이유. 지금은 `LEARNING_PROBLEM_ALREADY_ANALYZED` 하나다 —
   * 응답 유실이나 앱 재시작으로 서버가 이미 끝낸 문항이라 분석을 다시 받을 수 없다.
   *
   * 화면(`session/grade.tsx`)이 이 문자열 하나를 화면에도 그리고 낭독도 한다. `play.tsx`에
   * 띄워 봐야 바로 다음 줄의 `router.replace`가 화면을 치우고, 낭독도 `grade.tsx`의
   * `announce`가 `Speech.stop()`으로 끊는다 — 그래서 안내를 화면 간에 실어 나른다.
   */
  lastAttemptNotice: string | null;
  serverSessionId: number | null;
  problemSessionIds: number[];
  serverResult: CompletionResponse | null;
  start: (session: LearningSession, kind?: SessionKind, meta?: ServerSessionMeta) => void;
  current: () => Problem | null;
  submit: (cells: Cell[]) => GradeResult;
  /**
   * `from` 문항에서 다음 문항으로 넘어간다. 넘어갔으면 true.
   *
   * 지금 문항이 `from`이 아니면 아무것도 하지 않는다. 빠르게 두 번 누르면 화면이 다시
   * 그려지기 전에 같은 처리가 두 번 불리는데, 그때마다 하나씩 넘기면 문항을 건너뛴다.
   * 건너뛴 문항은 풀지 않은 채 남아 완료 제출이 거부된다.
   */
  next: (from: number) => boolean;
  correctCount: () => number;
  /** 완료 제출 페이로드. 목 세션이면 빈 배열이다 */
  buildAttempts: () => AttemptInput[];
  setServerResult: (result: CompletionResponse) => void;
  setLastAnalysis: (result: AttemptAnalysisResponse | null) => void;
  setLastAttemptNotice: (message: string | null) => void;
  reset: () => void;
}

export const useSessionStore = create<SessionState>()((set, get) => ({
  session: null,
  kind: "free",
  index: 0,
  answers: [],
  lastResult: null,
  lastAnalysis: null,
  lastAttemptNotice: null,
  serverSessionId: null,
  problemSessionIds: [],
  serverResult: null,
  start: (session, kind = "free", meta) =>
    set({
      session,
      kind,
      index: meta?.startIndex ?? 0,
      answers: [],
      lastResult: null,
      lastAnalysis: null,
      lastAttemptNotice: null,
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
      // 전까지) 이전 문항·이전 시도의 분석이 남아 있으면 그 사이 화면이 무관한 설명을 읽는다.
      // 안내(lastAttemptNotice)도 같은 이유로 같이 비운다
      lastAnalysis: null,
      lastAttemptNotice: null,
    }));
    return result;
  },
  next: (from) => {
    const { session, index } = get();
    if (!session || index !== from) return false;
    if (index + 1 >= session.problems.length) return false;
    set({ index: index + 1, lastResult: null, lastAnalysis: null, lastAttemptNotice: null });
    return true;
  },
  correctCount: () => get().answers.filter((a) => a.result.correct).length,
  /**
   * 완료 제출 페이로드. **학습자가 실제로 입력한 셀을 그대로 보낸다.**
   *
   * 예전에는 문항의 기대 칸 수에 맞춰 모자란 답을 빈 셀로 채우고 넘치는 답을 잘랐다. 그
   * 근거는 백엔드 `LearningSessionCompletionService.validateCellInputs`가 길이 동등을
   * 검사한다는 것이었는데 **그 함수는 존재하지 않는다** — dev가 길이 동등 검사를 걷어냈고,
   * 지금 `/completion`이 보는 것은 배정 대응(`validateAndIndexAttempts`)과 정답 셀의 순번
   * 연속성(`validateExpectedCells`)뿐이다.
   *
   * 그 사이 자르기·채우기는 해로웠다. `attempt-analysis`는 원본 `cells`를 보내 서버가
   * 그대로 저장하는데 `/completion`만 손댄 값을 보냈고, `prepareAnalysisAttempts`가 그
   * 손댄 값을 AI 세션 요약에 먹였다 — **요약이 학습자가 내지 않은 답으로 계산됐다.**
   * 그러면 화면에 보이는 점수(백엔드가 DB 시드로 채점한 `correctProblemCount`)와 요약이
   * 말하는 점수가 갈릴 수 있다.
   *
   * 12칸 상한은 `play.tsx`의 제출 가드(`describeTooManyCells`)가 보증한다.
   */
  buildAttempts: () => {
    const { answers } = get();
    // 백엔드는 같은 problemSessionId가 두 번 있으면 제출 전체를 거부한다
    // (LearningSessionCompletionService.validateAndIndexAttempts) — 재제출 시 마지막 답만 남긴다
    const latestByProblemSessionId = new Map<number, Cell[]>();
    for (const a of answers) {
      if (a.problemSessionId === null) continue;
      latestByProblemSessionId.set(a.problemSessionId, a.cells);
    }
    return Array.from(latestByProblemSessionId, ([problemSessionId, cells]) => ({
      problemSessionId,
      inputPatterns: cells,
    }));
  },
  setServerResult: (result) => set({ serverResult: result }),
  setLastAnalysis: (result) => set({ lastAnalysis: result }),
  setLastAttemptNotice: (message) => set({ lastAttemptNotice: message }),
  reset: () =>
    set({
      session: null,
      kind: "free",
      index: 0,
      answers: [],
      lastResult: null,
      lastAnalysis: null,
      lastAttemptNotice: null,
      serverSessionId: null,
      problemSessionIds: [],
      serverResult: null,
    }),
}));
