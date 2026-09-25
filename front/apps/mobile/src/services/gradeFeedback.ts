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
 * 서버 응답의 `missingDots`/`extraDots`는 계약(`attemptCellAnalysisSchema`,
 * `front/packages/contracts/src/index.ts`)에서 `z.array(z.number().int())`로만 검증되고 1~6
 * 범위를 강제하지 않는다 — 같은 셀의 `expectedPattern`/`inputPattern`이 `cellSchema`로 0~63을
 * 검증하는 것과 다르다.
 *
 * **스키마를 `min(1).max(6)`으로 조이지 않는다.** 그러면 서버가 잘못된 점 번호를 하나라도
 * 보내는 순간 응답 전체가 파싱에 실패한다. `attemptSubmission.ts`(제출 오케스트레이션)는
 * 이걸 네트워크 실패와 구분하지 못해 그 문항을 `failed`로 돌리고, 재시도해도 같은 응답이 오니
 * 3회 만에 소진된다 — 사용자는 세션 12문항을 처음부터 다시 풀어야 한다. 캡션에 잘못된 점
 * 번호가 하나 보이는 것(이 함수가 막는 문제)보다 그쪽이 훨씬 나쁘다.
 *
 * 그래서 받는 자리(스키마)는 느슨하게 두고, 화면에 쓰는 이 자리에서 범위 밖 값을 걸러낸다.
 */
function toDots(values: readonly number[]): Dot[] {
  return values.filter((d): d is Dot => Number.isInteger(d) && d >= 1 && d <= 6);
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
          missingDots: toDots(wrong.missingDots),
          extraDots: toDots(wrong.extraDots),
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
