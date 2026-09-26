import type { Level } from "@ieum/contracts";

/**
 * `users.level`의 한글 이름.
 *
 * 홈의 단계 배지와 설정의 단계 선택이 같은 이름을 써야 한다 — 두 화면이 서로 다른 말로
 * 같은 값을 부르면 학습자는 자기가 무엇을 바꿨는지 알 수 없다. 예전에는 홈 화면 안에만
 * 있어 설정에서 쓸 수 없었다.
 */
export const LEVEL_LABEL: Record<Level, string> = {
  CONSONANT: "자음",
  VOWEL: "모음",
  CONTRACTION: "약자",
  WORD: "단어",
  CONTRACTION_WORD: "약어",
  SENTENCE: "문장",
};

/**
 * 설정 화면이 보여주는 순서.
 *
 * `curriculum_stages`의 순서(자음·모음·약자·단어·약어·문장, id 1~6)를 따른다 — 쉬운 것부터
 * 늘어놓아야 학습자가 자기 위치를 가늠할 수 있다. `levelSchema`의 선언 순서와는 다르다.
 */
export const LEVEL_ORDER: readonly Level[] = [
  "CONSONANT",
  "VOWEL",
  "CONTRACTION",
  "WORD",
  "CONTRACTION_WORD",
  "SENTENCE",
];

/** 아직 고르지 않은 상태의 표시 */
export const LEVEL_UNSET_LABEL = "단계 미선택";
