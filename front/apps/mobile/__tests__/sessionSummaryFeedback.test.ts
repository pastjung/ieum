import { selectSessionSummaryFeedback } from "@/services/sessionSummaryFeedback";

/**
 * 서버(`apps/ai`)가 두 필드를 이렇게 만든다.
 *
 * ```python
 * summary = f"{n}문제 중 {n_correct}문제를 맞혔어요."
 * tts     = f"오늘 {n}문제 중 {n_correct}문제를 맞혔어요."
 * ```
 *
 * 점수 문장은 결과 화면이 자기 숫자(`correctProblemCount` / `totalProblemCount`)로 이미
 * 말한다. 서버 문구를 그대로 쓰면 한 화면에서 점수를 세 번 말하고, 서버의 `n_correct`는
 * AI가 따로 채점한 값이라 화면 숫자와 갈릴 수도 있다.
 */
describe("결과 화면에 보여줄 세션 요약을 고른다", () => {
  it("서버 문구의 점수 문장을 쓰지 않고 조언만 남긴다", () => {
    const result = selectSessionSummaryFeedback({
      summary: "12문제 중 8문제를 맞혔어요. 받침 자음은 조금 더 연습하면 좋겠어요.",
      ttsText: "오늘 12문제 중 8문제를 맞혔어요. 받침 자음을 다시 연습해 보세요.",
    });

    expect(result.lines).toEqual(["받침 자음을 다시 연습해 보세요."]);
    expect(result.speech).toBe("받침 자음을 다시 연습해 보세요.");
  });

  it("점수를 한 번도 말하지 않는다", () => {
    // 화면은 "8 / 12"를 보여주는데 낭독이 다른 숫자를 말하면 어느 쪽을 믿어야 할지 알 수 없다
    const result = selectSessionSummaryFeedback({
      summary: "12문제 중 9문제를 맞혔어요.",
      ttsText: "오늘 12문제 중 9문제를 맞혔어요. 아주 잘했어요.",
    });

    expect(result.speech).toBe("아주 잘했어요.");
    expect(result.speech).not.toMatch(/문제를 맞혔어요/);
    expect(result.lines.join(" ")).not.toMatch(/문제를 맞혔어요/);
  });

  it("어미만 다른 같은 문장을 두 번 보여주지 않는다", () => {
    const result = selectSessionSummaryFeedback({
      summary: "12문제 중 8문제를 맞혔어요. 받침 자음은 조금 더 연습하면 좋겠어요.",
      ttsText: "오늘 12문제 중 8문제를 맞혔어요. 받침 자음을 다시 연습해 보세요.",
    });

    expect(result.lines).toHaveLength(1);
  });

  it("점수 문장밖에 없으면 보여줄 것이 없다", () => {
    const result = selectSessionSummaryFeedback({
      summary: "12문제 중 12문제를 맞혔어요.",
      ttsText: "오늘 12문제 중 12문제를 맞혔어요.",
    });

    expect(result.lines).toEqual([]);
    expect(result.speech).toBeNull();
  });

  it("ttsText가 점수 문장뿐이면 summary의 조언으로 물러선다", () => {
    const result = selectSessionSummaryFeedback({
      summary: "12문제 중 8문제를 맞혔어요. 받침 자음은 조금 더 연습하면 좋겠어요.",
      ttsText: "오늘 12문제 중 8문제를 맞혔어요.",
    });

    expect(result.lines).toEqual(["받침 자음은 조금 더 연습하면 좋겠어요."]);
  });

  it("서버가 요약을 만들지 못하면(둘 다 null) 보여줄 것이 없다", () => {
    const result = selectSessionSummaryFeedback({ summary: null, ttsText: null });

    expect(result.lines).toEqual([]);
    expect(result.speech).toBeNull();
  });

  it("목 세션(serverResult가 null)이면 보여줄 것이 없다", () => {
    const result = selectSessionSummaryFeedback(null);

    expect(result.lines).toEqual([]);
    expect(result.speech).toBeNull();
  });

  it("점수 문장이 없는 문구는 그대로 쓴다", () => {
    const result = selectSessionSummaryFeedback({ summary: null, ttsText: "잘하고 있어요." });

    expect(result.lines).toEqual(["잘하고 있어요."]);
    expect(result.speech).toBe("잘하고 있어요.");
  });

  it("빈 문자열은 없는 것으로 취급한다", () => {
    const result = selectSessionSummaryFeedback({ summary: "", ttsText: "" });

    expect(result.lines).toEqual([]);
    expect(result.speech).toBeNull();
  });

  it("화면에 그리는 문장과 낭독 문구가 같은 재료다", () => {
    const result = selectSessionSummaryFeedback({
      summary: "12문제 중 8문제를 맞혔어요. 모음은 조금 더 연습하면 좋겠어요.",
      ttsText: "오늘 12문제 중 8문제를 맞혔어요. 모음을 다시 연습해 보세요.",
    });

    expect(result.speech).toBe(result.lines.join(" "));
  });
});
