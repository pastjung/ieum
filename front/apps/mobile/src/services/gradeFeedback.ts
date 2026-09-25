import { describeDiff, type Cell, type Dot, type GradeResult } from "@ieum/domain";
import type { AttemptAnalysisResponse, AttemptCellAnalysis } from "@ieum/contracts";
import { describeCells } from "@/services/cellFeedback";

/** 오답 화면에 나란히 그릴 정답 · 내 입력 점자 한 쌍 (기존 `GradeResult.firstDiff`와 같은 모양) */
export interface GradeCompareCell {
  expected: Cell;
  actual: Cell;
  missingDots: Dot[];
  extraDots: Dot[];
  /** 0부터 시작하는 칸 위치. `ordinalKo`에 그대로 넣는다 */
  index: number;
}

export interface GradeFeedback {
  correct: boolean;
  headline: string;
  detail: string;
  /** 낭독 문구. 서버 분석 경로에서는 항상 `${headline} ${detail}`.trim()과 같다
   *  (`describeCells`의 불변식). 로컬 경로는 기존 V16 낭독 문구를 그대로 유지한다 —
   *  화면 문구(headline/detail)와 다를 수 있으나 이는 이 작업 이전부터의 동작이다 */
  speech: string;
  /** 그릴 칸이 없으면(정답이거나, 오답인데 진단할 칸을 못 찾으면) null */
  compareCell: GradeCompareCell | null;
}

function firstWrongCell(cells: AttemptCellAnalysis[]): AttemptCellAnalysis | null {
  return cells.find((c) => c.diffMask !== 0) ?? null;
}

/**
 * 서버 분석(`analysis`)이 있으면 그것을, 없으면 로컬 채점(`local`)을 쓴다.
 *
 * 실시간 수업(`class/play.tsx`)은 서버 세션이 없어 `analysis`가 항상 null이다 — 그 경로는
 * 이 함수가 그대로 로컬 분기로 떨어져 기존 `describeDiff` 동작을 그대로 재현한다.
 *
 * `local`도 없으면(아직 아무것도 채점되지 않았으면) null을 돌려준다 — 호출부가 진입 가드로 쓴다.
 */
export function selectGradeFeedback(
  analysis: AttemptAnalysisResponse | null,
  local: GradeResult | null,
): GradeFeedback | null {
  if (analysis) {
    const { headline, detail, speech } = describeCells(
      analysis.cells,
      analysis.correct,
      analysis.feedback.tts,
    );
    const wrong = analysis.correct ? null : firstWrongCell(analysis.cells);
    const compareCell: GradeCompareCell | null = wrong
      ? {
          expected: wrong.expectedPattern,
          actual: wrong.inputPattern,
          missingDots: wrong.missingDots as Dot[],
          extraDots: wrong.extraDots as Dot[],
          index: wrong.cellIndex - 1,
        }
      : null;
    return { correct: analysis.correct, headline, detail, speech, compareCell };
  }

  if (!local) return null;

  // 기존 V16 경로. 화면 문구(headline/detail)와 낭독 문구(speech)를 그대로 유지한다 —
  // 실시간 수업이 이 경로만 쓰므로 문자 하나도 바꾸지 않는다
  const diff = describeDiff(local);
  const speech = local.correct ? "정답이에요!" : `오답이에요. ${diff.headline}. ${diff.detail}`;
  const compareCell: GradeCompareCell | null = local.firstDiff
    ? {
        expected: local.firstDiff.expected,
        actual: local.firstDiff.actual,
        missingDots: local.firstDiff.missing,
        extraDots: local.firstDiff.extra,
        index: local.firstDiff.index,
      }
    : null;
  return { correct: local.correct, headline: diff.headline, detail: diff.detail, speech, compareCell };
}
