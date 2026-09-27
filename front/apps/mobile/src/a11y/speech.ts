import { AccessibilityInfo } from "react-native";
import * as Speech from "expo-speech";
import { useSettingsStore, speechRateValue, type SpeechRate } from "@/stores/settingsStore";
import { canUseClonedVoice, speakWithClonedVoice, stopClonedVoice } from "./clonedVoice";

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
  systemSpeak(text, speechRate);
}

/** 시스템 TTS로 읽는다 — 복제 목소리가 없거나 실패했을 때의 경로. */
function systemSpeak(text: string, speechRate: SpeechRate): void {
  Speech.stop();
  Speech.speak(text, { language: "ko-KR", rate: speechRateValue(speechRate) });
}

/** 같은 낭독이되, 끝나면 resolve된다. TTS가 없거나 실패해도 반드시 풀린다. */
function systemSpeakAsync(text: string, speechRate: SpeechRate): Promise<void> {
  return new Promise((resolve) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      clearTimeout(guard);
      resolve();
    };
    // TTS 엔진이 아직 붙지 않았으면 콜백이 아예 오지 않는다. 그때도 흐름이 멈추면 안 된다.
    const guard = setTimeout(finish, estimateMs(text, speechRateValue(speechRate)) + 1_500);

    Speech.stop();
    Speech.speak(text, {
      language: "ko-KR",
      rate: speechRateValue(speechRate),
      onDone: finish,
      onStopped: finish,
      onError: finish,
    });
  });
}

/**
 * 잇다 응답처럼 **복제한 내 목소리로** 읽어야 하는 안내.
 *
 * 합성은 네트워크를 타므로 비동기다. 실패하면 시스템 TTS로 넘어간다 —
 * 어떤 경로로든 안내는 반드시 나가야 한다.
 */
export function speakAsItda(text: string): void {
  void speakAsItdaAsync(text);
}

/**
 * 잇다 응답을 읽고, **말이 실제로 끝나면** resolve된다.
 *
 * 잇다는 말을 마친 뒤에 마이크를 여는데, 길이를 글자 수로 어림잡으면 너무 늦게 열려
 * 사용자의 명령 앞부분을 통째로 놓친다. 그래서 낭독 완료 신호를 그대로 쓴다.
 */
export async function speakAsItdaAsync(text: string): Promise<void> {
  const { voiceEnabled, speechRate } = useSettingsStore.getState();
  if (!voiceEnabled) return;
  lastText = text;
  lastAt = Date.now();

  if (screenReaderOn) {
    AccessibilityInfo.announceForAccessibility(text);
    await new Promise((resolve) => setTimeout(resolve, estimateMs(text, speechRateValue(speechRate))));
    return;
  }

  Speech.stop();
  if (canUseClonedVoice() && (await speakWithClonedVoice(text))) return;
  await systemSpeakAsync(text, speechRate);
}

/** 화면 진입·상태 변화 알림 — 스크린리더와 TTS 모두 커버 */
export function announce(text: string): void {
  speak(text);
}

export function stopSpeaking(): void {
  Speech.stop();
  stopClonedVoice();
}

/** 글자 수 기반 낭독 시간 추정 (스크린리더 위임 시 완료 콜백이 없어서 사용) */
function estimateMs(text: string, rate: number): number {
  return Math.min(12000, 400 + (text.length * 110) / rate);
}

/**
 * 안내를 읽고, 낭독이 끝나면 resolve되는 Promise를 돌려준다.
 * 자동 화면 전환(스플래시·분석 중·수업 대기)은 이걸 기다렸다가 이동해
 * 말이 끝나기 전에 화면이 넘어가지 않게 한다.
 *
 * **TTS 경로는 반드시 `systemSpeakAsync`를 탄다.** 예전에는 여기서 `Speech.speak`를 직접
 * 부르고 onDone·onStopped·onError에만 기댔는데, 셋 중 무엇도 불리지 않는 경우가 실재한다 —
 * 안드로이드 에뮬레이터에서 Google TTS 서비스가 한국어 합성 도중 죽는 것을 확인했다
 * (logcat: "Scheduling restart of crashed service com.google.android.tts"). 그러면 이
 * Promise가 영영 settle되지 않고, 이걸 기다리는 화면은 **이동하지 못한 채 멈춘다.**
 * 스플래시(`src/app/index.tsx`)가 그래서 로고 화면에서 넘어가지 못했다. 아무것도 던지지
 * 않으므로 오류 로그조차 남지 않아 원인을 찾기도 어렵다.
 *
 * `systemSpeakAsync`는 낭독 길이 추정치에 여유를 더한 상한으로 그 기다림을 끊는다.
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
  // Speech.stop()은 systemSpeakAsync가 부른다 — 여기서 또 부르면 같은 일을 두 번 한다
  return systemSpeakAsync(text, speechRate);
}
