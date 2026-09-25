import { learningService } from "../src/services/learningService";
import { selectThemeRows } from "../src/services/themeStats";
import { api } from "../src/services/apiClient";
import type { StageStats } from "@ieum/contracts";

jest.mock("../src/services/apiClient", () => ({
  api: { post: jest.fn(), get: jest.fn(), put: jest.fn() },
}));

const get = api.get as jest.Mock;

function stats(): StageStats {
  return {
    stages: [
      { curriculumStageId: 1, name: "자음", level: "CONSONANT", solvedCount: 48, correctCount: 43 },
      { curriculumStageId: 2, name: "모음", level: "VOWEL", solvedCount: 0, correctCount: 0 },
    ],
  };
}

describe("테마별 통계 조회", () => {
  beforeEach(() => get.mockReset());

  it("GET /learning-sessions/stage-stats 를 부른다", async () => {
    get.mockResolvedValue(stats());

    await learningService.getStageStats();

    expect(get).toHaveBeenCalledWith("/learning-sessions/stage-stats");
  });

  it("응답이 계약과 다르면 던진다", async () => {
    get.mockResolvedValue({ stages: [{ curriculumStageId: 1 }] });

    await expect(learningService.getStageStats()).rejects.toThrow();
  });
});

describe("테마 목록이 그릴 값", () => {
  it("푼 문제와 정답률을 그대로 옮긴다", () => {
    const rows = selectThemeRows(stats());

    expect(rows[0]).toEqual({
      curriculumStageId: 1,
      label: "자음",
      description: "48문제 중 43개 맞힘",
      percent: 90,
    });
  });

  it("아직 안 푼 단계는 퍼센트를 말하지 않는다", () => {
    // 0으로 나누면 NaN이 화면에 그대로 나온다
    const rows = selectThemeRows(stats());

    expect(rows[1]).toEqual({
      curriculumStageId: 2,
      label: "모음",
      description: "아직 안 풀었어요",
      percent: null,
    });
  });

  it("서버가 준 순서를 지킨다 — 커리큘럼 순서다", () => {
    const rows = selectThemeRows(stats());

    expect(rows.map((r) => r.label)).toEqual(["자음", "모음"]);
  });

  it("서버 결과가 없으면 빈 목록이다", () => {
    // 고정값으로 물러서지 않는다 — 가짜 진도를 자기 기록으로 읽게 된다
    expect(selectThemeRows(undefined)).toEqual([]);
  });
});
