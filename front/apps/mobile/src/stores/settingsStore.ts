import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import AsyncStorage from "@react-native-async-storage/async-storage";
import type { ThemeName } from "@ieum/design-tokens";

export type UsageMode = "voice" | "screen";
export type SpeechRate = "slow" | "normal" | "fast";
export type FontScale = "normal" | "large";

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
  setMode: (mode: UsageMode) => void;
  setVoiceEnabled: (v: boolean) => void;
  setSpeechRate: (v: SpeechRate) => void;
  setHapticsEnabled: (v: boolean) => void;
  setFontScale: (v: FontScale) => void;
  setTheme: (v: ThemeName) => void;
  setOnboarded: (v: boolean) => void;
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
      setMode: (mode) => set({ mode }),
      setVoiceEnabled: (voiceEnabled) => set({ voiceEnabled }),
      setSpeechRate: (speechRate) => set({ speechRate }),
      setHapticsEnabled: (hapticsEnabled) => set({ hapticsEnabled }),
      setFontScale: (fontScale) => set({ fontScale }),
      setTheme: (theme) => set({ theme }),
      setOnboarded: (onboarded) => set({ onboarded }),
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
