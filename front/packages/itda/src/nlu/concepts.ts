import { fuzzyContains, normalize } from "../hangul";
import {
  defaultGazetteers,
  type ConceptType,
  type ConceptValue,
  type Gazetteer,
  type Gazetteers,
} from "../capsule/model";
import { DOT_ALL, encodeDots, isDot } from "../capsule/tactile";

/**
 * 발화에서 concept(발화 변수)를 뽑아낸다.
 *
 * 사전(gazetteer) 기반이라 학습 모델이 없어도 동작하고,
 * 학습 모델이 있으면 모델이 실어 보낸 사전을 얹어 쓴다.
 */
export class ConceptExtractor {
  constructor(private readonly gazetteers: Gazetteers = defaultGazetteers()) {}

  /**
   * 동적 어휘를 얹은 새 추출기.
   * 진행 중인 수업 이름처럼 서버에서 받아야 아는 값에 쓴다.
   */
  with(type: ConceptType, entries: Gazetteer): ConceptExtractor {
    return new ConceptExtractor({ ...this.gazetteers, [type]: entries });
  }

  extract(utterance: string): ConceptValue[] {
    const text = normalize(utterance);
    if (!text) return [];

    const found: ConceptValue[] = [];
    for (const [type, entries] of Object.entries(this.gazetteers)) {
      if (!entries) continue;
      const match = matchGazetteer(text, entries);
      if (match) found.push({ type: type as ConceptType, value: match.value, surface: match.surface });
    }

    const count = extractCount(text);
    if (count) found.push(count);

    const dots = extractDots(text);
    if (dots) found.push(dots);

    return found;
  }
}

/** 가장 긴(= 구체적인) 표현이 걸린 항목을 고른다. 정확히 포함되면 길이×2, 자모 거리 1이면 길이. */
function matchGazetteer(text: string, entries: Gazetteer): { value: string; surface: string } | null {
  let best: { value: string; surface: string } | null = null;
  let bestScore = 0;

  for (const [value, surfaces] of Object.entries(entries)) {
    for (const surface of surfaces) {
      const candidate = normalize(surface);
      if (!candidate) continue;

      let score: number;
      if (text.includes(candidate)) score = candidate.length * 2;
      else if (candidate.length >= 2 && fuzzyContains(text, candidate, 1)) score = candidate.length;
      else continue;

      if (score > bestScore) {
        bestScore = score;
        best = { value, surface };
      }
    }
  }
  return best;
}

const MAX_COUNT = 100;
const COUNT_UNITS = ["문제", "문항", "개"] as const;
const DIGIT_COUNT = /(\d{1,3})(?:문제|문항|개)/;

/** 긴 표현이 먼저 걸리도록 순서를 지킨다(열다섯 -> 열). */
const KOREAN_NUMBERS: readonly (readonly [string, number])[] = [
  ["스물다섯", 25], ["스무", 20], ["스물", 20],
  ["열다섯", 15], ["열두", 12], ["열한", 11], ["열", 10],
  ["아홉", 9], ["여덟", 8], ["일곱", 7], ["여섯", 6], ["다섯", 5],
  ["서른", 30], ["마흔", 40], ["쉰", 50],
  ["네", 4], ["넷", 4], ["세", 3], ["셋", 3], ["두", 2], ["둘", 2], ["한", 1], ["하나", 1],
];

/** "다섯 문제", "10개"처럼 말한 문항 수. */
function extractCount(text: string): ConceptValue | null {
  const digits = DIGIT_COUNT.exec(text);
  if (digits) {
    const number = Number.parseInt(digits[1], 10);
    if (number >= 1 && number <= MAX_COUNT) {
      return { type: "QuestionCount", value: String(number), surface: digits[0] };
    }
  }

  for (const [word, number] of KOREAN_NUMBERS) {
    for (const unit of COUNT_UNITS) {
      const surface = word + unit;
      if (text.includes(surface)) {
        return { type: "QuestionCount", value: String(number), surface };
      }
    }
  }
  return null;
}

/**
 * 점 번호 + 조사. 한글 수사는 긴 것을 먼저 두어 "다섯"이 "다"+"섯"으로 잘리지 않게 한다.
 * "점자", "6점 입력"의 점은 촉각 모듈의 점이 아니라 제외한다.
 */
const DOT_NUMBER = /(\d{1,6}|다섯|여섯|하나|한|두|세|네|첫|일|이|삼|사|오|육|륙)(?:번째|번|점(?!자|수|입력))/g;

/** "이번 주", "이번에"의 "이번"은 2번 점이 아니다. */
const DOT_NOISE = /이번(?=주|달|에|엔|은|시간|기회)/g;

/** "점 전부 내려", "모든 점 올려", 되묻기 답변 "전부" — 번호 없이 전부를 가리키는 말. */
const DOT_ALL_PATTERN =
  /(?:전부|모두|모든|전체|다)점?(?=올려|올리|내려|내리|켜|꺼|끄|세워|해제|초기화)|(?:전부|모두|전체)$/;

const KOREAN_DIGITS: Readonly<Record<string, number>> = {
  일: 1, 하나: 1, 한: 1, 첫: 1,
  이: 2, 두: 2,
  삼: 3, 세: 3,
  사: 4, 네: 4,
  오: 5, 다섯: 5,
  육: 6, 륙: 6, 여섯: 6,
};

/**
 * "3번 점", "1 3 5번", "삼번", "점 전부"처럼 말한 점 번호.
 * 값은 "1,3,5"(오름차순) 또는 `DOT_ALL`.
 * 조사(번/점)가 붙은 숫자만 인정해 "5문제"(문항 수)와 섞이지 않게 한다.
 */
function extractDots(text: string): ConceptValue | null {
  const all = DOT_ALL_PATTERN.exec(text);
  if (all) return { type: "DotNumber", value: DOT_ALL, surface: all[0] };

  const cleaned = text.replace(DOT_NOISE, "");
  const dots = new Set<number>();
  const surfaces: string[] = [];

  DOT_NUMBER.lastIndex = 0;
  for (const match of cleaned.matchAll(DOT_NUMBER)) {
    const token = match[1];
    // 띄어쓰기가 사라진 "135번"은 1·3·5번 — 점 번호는 한 자리라 자리마다 끊어 읽는다.
    const numbers = /^\d/.test(token)
      ? [...token].map((digit) => Number.parseInt(digit, 10))
      : [KOREAN_DIGITS[token]];

    if (numbers.every(isDot)) {
      numbers.forEach((n) => dots.add(n));
      surfaces.push(match[0]);
    }
  }

  if (dots.size === 0) return null;
  return { type: "DotNumber", value: encodeDots(dots), surface: surfaces.join(" ") };
}
