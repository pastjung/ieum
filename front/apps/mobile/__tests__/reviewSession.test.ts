import { learningService } from "../src/services/learningService";
import { api } from "../src/services/apiClient";

jest.mock("../src/services/apiClient", () => ({
  api: { post: jest.fn(), get: jest.fn() },
}));

const post = api.post as jest.Mock;

/** 서버가 돌려주는 ProblemsResponse — 자유 학습과 같은 모양이다 */
function problemsResponse() {
  return {
    sessionId: 7,
    problems: [
      {
        problemSessionId: 70,
        problemId: 700,
        sequence: 1,
        targetText: "가방",
        expectedPatterns: [43, 24, 54],
        completed: false,
      },
    ],
  };
}

describe("복습 세션 시작", () => {
  beforeEach(() => post.mockReset());

  it("본문 없이 POST /review-sessions 를 부른다", async () => {
    // 단계를 고르지 않는다 — 서버가 내 기록에서 약한 요소를 골라 출제한다.
    // 여기에 curriculumId를 실어 보내면 자유 학습 출제로 새는 길이 생긴다
    post.mockResolvedValue(problemsResponse());

    await learningService.startReviewSession();

    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith("/review-sessions");
  });

  it("세션 제목이 '복습'이다", async () => {
    // intro 화면이 이 제목을 그대로 낭독한다 — "자음"이 나오면 학습자는 복습을 눌렀는데
    // 자음 학습이 시작된 것으로 듣는다. 이 앱이 오래 그랬다
    post.mockResolvedValue(problemsResponse());

    const { session } = await learningService.startReviewSession();

    expect(session.title).toBe("복습");
  });

  it("문항과 배정 식별자를 자유 학습과 같은 모양으로 옮긴다", async () => {
    // 이후 흐름(제출·채점·완료)이 자유 학습과 완전히 같아야 한다
    post.mockResolvedValue(problemsResponse());

    const { session, meta } = await learningService.startReviewSession();

    expect(meta).toEqual({ serverSessionId: 7, problemSessionIds: [70] });
    expect(session.problems).toEqual([
      {
        id: "700",
        prompt: "'가방'을 점자로 입력하세요",
        target: "가방",
        expectedCells: [43, 24, 54],
      },
    ]);
  });

  it("응답이 계약과 다르면 던진다", async () => {
    // 조용히 빈 세션으로 넘어가면 학습자는 문항 0개짜리 화면을 만난다
    post.mockResolvedValue({ sessionId: 7 });

    await expect(learningService.startReviewSession()).rejects.toThrow();
  });
});
