import { fuzzyIndexOfEnd, normalize } from "./hangul";

/**
 * "하이 잇다" 웨이크워드 판정.
 *
 * 음성 인식 결과는 발음이 비슷한 다른 말로 적히는 일이 잦아서
 * 정확히 일치하는지가 아니라 **자모 편집 거리**로 판정한다.
 */

/** `maxDistance`: 자모 기준으로 이만큼 틀려도 같은 말로 본다. */
export interface WakePhrase {
  readonly text: string;
  readonly maxDistance: number;
}

export interface WakeResult {
  readonly phrase: string;
  /** 웨이크워드 뒤에 이어 말한 명령("하이 잇다 복습 열어줘" -> "복습열어줘"). 없으면 빈 문자열. */
  readonly command: string;
}

export const WAKE_PHRASES: readonly WakePhrase[] = [
  { text: "하이잇다", maxDistance: 2 },
  { text: "헤이잇다", maxDistance: 2 },
  { text: "안녕잇다", maxDistance: 2 },
  { text: "오케이잇다", maxDistance: 2 },
  { text: "hi잇다", maxDistance: 1 },
  { text: "잇다야", maxDistance: 1 },
];

/** 음성 인식기가 준 후보(n-best) 중 하나라도 걸리면 잡아낸다. */
export function detectWakeWord(candidates: readonly string[]): WakeResult | null {
  for (const candidate of candidates) {
    const found = detectWakeWordIn(candidate);
    if (found) return found;
  }
  return null;
}

export function detectWakeWordIn(text: string): WakeResult | null {
  const normalized = normalize(text);
  if (!normalized) return null;

  for (const phrase of WAKE_PHRASES) {
    const end = fuzzyIndexOfEnd(normalized, phrase.text, phrase.maxDistance);
    if (end >= 0) {
      return { phrase: phrase.text, command: normalized.slice(end) };
    }
  }
  return null;
}
