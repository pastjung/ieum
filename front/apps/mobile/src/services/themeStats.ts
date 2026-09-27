import type { StageStats } from "@ieum/contracts";

export interface ThemeRow {
  /** 세션을 만들 때 그대로 보내는 값 */
  curriculumStageId: number;
  label: string;
  description: string;
  /** 정답률. 아직 안 푼 단계는 null */
  percent: number | null;
}

/**
 * 테마 선택 화면이 그릴 줄.
 *
 * <p>예전에는 `mockData.learningThemes`의 고정 숫자였다 — "자음 48문제 중 43개 맞힘 90%"처럼
 * 여섯 줄이 모두 지어낸 값이었고, 학습자는 이것을 자기 기록으로 읽었다.
 *
 * <p>서버가 커리큘럼 순서로 모든 단계를 주므로 여기서 정렬하거나 채우지 않는다. 기록이 없는
 * 단계도 0으로 오므로 목록에서 빠지지 않는다.
 */
export function selectThemeRows(stats: StageStats | null | undefined): ThemeRow[] {
  if (!stats) return [];
  return stats.stages.map((stage) => ({
    curriculumStageId: stage.curriculumStageId,
    label: stage.name,
    description:
      stage.solvedCount > 0
        ? `${stage.solvedCount}문제 중 ${stage.correctCount}개 맞힘`
        : "아직 안 풀었어요",
    // 0으로 나누면 NaN이 화면에 그대로 나온다
    percent:
      stage.solvedCount > 0 ? Math.round((stage.correctCount / stage.solvedCount) * 100) : null,
  }));
}
