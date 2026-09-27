import { describe, expect, it } from 'vitest'
import { brailleGlyph, dotLabel, dotsOf } from '../braille'

describe('braille', () => {
  it('6비트 점형을 점 번호로 바꾼다 (bit0 = 1점)', () => {
    expect(dotsOf(8)).toEqual([4])
    expect(dotsOf(21)).toEqual([1, 3, 5])
    expect(dotsOf(63)).toEqual([1, 2, 3, 4, 5, 6])
    expect(dotsOf(0)).toEqual([])
    expect(dotsOf(64 + 1)).toEqual([1])
  })

  it('칸별 점 번호 문자열을 만든다', () => {
    expect(dotLabel([21, 42])).toBe('1·3·5 / 2·4·6')
    expect(dotLabel([8])).toBe('4')
    expect(dotLabel([0, 1])).toBe('빈칸 / 1')
    expect(dotLabel([])).toBe('')
  })

  it('유니코드 점자 문자로 바꾼다', () => {
    expect(brailleGlyph([8])).toBe('⠈')
    expect(brailleGlyph([21, 42])).toBe('⠕⠪')
    expect(brailleGlyph([0])).toBe('⠀')
    expect(brailleGlyph([63 + 64])).toBe('⠿')
  })
})
