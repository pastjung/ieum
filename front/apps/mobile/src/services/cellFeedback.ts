import { cellToDots, ordinalKo } from "@ieum/domain";
import type { AttemptCellAnalysis } from "@ieum/contracts";

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

/** 채점 화면이 한 번에 하나씩 보여 주는 단계. 틀린 칸 하나에 단계 하나가 대응한다 */
export interface CellStep {
  /** 이 단계가 설명하는 칸의 1-기반 위치. 그릴 칸이 없는 방어 경로는 null */
  cellIndex: number | null;
  /** 화면에 그리는 설명문 */
  detail: string;
  /** 이 단계에 도착했을 때 읽을 문장 */
  speech: string;
}

/**
 * 서버의 셀 진단(`AttemptCellAnalysis[]`)을 사용자가 한 칸씩 듣고 볼 단계로 옮긴다.
 *
 * 점 수준을 먼저, 규칙 설명을 뒤에 둔다 — 방금 틀린 것을 바로 고칠 수 있게 하고 이유는
 * 그 뒤에 설명하는 순서다 (설계 4.2절). 서버는 "첫소리 이응은 점자로 적지 않아요" 같은
 * 규칙 수준 설명(`ruleText`)만 주므로, 몇 번째 칸의 몇 번 점이 틀렸는지는 이 함수가 채운다.
 *
 * **왜 한 문장이 아니라 단계인가.** 예전에는 틀린 칸 전부를 한 문장으로 이어 읽고 화면에는
 * 첫 칸 하나만 그렸다. 단어·문장처럼 칸이 여럿인 문항에서 이 둘이 갈렸다 — 귀로는 세 칸을
 * 듣는데 눈앞에는 첫 칸만 있었고, 넷째 칸부터는 "그 외 2칸도 달라요"로 뭉개져 무엇이
 * 틀렸는지 영영 알 수 없었다. 이제 칸마다 화면과 낭독이 함께 한 단계를 이룬다.
 *
 * 각 단계의 `speech`는 그 단계의 `detail`과 같다 — 첫 단계만 헤드라인을 앞에 붙인다.
 * 화면과 낭독이 갈라지지 않도록 한 곳에서 만든다.
 *
 * 개요("모두 3칸이 틀렸어요")는 첫 단계에서만, 규칙 설명은 마지막 단계에서만 붙는다.
 * 칸을 넘길 때마다 다시 읽으면 매번 새로 틀린 것처럼 들린다.
 */
export function describeCells(
  cells: AttemptCellAnalysis[],
  correct: boolean,
  ruleText: string,
): { headline: string; steps: CellStep[] } {
  if (correct) {
    // **정답에는 `ruleText`를 붙이지 않는다.** 두 값이 서로 다른 채점에서 오기 때문이다:
    // `correct`는 백엔드가 DB 시드 셀과 비교한 결과이고(`LearningAttemptService`),
    // `ruleText`(= `feedback.detail`)는 AI가 `target_text`를 규칙 변환해 다시 채점한 뒤
    // 고른 설명이다 (`apps/ai` 의 `_detail`). 둘이 갈리면 "정답이에요! 3번 점이 빠졌어요"가 나온다 —
    // 정답 화면에 오답 설명이 붙는 것보다 설명이 없는 편이 낫다.
    const headline = "정답이에요!";
    return { headline, steps: [{ cellIndex: null, detail: "", speech: headline }] };
  }

  const headline = "오답이에요.";
  const rule = ruleText.trim();
  const wrongCells = cells.filter((c) => c.diffMask !== 0);

  // 정상 서버 응답이라면 correct === false일 때 틀린 칸이 최소 하나 있어야 하지만, 방어적으로
  // 처리한다 — 그렇지 않으면 "모두 0칸이 틀렸어요" 같은 자기모순 문장이 나온다.
  if (wrongCells.length === 0) {
    return {
      headline,
      steps: [{ cellIndex: null, detail: rule, speech: `${headline} ${rule}`.trim() }],
    };
  }

  // 한 칸만 틀렸을 때 "모두 1칸이 틀렸어요"는 어색하므로 "모두"를 뺀다.
  const countMsg =
    wrongCells.length === 1 ? "1칸이 틀렸어요." : `모두 ${wrongCells.length}칸이 틀렸어요.`;

  const steps = wrongCells.map((c, i): CellStep => {
    const sentence = `${ordinalKo(c.cellIndex - 1)} 칸의 정답은 ${expectedDescription(c.expectedPattern)}. ${cellDiffPhrase(c)}.`;
    const opening = i === 0 ? `${countMsg} ` : "";
    // 규칙은 칸이 아니라 문항 전체에 대한 설명이라 첫 단계에만 붙인다. 마지막 단계에 두면
    // 여러 칸을 틀렸을 때 끝까지 넘겨야 들을 수 있어 사실상 안 들린다.
    // 점 수준 설명 뒤에 두는 순서는 그대로다 — 무엇을 고칠지 먼저, 왜인지는 그 뒤 (설계 4.2절)
    const closing = i === 0 && rule ? ` ${rule}` : "";
    const detail = `${opening}${sentence}${closing}`;
    return {
      cellIndex: c.cellIndex,
      detail,
      speech: i === 0 ? `${headline} ${detail}`.trim() : detail,
    };
  });

  return { headline, steps };
}
