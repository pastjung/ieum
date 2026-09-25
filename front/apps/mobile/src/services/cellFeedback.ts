import { cellToDots, ordinalKo } from "@ieum/domain";
import type { AttemptCellAnalysis } from "@ieum/contracts";

/**
 * 낭독에서 각 칸을 자세히 설명하는 최대 개수. 그 뒤는 개수로만 줄인다
 * (`@ieum/domain`의 `describeDiff`와 같은 기준 — 설계 4.2절).
 */
const DETAIL_LIMIT = 3;

function dotsList(dots: readonly number[]): string {
  return dots.map((d) => `${d}번`).join(", ");
}

function cellDiffPhrase(cell: AttemptCellAnalysis): string {
  if (cell.missingDots.length > 0 && cell.extraDots.length === 0) {
    return `${dotsList(cell.missingDots)} 점이 빠졌어요`;
  }
  if (cell.extraDots.length > 0 && cell.missingDots.length === 0) {
    return `${dotsList(cell.extraDots)} 점이 더 눌렸어요`;
  }
  return "점 위치가 달라요";
}

/**
 * 서버의 셀 진단(`AttemptCellAnalysis[]`)을 사용자가 들을 문장으로 옮긴다.
 *
 * 점 수준을 먼저, 규칙 설명을 뒤에 둔다 — 방금 틀린 것을 바로 고칠 수 있게 하고 이유는
 * 그 뒤에 설명하는 순서다 (설계 4.2절). 서버는 "첫소리 이응은 점자로 적지 않아요" 같은
 * 규칙 수준 설명(`ruleText`)만 주므로, 몇 번째 칸의 몇 번 점이 틀렸는지는 이 함수가 채운다.
 *
 * `speech`는 `headline`과 `detail`을 이어 붙인 것이다. 화면과 낭독이 갈라지지 않도록
 * 한 곳에서 만든다 — 둘이 다르면 사용자가 어느 쪽을 믿어야 할지 알 수 없다.
 */
export function describeCells(
  cells: AttemptCellAnalysis[],
  correct: boolean,
  ruleText: string,
): { headline: string; detail: string; speech: string } {
  if (correct) {
    const headline = "정답이에요!";
    const detail = ruleText.trim();
    return { headline, detail, speech: `${headline} ${detail}`.trim() };
  }

  const headline = "오답이에요.";
  const wrongCells = cells.filter((c) => c.diffMask !== 0);

  const countMsg = `모두 ${wrongCells.length}칸이 틀렸어요.`;

  const detailed = wrongCells.slice(0, DETAIL_LIMIT);
  const cellSentences = detailed
    .map((c) => {
      const expectedList = dotsList(cellToDots(c.expectedPattern));
      return `${ordinalKo(c.cellIndex - 1)} 칸의 정답은 ${expectedList}이에요. ${cellDiffPhrase(c)}.`;
    })
    .join(" ");

  const omitted = wrongCells.length - detailed.length;
  const omittedMsg = omitted > 0 ? ` 그 외 ${omitted}칸도 달라요.` : "";
  const ruleMsg = ruleText.trim() ? ` ${ruleText.trim()}` : "";

  const detail = `${countMsg} ${cellSentences}${omittedMsg}${ruleMsg}`.trim();

  return { headline, detail, speech: `${headline} ${detail}`.trim() };
}
