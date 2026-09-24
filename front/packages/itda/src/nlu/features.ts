import { normalize, toJamo } from "../hangul";

/**
 * 의도 분류 모델이 쓰는 피처.
 *
 * 문자 2·3-gram과 **자모 3-gram**을 함께 쓴다. 자모 단위가 있어야
 * "복습"을 "복십"으로 잘못 받아써도 견딘다.
 *
 * 학습 쪽(`apps/ai`의 `features.py`)과 **한 글자도 다르면 안 된다.**
 * 피처 이름이 어긋나면 학습한 가중치를 못 찾아 정확도가 조용히 무너진다.
 */
export const FEATURE_SPEC = "char2,char3,jamo3";

/** 중복을 포함해 돌려준다 — 다항 나이브 베이즈라 등장 횟수를 센다. */
export function extractFeatures(utterance: string): string[] {
  const text = normalize(utterance);
  if (!text) return [];

  const features: string[] = [];
  for (const n of [2, 3]) {
    for (let i = 0; i + n <= text.length; i += 1) {
      features.push(`c${n}:${text.slice(i, i + n)}`);
    }
  }

  const jamo = toJamo(text);
  for (let i = 0; i + 3 <= jamo.length; i += 1) {
    features.push(`j3:${jamo.slice(i, i + 3)}`);
  }
  return features;
}
