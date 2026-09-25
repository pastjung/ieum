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
  const hasMissing = cell.missingDots.length > 0;
  const hasExtra = cell.extraDots.length > 0;
  // 빠진 점과 더 눌린 점이 동시에 있는 경우가 실제로는 가장 흔한 오답 형태다(예:
  // 정답 1·3번인데 1·2번을 누름 → missing=[3], extra=[2]). 이 정보를 "점 위치가 달라요"로
  // 뭉뚱그리면 사용자가 무엇을 고쳐야 할지 알 수 없으므로 둘 다 말한다.
  if (hasMissing && hasExtra) {
    return `${dotsList(cell.missingDots)} 점이 빠지고 ${dotsList(cell.extraDots)} 점이 더 눌렸어요`;
  }
  if (hasMissing) {
    return `${dotsList(cell.missingDots)} 점이 빠졌어요`;
  }
  if (hasExtra) {
    return `${dotsList(cell.extraDots)} 점이 더 눌렸어요`;
  }
  return "점 위치가 달라요";
}

/** 정답 칸의 점 구성을 말한다. 정답이 빈 칸(`expectedPattern === 0`)인 경우도 있다
 * (문장 단계 시드에서 섞일 수 있다) — `cellToDots(0)`은 빈 배열이라 그대로 이으면
 * 주어 없는 문장("정답은 이에요")이 된다. */
function expectedDescription(expectedPattern: number): string {
  const dots = cellToDots(expectedPattern);
  return dots.length > 0 ? `${dotsList(dots)}이에요` : "빈 칸이에요";
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

  // 정상 서버 응답이라면 correct === false일 때 틀린 칸이 최소 하나 있어야 하지만, 방어적으로
  // 처리한다 — 그렇지 않으면 "모두 0칸이 틀렸어요" 같은 자기모순 문장이 나온다.
  if (wrongCells.length === 0) {
    const detail = ruleText.trim();
    return { headline, detail, speech: `${headline} ${detail}`.trim() };
  }

  // 한 칸만 틀렸을 때 "모두 1칸이 틀렸어요"는 어색하므로 "모두"를 뺀다.
  const countMsg =
    wrongCells.length === 1 ? "1칸이 틀렸어요." : `모두 ${wrongCells.length}칸이 틀렸어요.`;

  const detailed = wrongCells.slice(0, DETAIL_LIMIT);
  const cellSentences = detailed
    .map((c) => {
      return `${ordinalKo(c.cellIndex - 1)} 칸의 정답은 ${expectedDescription(c.expectedPattern)}. ${cellDiffPhrase(c)}.`;
    })
    .join(" ");

  const omitted = wrongCells.length - detailed.length;
  const omittedMsg = omitted > 0 ? ` 그 외 ${omitted}칸도 달라요.` : "";
  const ruleMsg = ruleText.trim() ? ` ${ruleText.trim()}` : "";

  const detail = `${countMsg} ${cellSentences}${omittedMsg}${ruleMsg}`.trim();

  return { headline, detail, speech: `${headline} ${detail}`.trim() };
}
