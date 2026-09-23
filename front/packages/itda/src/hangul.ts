/**
 * 한글 문자열 유틸 — 정규화, 자모 분해, 편집 거리.
 *
 * 음성 인식 결과는 띄어쓰기와 받침이 자주 흔들린다.
 * ("하이 잇다" -> "하이 있다", "하이잇따", "하이 이따" …)
 * 그래서 문자열을 그대로 비교하지 않고
 * 1) 공백·문장부호를 제거해 정규화하고
 * 2) 한글을 자모 단위로 풀어서
 * 3) 편집 거리(Levenshtein)로 비슷한 정도를 잰다.
 *
 * 학습 파이프라인(`apps/ai`의 `features.py`)과 **정의가 같아야 한다.**
 * 한쪽만 바꾸면 학습한 모델이 앱에서 다른 결과를 낸다.
 */

const SYLLABLE_BASE = 0xac00;
const SYLLABLE_LAST = 0xd7a3;

const CHOSEONG = [
  "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ",
  "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
];

const JUNGSEONG = [
  "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ",
  "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ", "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ", "ㅣ",
];

const JONGSEONG = [
  "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ",
  "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ",
  "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
];

/** 한글·영문·숫자만 남기고 소문자로 내린, 비교 전용 문자열. */
export function normalize(text: string): string {
  let out = "";
  for (const ch of text.toLowerCase()) {
    if (/[0-9a-zㄱ-ㆎ가-힣]/.test(ch)) out += ch;
  }
  return out;
}

/** 한글 음절을 초성·중성·종성으로 풀어 쓴다. 한글이 아니면 그대로 둔다. */
export function toJamo(text: string): string {
  let out = "";
  for (const ch of text) {
    const code = ch.codePointAt(0) ?? 0;
    if (code < SYLLABLE_BASE || code > SYLLABLE_LAST) {
      out += ch;
      continue;
    }
    const index = code - SYLLABLE_BASE;
    out += CHOSEONG[Math.floor(index / (21 * 28))];
    out += JUNGSEONG[Math.floor((index % (21 * 28)) / 28)];
    out += JONGSEONG[index % 28];
  }
  return out;
}

/** 레벤슈타인 편집 거리. */
export function editDistance(a: string, b: string): number {
  if (a === b) return 0;
  if (!a) return b.length;
  if (!b) return a.length;

  let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
  let current = new Array<number>(b.length + 1);

  for (let i = 1; i <= a.length; i += 1) {
    current[0] = i;
    for (let j = 1; j <= b.length; j += 1) {
      const substitution = previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1);
      current[j] = Math.min(substitution, previous[j] + 1, current[j - 1] + 1);
    }
    [previous, current] = [current, previous];
  }
  return previous[b.length];
}

/** 자모 단위 편집 거리. "잇다"와 "있다"는 1, "잇다"와 "이따"는 2쯤 나온다. */
export function jamoDistance(a: string, b: string): number {
  return editDistance(toJamo(a), toJamo(b));
}

/**
 * `haystack` 안에서 `needle`과 자모 편집 거리 `maxDistance` 이하로 닮은 구간을 찾는다.
 * 찾은 구간의 끝 인덱스를 돌려준다(없으면 -1). 웨이크워드 뒤에 붙은 명령을 잘라내는 데 쓴다.
 */
export function fuzzyIndexOfEnd(haystack: string, needle: string, maxDistance: number): number {
  if (!needle || !haystack) return -1;
  if (maxDistance === 0) {
    const index = haystack.indexOf(needle);
    return index < 0 ? -1 : index + needle.length;
  }

  // 인식기가 음절을 한두 개 더/덜 붙일 수 있어 길이를 앞뒤로 열어 둔다.
  const minLength = Math.max(1, needle.length - maxDistance);
  const maxLength = needle.length + maxDistance;

  let bestEnd = -1;
  let bestDistance = Number.MAX_SAFE_INTEGER;
  for (let start = 0; start < haystack.length; start += 1) {
    for (let length = minLength; length <= maxLength; length += 1) {
      const end = start + length;
      if (end > haystack.length) break;
      const distance = jamoDistance(haystack.slice(start, end), needle);
      if (distance <= maxDistance && distance < bestDistance) {
        bestDistance = distance;
        bestEnd = end;
        if (distance === 0) return bestEnd;
      }
    }
  }
  return bestEnd;
}

/** `haystack`에 `needle`과 비슷한 말이 들어 있는지. */
export function fuzzyContains(haystack: string, needle: string, maxDistance: number): boolean {
  return fuzzyIndexOfEnd(haystack, needle, maxDistance) >= 0;
}
