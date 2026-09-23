import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import AsyncStorage from "@react-native-async-storage/async-storage";
import type { ThemeName } from "@ieum/design-tokens";

export type UsageMode = "voice" | "screen";
export type SpeechRate = "slow" | "normal" | "fast";
export type FontScale = "normal" | "large";

/** 복제한 내 목소리. 샘플은 올린 뒤 지우고 id만 남긴다. */
export interface ClonedVoice {
  id: string;
  name: string;
}

interface SettingsState {
  /** 사용 방식 (최초 1회 선택, null이면 미선택) */
  mode: UsageMode | null;
  voiceEnabled: boolean;
  speechRate: SpeechRate;
  hapticsEnabled: boolean;
  fontScale: FontScale;
  theme: ThemeName;
  /** 접근성 설정·사용 안내까지 마쳤는지 */
  onboarded: boolean;
  /** 음성 비서 '잇다' 사용 여부 — 끄면 호출어를 듣지 않는다 */
  itdaEnabled: boolean;
  /** 복제한 목소리. null이면 시스템 TTS로 안내한다 */
  clonedVoice: ClonedVoice | null;
  setMode: (mode: UsageMode) => void;
  setVoiceEnabled: (v: boolean) => void;
  setSpeechRate: (v: SpeechRate) => void;
  setHapticsEnabled: (v: boolean) => void;
  setFontScale: (v: FontScale) => void;
  setTheme: (v: ThemeName) => void;
  setOnboarded: (v: boolean) => void;
  setItdaEnabled: (v: boolean) => void;
  setClonedVoice: (v: ClonedVoice | null) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      mode: null,
      voiceEnabled: true,
      speechRate: "normal",
      hapticsEnabled: true,
      fontScale: "normal",
      theme: "dark",
      onboarded: false,
      itdaEnabled: false,
      clonedVoice: null,
      setMode: (mode) => set({ mode }),
      setVoiceEnabled: (voiceEnabled) => set({ voiceEnabled }),
      setSpeechRate: (speechRate) => set({ speechRate }),
      setHapticsEnabled: (hapticsEnabled) => set({ hapticsEnabled }),
      setFontScale: (fontScale) => set({ fontScale }),
      setTheme: (theme) => set({ theme }),
      setOnboarded: (onboarded) => set({ onboarded }),
      setItdaEnabled: (itdaEnabled) => set({ itdaEnabled }),
      setClonedVoice: (clonedVoice) => set({ clonedVoice }),
    }),
    {
      name: "ieum-settings",
      storage: createJSONStorage(() => AsyncStorage),
    },
  ),
);

export function speechRateValue(rate: SpeechRate): number {
  switch (rate) {
    case "slow":
      return 0.8;
    case "fast":
      return 1.3;
    default:
      return 1.0;
  }
}

export const speechRateLabel: Record<SpeechRate, string> = {
  slow: "느리게",
  normal: "보통",
  fast: "빠르게",
};

export const fontScaleLabel: Record<FontScale, string> = {
  normal: "보통",
  large: "크게",
};
