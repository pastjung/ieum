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
  myResult: LiveAnswer | null = null,
): Transition {
  if (currentProblem === null) {
    if (phase !== null && (phase.kind === "waiting" || phase.kind === "graded")) return { phase, announcement: null };
    return { phase: { kind: "waiting" }, announcement: WAITING_MESSAGE };
  }

  // 풀던 문제가 이미 채점되어 있다(제출이 ALREADY_ANSWERED로 막혀 다시 조회한 경우 등) — 제출
  // 대기로만 두면 이 결과를 영영 읽어 주지 못하므로 곧바로 채점 화면으로 간다 (M5)
  if (phase?.kind === "solving" && myResult && myResult.problemId === phase.problem.problemId) {
    return { phase: { kind: "graded", problem: phase.problem, result: myResult }, announcement: null };
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
