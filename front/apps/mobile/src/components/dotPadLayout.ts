import { dotButtonSize } from "@ieum/design-tokens";

/**
 * 점 지름의 상한 비율. 가운데 줄(top 42%)과 아랫줄(bottom 3%)이 겹치지 않으려면
 * 42% + d ≤ 97% − d, 곧 d ≤ 27.5%다. 화면 위에 버튼 줄이 늘어 패드가 낮아져도 점이 겹치지 않게 한다.
 */
const MAX_DOT_RATIO = 0.27;

/** 패드 높이에 맞춘 점 지름 — 공간이 넉넉하면 기본 크기 그대로다 */
export function fitDotSize(padHeight: number): number {
  if (padHeight <= 0) return dotButtonSize;
  return Math.min(dotButtonSize, Math.floor(padHeight * MAX_DOT_RATIO));
}
