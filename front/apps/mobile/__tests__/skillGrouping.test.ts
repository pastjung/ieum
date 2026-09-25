import { selectSkillDashboard, skillDisplayName } from "../src/services/skillDashboard";
import type { SkillMastery, SkillSummary } from "@ieum/contracts";

function skill(
  id: number,
  name: string,
  target: string,
  masteryProb: number | null,
  label = "더 연습해요",
): SkillMastery {
  return { learningSkillId: id, name, target, masteryProb, label };
}

function summary(skills: SkillMastery[]): SkillSummary {
  return { totalAttemptCount: 100, correctAttemptCount: 50, skills };
}

describe("요소 이름 표시", () => {
  it("분류 이름에 대상 글자를 붙인다", () => {
    // learning_skills 의 name 은 분류다 — "첫소리 기본 자음"만 14행이라, 이름만 쓰면
    // 화면에 같은 줄이 14번 반복돼 학습자가 구별할 수 없다
    expect(skillDisplayName(skill(1, "첫소리 기본 자음", "ㄱ", 0.1))).toBe("첫소리 기본 자음 ㄱ");
  });

  it("대상 글자가 없으면 이름만 쓴다", () => {
    expect(skillDisplayName(skill(1, "약자를 쓰지 않는 경우", "", 0.1))).toBe(
      "약자를 쓰지 않는 경우",
    );
  });
});

describe("분류별로 묶기", () => {
  it("같은 이름을 하나로 묶고 평균을 낸다", () => {
    // 74줄을 그대로 그리면 같은 이름이 반복돼 읽을 수 없다
    const view = selectSkillDashboard(
      summary([
        skill(1, "첫소리 기본 자음", "ㄱ", 0.1),
        skill(2, "첫소리 기본 자음", "ㄴ", 0.3),
        skill(3, "겹받침", "ㄺ", 0.8),
      ]),
    );

    expect(view.rules).toEqual([
      { label: "첫소리 기본 자음", percent: 20, count: 2 },
      { label: "겹받침", percent: 80, count: 1 },
    ]);
  });

  it("약한 분류부터 보여준다", () => {
    const view = selectSkillDashboard(
      summary([
        skill(1, "겹받침", "ㄺ", 0.9),
        skill(2, "된소리", "ㄲ", 0.2),
        skill(3, "기본 모음", "ㅏ", 0.5),
      ]),
    );

    expect(view.rules.map((r) => r.label)).toEqual(["된소리", "기본 모음", "겹받침"]);
  });

  it("숙련도가 없는 요소는 평균에 넣지 않는다", () => {
    const view = selectSkillDashboard(
      summary([
        skill(1, "된소리", "ㄲ", 0.4),
        skill(2, "된소리", "ㄸ", null),
      ]),
    );

    // 0으로 치면 평균이 절반으로 떨어져 실제보다 못하는 것처럼 보인다
    expect(view.rules[0]).toEqual({ label: "된소리", percent: 40, count: 2 });
  });

  it("분류의 숙련도를 모두 모르면 퍼센트가 없다", () => {
    const view = selectSkillDashboard(summary([skill(1, "된소리", "ㄲ", null)]));

    expect(view.rules[0]).toEqual({ label: "된소리", percent: null, count: 1 });
  });

  it("묶어도 전체 숙련도는 요소 단위 평균이다", () => {
    // 분류마다 요소 수가 달라, 분류 평균을 다시 평균하면 요소가 적은 분류가 과대평가된다
    const view = selectSkillDashboard(
      summary([
        skill(1, "첫소리 기본 자음", "ㄱ", 0.0),
        skill(2, "첫소리 기본 자음", "ㄴ", 0.0),
        skill(3, "첫소리 기본 자음", "ㄷ", 0.0),
        skill(4, "겹받침", "ㄺ", 1.0),
      ]),
    );

    expect(view.overallPercent).toBe(25);
  });

  it("익힌 요소 수는 묶기 전 기준이다", () => {
    const view = selectSkillDashboard(
      summary([
        skill(1, "첫소리 기본 자음", "ㄱ", 0.95, "익숙해요"),
        skill(2, "첫소리 기본 자음", "ㄴ", 0.1),
      ]),
    );

    expect(view.overallDetail).toBe("요소 2개 중 1개를 익혔어요");
  });
});
