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
