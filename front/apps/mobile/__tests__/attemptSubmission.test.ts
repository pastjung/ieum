import { submitAttempt, MAX_ATTEMPT_TRIES } from "@/services/attemptSubmission";
import { ApiError } from "@ieum/api-client";

const analysis = {
  problemSessionId: 101,
  problemId: 7,
  attemptId: 55,
  correct: true,
  attemptedAt: "2026-09-25T02:00:00Z",
  cells: [],
  feedback: { tts: "정답이에요.", detail: "" },
};

describe("문항 제출", () => {
  it("성공하면 분석 결과를 돌려준다", async () => {
    const analyze = jest.fn(async () => analysis);
    const outcome = await submitAttempt({
      analyze,
      sessionId: 12,
      problemSessionId: 101,
      inputPatterns: [1],
      attempt: 1,
    });

    expect(outcome).toEqual({ kind: "analyzed", result: analysis });
    expect(analyze).toHaveBeenCalledTimes(1);
  });

  it("이미 분석된 문항은 오류로 보지 않는다", async () => {
    // 앱이 응답을 못 받았지만 서버는 성공한 경우다. 오류로 다루면 이미 끝난 문항에서 막힌다
    const analyze = jest.fn(async () => {
      throw new ApiError(409, "LEARNING_PROBLEM_ALREADY_ANALYZED", "이미 분석했어요");
    });

    const outcome = await submitAttempt({
      analyze,
      sessionId: 12,
      problemSessionId: 101,
      inputPatterns: [1],
      attempt: 1,
    });

    expect(outcome.kind).toBe("already");
  });

  it("실패하면 시도 횟수를 담은 문구를 돌려준다", async () => {
    // a11y/speech.ts 가 같은 문구를 1.5초 안에 중복 제거한다. 시도마다 문구가 달라야
    // 재시도 실패가 묵음이 되지 않는다
    const analyze = jest.fn(async () => {
      throw new ApiError(500, "INTERNAL_SERVER_ERROR", "일시적인 오류");
    });

    const first = await submitAttempt({
      analyze, sessionId: 12, problemSessionId: 101, inputPatterns: [1], attempt: 1,
    });
    const second = await submitAttempt({
      analyze, sessionId: 12, problemSessionId: 101, inputPatterns: [1], attempt: 2,
    });

    expect(first.kind).toBe("failed");
    expect(second.kind).toBe("failed");
    expect(first).not.toEqual(second);
  });

  it("시도를 소진하면 다시 시작하라고 알린다", async () => {
    // 12칸 초과 문제처럼 영구 실패가 실재한다. 무한 재시도는 사용자를 가둔다
    const analyze = jest.fn(async () => {
      throw new ApiError(500, "INTERNAL_SERVER_ERROR", "일시적인 오류");
    });

    const outcome = await submitAttempt({
      analyze,
      sessionId: 12,
      problemSessionId: 101,
      inputPatterns: [1],
      attempt: MAX_ATTEMPT_TRIES,
    });

    expect(outcome.kind).toBe("exhausted");
    expect(outcome.message).toContain("처음부터");
  });
});
