import type { AnswerCell, AttemptCellAnalysis, LiveAnswer } from "@ieum/contracts";
import { cellToDots } from "@ieum/domain";
import { describeCells } from "./cellFeedback";
import type { GradeFeedback } from "./gradeFeedback";

const SIX_DOTS = 0b111111;

/** LIVE 칸 채점(AnswerCell)에는 빠진·더 눌린 점이 없다 — 두 점형에서 계산해 기존 진단 모양으로 맞춘다 */
function toCellAnalysis(cell: AnswerCell): AttemptCellAnalysis {
  const missingMask = cell.expectedPattern & ~cell.inputPattern & SIX_DOTS;
  const extraMask = cell.inputPattern & ~cell.expectedPattern & SIX_DOTS;
  return {
    cellIndex: cell.cellIndex,
    expectedPattern: cell.expectedPattern,
    inputPattern: cell.inputPattern,
    diffMask: cell.diffMask,
    missingMask,
    extraMask,
    missingDots: cellToDots(missingMask),
    extraDots: cellToDots(extraMask),
  };
}

/**
 * 수업 답안의 서버 채점을 채점 화면 단계로 옮긴다. 정오는 서버 값(isCorrect)만 따른다.
 * LIVE는 AI 분석을 하지 않으므로 규칙 설명 없이 점 수준 설명만 한다 (설계서 §3.3).
 * 문장은 자기주도 학습과 같은 `describeCells`로 만든다 — 두 채점 화면의 말투가 갈리지 않게.
 */
export function liveGradeFeedback(result: LiveAnswer): GradeFeedback {
  const cells = result.cells.map(toCellAnalysis);
  const { headline, steps } = describeCells(cells, result.isCorrect);
  const byIndex = new Map(cells.map((c) => [c.cellIndex, c]));
  return {
    correct: result.isCorrect,
    headline,
    steps: steps.map((step) => {
      const wrong = step.cellIndex === null ? undefined : byIndex.get(step.cellIndex);
      return {
        detail: step.detail,
        speech: step.speech,
        compare: wrong
          ? {
              expected: wrong.expectedPattern,
              actual: wrong.inputPattern,
              missingDots: cellToDots(wrong.missingMask),
              extraDots: cellToDots(wrong.extraMask),
              index: wrong.cellIndex - 1,
            }
          : null,
      };
    }),
  };
}
