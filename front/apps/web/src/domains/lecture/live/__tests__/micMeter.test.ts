import { describe, expect, it } from 'vitest'
import { meterHeights } from '../micMeter'

describe('meterHeights', () => {
  it('송출하지 않으면 막대가 바닥에 붙는다', () => {
    expect(meterHeights(0.9, false).every((h) => h === 12)).toBe(true)
  })

  it('소리가 클수록 막대가 높아지고 100%를 넘지 않는다', () => {
    const quiet = meterHeights(0.05, true)
    const loud = meterHeights(1, true)
    expect(loud.reduce((a, b) => a + b)).toBeGreaterThan(quiet.reduce((a, b) => a + b))
    expect(Math.max(...loud)).toBeLessThanOrEqual(100)
    expect(Math.min(...quiet)).toBeGreaterThanOrEqual(12)
  })
})
