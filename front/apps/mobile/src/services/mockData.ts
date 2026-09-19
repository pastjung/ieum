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

export interface HomeSummary {
  userName: string;
  /** 진행 중 학습 (없으면 null → 복습 제안 변형) */
  inProgress: { title: string; progressLabel: string } | null;
  deviceConnected: boolean;
  stepLabel: string;
}

export const homeSummary: HomeSummary = {
  userName: "하나",
  inProgress: { title: "3단계 자음과 모음", progressLabel: "7 / 10 문항" },
  deviceConnected: false,
  stepLabel: "3단계",
};

export const learningThemes = [
  { id: "consonant", label: "자음" },
  { id: "vowel", label: "모음" },
  { id: "abbrev-char", label: "약자" },
  { id: "word", label: "단어" },
  { id: "abbrev-word", label: "약어" },
  { id: "sentence", label: "문장" },
] as const;
export type LearningThemeId = (typeof learningThemes)[number]["id"];

export const skillLevels = [
  { label: "모음 ㅏ·ㅓ", level: "familiar" as const },
  { label: "자음 ㅅ", level: "learning" as const },
  { label: "받침 ㅆ", level: "needs-practice" as const },
];
