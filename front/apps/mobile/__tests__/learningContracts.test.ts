import {
  problemsResponseSchema,
  completionResponseSchema,
  completionRequestSchema,
} from "@ieum/contracts";

const serverProblems = {
  sessionId: 12,
  problems: [
    {
      problemSessionId: 101,
      problemId: 7,
      sequence: 1,
      targetText: "가방",
      expectedPatterns: [1, 32],
      completed: false,
    },
  ],
};

describe("학습 세션 응답 계약", () => {
  it("백엔드가 보내는 모양을 그대로 받는다", () => {
    const parsed = problemsResponseSchema.parse(serverProblems);
    expect(parsed.problems[0].problemSessionId).toBe(101);
  });

  it("problemSessionId가 없으면 거부한다", () => {
    // 이 값이 없으면 완료 제출을 만들 수 없다. 화면까지 흘려보내면 제출 직전에 터진다
    const { problemSessionId: _omit, ...rest } = serverProblems.problems[0];
    expect(() =>
      problemsResponseSchema.parse({ sessionId: 12, problems: [rest] }),
    ).toThrow();
  });

  it("6비트 범위를 벗어난 패턴을 거부한다", () => {
    expect(() =>
      problemsResponseSchema.parse({
        sessionId: 12,
        problems: [{ ...serverProblems.problems[0], expectedPatterns: [64] }],
      }),
    ).toThrow();
  });

  it("문항이 없는 세션을 거부한다", () => {
    expect(() => problemsResponseSchema.parse({ sessionId: 12, problems: [] })).toThrow();
  });
});

describe("완료 제출 계약", () => {
  it("백엔드 검증과 같은 범위를 강제한다", () => {
    // 백엔드가 @NotEmpty @Min(0) @Max(63)으로 막는다. 여기서 먼저 막아 네트워크까지 가지 않게 한다
    expect(() =>
      completionRequestSchema.parse({ attempts: [] }),
    ).toThrow();
    expect(() =>
      completionRequestSchema.parse({
        attempts: [{ problemSessionId: 101, inputPatterns: [64] }],
      }),
    ).toThrow();
    expect(() =>
      completionRequestSchema.parse({
        attempts: [{ problemSessionId: 101, inputPatterns: [1, 32] }],
      }),
    ).not.toThrow();
  });

  it("요청 식별자로 0을 거부한다", () => {
    // 백엔드가 @Positive로 막는다. 여기서 통과시키면 사용자는 이유를 알 수 없는 422를 본다
    expect(() =>
      completionRequestSchema.parse({
        attempts: [{ problemSessionId: 0, inputPatterns: [1] }],
      }),
    ).toThrow();
  });

  it("완료 응답의 셀 진단을 읽는다", () => {
    const parsed = completionResponseSchema.parse({
      sessionId: 12,
      completedAt: "2026-09-24T11:00:00Z",
      totalProblemCount: 1,
      correctProblemCount: 0,
      problemResults: [
        {
          problemSessionId: 101,
          problemId: 7,
          attemptId: 5,
          correct: false,
          cells: [
            {
              cellIndex: 1,
              expectedPattern: 1,
              inputPattern: 3,
              diffMask: 2,
              missingMask: 0,
              extraMask: 2,
              missingDots: [],
              extraDots: [2],
            },
          ],
        },
      ],
    });
    expect(parsed.problemResults[0].cells[0].extraDots).toEqual([2]);
  });
});
