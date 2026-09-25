import { learningService } from "../src/services/learningService";
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
      { learningSkillId: 2, name: "된소리", target: "ㄲ", masteryProb: 0.21, label: "더 연습해요" },
      { learningSkillId: 1, name: "첫소리 기본 자음", target: "ㄱ", masteryProb: 0.93, label: "익숙해요" },
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
