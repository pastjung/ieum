import type { CompletionResponse } from "@ieum/contracts";
import { submitSessionResults } from "../src/services/learningSessionSubmission";

/**
 * `analyzing.tsx`의 제출 분기(`submitSessionResults`)를 직접 부른다. `beginLearningSession`
 * 테스트와 같은 이유 — 화면이 이 함수 하나만 불러 결과를 판단하므로, 이 함수의 동작이
 * 화면의 동작이다.
 */
describe("submitSessionResults", () => {
  it("목 세션(serverSessionId 없음)이면 서버를 부르지 않고 건너뛴다", async () => {
    const completeSession = jest.fn();

    const outcome = await submitSessionResults({
      serverSessionId: null,
      attempts: [],
      completeSession,
    });

    expect(outcome).toEqual({ kind: "skipped" });
    expect(completeSession).not.toHaveBeenCalled();
  });

  it("성공하면 응답을 그대로 담아 돌려준다", async () => {
    const result: CompletionResponse = {
      sessionId: 12,
      completedAt: "2026-09-24T00:00:00Z",
      totalProblemCount: 2,
      correctProblemCount: 1,
      problemResults: [],
    };
    const completeSession = jest.fn().mockResolvedValue(result);
    const attempts = [{ problemSessionId: 101, inputPatterns: [1] }];

    const outcome = await submitSessionResults({ serverSessionId: 12, attempts, completeSession });

    expect(outcome).toEqual({ kind: "success", result });
    expect(completeSession).toHaveBeenCalledWith(12, attempts);
  });

  it("실패하면(네트워크·검증 거부 등) 오류를 담아 돌려주고 던지지 않는다", async () => {
    const error = new Error("network down");
    const completeSession = jest.fn().mockRejectedValue(error);

    const outcome = await submitSessionResults({ serverSessionId: 12, attempts: [], completeSession });

    expect(outcome).toEqual({ kind: "failed", error });
  });
});
