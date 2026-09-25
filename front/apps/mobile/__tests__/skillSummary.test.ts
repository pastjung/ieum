import { learningService } from "../src/services/learningService";
import { selectSkillDashboard } from "../src/services/skillDashboard";
import { api } from "../src/services/apiClient";
import type { SkillSummary } from "@ieum/contracts";

jest.mock("../src/services/apiClient", () => ({
  api: { post: jest.fn(), get: jest.fn(), put: jest.fn() },
}));

const get = api.get as jest.Mock;

function summary(overrides: Partial<SkillSummary> = {}): SkillSummary {
  return {
    totalAttemptCount: 184,
    correctAttemptCount: 151,
    skills: [
      { learningSkillId: 2, name: "된소리", masteryProb: 0.21, label: "더 연습해요" },
      { learningSkillId: 1, name: "첫소리 기본 자음", masteryProb: 0.93, label: "익숙해요" },
    ],
    ...overrides,
  };
}

describe("숙련 상태 조회", () => {
  beforeEach(() => get.mockReset());

  it("GET /learning-sessions/skill-summary 를 부른다", async () => {
    get.mockResolvedValue(summary());

    await learningService.getSkillSummary();

    expect(get).toHaveBeenCalledWith("/learning-sessions/skill-summary");
  });

  it("응답이 계약과 다르면 던진다", async () => {
    get.mockResolvedValue({ totalAttemptCount: 1 });

    await expect(learningService.getSkillSummary()).rejects.toThrow();
  });
});

describe("내 실력 화면이 그릴 값", () => {
  it("정답률을 푼 문제 수로 계산한다", () => {
    const view = selectSkillDashboard(summary({ totalAttemptCount: 200, correctAttemptCount: 150 }));

    expect(view.stats).toContainEqual({ label: "푼 문제", value: "200" });
    expect(view.stats).toContainEqual({ label: "정답률", value: "75%" });
  });

  it("푼 문제가 없으면 정답률을 말하지 않는다", () => {
    // 0으로 나누면 NaN이 화면에 그대로 나온다
    const view = selectSkillDashboard(
      summary({ totalAttemptCount: 0, correctAttemptCount: 0, skills: [] }),
    );

    expect(view.stats.map((s) => s.label)).not.toContain("정답률");
  });

  it("전체 숙련도는 요소들의 평균이다", () => {
    const view = selectSkillDashboard(
      summary({
        skills: [
          { learningSkillId: 1, name: "가", masteryProb: 0.2, label: "더 연습해요" },
          { learningSkillId: 2, name: "나", masteryProb: 0.8, label: "익숙해요" },
        ],
      }),
    );

    expect(view.overallPercent).toBe(50);
  });

  it("익힌 요소 수를 함께 말한다", () => {
    const view = selectSkillDashboard(
      summary({
        skills: [
          { learningSkillId: 1, name: "가", masteryProb: 0.2, label: "더 연습해요" },
          { learningSkillId: 2, name: "나", masteryProb: 0.9, label: "익숙해요" },
          { learningSkillId: 3, name: "다", masteryProb: 0.95, label: "익숙해요" },
        ],
      }),
    );

    expect(view.overallDetail).toBe("요소 3개 중 2개를 익혔어요");
  });

  it("기록이 없으면 숙련도를 꾸며 내지 않는다", () => {
    const view = selectSkillDashboard(summary({ skills: [] }));

    expect(view.overallPercent).toBeNull();
    expect(view.rules).toEqual([]);
  });

  it("서버 결과가 없으면 빈 화면이다", () => {
    const view = selectSkillDashboard(undefined);

    expect(view.overallPercent).toBeNull();
    expect(view.stats).toEqual([]);
    expect(view.rules).toEqual([]);
  });

  it("요소 순서를 바꾸지 않는다", () => {
    // 서버가 약한 것부터 정렬해 보낸다. 다시 정렬하면 복습 카드와 순서가 갈린다
    const view = selectSkillDashboard(summary());

    expect(view.rules.map((r) => r.label)).toEqual(["된소리", "첫소리 기본 자음"]);
  });
});
