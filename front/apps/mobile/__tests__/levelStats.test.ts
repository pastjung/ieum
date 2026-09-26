import { learningService } from "../src/services/learningService";
import { selectLevelDashboard, weakestLevels } from "../src/services/levelStats";
import { api } from "../src/services/apiClient";
import type { LevelStats } from "@ieum/contracts";

jest.mock("../src/services/apiClient", () => ({
  api: { post: jest.fn(), get: jest.fn(), put: jest.fn() },
}));

const get = api.get as jest.Mock;

function stats(): LevelStats {
  return {
    levels: [
      { level: "CONSONANT", solvedCount: 48, correctCount: 43 },
      { level: "WORD", solvedCount: 10, correctCount: 4 },
    ],
  };
}

describe("단계별 통계 조회", () => {
  beforeEach(() => get.mockReset());

  it("GET /learning-sessions/level-stats 를 부른다", async () => {
    get.mockResolvedValue(stats());

    await learningService.getLevelStats();

    expect(get).toHaveBeenCalledWith("/learning-sessions/level-stats");
  });

  it("응답이 계약과 다르면 던진다", async () => {
    get.mockResolvedValue({ levels: [{ level: "NOPE", solvedCount: 1, correctCount: 0 }] });

    await expect(learningService.getLevelStats()).rejects.toThrow();
  });
});

describe("내 실력이 그릴 값", () => {
  it("푼 수와 맞힌 수로 단계별 정답률을 낸다", () => {
    const rows = selectLevelDashboard(stats()).rows;

    expect(rows.find((r) => r.level === "CONSONANT")).toEqual({
      level: "CONSONANT",
      label: "자음",
      solvedCount: 48,
      correctCount: 43,
      percent: 90,
    });
  });

  it("여섯 단계를 학습 순서로 모두 그린다", () => {
    // 서버는 기록이 있는 단계만 보낸다. 안 푼 단계가 목록에서 빠지면 학습자는
    // 자기가 어디를 아직 건드리지 않았는지 알 수 없다
    const rows = selectLevelDashboard(stats()).rows;

    expect(rows.map((r) => r.label)).toEqual(["자음", "모음", "약자", "단어", "약어", "문장"]);
  });

  it("기록이 없는 단계는 퍼센트를 말하지 않는다", () => {
    // 0%로 두면 "다 틀렸다"로 읽힌다 — 안 푼 것과 다 틀린 것은 다른 사실이다
    const vowel = selectLevelDashboard(stats()).rows.find((r) => r.level === "VOWEL");

    expect(vowel).toEqual({
      level: "VOWEL",
      label: "모음",
      solvedCount: 0,
      correctCount: 0,
      percent: null,
    });
  });

  it("한 번도 맞히지 못한 단계는 0퍼센트다", () => {
    // 기록 없음(null)과 구별된다. 푼 적은 있으니 "아직 안 풀었어요"가 아니다
    const row = selectLevelDashboard({
      levels: [{ level: "SENTENCE", solvedCount: 7, correctCount: 0 }],
    }).rows.find((r) => r.level === "SENTENCE");

    expect(row?.percent).toBe(0);
  });

  it("전체 정답률은 모든 단계를 합해서 낸다", () => {
    const d = selectLevelDashboard(stats());

    expect(d.totalSolved).toBe(58);
    expect(d.totalCorrect).toBe(47);
    expect(d.overallPercent).toBe(81);
  });

  it("기록이 하나도 없으면 전체 정답률을 말하지 않는다", () => {
    const d = selectLevelDashboard({ levels: [] });

    expect(d.totalSolved).toBe(0);
    expect(d.overallPercent).toBeNull();
    expect(d.rows).toHaveLength(6);
  });

  it("서버 결과가 없어도 여섯 단계를 기록 없음으로 그린다", () => {
    // 고정값으로 물러서지 않는다 — 가짜 진도를 자기 기록으로 읽게 된다
    const d = selectLevelDashboard(undefined);

    expect(d.overallPercent).toBeNull();
    expect(d.rows.every((r) => r.percent === null)).toBe(true);
  });
});

describe("음성 모드가 읽을 단계", () => {
  it("정답률이 낮은 것부터 세 개만 고른다", () => {
    // 여섯 단계를 다 읽으면 낭독이 길어 무엇이 약한지 남지 않는다
    const d = selectLevelDashboard({
      levels: [
        { level: "CONSONANT", solvedCount: 10, correctCount: 9 },
        { level: "VOWEL", solvedCount: 10, correctCount: 3 },
        { level: "WORD", solvedCount: 10, correctCount: 5 },
        { level: "SENTENCE", solvedCount: 10, correctCount: 1 },
      ],
    });

    expect(weakestLevels(d.rows).map((r) => r.label)).toEqual(["문장", "모음", "단어"]);
  });

  it("아직 안 푼 단계는 읽지 않는다", () => {
    // 기록이 없는 단계를 "0퍼센트"로 읽으면 다 틀렸다는 말이 된다
    const d = selectLevelDashboard(stats());

    expect(weakestLevels(d.rows).map((r) => r.label)).toEqual(["단어", "자음"]);
  });

  it("기록이 하나도 없으면 읽을 것이 없다", () => {
    expect(weakestLevels(selectLevelDashboard({ levels: [] }).rows)).toEqual([]);
  });
});
