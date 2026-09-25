import type { SkillSummary } from "@ieum/contracts";

/** 서버가 "익숙해요"로 판정한 요소만 익힌 것으로 센다. 기준은 FastAPI에만 있다 */
const MASTERED_LABEL = "익숙해요";

export interface SkillStat {
  label: string;
  value: string;
}

export interface SkillRule {
  label: string;
  /** 0~100. 서버가 숙련도를 주지 않았으면 null */
  percent: number | null;
}

export interface SkillDashboard {
  /** 전체 숙련도. 기록이 없으면 null — 0%로 보여주면 "다 틀렸다"로 읽힌다 */
  overallPercent: number | null;
  overallDetail: string | null;
  stats: SkillStat[];
  rules: SkillRule[];
}

const EMPTY: SkillDashboard = {
  overallPercent: null,
  overallDetail: null,
  stats: [],
  rules: [],
};

/**
 * "내 실력" 화면이 그릴 값.
 *
 * <p>예전에는 `mockData.skillDashboard`의 고정값이었다 — 전체 76%, 연속 학습 7일, 푼 문제 184,
 * 정답률 82%, 그리고 규칙 10개의 퍼센트까지 전부 지어낸 숫자였다. 학습자는 이것을 자기
 * 실력으로 읽었다.
 *
 * **연속 학습은 보여주지 않는다.** 날짜별 학습 여부를 세는 기록이 아직 없어 계산할 수 없고,
 * 없는 것보다 지어낸 값이 나쁘다.
 *
 * 요소 순서는 서버가 준 대로 둔다 — 약한 것부터 정렬해 오고, 홈의 복습 카드도 같은 순서를
 * 쓴다. 여기서 다시 정렬하면 두 화면이 갈린다.
 */
export function selectSkillDashboard(summary: SkillSummary | null | undefined): SkillDashboard {
  if (!summary) return EMPTY;

  const stats: SkillStat[] = [{ label: "푼 문제", value: String(summary.totalAttemptCount) }];
  // 0으로 나누면 NaN이 화면에 그대로 나온다
  if (summary.totalAttemptCount > 0) {
    const rate = Math.round((summary.correctAttemptCount / summary.totalAttemptCount) * 100);
    stats.push({ label: "정답률", value: `${rate}%` });
  }

  if (summary.skills.length === 0) {
    return { overallPercent: null, overallDetail: null, stats, rules: [] };
  }

  const known = summary.skills.filter((skill) => skill.masteryProb !== null);
  const overallPercent =
    known.length === 0
      ? null
      : Math.round((known.reduce((sum, s) => sum + (s.masteryProb ?? 0), 0) / known.length) * 100);
  const mastered = summary.skills.filter((skill) => skill.label === MASTERED_LABEL).length;

  return {
    overallPercent,
    overallDetail: `요소 ${summary.skills.length}개 중 ${mastered}개를 익혔어요`,
    stats,
    rules: summary.skills.map((skill) => ({
      label: skill.name,
      percent: skill.masteryProb === null ? null : Math.round(skill.masteryProb * 100),
    })),
  };
}
