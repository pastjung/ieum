import { ApiError } from "@ieum/api-client";
import type { LiveAnswer } from "@ieum/contracts";
import type { Cell } from "@ieum/domain";
import { announce } from "@/a11y";
import { useLectureProblemStore } from "@/stores/lectureProblemStore";
import { classService } from "./classService";
import { newIdempotencyKey } from "./idempotencyKey";
import {
  acknowledgeGrade,
  applySnapshot,
  PROBLEM_CHANGED_MESSAGE,
  SUBMIT_CLOSED_MESSAGE,
  SUBMIT_FAILED_MESSAGE,
  WAITING_MESSAGE,
  type FlowDeps,
  type LectureProblem,
  type ProblemPhase,
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
// 제출 세대 번호. begin/stop/reset이 올려서, 끊긴 뒤 뒤늦게 끝나는 제출의 finally가
// 그사이 시작된 다음 제출의 submitting 플래그를 잘못 풀지 못하게 막는다 (I1)
let submitGen = 0;

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

/** phase가 가리키는 문제 id. 채점 결과가 그 문제에 여전히 해당하는지 비교하는 데 쓴다 */
function phaseProblemId(p: ProblemPhase | null): string | null {
  if (p?.kind === "solving" || p?.kind === "graded") return p.problem.problemId;
  if (p?.kind === "submitted") return p.problemId;
  return null;
}

export const lectureProblemSync = {
  /** 대기 화면이 입장을 시도할 때. 같은 수업의 재접속이면 집계는 두고 상태만 조회 전으로 되돌린다 */
  begin(lectureId: string): void {
    requestSeq++;
    submitGen++;
    submitting = false; // 끊긴 연결이 복구된 것일 수 있다 — 제출이 다시 막히지 않게 한다 (I1)
    if (store().lectureId !== lectureId) store().reset();
    store().set({ lectureId, active: true, phase: null });
  },

  /** 방을 나왔다(종료·끊김·퇴장). 집계는 종료 화면이 쓰므로 남긴다 */
  stop(): void {
    requestSeq++;
    submitGen++;
    submitting = false;
    store().set({ active: false });
  },

  /** 수업을 완전히 떠날 때(홈으로·수업 나가기) */
  reset(): void {
    requestSeq++;
    submitGen++;
    submitting = false;
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
    const gen = ++submitGen;
    requestSeq++;
    const submittedProblem: LectureProblem = phase.problem;
    const durationMs = Math.max(0, Math.round(deps.now() - phase.startedAt));
    try {
      const result = await classService.submitLiveAnswer(
        lectureId,
        submittedProblem.problemId,
        { cells: [...cells], durationMs },
        phase.idempotencyKey,
      );
      if (!isActiveFor(lectureId)) return "ignored";
      record(result);
      // 그 사이 다른 문제로 넘어갔으면(화면이 이미 새 문제를 보여준다) 결과는 집계만 하고 화면은 그대로 둔다.
      // 제출 중 재접속으로 phase가 null·submitted(같은 문제)로 바뀐 것은 다른 문제로 넘어간 게 아니므로 반영한다 (M1)
      const curId = phaseProblemId(store().phase);
      if (curId !== null && curId !== result.problemId) return "ignored";
      commit({ phase: { kind: "graded", problem: submittedProblem, result }, announcement: null });
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
      // begin/stop/reset이 그사이 새 세대를 열었으면(끊김·재접속) 이 finally는 그 세대의 플래그를 건드리지 않는다 (I1)
      if (gen === submitGen) submitting = false;
    }
  },

  /** 채점 화면의 확인 — 제출 대기로 돌아가며 안내한다 */
  acknowledgeGrade(): void {
    const transition = acknowledgeGrade(store().phase);
    if (transition) commit(transition);
  },
};
