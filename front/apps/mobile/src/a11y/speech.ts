import { AccessibilityInfo } from "react-native";
import * as Speech from "expo-speech";
import { useSettingsStore, speechRateValue } from "@/stores/settingsStore";

let screenReaderOn = false;
AccessibilityInfo.isScreenReaderEnabled().then((v) => {
  screenReaderOn = v;
});
AccessibilityInfo.addEventListener("screenReaderChanged", (v) => {
  screenReaderOn = v;
});

let lastText = "";
let lastAt = 0;

/**
 * 앱 자체 음성 안내 (expo-speech).
 * TalkBack/VoiceOver가 켜져 있으면 중복 낭독을 피하기 위해
 * announceForAccessibility로 위임한다. (정책 일원화 지점)
 */
export function speak(text: string, opts?: { force?: boolean }): void {
  const { voiceEnabled, speechRate } = useSettingsStore.getState();
  if (!voiceEnabled && !opts?.force) return;
  const now = Date.now();
  if (!opts?.force && text === lastText && now - lastAt < 1500) return; // 중복 방지
  lastText = text;
  lastAt = now;
  if (screenReaderOn) {
    AccessibilityInfo.announceForAccessibility(text);
    return;
  }
  Speech.stop();
  Speech.speak(text, { language: "ko-KR", rate: speechRateValue(speechRate) });
}

/** 화면 진입·상태 변화 알림 — 스크린리더와 TTS 모두 커버 */
export function announce(text: string): void {
  speak(text);
}

export function stopSpeaking(): void {
  Speech.stop();
}
