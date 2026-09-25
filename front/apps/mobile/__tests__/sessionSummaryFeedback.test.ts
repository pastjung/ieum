import { selectSessionSummaryFeedback } from "@/services/sessionSummaryFeedback";

describe("결과 화면에 보여줄 세션 요약을 고른다", () => {
  it("summary·ttsText가 둘 다 있으면 ttsText를 먼저, summary를 이어서 보여주고 낭독한다", () => {
    const result = selectSessionSummaryFeedback({
      summary: "받침 자음에서 자주 틀렸어요.",
      ttsText: "오늘은 받침 자음 연습이 도움이 될 것 같아요.",
    });

    expect(result.lines).toEqual([
      "오늘은 받침 자음 연습이 도움이 될 것 같아요.",
      "받침 자음에서 자주 틀렸어요.",
    ]);
    expect(result.speech).toBe(
      "오늘은 받침 자음 연습이 도움이 될 것 같아요. 받침 자음에서 자주 틀렸어요.",
    );
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

  it("ttsText만 있으면 그것만 보여주고 낭독한다", () => {
    const result = selectSessionSummaryFeedback({ summary: null, ttsText: "잘하고 있어요." });

    expect(result.lines).toEqual(["잘하고 있어요."]);
    expect(result.speech).toBe("잘하고 있어요.");
  });

  it("summary만 있으면 그것만 보여주고 낭독한다", () => {
    const result = selectSessionSummaryFeedback({ summary: "모음에서 자주 틀렸어요.", ttsText: null });

    expect(result.lines).toEqual(["모음에서 자주 틀렸어요."]);
    expect(result.speech).toBe("모음에서 자주 틀렸어요.");
  });

  it("summary와 ttsText가 같은 문자열이면 한 번만 보여준다", () => {
    const result = selectSessionSummaryFeedback({ summary: "잘했어요.", ttsText: "잘했어요." });

    expect(result.lines).toEqual(["잘했어요."]);
    expect(result.speech).toBe("잘했어요.");
  });

  it("빈 문자열은 없는 것으로 취급한다", () => {
    const result = selectSessionSummaryFeedback({ summary: "", ttsText: "" });

    expect(result.lines).toEqual([]);
    expect(result.speech).toBeNull();
  });
});
