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

/**
 * 채점 화면이 한 번에 하나씩 보여 주는 단계. 학습자가 "다음 점자"로 넘긴다.
 *
 * `compare`와 `speech`는 **같은 칸**을 가리켜야 한다 — 어긋나면 화면에는 세 번째 칸이
 * 떠 있는데 "두 번째 칸" 설명이 들린다.
 */
export interface GradeStep {
  /** 화면 문구 */
  detail: string;
  /** 이 단계에 도착했을 때 읽을 문장 */
  speech: string;
  /** 나란히 그릴 정답·내 입력 한 쌍. 그릴 칸이 없으면 null */
  compare: GradeCompareCell | null;
}

export interface GradeFeedback {
  correct: boolean;
  /** 화면 헤더. 단계를 넘겨도 그대로 떠 있다 */
  headline: string;
  /** 항상 한 개 이상이다 — 정답일 때도 화면이 steps[0]을 그대로 읽는다 */
  steps: GradeStep[];
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
    // 규칙 설명은 `detail`이다. `tts`가 아니다 — 둘은 AI가 만드는 서로 다른 문장이다
    // (`apps/ai` 의 `session_services._detail`). `tts`는 AI가 자체 채점한 점 수준 서술이라
    // 우리 XOR 설명과 겹치고 백엔드 채점과 갈릴 수 있다. 학습자가 "왜 틀렸는지"를 아는
    // 경로는 `detail` 하나뿐인데, 앱이 오랫동안 이 둘을 뒤바꿔 써서 규칙 설명이 화면에
    // 나온 적이 없었다
    const { headline, steps } = describeCells(
      analysis.cells,
      analysis.correct,
      analysis.feedback.detail,
    );
    // `describeCells`가 틀린 칸마다 단계를 만들고 그 칸 번호(`cellIndex`)를 달아 준다.
    // 그릴 칸은 그 번호로 되찾는다 — 두 곳에서 따로 "틀린 칸"을 고르면 어긋날 수 있다
    const byIndex = new Map(analysis.cells.map((c) => [c.cellIndex, c]));
    return {
      correct: analysis.correct,
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
                missingDots: toDots(wrong.missingDots),
                extraDots: toDots(wrong.extraDots),
                index: wrong.cellIndex - 1,
              }
            : null,
        };
      }),
    };
  }

  if (!local) return null;

  // 기존 V16 경로. 화면 문구(headline/detail)와 낭독 문구(speech)를 그대로 유지한다 —
  // 실시간 수업이 이 경로만 쓰므로 문자 하나도 바꾸지 않는다.
  //
  // 로컬 채점(`GradeResult`)은 틀린 첫 칸(`firstDiff`) 하나만 안다. 그래서 단계도 하나다 —
  // 칸별 순회는 서버 분석이 있을 때의 기능이고, 실시간 수업은 서버 세션이 없다
  const diff = describeDiff(local);
  const speech = local.correct ? "정답이에요!" : `오답이에요. ${diff.headline}. ${diff.detail}`;
  const compare: GradeCompareCell | null = local.firstDiff
    ? {
        expected: local.firstDiff.expected,
        actual: local.firstDiff.actual,
        missingDots: local.firstDiff.missing,
        extraDots: local.firstDiff.extra,
        index: local.firstDiff.index,
      }
    : null;
  return {
    correct: local.correct,
    headline: diff.headline,
    steps: [{ detail: local.correct ? "" : diff.detail, speech, compare }],
  };
}
