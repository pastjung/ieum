import type { SkillMastery, SkillSummary } from "@ieum/contracts";

/** 서버가 "익숙해요"로 판정한 요소만 익힌 것으로 센다. 기준은 FastAPI에만 있다 */
const MASTERED_LABEL = "익숙해요";

export interface SkillStat {
  label: string;
  value: string;
}

export interface SkillGroup {
  /** 분류 이름 */
  label: string;
  /** 0~100. 그 분류의 숙련도를 하나도 모르면 null */
  percent: number | null;
  /** 이 분류에 묶인 요소 수 */
  count: number;
}

export interface SkillDashboard {
  /** 전체 숙련도. 기록이 없으면 null — 0%로 보여주면 "다 틀렸다"로 읽힌다 */
  overallPercent: number | null;
  overallDetail: string | null;
  stats: SkillStat[];
  /** 분류로 묶은 목록. 약한 분류부터 */
  rules: SkillGroup[];
}

const EMPTY: SkillDashboard = {
  overallPercent: null,
  overallDetail: null,
  stats: [],
  rules: [],
};

/**
 * 요소 한 줄에 쓸 이름.
 *
 * <p>`learning_skills.name`은 규칙의 **분류**다. 같은 값이 여러 행에 걸쳐 있어 — "첫소리 기본
 * 자음"만 14행, "단독 자모"는 35행 — 이름만 쓰면 화면에 같은 줄이 반복돼 학습자가 구별할 수
 * 없다. 그 분류 안에서 다루는 글자를 붙여 구별한다.
 */
export function skillDisplayName(skill: SkillMastery): string {
  const target = skill.target.trim();
  return target ? `${skill.name} ${target}` : skill.name;
}

function percentOf(skills: SkillMastery[]): number | null {
  const known = skills.filter((skill) => skill.masteryProb !== null);
  // 모르는 것을 0으로 치면 평균이 떨어져 실제보다 못하는 것처럼 보인다
  if (known.length === 0) return null;
  return Math.round((known.reduce((sum, s) => sum + (s.masteryProb ?? 0), 0) / known.length) * 100);
}

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
 * **목록은 분류로 묶는다.** 요소 74개를 그대로 그리면 같은 이름이 반복돼 읽을 수 없다.
 * 묶으면 17줄이 된다. 분류 안에서 개별 글자를 알아야 하는 자리(음성 중심 화면의 상위 몇 개)는
 * `skillDisplayName`을 쓴다.
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

  const byName = new Map<string, SkillMastery[]>();
  for (const skill of summary.skills) {
    const group = byName.get(skill.name);
    if (group) group.push(skill);
    else byName.set(skill.name, [skill]);
  }

  const rules: SkillGroup[] = [...byName].map(([label, skills]) => ({
    label,
    percent: percentOf(skills),
    count: skills.length,
  }));
  // 약한 분류부터. 퍼센트를 모르는 분류는 끝으로 보낸다 — 약하다고 단정할 근거가 없다
  rules.sort((a, b) => (a.percent ?? Infinity) - (b.percent ?? Infinity));

  const mastered = summary.skills.filter((skill) => skill.label === MASTERED_LABEL).length;

  return {
    // 분류 평균을 다시 평균하면 요소가 적은 분류가 과대평가된다 — 요소 단위로 낸다
    overallPercent: percentOf(summary.skills),
    overallDetail: `요소 ${summary.skills.length}개 중 ${mastered}개를 익혔어요`,
    stats,
    rules,
  };
}
