import { fitDotSize } from "@/components/dotPadLayout";
import { dotButtonSize } from "@ieum/design-tokens";

describe("fitDotSize", () => {
  it("공간이 넉넉하면 기본 크기 그대로다", () => {
    expect(fitDotSize(800)).toBe(dotButtonSize);
  });

  it("패드가 낮아지면 가운데 줄과 아랫줄이 겹치지 않게 줄인다", () => {
    const height = 300;
    const size = fitDotSize(height);
    expect(size).toBeLessThan(dotButtonSize);
    // 가운데 줄 아래 끝(42% + d) ≤ 아랫줄 위 끝(97% − d)
    expect(height * 0.42 + size).toBeLessThanOrEqual(height * 0.97 - size);
  });

  it("아직 재지 않았으면(0) 기본 크기로 그린다", () => {
    expect(fitDotSize(0)).toBe(dotButtonSize);
  });
});
