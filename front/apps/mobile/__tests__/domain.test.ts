import {
  cellToDots,
  cellToUnicode,
  describeDiff,
  dotsToCell,
  gradeAnswer,
} from "@ieum/domain";

describe("점자 셀 변환", () => {
  it("점 목록을 6비트 셀로 바꾼다 (dot1=bit0)", () => {
    expect(dotsToCell([1])).toBe(0b000001);
    expect(dotsToCell([1, 2, 6])).toBe(0b100011);
    expect(dotsToCell([])).toBe(0);
  });

  it("셀을 점 목록으로 되돌린다", () => {
    expect(cellToDots(0b100011)).toEqual([1, 2, 6]);
  });

  it("유니코드 점자 문자로 바꾼다 (U+2800 오프셋)", () => {
    expect(cellToUnicode(0)).toBe("⠀");
    expect(cellToUnicode(0b000001)).toBe("⠁");
  });
});

describe("채점", () => {
  const expected = [dotsToCell([2, 3, 4, 5])];

  it("정답을 판정한다", () => {
    const r = gradeAnswer(expected, [dotsToCell([2, 3, 4, 5])]);
    expect(r.correct).toBe(true);
    expect(r.firstDiff).toBeNull();
  });

  it("빠진 점을 찾아낸다", () => {
    const r = gradeAnswer(expected, [dotsToCell([2, 4, 5])]);
    expect(r.correct).toBe(false);
    expect(r.firstDiff?.missing).toEqual([3]);
    expect(r.firstDiff?.extra).toEqual([]);
  });

  it("더 누른 점을 찾아낸다", () => {
    const r = gradeAnswer(expected, [dotsToCell([1, 2, 3, 4, 5])]);
    expect(r.firstDiff?.extra).toEqual([1]);
  });

  it("입력 칸이 모자라면 오답이다", () => {
    const r = gradeAnswer(
      [dotsToCell([1]), dotsToCell([2])],
      [dotsToCell([1])],
    );
    expect(r.correct).toBe(false);
    expect(r.firstDiff?.index).toBe(1);
  });
});

describe("오답 차이 설명 (V16 문구)", () => {
  it("빠진 점 문구를 만든다", () => {
    const expected5 = [
      dotsToCell([1]), dotsToCell([1]), dotsToCell([1]), dotsToCell([1]),
      dotsToCell([2, 3, 4, 5]),
    ];
    const actual = [
      dotsToCell([1]), dotsToCell([1]), dotsToCell([1]), dotsToCell([1]),
      dotsToCell([2, 4, 5]),
    ];
    const { headline, detail } = describeDiff(gradeAnswer(expected5, actual));
    expect(headline).toBe("3번 점이 빠졌어요");
    expect(detail).toContain("다섯 번째 칸에서요");
    expect(detail).toContain("네 칸은 맞았어요");
  });
});
