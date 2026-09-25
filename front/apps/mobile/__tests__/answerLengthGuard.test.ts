import { MAX_INPUT_CELLS } from "@ieum/contracts";
import { describeTooManyCells } from "@/services/answerLengthGuard";

describe("제출 전 칸 수 검사", () => {
  it("12칸 이하이면 막지 않는다", () => {
    expect(describeTooManyCells(1)).toBeNull();
    expect(describeTooManyCells(MAX_INPUT_CELLS)).toBeNull();
  });

  it("기대 칸 수를 넘어도 12칸 이하면 막지 않는다", () => {
    // dev가 validateExpectedCells에서 길이 동등 검사를 뺐다 — 기대 칸 수는 이 함수와 무관하다.
    // 5칸짜리 문제에 12칸을 넣어도(넘치지만 AI 상한 이내) 여기서는 막지 않는다.
    expect(describeTooManyCells(12)).toBeNull();
  });

  it("12칸을 넘으면 막는다", () => {
    // FastAPI가 input_patterns를 12개까지만 받는다. 기대 칸 수와 무관하게 거부된다
    expect(describeTooManyCells(MAX_INPUT_CELLS + 1)).not.toBeNull();
  });

  it("막을 때는 몇 칸까지인지·지금 몇 칸인지·어떻게 지우는지를 알려 준다", () => {
    const message = describeTooManyCells(13);

    expect(message).toBe(
      "한 번에 보낼 수 있는 답은 12칸까지예요. 지금 13칸 입력했어요. 지우기 버튼을 누르면 마지막 칸이 지워져요.",
    );
  });
});
