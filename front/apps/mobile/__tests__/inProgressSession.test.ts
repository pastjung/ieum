import { learningService } from "../src/services/learningService";
import { api } from "../src/services/apiClient";

jest.mock("../src/services/apiClient", () => ({
  api: { post: jest.fn(), get: jest.fn(), put: jest.fn() },
}));

const get = api.get as jest.Mock;

describe("진행 중인 세션 조회", () => {
  beforeEach(() => get.mockReset());

  it("GET /learning-sessions/in-progress 를 부른다", async () => {
    get.mockResolvedValue({
      sessionId: 7,
      stageName: "단어",
      review: false,
      totalCount: 12,
      completedCount: 5,
    });

    await learningService.getInProgressSession();

    expect(get).toHaveBeenCalledWith("/learning-sessions/in-progress");
  });

  it("진행 중인 세션을 그대로 돌려준다", async () => {
    get.mockResolvedValue({
      sessionId: 7,
      stageName: "단어",
      review: false,
      totalCount: 12,
      completedCount: 5,
    });

    const result = await learningService.getInProgressSession();

    expect(result).toEqual({
      sessionId: 7,
      stageName: "단어",
      review: false,
      totalCount: 12,
      completedCount: 5,
    });
  });

  it("204(본문 없음)면 null이다", async () => {
    // 진행 중인 것이 없다는 뜻이다. 빈 객체로 다루면 "0문항짜리 세션"과 구별되지 않는다
    get.mockResolvedValue(undefined);

    expect(await learningService.getInProgressSession()).toBeNull();
  });

  it("응답이 계약과 다르면 던진다", async () => {
    get.mockResolvedValue({ sessionId: 7 });

    await expect(learningService.getInProgressSession()).rejects.toThrow();
  });
});

describe("세션 이어받기", () => {
  beforeEach(() => get.mockReset());

  it("GET /learning-sessions/{id} 로 기존 배정을 가져온다", async () => {
    get.mockResolvedValue({
      sessionId: 7,
      problems: [
        {
          problemSessionId: 70,
          problemId: 700,
          sequence: 1,
          targetText: "가방",
          expectedPatterns: [43, 24, 54],
          completed: true,
        },
        {
          problemSessionId: 71,
          problemId: 701,
          sequence: 2,
          targetText: "나무",
          expectedPatterns: [14, 26],
          completed: false,
        },
      ],
    });

    const { session, meta } = await learningService.resumeSession(7);

    expect(get).toHaveBeenCalledWith("/learning-sessions/7");
    expect(session.title).toBe("이어서 학습");
    // 이미 푼 문항은 빼고 남은 것만 이어 푼다 — 다시 제출하면 서버가 거부한다
    expect(session.problems).toHaveLength(1);
    expect(session.problems[0].target).toBe("나무");
    expect(meta.problemSessionIds).toEqual([71]);
  });

  it("남은 문항이 없으면 던진다", async () => {
    // 모두 푼 세션은 이어할 것이 없다. 빈 세션으로 넘어가면 문항 0개짜리 화면을 만난다
    get.mockResolvedValue({
      sessionId: 7,
      problems: [
        {
          problemSessionId: 70,
          problemId: 700,
          sequence: 1,
          targetText: "가방",
          expectedPatterns: [43, 24, 54],
          completed: true,
        },
      ],
    });

    await expect(learningService.resumeSession(7)).rejects.toThrow();
  });
});
