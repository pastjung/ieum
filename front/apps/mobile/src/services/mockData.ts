import type { LearningSession } from "@ieum/contracts";
import { dotsToCell } from "@ieum/domain";

/**
 * 목데이터 — 서버 API 연동 전까지 사용.
 * 셀 값은 시연용 예시이며, 실제 한국 점자 규정 변환은 @ieum/domain에
 * 변환기가 들어오는 차수에서 교체한다.
 */
export const freeSessionVowel: LearningSession = {
  id: "free-vowel-1",
  title: "3단계 자음과 모음",
  problems: [
    {
      id: "p1",
      prompt: "'ㅏ'를 점자로 입력하세요",
      target: "ㅏ",
      expectedCells: [dotsToCell([1, 2, 6])],
    },
    {
      id: "p2",
      prompt: "'ㅓ'를 점자로 입력하세요",
      target: "ㅓ",
      expectedCells: [dotsToCell([2, 3, 4])],
    },
    {
      id: "p3",
      prompt: "'ㅗ'를 점자로 입력하세요",
      target: "ㅗ",
      expectedCells: [dotsToCell([1, 3, 6])],
    },
    {
      id: "p4",
      prompt: "'ㅜ'를 점자로 입력하세요",
      target: "ㅜ",
      expectedCells: [dotsToCell([1, 3, 4])],
    },
    {
      id: "p5",
      prompt: "'ㅡ'를 점자로 입력하세요",
      target: "ㅡ",
      expectedCells: [dotsToCell([2, 4, 6])],
    },
  ],
};

export const learningThemes = [
  { id: "consonant", label: "자음", solved: 48, correct: 43 },
  { id: "vowel", label: "모음", solved: 36, correct: 27 },
  { id: "abbrev-char", label: "약자", solved: 24, correct: 15 },
  { id: "word", label: "단어", solved: 12, correct: 7 },
  { id: "abbrev-word", label: "약어", solved: 0, correct: 0 },
  { id: "sentence", label: "문장", solved: 0, correct: 0 },
] as const;
export type LearningThemeId = (typeof learningThemes)[number]["id"];

/** L10 홈 · 오늘의 복습 요소 */
export const todayReview = ["받침 ㅆ", "자음 ㅅ", "모음 ㅕ"];

/** L26 내 실력 대시보드 (화면 중심 모드) */
export const skillDashboard = {
  overallPercent: 76,
  overallLabel: "모음 익히는 중",
  overallDetail: "규칙 12개 중 9개를 익혔어요",
  stats: [
    { label: "연속 학습", value: "7일" },
    { label: "푼 문제", value: "184" },
    { label: "정답률", value: "82%" },
  ],
  rules: [
    { label: "자음 규칙 'ㅅ'", percent: 94 },
    { label: "모음 규칙 'ㅢ'", percent: 91 },
    { label: "받침 규칙 'ㅌ'", percent: 88 },
    { label: "이중모음 'ㅒ'", percent: 86 },
    { label: "겹받침 'ㄺ'", percent: 85 },
    { label: "첫소리 ㅇ '아'", percent: 79 },
    { label: "모음 연쇄 '예'", percent: 76 },
    { label: "약자 '그리고'", percent: 63 },
    { label: "된소리 'ㅆ'", percent: 42 },
    { label: "문장부호 '.'", percent: null },
  ] as { label: string; percent: number | null }[],
};

export const skillLevels = [
  { label: "모음 ㅏ·ㅓ", level: "familiar" as const },
  { label: "자음 ㅅ", level: "learning" as const },
  { label: "받침 ㅆ", level: "needs-practice" as const },
];
