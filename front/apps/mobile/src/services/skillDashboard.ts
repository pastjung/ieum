import type { SkillMastery, SkillSummary } from "@ieum/contracts";

/** 서버가 "익숙해요"로 판정한 요소만 익힌 것으로 센다. 기준은 FastAPI에만 있다 */
const MASTERED_LABEL = "익숙해요";

/** 화면에 보여줄 약한 요소 수 */
const WEAKEST_COUNT = 5;

/** 화면에 보여줄 잘하는 요소 수 */
const STRONGEST_COUNT = 3;

export interface SkillStat {
  label: string;
  value: string;
}

export interface SkillRow {
  learningSkillId: number;
  /** 분류 이름 + 대상 글자 */
  label: string;
  /** 0~100. 숙련도를 모르면 null */
  percent: number | null;
  /** "익숙해요" | "배우는 중" | "더 연습해요" */
  level: string;
}

export interface SkillDashboard {
  /** 전체 숙련도. 기록이 없으면 null — 0%로 보여주면 "다 틀렸다"로 읽힌다 */
  overallPercent: number | null;
  overallDetail: string | null;
  stats: SkillStat[];
  /** 더 연습할 것. 약한 순서 */
  weakest: SkillRow[];
  /** 잘하고 있는 것. 잘하는 순서 */
  strongest: SkillRow[];
}

const EMPTY: SkillDashboard = {
  overallPercent: null,
  overallDetail: null,
  stats: [],
  weakest: [],
  strongest: [],
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

function toRow(skill: SkillMastery): SkillRow {
  return {
    learningSkillId: skill.learningSkillId,
    label: skillDisplayName(skill),
    percent: skill.masteryProb === null ? null : Math.round(skill.masteryProb * 100),
    level: skill.label,
  };
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
 * 정답률 82%, 그리고 규칙 10개의 퍼센트까지 전부 지어낸 숫자였다.
 *
 * <p>**목록은 약한 것 몇 개와 잘하는 것 몇 개만 보여준다.** 요소 74개를 그대로 그리면 읽을 수
 * 없고, 분류로 묶으면 "단독 자모 35개 평균 12%"처럼 무엇을 연습해야 할지 알 수 없는 줄이 된다.
 * 서버가 약한 것부터 정렬해 보내므로 앞뒤에서 잘라 쓴다.
 *
 * <p>**연속 학습은 보여주지 않는다.** 날짜별 학습 여부를 세는 기록이 아직 없어 계산할 수 없고,
 * 없는 것보다 지어낸 값이 나쁘다.
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
    return { ...EMPTY, stats };
  }

  const weakest = summary.skills.slice(0, WEAKEST_COUNT);
  // 앞에서 가져간 만큼을 빼고 뒤에서 가져온다 — 요소가 적으면 앞뒤가 만나, 같은 줄이
  // "더 연습할 것"과 "잘하고 있어요"에 동시에 나올 수 있다
  const remaining = summary.skills.slice(weakest.length);
  const strongest = remaining.slice(Math.max(0, remaining.length - STRONGEST_COUNT)).reverse();

  return {
    overallPercent: percentOf(summary.skills),
    overallDetail: `요소 ${summary.skills.length}개 중 ${
      summary.skills.filter((skill) => skill.label === MASTERED_LABEL).length
    }개를 익혔어요`,
    stats,
    weakest: weakest.map(toRow),
    strongest: strongest.map(toRow),
  };
}
