/**
 * 점자 도메인 로직 — 순수 TypeScript (React/RN/브라우저 API 금지)
 * 점자 셀은 전 구간 6비트 정수: dot1=bit0 … dot6=bit5 (U+2800 오프셋)
 */

export type Dot = 1 | 2 | 3 | 4 | 5 | 6;
export type Cell = number; // 0 ~ 63

export const EMPTY_CELL: Cell = 0;

export function dotsToCell(dots: readonly Dot[]): Cell {
  return dots.reduce<Cell>((cell, dot) => cell | (1 << (dot - 1)), 0);
}

export function cellToDots(cell: Cell): Dot[] {
  const dots: Dot[] = [];
  for (let d = 1 as Dot; d <= 6; d = (d + 1) as Dot) {
    if (cell & (1 << (d - 1))) dots.push(d);
  }
  return dots;
}

export function cellHasDot(cell: Cell, dot: Dot): boolean {
  return (cell & (1 << (dot - 1))) !== 0;
}

export function cellToUnicode(cell: Cell): string {
  return String.fromCodePoint(0x2800 + (cell & 0x3f));
}

export function cellsToUnicode(cells: readonly Cell[]): string {
  return cells.map(cellToUnicode).join("");
}

/** 한 셀 비교 결과 */
export interface CellDiff {
  /** 0부터 시작하는 셀 위치 */
  index: number;
  expected: Cell;
  actual: Cell;
  /** 빠진 점 (정답에는 있고 입력에는 없음) */
  missing: Dot[];
  /** 더 누른 점 (입력에는 있고 정답에는 없음) */
  extra: Dot[];
}

export interface GradeResult {
  correct: boolean;
  /** 전체 셀 수 */
  total: number;
  /** 일치한 셀 수 */
  matched: number;
  /** 첫 번째 불일치 (정답이면 null) */
  firstDiff: CellDiff | null;
}

export function gradeAnswer(
  expected: readonly Cell[],
  actual: readonly Cell[],
): GradeResult {
  const total = expected.length;
  let matched = 0;
  let firstDiff: CellDiff | null = null;
  for (let i = 0; i < total; i++) {
    const exp = expected[i] ?? EMPTY_CELL;
    const act = actual[i] ?? EMPTY_CELL;
    if (exp === act) {
      matched += 1;
      continue;
    }
    if (!firstDiff) {
      const missing = cellToDots(exp & ~act & 0x3f);
      const extra = cellToDots(act & ~exp & 0x3f);
      firstDiff = { index: i, expected: exp, actual: act, missing, extra };
    }
  }
  if (actual.length > expected.length && !firstDiff) {
    const act = actual[expected.length] ?? EMPTY_CELL;
    firstDiff = {
      index: expected.length,
      expected: EMPTY_CELL,
      actual: act,
      missing: [],
      extra: cellToDots(act),
    };
    return { correct: false, total, matched, firstDiff };
  }
  return {
    correct: firstDiff === null && actual.length >= total,
    total,
    matched,
    firstDiff,
  };
}

const ORDINALS = [
  "첫 번째", "두 번째", "세 번째", "네 번째", "다섯 번째",
  "여섯 번째", "일곱 번째", "여덟 번째", "아홉 번째", "열 번째",
] as const;

export function ordinalKo(index: number): string {
  return ORDINALS[index] ?? `${index + 1}번째`;
}

function listDots(dots: readonly Dot[]): string {
  return dots.join("번과 ") + "번";
}

/**
 * 오답 차이 설명 생성 (V16 화면·음성 문구)
 * 예: { headline: "3번 점이 빠졌어요", detail: "다섯 번째 칸에서요. 나머지 네 칸은 맞았어요." }
 */
export function describeDiff(result: GradeResult): {
  headline: string;
  detail: string;
} {
  if (result.correct || !result.firstDiff) {
    return { headline: "정답이에요!", detail: "" };
  }
  const { index, missing, extra } = result.firstDiff;
  let headline: string;
  if (missing.length > 0 && extra.length === 0) {
    headline = `${listDots(missing)} 점이 빠졌어요`;
  } else if (extra.length > 0 && missing.length === 0) {
    headline = `${listDots(extra)} 점이 더 눌렸어요`;
  } else {
    headline = "점 위치가 달라요";
  }
  const rest = result.total - 1;
  const where = `${ordinalKo(index)} 칸에서요.`;
  const restMsg =
    result.matched === rest && rest > 0
      ? ` 나머지 ${rest === 4 ? "네 " : `${rest}`}칸은 맞았어요.`
      : result.matched > 0
        ? ` ${result.matched}칸은 맞았어요.`
        : "";
  return { headline, detail: `${where}${restMsg}` };
}
