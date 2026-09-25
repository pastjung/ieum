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

/** 서버는 약한 것부터 정렬해 보낸다 */
function ranked(count: number): SkillMastery[] {
  return Array.from({ length: count }, (_, i) =>
    skill(i + 1, "첫소리 기본 자음", String.fromCharCode(0x3131 + i), i / count),
  );
}

describe("요소 이름 표시", () => {
  it("분류 이름에 대상 글자를 붙인다", () => {
    // learning_skills 의 name 은 분류다 — "첫소리 기본 자음"만 14행이라, 이름만 쓰면
    // 화면에 같은 줄이 여러 번 반복돼 학습자가 구별할 수 없다
    expect(skillDisplayName(skill(1, "첫소리 기본 자음", "ㄱ", 0.1))).toBe("첫소리 기본 자음 ㄱ");
  });

  it("대상 글자가 없으면 이름만 쓴다", () => {
    expect(skillDisplayName(skill(1, "약자를 쓰지 않는 경우", "", 0.1))).toBe(
      "약자를 쓰지 않는 경우",
    );
  });
});

describe("약한 것과 잘하는 것만 보여준다", () => {
  it("약한 것은 앞에서, 잘하는 것은 뒤에서 가져온다", () => {
    // 74개를 다 그리면 읽을 수 없고, 분류로 묶으면 "단독 자모 35개 평균 12%"처럼
    // 무엇을 연습해야 할지 알 수 없는 줄이 된다
    const view = selectSkillDashboard(summary(ranked(20)));

    expect(view.weakest).toHaveLength(5);
    expect(view.strongest).toHaveLength(3);
    expect(view.weakest[0].percent).toBeLessThan(view.strongest[0].percent!);
  });

  it("잘하는 것은 잘하는 순서로 보여준다", () => {
    const view = selectSkillDashboard(summary(ranked(20)));

    expect(view.strongest[0].percent!).toBeGreaterThan(view.strongest[1].percent!);
  });

  it("같은 요소가 양쪽에 겹치지 않는다", () => {
    // 요소가 적으면 앞뒤가 만난다. 같은 줄이 "약해요"와 "잘해요"에 동시에 나오면 안 된다
    const view = selectSkillDashboard(summary(ranked(6)));

    const weakIds = view.weakest.map((r) => r.learningSkillId);
    const strongIds = view.strongest.map((r) => r.learningSkillId);
    expect(weakIds.filter((id) => strongIds.includes(id))).toEqual([]);
    expect(weakIds.length + strongIds.length).toBe(6);
  });

  it("요소가 아주 적으면 약한 쪽에만 넣는다", () => {
    const view = selectSkillDashboard(summary(ranked(2)));

    expect(view.weakest).toHaveLength(2);
    expect(view.strongest).toEqual([]);
  });

  it("줄마다 이름·퍼센트·라벨을 담는다", () => {
    const view = selectSkillDashboard(
      summary([skill(7, "된소리", "ㄲ", 0.42, "더 연습해요")]),
    );

    expect(view.weakest[0]).toEqual({
      learningSkillId: 7,
      label: "된소리 ㄲ",
      percent: 42,
      level: "더 연습해요",
    });
  });

  it("숙련도를 모르는 요소도 이름은 보여준다", () => {
    const view = selectSkillDashboard(summary([skill(7, "된소리", "ㄲ", null)]));

    expect(view.weakest[0].percent).toBeNull();
  });
});

describe("전체 요약", () => {
  it("전체 숙련도는 요소들의 평균이다", () => {
    const view = selectSkillDashboard(
      summary([skill(1, "가", "ㄱ", 0.2), skill(2, "나", "ㄴ", 0.8, "익숙해요")]),
    );

    expect(view.overallPercent).toBe(50);
  });

  it("익힌 요소 수를 함께 말한다", () => {
    const view = selectSkillDashboard(
      summary([
        skill(1, "가", "ㄱ", 0.2),
        skill(2, "나", "ㄴ", 0.9, "익숙해요"),
        skill(3, "다", "ㄷ", 0.95, "익숙해요"),
      ]),
    );

    expect(view.overallDetail).toBe("요소 3개 중 2개를 익혔어요");
  });

  it("정답률을 푼 문제 수로 계산한다", () => {
    const view = selectSkillDashboard({
      totalAttemptCount: 200,
      correctAttemptCount: 150,
      skills: [],
    });

    expect(view.stats).toContainEqual({ label: "푼 문제", value: "200" });
    expect(view.stats).toContainEqual({ label: "정답률", value: "75%" });
  });

  it("푼 문제가 없으면 정답률을 말하지 않는다", () => {
    // 0으로 나누면 NaN이 화면에 그대로 나온다
    const view = selectSkillDashboard({
      totalAttemptCount: 0,
      correctAttemptCount: 0,
      skills: [],
    });

    expect(view.stats.map((s) => s.label)).not.toContain("정답률");
  });

  it("기록이 없으면 숙련도를 꾸며 내지 않는다", () => {
    const view = selectSkillDashboard(summary([]));

    expect(view.overallPercent).toBeNull();
    expect(view.weakest).toEqual([]);
    expect(view.strongest).toEqual([]);
  });

  it("서버 결과가 없으면 빈 화면이다", () => {
    const view = selectSkillDashboard(undefined);

    expect(view.overallPercent).toBeNull();
    expect(view.stats).toEqual([]);
  });
});
