import { create } from "zustand";

/**
 * 점자 모듈(HC-06) 연결 상태 — 화면이 구독해 그리는 값.
 *
 * 실제 통신은 `services/tactileDevice.ts`가 하고, 그쪽에서 이 값을 갱신한다.
 * 화면은 이 스토어만 보므로 **기기 없이도 화면을 띄우고 테스트할 수 있다.**
 */
interface TactileState {
  connected: boolean;
  /** 연결된 기기 이름. 끊겨 있으면 null */
  name: string | null;
  set: (patch: Partial<Pick<TactileState, "connected" | "name">>) => void;
}

export const useTactileStore = create<TactileState>()((set) => ({
  connected: false,
  name: null,
  set: (patch) => set(patch),
}));

export function tactileStatusLabel(connected: boolean, name: string | null): string {
  return connected ? (name ?? "연결됨") : "연결 안 됨";
}
