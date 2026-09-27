/** 음량 막대 모양(%) — 가운데가 높은 산 모양을 소리 크기만큼 키운다 */
const SHAPE = [55, 75, 90, 100, 90, 75, 55]
const FLOOR = 12
/** LiveKit audioLevel은 말소리에서 0.1~0.4 정도라 그대로 쓰면 막대가 거의 움직이지 않는다 */
const GAIN = 2.5

/**
 * 강사 마이크 음량 막대 높이(%). 송출 중이 아니면 바닥에 붙인다.
 * @param level LiveKit LocalParticipant.audioLevel (0~1)
 */
export function meterHeights(level: number, live: boolean): number[] {
  if (!live) return SHAPE.map(() => FLOOR)
  const scale = Math.min(1, Math.max(0, level) * GAIN)
  return SHAPE.map((peak) => Math.round(FLOOR + (peak - FLOOR) * scale))
}
