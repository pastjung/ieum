import type { LearningSession } from "@ieum/contracts";
import { dotsToCell } from "@ieum/domain";

/**
 * 목데이터 — 서버 API 연동 전까지 사용.
 * 셀 값은 시연용 예시이며, 실제 한국 점자 규정 변환은 @ieum/domain에
 * 변환기가 들어오는 차수에서 교체한다.
 */
export const learningThemes = [
  { id: "consonant", label: "자음", solved: 48, correct: 43 },
  { id: "vowel", label: "모음", solved: 36, correct: 27 },
  { id: "abbrev-char", label: "약자", solved: 24, correct: 15 },
  { id: "word", label: "단어", solved: 12, correct: 7 },
  { id: "abbrev-word", label: "약어", solved: 0, correct: 0 },
  { id: "sentence", label: "문장", solved: 0, correct: 0 },
] as const;
export type LearningThemeId = (typeof learningThemes)[number]["id"];
