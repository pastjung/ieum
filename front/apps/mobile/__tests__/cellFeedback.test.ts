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

/** 모든 단계의 낭독을 이어 붙인다 — "세션 전체에서 이 말이 나오는가"를 볼 때 쓴다 */
function allSpeech(steps: { speech: string }[]): string {
  return steps.map((s) => s.speech).join(" ");
}

describe("셀 진단을 칸별 단계로", () => {
  it("정답이면 설명을 붙이지 않는다", () => {
    // `correct`(백엔드가 DB 시드 셀로 채점)와 `feedback.tts`(AI가 target_text를 규칙
    // 변환해 다시 채점)는 서로 다른 채점에서 온다. 갈리면 "정답이에요! 3번 점이
    // 빠졌어요"가 나온다 — 정답에 오답 설명이 붙는 것보다 설명이 없는 편이 낫다
    const r = describeCells([cell(1, 5, 5, [], [])], true);

    expect(r.headline).toBe("정답이에요!");
    expect(r.steps).toHaveLength(1);
    expect(r.steps[0].detail).toBe("");
    expect(r.steps[0].speech).toBe("정답이에요!");
    expect(r.steps[0].cellIndex).toBeNull();
  });

  it("정답이어도 단계가 하나는 있다 — 화면이 언제나 steps[0]을 읽는다", () => {
    const r = describeCells([cell(1, 5, 5, [], [])], true);
    expect(r.steps.length).toBeGreaterThanOrEqual(1);
  });

  it("틀린 칸의 정답 점과 틀린 점을 모두 말한다", () => {
    // 1번 칸: 정답은 1,3번인데 1번만 눌러 3번이 빠졌다
    const r = describeCells([cell(1, 0b000101, 0b000001, [3], [])], false);

    expect(r.steps[0].speech).toContain("첫 번째 칸의 정답은 1번, 3번이에요");
    expect(r.steps[0].speech).toContain("3번 점이 빠졌어요");
  });

  it("더 눌린 점도 말한다", () => {
    const r = describeCells([cell(1, 0b000001, 0b000101, [], [3])], false);
    expect(r.steps[0].speech).toContain("3번 점이 더 눌렸어요");
  });

  it("틀린 칸마다 단계를 하나씩 만든다", () => {
    const r = describeCells(
      [cell(1, 0b000101, 0b000001, [3], []), cell(2, 0b000010, 0b000110, [], [3])],
      false,
    );

    expect(r.steps).toHaveLength(2);
    expect(r.steps[0].cellIndex).toBe(1);
    expect(r.steps[1].cellIndex).toBe(2);
  });

  it("각 단계는 자기 칸만 말한다 — 다음 칸 이야기가 섞이지 않는다", () => {
    // 사용자가 "다음 점자"로 넘어가며 한 칸씩 듣는다. 한 단계에 두 칸이 섞이면
    // 지금 화면에 그려진 칸과 들리는 설명이 어긋난다
    const r = describeCells(
      [cell(1, 0b000101, 0b000001, [3], []), cell(2, 0b000010, 0b000110, [], [3])],
      false,
    );

    expect(r.steps[0].speech).toContain("첫 번째 칸");
    expect(r.steps[0].speech).not.toContain("두 번째 칸");
    expect(r.steps[1].speech).toContain("두 번째 칸");
    expect(r.steps[1].speech).not.toContain("첫 번째 칸");
  });

  it("맞은 칸은 단계를 만들지 않는다", () => {
    const r = describeCells(
      [cell(1, 5, 5, [], []), cell(2, 0b000010, 0b000110, [], [3])],
      false,
    );

    expect(r.steps).toHaveLength(1);
    expect(r.steps[0].cellIndex).toBe(2);
    expect(r.steps[0].speech).toContain("두 번째 칸");
  });

  it("틀린 칸이 많아도 줄이지 않고 전부 단계로 만든다", () => {
    // 예전에는 앞 세 칸만 말하고 나머지를 "그 외 N칸"으로 줄였다. 한 번에 다 읽던
    // 시절의 타협이었고, 이제는 칸마다 화면과 낭독이 따로 있으므로 줄일 이유가 없다 —
    // 줄이면 넷째 칸부터는 무엇이 틀렸는지 영영 알 수 없다
    const cells = [1, 2, 3, 4, 5].map((i) => cell(i, 0b000101, 0b000001, [3], []));
    const r = describeCells(cells, false);

    expect(r.steps).toHaveLength(5);
    expect(r.steps[3].speech).toContain("네 번째 칸");
    expect(r.steps[4].speech).toContain("다섯 번째 칸");
    expect(allSpeech(r.steps)).not.toContain("그 외");
  });

  it("몇 칸이 틀렸는지는 첫 단계에서 한 번만 말한다", () => {
    const cells = [1, 2, 3].map((i) => cell(i, 0b000101, 0b000001, [3], []));
    const r = describeCells(cells, false);

    expect(r.steps[0].speech).toContain("모두 3칸이 틀렸어요");
    expect(r.steps[1].speech).not.toContain("틀렸어요");
    expect(r.steps[2].speech).not.toContain("틀렸어요");
  });

  it("헤드라인은 첫 단계에서만 낭독에 들어간다", () => {
    // 화면 헤더("오답이에요.")는 계속 떠 있다. 칸을 넘길 때마다 다시 읽으면 귀로는
    // 매번 새로 틀린 것처럼 들린다
    const cells = [1, 2].map((i) => cell(i, 0b000101, 0b000001, [3], []));
    const r = describeCells(cells, false);

    expect(r.steps[0].speech.startsWith("오답이에요.")).toBe(true);
    expect(r.steps[1].speech).not.toContain("오답이에요");
  });

  it("틀린 칸이 하나면 그 한 단계에 개요가 붙는다", () => {
    const r = describeCells([cell(1, 0b000101, 0b000001, [3], [])], false);

    expect(r.steps).toHaveLength(1);
    expect(r.steps[0].speech).toContain("1칸이 틀렸어요");
    expect(r.steps[0].speech).not.toContain("모두 1칸");
    expect(r.steps[0].speech).not.toMatch(/\s{2,}/);
  });

  it("단계마다 화면 문구와 낭독 문구가 같은 내용이다", () => {
    // 첫 단계만 헤드라인을 앞에 붙이고, 그 뒤는 detail 그대로다
    const cells = [1, 2].map((i) => cell(i, 0b000101, 0b000001, [3], []));
    const r = describeCells(cells, false);

    expect(r.steps[0].speech).toBe(`${r.headline} ${r.steps[0].detail}`.trim());
    expect(r.steps[1].speech).toBe(r.steps[1].detail);
  });

  it("빠진 점과 더 눌린 점이 동시에 있으면 둘 다 말한다", () => {
    // 정답은 1·3번인데 1·2번을 눌렀다 — 가장 흔한 오답 형태. missing=[3], extra=[2]
    const r = describeCells([cell(1, 0b000101, 0b000011, [3], [2])], false);

    expect(r.steps[0].speech).toContain("3번 점이 빠지고 2번 점이 더 눌렸어요");
  });

  it("정답 칸이 빈 칸이면 '비어 있어야 한다'는 것을 말한다", () => {
    // 문장 단계 시드는 빈 칸(expectedPattern === 0)이 섞일 수 있다
    const r = describeCells([cell(1, 0, 0b000010, [], [2])], false);

    expect(r.steps[0].speech).toContain("정답은 빈 칸이에요");
    expect(r.steps[0].speech).toContain("2번 점이 더 눌렸어요");
  });

  it("오답인데 틀린 칸을 찾지 못해도 자기모순 문장을 만들지 않는다", () => {
    // 정상 서버 응답에서는 없는 상태지만 방어적으로 처리한다
    const r = describeCells([cell(1, 5, 5, [], [])], false);

    expect(r.steps).toHaveLength(1);
    expect(r.steps[0].cellIndex).toBeNull();
    expect(allSpeech(r.steps)).not.toContain("0칸");
    expect(r.steps[0].speech).not.toMatch(/\s{2,}/);
    expect(r.steps[0].speech).toBe(`${r.headline} ${r.steps[0].detail}`.trim());
  });
});
