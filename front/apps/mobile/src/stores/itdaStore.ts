import { create } from "zustand";
import type { VoicePhase } from "@/services/itdaVoiceEngine";

/**
 * 잇다의 현재 상태 — 화면이 보고 그리는 값만 담는다.
 *
 * 동작 자체는 `services/itda.ts`가 하고, 여기는 표시용이다.
 */
interface ItdaState {
  phase: VoicePhase;
  /** 인식 중간 결과 */
  partial: string;
  /** 마지막으로 알아들은 말 */
  lastHeard: string;
  /** 잇다가 답한 말 */
  speech: string;
  /** 되묻는 중인 질문. 없으면 null */
  pendingQuestion: string | null;
  /** 권한·인식기 문제 안내 */
  notice: string;
  set: (patch: Partial<Omit<ItdaState, "set" | "reset">>) => void;
  reset: () => void;
}

const EMPTY = {
  phase: "stopped" as VoicePhase,
  partial: "",
  lastHeard: "",
  speech: "",
  pendingQuestion: null,
  notice: "",
};

export const useItdaStore = create<ItdaState>()((set) => ({
  ...EMPTY,
  set: (patch) => set(patch),
  reset: () => set(EMPTY),
}));

export const itdaPhaseLabel: Record<VoicePhase, string> = {
  stopped: "꺼짐",
  wake: '대기 중 — "하이 잇다"라고 불러 보세요',
  command: "명령을 듣는 중",
  thinking: "처리 중",
  speaking: "잇다가 말하는 중",
  error: "오류",
};
