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

/** 글자 수 기반 낭독 시간 추정 (스크린리더 위임 시 완료 콜백이 없어서 사용) */
function estimateMs(text: string, rate: number): number {
  return Math.min(12000, 400 + (text.length * 110) / rate);
}

/**
 * 안내를 읽고, 낭독이 끝나면 resolve되는 Promise를 돌려준다.
 * 자동 화면 전환(스플래시·분석 중·수업 대기)은 이걸 기다렸다가 이동해
 * 말이 끝나기 전에 화면이 넘어가지 않게 한다.
 */
export function speakAsync(text: string): Promise<void> {
  const { voiceEnabled, speechRate } = useSettingsStore.getState();
  const rate = speechRateValue(speechRate);
  if (!voiceEnabled) return Promise.resolve();
  lastText = text;
  lastAt = Date.now();
  if (screenReaderOn) {
    AccessibilityInfo.announceForAccessibility(text);
    return new Promise((resolve) => setTimeout(resolve, estimateMs(text, rate)));
  }
  Speech.stop();
  return new Promise((resolve) => {
    Speech.speak(text, {
      language: "ko-KR",
      rate,
      onDone: () => resolve(),
      onStopped: () => resolve(),
      onError: () => resolve(),
    });
  });
}
