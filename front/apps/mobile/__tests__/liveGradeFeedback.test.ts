import type { LiveAnswer } from "@ieum/contracts";
import { liveGradeFeedback } from "@/services/liveGradeFeedback";

function result(isCorrect: boolean, cells: LiveAnswer["cells"]): LiveAnswer {
  return {
    attemptId: "900",
    lectureId: "42",
    problemId: "26578",
    isCorrect,
    score: isCorrect ? 100 : 0,
    attemptedAt: "2026-09-27T01:00:00.000Z",
    cells,
  };
}

describe("liveGradeFeedback", () => {
  it("정답이면 헤드라인 한 단계만 읽는다", () => {
    const feedback = liveGradeFeedback(
      result(true, [{ cellIndex: 1, inputPattern: 8, expectedPattern: 8, diffMask: 0, isCorrect: true }]),
    );

    expect(feedback).toEqual({
      correct: true,
      headline: "정답이에요!",
      steps: [{ detail: "", speech: "정답이에요!", compare: null }],
    });
  });

  it("빠진 점을 칸 위치와 함께 설명하고 그 칸을 나란히 그린다", () => {
    const feedback = liveGradeFeedback(
      result(false, [{ cellIndex: 1, inputPattern: 0, expectedPattern: 8, diffMask: 8, isCorrect: false }]),
    );

    expect(feedback.correct).toBe(false);
    expect(feedback.headline).toBe("오답이에요.");
    expect(feedback.steps).toEqual([
      {
        detail: "1칸이 틀렸어요. 첫 번째 칸의 정답은 4번이에요. 4번 점이 빠졌어요.",
        speech: "오답이에요. 1칸이 틀렸어요. 첫 번째 칸의 정답은 4번이에요. 4번 점이 빠졌어요.",
        compare: { expected: 8, actual: 0, missingDots: [4], extraDots: [], index: 0 },
      },
    ]);
  });

  it("칸 수가 다르면 넘친 칸을 빈 칸 정답과 더 눌린 점으로 설명한다", () => {
    const feedback = liveGradeFeedback(
      result(false, [
        { cellIndex: 1, inputPattern: 8, expectedPattern: 8, diffMask: 0, isCorrect: true },
        { cellIndex: 2, inputPattern: 1, expectedPattern: 0, diffMask: 1, isCorrect: false },
      ]),
    );

    expect(feedback.steps).toHaveLength(1);
    expect(feedback.steps[0]!.detail).toBe("1칸이 틀렸어요. 두 번째 칸의 정답은 빈 칸이에요. 1번 점이 더 눌렸어요.");
    expect(feedback.steps[0]!.compare).toEqual({ expected: 0, actual: 1, missingDots: [], extraDots: [1], index: 1 });
  });

  it("틀린 칸이 여럿이면 칸마다 한 단계다", () => {
    const feedback = liveGradeFeedback(
      result(false, [
        { cellIndex: 1, inputPattern: 1, expectedPattern: 3, diffMask: 2, isCorrect: false },
        { cellIndex: 2, inputPattern: 5, expectedPattern: 1, diffMask: 4, isCorrect: false },
      ]),
    );

    expect(feedback.steps.map((s) => s.compare?.index)).toEqual([0, 1]);
    expect(feedback.steps[0]!.speech.startsWith("오답이에요. 모두 2칸이 틀렸어요.")).toBe(true);
    expect(feedback.steps[1]!.speech).toBe("두 번째 칸의 정답은 1번이에요. 3번 점이 더 눌렸어요.");
  });
});
