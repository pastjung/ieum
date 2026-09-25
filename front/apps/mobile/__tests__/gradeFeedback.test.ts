import { selectGradeFeedback } from "@/services/gradeFeedback";
import { gradeAnswer } from "@ieum/domain";
import type { AttemptAnalysisResponse, AttemptCellAnalysis } from "@ieum/contracts";

function cell(
  cellIndex: number,
  expectedPattern: number,
  inputPattern: number,
  missingDots: number[],
  extraDots: number[],
): AttemptCellAnalysis {
  return {
    cellIndex,
    expectedPattern,
    inputPattern,
    diffMask: expectedPattern ^ inputPattern,
    missingMask: 0,
    extraMask: 0,
    missingDots,
    extraDots,
  };
}

function analysis(overrides: Partial<AttemptAnalysisResponse>): AttemptAnalysisResponse {
  return {
    problemSessionId: 1,
    problemId: "p1",
    attemptId: "a1",
    correct: true,
    attemptedAt: "2026-09-25T00:00:00Z",
    cells: [],
    feedback: { tts: "", detail: "" },
    ...overrides,
  };
}

describe("서버 분석과 로컬 결과 중 무엇을 쓸지 고른다", () => {
  it("서버 분석이 있으면 로컬 결과가 있어도 서버를 우선한다", () => {
    // 로컬은 오답, 서버는 정답 — 서버 판정이 이겨야 한다 (설계: 정답 여부는 서버가 정한다)
    const local = gradeAnswer([5], [3]);
    const result = selectGradeFeedback(analysis({ correct: true, cells: [] }), local);

    expect(result?.correct).toBe(true);
    expect(result?.steps).toHaveLength(1);
    expect(result?.steps[0].compare).toBeNull();
  });

  it("서버 분석의 오답 헤드라인·상세는 describeCells와 같은 문자열이다", () => {
    const cells = [cell(1, 0b000101, 0b000001, [3], [])];
    const result = selectGradeFeedback(
      analysis({ correct: false, cells, feedback: { tts: "제13항", detail: "" } }),
      null,
    );

    expect(result?.headline).toBe("오답이에요.");
    expect(result?.steps[0].detail).toContain("첫 번째 칸의 정답은 1번, 3번이에요");
    expect(result?.steps[0].speech).toBe(`${result?.headline} ${result?.steps[0].detail}`.trim());
  });

  it("틀린 칸마다 단계를 만들고 각 단계가 자기 칸을 그린다", () => {
    const cells = [
      cell(1, 5, 5, [], []), // 맞음 — 단계를 만들지 않는다
      cell(2, 0b000101, 0b000001, [3], []), // 첫 오답
      cell(3, 0b000010, 0b000110, [], [3]), // 두 번째 오답 — 예전에는 그려지지 않았다
    ];
    const result = selectGradeFeedback(
      analysis({ correct: false, cells, feedback: { tts: "", detail: "" } }),
      null,
    );

    expect(result?.steps).toHaveLength(2);
    expect(result?.steps[0].compare).toEqual({
      expected: 0b000101,
      actual: 0b000001,
      missingDots: [3],
      extraDots: [],
      index: 1, // cellIndex(2) - 1
    });
    expect(result?.steps[1].compare).toEqual({
      expected: 0b000010,
      actual: 0b000110,
      missingDots: [],
      extraDots: [3],
      index: 2, // cellIndex(3) - 1
    });
  });

  it("그리는 칸과 읽는 문장이 같은 칸을 가리킨다", () => {
    // 이 둘이 어긋나면 화면에는 세 번째 칸이 떠 있는데 "두 번째 칸" 설명이 들린다
    const cells = [
      cell(1, 0b000101, 0b000001, [3], []),
      cell(2, 0b000010, 0b000110, [], [3]),
    ];
    const result = selectGradeFeedback(
      analysis({ correct: false, cells, feedback: { tts: "", detail: "" } }),
      null,
    );

    expect(result?.steps[0].compare?.index).toBe(0);
    expect(result?.steps[0].speech).toContain("첫 번째 칸");
    expect(result?.steps[1].compare?.index).toBe(1);
    expect(result?.steps[1].speech).toContain("두 번째 칸");
  });

  it("규칙 설명은 feedback.detail 에서 가져온다 (tts 가 아니다)", () => {
    // AI는 두 문장을 따로 만든다 (`apps/ai` 의 `session_services._detail`):
    //   tts    — AI가 자체 채점한 점 수준 서술. 우리 XOR 설명과 겹치고, 백엔드 채점과
    //            갈릴 수 있어 오답 화면에 쓰면 두 채점이 한 화면에서 싸운다
    //   detail — 규칙 설명("첫소리 이응은 점자로 적지 않습니다"). 학습자가 왜 틀렸는지
    //            아는 유일한 경로다
    // 앱은 오랫동안 이 둘을 뒤바꿔 써서 규칙 설명이 화면에 나온 적이 없었다
    const cells = [cell(1, 0b000101, 0b000001, [3], [])];
    const result = selectGradeFeedback(
      analysis({
        correct: false,
        cells,
        feedback: {
          tts: "AI가 다시 채점한 점 수준 서술",
          detail: "첫소리 이응은 점자로 적지 않습니다.",
        },
      }),
      null,
    );

    expect(result?.steps[0].speech).toContain("첫소리 이응은 점자로 적지 않습니다.");
    expect(result?.steps[0].speech).not.toContain("AI가 다시 채점한 점 수준 서술");
  });

  it("정답 화면에는 규칙 설명을 붙이지 않는다", () => {
    // `correct`(백엔드가 DB 시드로 채점)와 AI 문장은 서로 다른 채점에서 온다 —
    // 갈리면 "정답이에요!" 아래에 오답 설명이 붙는다
    const result = selectGradeFeedback(
      analysis({
        correct: true,
        cells: [cell(1, 5, 5, [], [])],
        feedback: { tts: "점 수준 서술", detail: "규칙 설명" },
      }),
      null,
    );

    expect(result?.steps[0].speech).toBe("정답이에요!");
  });

  it("범위 밖 점 번호(0, 7 등)는 걸러내고 나머지는 남긴다", () => {
    // 계약은 missingDots/extraDots를 1~6으로 강제하지 않는다 — 서버가 잘못된 값을 보내도
    // 응답 전체를 버리지 않고(재시도 소진 방지), 화면에 쓰는 자리에서만 방어한다
    const cells = [cell(1, 0b000101, 0b000001, [3, 7, 0], [-1, 2])];
    const result = selectGradeFeedback(
      analysis({ correct: false, cells, feedback: { tts: "", detail: "" } }),
      null,
    );

    expect(result?.steps[0].compare?.missingDots).toEqual([3]);
    expect(result?.steps[0].compare?.extraDots).toEqual([2]);
  });

  it("서버가 오답인데 틀린 칸을 못 찾으면(방어적) 그릴 칸 없는 단계 하나를 준다", () => {
    const cells = [cell(1, 5, 5, [], [])];
    const result = selectGradeFeedback(
      analysis({ correct: false, cells, feedback: { tts: "", detail: "" } }),
      null,
    );

    expect(result?.steps).toHaveLength(1);
    expect(result?.steps[0].compare).toBeNull();
  });

  it("서버 분석이 없고 로컬 결과도 없으면 null이다 (실시간 수업 진입 가드)", () => {
    expect(selectGradeFeedback(null, null)).toBeNull();
  });

  it("서버 분석이 없으면 로컬 정답 경로를 그대로 쓴다", () => {
    const local = gradeAnswer([5], [5]);
    const result = selectGradeFeedback(null, local);

    expect(result?.correct).toBe(true);
    expect(result?.steps).toHaveLength(1);
    expect(result?.steps[0].speech).toBe("정답이에요!");
    expect(result?.steps[0].compare).toBeNull();
  });

  it("서버 분석이 없으면 로컬 오답 경로(기존 V16 낭독 문구)를 그대로 쓴다", () => {
    // 실시간 수업이 쓰는 경로 — 문자 하나도 바뀌면 안 된다
    const local = gradeAnswer([0b000101], [0b000001]);
    const result = selectGradeFeedback(null, local);

    expect(result?.correct).toBe(false);
    expect(result?.steps).toHaveLength(1);
    expect(result?.steps[0].speech).toBe(
      `오답이에요. ${result?.headline}. ${result?.steps[0].detail}`,
    );
    expect(result?.steps[0].compare).toEqual({
      expected: 0b000101,
      actual: 0b000001,
      missingDots: [3],
      extraDots: [],
      index: 0,
    });
  });

  it("로컬 경로는 칸이 여러 개여도 단계를 하나만 만든다", () => {
    // 로컬 채점(`GradeResult`)은 firstDiff 하나만 알고 있다 — 실시간 수업은 서버 분석이
    // 없으므로 이 경로가 그대로 남는다. 단계 순회는 서버 분석이 있을 때의 기능이다
    const local = gradeAnswer([0b000101, 0b000010], [0b000001, 0b000110]);
    const result = selectGradeFeedback(null, local);

    expect(result?.steps).toHaveLength(1);
  });
});
