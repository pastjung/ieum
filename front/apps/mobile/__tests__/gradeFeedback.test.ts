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
    expect(result?.compareCell).toBeNull();
  });

  it("서버 분석의 오답 헤드라인·상세는 describeCells와 같은 문자열이다", () => {
    const cells = [cell(1, 0b000101, 0b000001, [3], [])];
    const result = selectGradeFeedback(
      analysis({ correct: false, cells, feedback: { tts: "제13항", detail: "" } }),
      null,
    );

    expect(result?.headline).toBe("오답이에요.");
    expect(result?.detail).toContain("첫 번째 칸의 정답은 1번, 3번이에요");
    expect(result?.speech).toBe(`${result?.headline} ${result?.detail}`.trim());
  });

  it("서버 분석에서 첫 번째 틀린 칸을 compareCell로 뽑는다", () => {
    const cells = [
      cell(1, 5, 5, [], []), // 맞음 — 건너뛴다
      cell(2, 0b000101, 0b000001, [3], []), // 첫 오답
      cell(3, 0b000010, 0b000110, [], [3]), // 두 번째 오답 — 그려지지 않는다
    ];
    const result = selectGradeFeedback(
      analysis({ correct: false, cells, feedback: { tts: "", detail: "" } }),
      null,
    );

    expect(result?.compareCell).toEqual({
      expected: 0b000101,
      actual: 0b000001,
      missingDots: [3],
      extraDots: [],
      index: 1, // cellIndex(2) - 1
    });
  });

  it("범위 밖 점 번호(0, 7 등)는 compareCell에서 걸러내고 나머지는 남긴다", () => {
    // 계약은 missingDots/extraDots를 1~6으로 강제하지 않는다 — 서버가 잘못된 값을 보내도
    // 응답 전체를 버리지 않고(재시도 소진 방지), 화면에 쓰는 자리에서만 방어한다
    const cells = [cell(1, 0b000101, 0b000001, [3, 7, 0], [-1, 2])];
    const result = selectGradeFeedback(
      analysis({ correct: false, cells, feedback: { tts: "", detail: "" } }),
      null,
    );

    expect(result?.compareCell?.missingDots).toEqual([3]);
    expect(result?.compareCell?.extraDots).toEqual([2]);
  });

  it("서버가 오답인데 틀린 칸을 못 찾으면(방어적) compareCell이 null이다", () => {
    const cells = [cell(1, 5, 5, [], [])];
    const result = selectGradeFeedback(
      analysis({ correct: false, cells, feedback: { tts: "", detail: "" } }),
      null,
    );

    expect(result?.compareCell).toBeNull();
  });

  it("서버 분석이 없고 로컬 결과도 없으면 null이다 (실시간 수업 진입 가드)", () => {
    expect(selectGradeFeedback(null, null)).toBeNull();
  });

  it("서버 분석이 없으면 로컬 정답 경로를 그대로 쓴다", () => {
    const local = gradeAnswer([5], [5]);
    const result = selectGradeFeedback(null, local);

    expect(result?.correct).toBe(true);
    expect(result?.speech).toBe("정답이에요!");
    expect(result?.compareCell).toBeNull();
  });

  it("서버 분석이 없으면 로컬 오답 경로(기존 V16 낭독 문구)를 그대로 쓴다", () => {
    // 실시간 수업이 쓰는 경로 — 문자 하나도 바뀌면 안 된다
    const local = gradeAnswer([0b000101], [0b000001]);
    const result = selectGradeFeedback(null, local);

    expect(result?.correct).toBe(false);
    expect(result?.speech).toBe(`오답이에요. ${result?.headline}. ${result?.detail}`);
    expect(result?.compareCell).toEqual({
      expected: 0b000101,
      actual: 0b000001,
      missingDots: [3],
      extraDots: [],
      index: 0,
    });
  });
});
