import {
  cellToDots,
  cellToUnicode,
  describeDiff,
  dotsToCell,
  gradeAnswer,
  type Cell,
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

describe("여러 칸 오답 (V17 문구)", () => {
  // 8칸짜리 기대 답안 (단어 단계 최대 칸 수)
  const expected8: Cell[] = [1, 2, 3, 4, 5, 6, 1, 2].map(
    (n) => dotsToCell([n as 1 | 2 | 3 | 4 | 5 | 6]),
  );

  it("두 칸 이상 틀렸을 때 모든 틀린 칸이 diffs에 담긴다", () => {
    const actual = expected8.slice();
    // 0, 2번째 칸을 다른 점으로 바꿔 틀리게 만든다
    actual[0] = dotsToCell([6]);
    actual[2] = dotsToCell([5]);
    const r = gradeAnswer(expected8, actual);
    expect(r.diffs.map((d) => d.index)).toEqual([0, 2]);
    expect(r.firstDiff).toEqual(r.diffs[0]);
    // 판정 규칙은 그대로: matched는 나머지 6칸
    expect(r.matched).toBe(6);
    expect(r.correct).toBe(false);
  });

  it("낭독 문구에 두 번째 이후 칸이 실제로 등장한다", () => {
    const actual = expected8.slice();
    actual[0] = dotsToCell([6]);
    actual[2] = dotsToCell([5]);
    const { detail } = describeDiff(gradeAnswer(expected8, actual));
    expect(detail).toContain("세 번째 칸은");
  });

  it("몇 칸이 틀렸는지는 항상 말한다 (자세히 읽는 칸 수와 무관)", () => {
    const actual = expected8.slice();
    actual[0] = dotsToCell([6]);
    actual[2] = dotsToCell([5]);
    const { detail } = describeDiff(gradeAnswer(expected8, actual));
    expect(detail).toContain("모두 2칸이 틀렸어요");
  });

  it("한 칸만 틀린 경우 문구가 기존과 달라지지 않는다 (퇴행 방지)", () => {
    const actual = expected8.slice();
    actual[0] = dotsToCell([6]);
    const { detail } = describeDiff(gradeAnswer(expected8, actual));
    // 기존 V16 문구 그대로: "모두 N칸이 틀렸어요"나 "그 외" 요약 문구가 섞여 들어가면 안 된다
    expect(detail).not.toContain("모두");
    expect(detail).not.toContain("그 외");
    expect(detail).toContain("나머지 7칸은 맞았어요");
  });

  it("DETAIL_LIMIT(3칸) 경계 — 정확히 3칸 틀리면 요약 문구가 없다", () => {
    const actual = expected8.slice();
    actual[0] = dotsToCell([6]);
    actual[1] = dotsToCell([5]);
    actual[2] = dotsToCell([4]);
    const { detail } = describeDiff(gradeAnswer(expected8, actual));
    expect(detail).not.toContain("그 외");
    expect(detail).toContain("모두 3칸이 틀렸어요");
  });

  it("DETAIL_LIMIT(3칸)을 넘으면 나머지를 개수로 요약한다 — 4칸 틀린 경계", () => {
    const actual = expected8.slice();
    actual[0] = dotsToCell([6]);
    actual[1] = dotsToCell([5]);
    actual[2] = dotsToCell([4]);
    actual[3] = dotsToCell([3]);
    const { detail } = describeDiff(gradeAnswer(expected8, actual));
    expect(detail).toContain("그 외 1칸도 더 틀렸어요");
  });

  it("5칸이 틀리면 앞 3칸만 자세히 읽고 나머지 2칸은 개수로 줄인다", () => {
    const actual = expected8.slice();
    actual[0] = dotsToCell([6]);
    actual[1] = dotsToCell([5]);
    actual[2] = dotsToCell([4]);
    actual[3] = dotsToCell([3]);
    actual[4] = dotsToCell([2]);
    const r = gradeAnswer(expected8, actual);
    expect(r.diffs).toHaveLength(5);
    const { detail } = describeDiff(r);
    expect(detail).toContain("모두 5칸이 틀렸어요");
    expect(detail).toContain("그 외 2칸도 더 틀렸어요");
    // 자세히 읽는 건 첫 칸(headline/where) + 두 번째, 세 번째뿐 — 다섯 번째 칸 상세 설명은 없다
    expect(detail).toContain("두 번째 칸은");
    expect(detail).toContain("세 번째 칸은");
    expect(detail).not.toContain("다섯 번째 칸은");
  });
});
