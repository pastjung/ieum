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
  /** 첫 번째 불일치 (정답이면 null). 항상 `diffs[0] ?? null` 과 같다 — 바꾸지 않는다.
   *  `grade.tsx` 가 점자 셀 비교 그림(정답/내 입력 두 칸)을 그릴 때 이것만 쓴다. */
  firstDiff: CellDiff | null;
  /** 틀린 칸 전부(순서대로). 판정 규칙(correct/total/matched/firstDiff)에는 영향을 주지 않는
   *  추가 정보다 — 낭독·화면 문구(`describeDiff`)가 여러 칸을 알려줄 때 이걸 읽는다. */
  diffs: CellDiff[];
}

export function gradeAnswer(
  expected: readonly Cell[],
  actual: readonly Cell[],
): GradeResult {
  const total = expected.length;
  let matched = 0;
  const diffs: CellDiff[] = [];
  for (let i = 0; i < total; i++) {
    const exp = expected[i] ?? EMPTY_CELL;
    const act = actual[i] ?? EMPTY_CELL;
    if (exp === act) {
      matched += 1;
      continue;
    }
    const missing = cellToDots(exp & ~act & 0x3f);
    const extra = cellToDots(act & ~exp & 0x3f);
    diffs.push({ index: i, expected: exp, actual: act, missing, extra });
  }
  // 기대 칸 수를 넘는 입력은 막지 않는다 — 앱도 서버도 정상적인 오답으로 처리한다. 앞 칸이 다
  // 맞았다면 넘친 첫 칸 하나만 오답으로 보여준다 — 기존 동작 그대로, diffs 유무로 판단 바꾸지 않음
  if (actual.length > expected.length && diffs.length === 0) {
    const act = actual[expected.length] ?? EMPTY_CELL;
    diffs.push({
      index: expected.length,
      expected: EMPTY_CELL,
      actual: act,
      missing: [],
      extra: cellToDots(act),
    });
    return { correct: false, total, matched, firstDiff: diffs[0]!, diffs };
  }
  const firstDiff = diffs[0] ?? null;
  return {
    correct: firstDiff === null && actual.length >= total,
    total,
    matched,
    firstDiff,
    diffs,
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

function diffPhrase({ missing, extra }: CellDiff): string {
  if (missing.length > 0 && extra.length === 0) {
    return `${listDots(missing)} 점이 빠졌어요`;
  }
  if (extra.length > 0 && missing.length === 0) {
    return `${listDots(extra)} 점이 더 눌렸어요`;
  }
  return "점 위치가 달라요";
}

/**
 * 낭독에서 각 칸을 자세히 설명하는 최대 개수. 단어 단계는 최대 8칸이라 다 틀리면 8칸을 전부
 * 자세히 읽게 되는데, 그러면 낭독이 지나치게 길어진다(설계: multi-cell-feedback). 앞의 3칸만
 * "몇 번째 칸은 무슨 점이 빠졌다/더 눌렸다"로 자세히 읽고, 그 뒤는 "그 외 N칸도 더 틀렸어요"로
 * 개수만 요약한다. 몇 칸이 틀렸는지는 이 요약과 별개로 "모두 N칸이 틀렸어요"에 항상 나온다.
 */
const DETAIL_LIMIT = 3;

/**
 * 오답 차이 설명 생성 (V16 화면·음성 문구, V17에서 다중 칸으로 확장)
 * 예(1칸 오답, 기존과 동일): { headline: "3번 점이 빠졌어요", detail: "다섯 번째 칸에서요. 나머지 네 칸은 맞았어요." }
 * 예(다중 오답): { headline: "3번 점이 빠졌어요", detail: "첫 번째 칸에서요. 모두 3칸이 틀렸어요. 세 번째 칸은 1번 점이 더 눌렸어요. 다섯 번째 칸은 점 위치가 달라요." }
 */
export function describeDiff(result: GradeResult): {
  headline: string;
  detail: string;
} {
  if (result.correct || !result.firstDiff) {
    return { headline: "정답이에요!", detail: "" };
  }
  const headline = diffPhrase(result.firstDiff);
  const where = `${ordinalKo(result.firstDiff.index)} 칸에서요.`;

  // 한 칸만 틀렸으면 기존 V16 문구를 그대로 유지한다 (퇴행 금지)
  if (result.diffs.length <= 1) {
    const rest = result.total - 1;
    const restMsg =
      result.matched === rest && rest > 0
        ? ` 나머지 ${rest === 4 ? "네 " : `${rest}`}칸은 맞았어요.`
        : result.matched > 0
          ? ` ${result.matched}칸은 맞았어요.`
          : "";
    return { headline, detail: `${where}${restMsg}` };
  }

  // 두 칸 이상 틀렸을 때: 몇 칸이 틀렸는지는 항상 말하고, 앞의 DETAIL_LIMIT칸(첫 칸은 위의
  // headline·where 로 이미 말했으니 그 다음 칸부터)만 자세히 읽은 뒤 나머지는 개수로 줄인다.
  const detailed = result.diffs.slice(0, DETAIL_LIMIT);
  const laterSentences = detailed
    .slice(1)
    .map((d) => ` ${ordinalKo(d.index)} 칸은 ${diffPhrase(d)}.`)
    .join("");
  const omittedCount = result.diffs.length - detailed.length;
  const summary = omittedCount > 0 ? ` 그 외 ${omittedCount}칸도 더 틀렸어요.` : "";
  const matchedMsg = result.matched > 0 ? ` ${result.matched}칸은 맞았어요.` : "";

  return {
    headline,
    detail: `${where} 모두 ${result.diffs.length}칸이 틀렸어요.${laterSentences}${summary}${matchedMsg}`,
  };
}
