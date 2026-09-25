import { describeCells } from "@/services/cellFeedback";
import type { AttemptCellAnalysis } from "@ieum/contracts";

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

describe("셀 진단을 점 수준 문장으로", () => {
  it("정답이면 규칙 설명을 붙이지 않는다", () => {
    // `correct`(백엔드가 DB 시드 셀로 채점)와 `feedback.tts`(AI가 target_text를 규칙
    // 변환해 다시 채점)는 서로 다른 채점에서 온다. 갈리면 "정답이에요! 3번 점이
    // 빠졌어요"가 나온다 — 정답에 오답 설명이 붙는 것보다 설명이 없는 편이 낫다
    const r = describeCells([cell(1, 5, 5, [], [])], true, "3번 점이 빠졌어요.");

    expect(r.headline).toBe("정답이에요!");
    expect(r.detail).toBe("");
    expect(r.speech).toBe("정답이에요!");
    expect(r.speech).not.toContain("3번 점이 빠졌어요.");
  });

  it("정답 화면의 낭독 문구가 화면에 그리는 문구와 같다", () => {
    // 규칙 설명을 낭독에서만 빼면 화면과 낭독이 갈라진다 — 둘은 같은 곳에서 사라져야 한다
    const r = describeCells([cell(1, 5, 5, [], [])], true, "제13항");

    expect(r.speech).toBe(`${r.headline} ${r.detail}`.trim());
  });

  it("틀린 칸의 정답 점과 틀린 점을 모두 말한다", () => {
    // 1번 칸: 정답은 1,3번인데 1번만 눌러 3번이 빠졌다
    const r = describeCells([cell(1, 0b000101, 0b000001, [3], [])], false, "제13항");

    expect(r.speech).toContain("첫 번째 칸의 정답은 1번, 3번이에요");
    expect(r.speech).toContain("3번 점이 빠졌어요");
  });

  it("더 눌린 점도 말한다", () => {
    const r = describeCells([cell(1, 0b000001, 0b000101, [], [3])], false, "제13항");
    expect(r.speech).toContain("3번 점이 더 눌렸어요");
  });

  it("여러 칸이 틀리면 칸마다 말한다", () => {
    const r = describeCells(
      [cell(1, 0b000101, 0b000001, [3], []), cell(2, 0b000010, 0b000110, [], [3])],
      false,
      "제13항",
    );
    expect(r.speech).toContain("첫 번째 칸");
    expect(r.speech).toContain("두 번째 칸");
  });

  it("맞은 칸은 말하지 않는다", () => {
    const r = describeCells(
      [cell(1, 5, 5, [], []), cell(2, 0b000010, 0b000110, [], [3])],
      false,
      "제13항",
    );
    expect(r.speech).not.toContain("첫 번째 칸");
    expect(r.speech).toContain("두 번째 칸");
  });

  it("틀린 칸이 많으면 앞 세 칸만 자세히 말하고 나머지는 개수로 줄인다", () => {
    // 낭독이 지나치게 길어지면 사용자가 끝까지 듣지 못한다. 다만 몇 칸이 틀렸는지는 항상 말한다
    const cells = [1, 2, 3, 4, 5].map((i) => cell(i, 0b000101, 0b000001, [3], []));
    const r = describeCells(cells, false, "제13항");

    expect(r.speech).toContain("모두 5칸이 틀렸어요");
    expect(r.speech).toContain("세 번째 칸");
    expect(r.speech).not.toContain("네 번째 칸");
    expect(r.speech).toContain("그 외 2칸");
  });

  it("규칙 설명이 비어 있어도 문장이 만들어진다", () => {
    // 서버가 규칙을 못 찾는 경우가 있다. 그때도 점 수준 안내는 나와야 한다
    const r = describeCells([cell(1, 0b000101, 0b000001, [3], [])], false, "");
    expect(r.speech).toContain("첫 번째 칸");
    expect(r.speech.trim()).not.toBe("");
  });

  it("화면 문구와 낭독 문구가 같은 내용이다", () => {
    const r = describeCells([cell(1, 0b000101, 0b000001, [3], [])], false, "제13항");
    expect(r.speech).toBe(`${r.headline} ${r.detail}`.trim());
  });

  it("빠진 점과 더 눌린 점이 동시에 있으면 둘 다 말한다", () => {
    // 정답은 1·3번인데 1·2번을 눌렀다 — 가장 흔한 오답 형태. missing=[3], extra=[2]
    const r = describeCells([cell(1, 0b000101, 0b000011, [3], [2])], false, "제13항");

    expect(r.speech).toContain("3번 점이 빠지고 2번 점이 더 눌렸어요");
  });

  it("정답 칸이 빈 칸이면 '비어 있어야 한다'는 것을 말한다", () => {
    // 문장 단계 시드는 빈 칸(expectedPattern === 0)이 섞일 수 있다
    const r = describeCells([cell(1, 0, 0b000010, [], [2])], false, "제13항");

    expect(r.speech).toContain("정답은 빈 칸이에요");
    expect(r.speech).toContain("2번 점이 더 눌렸어요");
  });

  it("틀린 칸이 하나면 '모두'를 붙이지 않는다", () => {
    const r = describeCells([cell(1, 0b000101, 0b000001, [3], [])], false, "제13항");
    expect(r.speech).toContain("1칸이 틀렸어요");
    expect(r.speech).not.toContain("모두 1칸");
  });

  it("오답인데 틀린 칸을 찾지 못해도 자기모순 문장을 만들지 않는다", () => {
    // 정상 서버 응답에서는 없는 상태지만 방어적으로 처리한다
    const r = describeCells([cell(1, 5, 5, [], [])], false, "제13항");

    expect(r.speech).not.toContain("0칸");
    expect(r.speech).not.toMatch(/\s{2,}/);
    expect(r.speech).toBe(`${r.headline} ${r.detail}`.trim());
  });
});
