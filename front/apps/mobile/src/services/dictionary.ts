import { dotsToCell, type Cell } from "@ieum/domain";

export interface DictEntry {
  word: string;
  cells: Cell[];
  /** 글자별 점 설명. 예: 사 (1·6) · 랑 (2·3·5) */
  breakdown: string;
}

/** 점자 사전 목데이터 — 실제 변환기는 @ieum/domain 한국 점자 변환 차수에서 교체 */
const entries: DictEntry[] = [
  { word: "사랑", cells: [dotsToCell([1, 6]), dotsToCell([2, 3, 5])], breakdown: "사 (1·6) · 랑 (2·3·5)" },
  { word: "이음", cells: [dotsToCell([1, 3, 5]), dotsToCell([2, 4, 5])], breakdown: "이 (1·3·5) · 음 (2·4·5)" },
  { word: "점자", cells: [dotsToCell([2, 3, 6]), dotsToCell([4, 6])], breakdown: "점 (2·3·6) · 자 (4·6)" },
  { word: "학교", cells: [dotsToCell([1, 2, 4]), dotsToCell([1, 3, 4, 6])], breakdown: "학 (1·2·4) · 교 (1·3·4·6)" },
];

export function lookupWord(word: string): DictEntry | null {
  return entries.find((e) => e.word === word.trim()) ?? null;
}

export const sampleWords = entries.map((e) => e.word);
