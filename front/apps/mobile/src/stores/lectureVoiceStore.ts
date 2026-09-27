import { create } from "zustand";

/**
 * 수업 중 내 발언 상태 — 풀이 화면이 구독해 손들기·마이크 버튼을 그린다.
 *
 * 값은 `services/liveLecture.ts`만 바꾼다. 방을 떠나면 처음 값으로 돌아간다.
 * 실제로 말할 수 있는지는 LiveKit 권한이 원천이고, 여기는 그 사본이다.
 */
interface LectureVoiceState {
  /** 선생님이 발언을 허용했다 (LiveKit canPublish + MICROPHONE) */
  speakingAllowed: boolean;
  /** 내 마이크가 수업에 송출 중이다 */
  micOn: boolean;
  /** 손을 들었다 (참가자 속성 hand=up) */
  handUp: boolean;
  set: (patch: Partial<Pick<LectureVoiceState, "speakingAllowed" | "micOn" | "handUp">>) => void;
  reset: () => void;
}

const INITIAL = { speakingAllowed: false, micOn: false, handUp: false };

export const useLectureVoiceStore = create<LectureVoiceState>()((set) => ({
  ...INITIAL,
  set: (patch) => set(patch),
  reset: () => set(INITIAL),
}));
