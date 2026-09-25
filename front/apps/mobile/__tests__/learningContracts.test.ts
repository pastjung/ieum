import {
  problemsResponseSchema,
  completionResponseSchema,
  completionRequestSchema,
  attemptAnalysisRequestSchema,
  attemptAnalysisResponseSchema,
  MAX_INPUT_CELLS,
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

const serverAttempt = {
  problemSessionId: 101,
  problemId: 7,
  attemptId: 55,
  correct: false,
  attemptedAt: "2026-09-25T02:00:00Z",
  cells: [
    {
      cellIndex: 1,
      expectedPattern: 5,
      inputPattern: 1,
      diffMask: 4,
      missingMask: 4,
      extraMask: 0,
      missingDots: [3],
      extraDots: [],
    },
  ],
  feedback: { tts: "첫소리 이응은 점자로 적지 않아요.", detail: "제13항" },
};

describe("문항별 분석 계약", () => {
  it("서버가 보내는 모양을 그대로 받는다", () => {
    const parsed = attemptAnalysisResponseSchema.parse(serverAttempt);
    expect(parsed.feedback.tts).toContain("이응");
    expect(parsed.cells[0].missingDots).toEqual([3]);
  });

  it("피드백이 없으면 거부한다", () => {
    // 낭독할 문장이 없으면 오답 화면이 아무 말도 못 한다 — 화면까지 흘려보내지 않는다
    const { feedback: _omit, ...rest } = serverAttempt;
    expect(() => attemptAnalysisResponseSchema.parse(rest)).toThrow();
  });

  it("요청은 12칸을 넘길 수 없다", () => {
    // FastAPI가 input_patterns를 12개까지만 받는다(router.py:121).
    // 여기서 막지 않으면 사용자는 이유를 알 수 없는 실패를 본다
    const thirteen = Array.from({ length: 13 }, () => 1);
    expect(() =>
      attemptAnalysisRequestSchema.parse({ problemSessionId: 101, inputPatterns: thirteen }),
    ).toThrow();
    expect(MAX_INPUT_CELLS).toBe(12);
  });

  it("요청 식별자로 0을 거부한다", () => {
    expect(() =>
      attemptAnalysisRequestSchema.parse({ problemSessionId: 0, inputPatterns: [1] }),
    ).toThrow();
  });

  it("완료 응답의 세션 요약을 읽는다", () => {
    const parsed = completionResponseSchema.parse({
      sessionId: 12,
      completedAt: "2026-09-25T02:00:00Z",
      totalProblemCount: 1,
      correctProblemCount: 0,
      problemResults: [],
      summary: {
        summary: "받침에서 자주 틀렸어요.",
        ttsText: "받침 규칙을 더 연습해요.",
        errorTypeCounts: { missing_dot: 3 },
        weakSkills: [],
      },
    });
    expect(parsed.summary?.ttsText).toContain("받침");
  });
});
