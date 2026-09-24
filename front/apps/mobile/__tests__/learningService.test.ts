import { particleFor } from "../src/services/learningService";

describe("particleFor — 조사 선택", () => {
  it("받침이 있으면 '을'을 고른다", () => {
    expect(particleFor("가방")).toBe("을");
    expect(particleFor("ㅏ")).toBe("을"); // ㅏ는 완성형이 아니라 자모 단독 코드포인트라 받침 없음 범위 밖 → "을"
  });

  it("받침이 없으면 '를'을 고른다", () => {
    expect(particleFor("사과")).toBe("를");
    expect(particleFor("가")).toBe("를");
  });

  it("한글 완성형이 아닌 글자로 끝나면 '을'로 둔다", () => {
    expect(particleFor("A")).toBe("을");
    expect(particleFor("1")).toBe("을");
  });

  it("빈 문자열이면 '을'로 둔다 — charCodeAt(-1)이 NaN이 되는 경로에 기대지 않는다", () => {
    expect(particleFor("")).toBe("을");
  });
});
