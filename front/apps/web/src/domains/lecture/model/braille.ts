/** 점자 셀은 6비트 정수다 (bit0 = 1점 … bit5 = 6점, 유니코드 U+2800 + 값) */

/** 볼록한 점 번호 (오름차순). 6비트 밖의 비트는 무시한다 */
export function dotsOf(pattern: number): number[] {
  const dots: number[] = []
  for (let dot = 1; dot <= 6; dot += 1) {
    if (pattern & (1 << (dot - 1))) dots.push(dot)
  }
  return dots
}

/** 칸별 점 번호 문자열. [21, 42] → "1·3·5 / 2·4·6" */
export function dotLabel(patterns: readonly number[]): string {
  return patterns
    .map((pattern) => {
      const dots = dotsOf(pattern)
      return dots.length > 0 ? dots.join('·') : '빈칸'
    })
    .join(' / ')
}

/** 유니코드 점자 문자열 */
export function brailleGlyph(patterns: readonly number[]): string {
  return patterns.map((pattern) => String.fromCharCode(0x2800 + (pattern & 0x3f))).join('')
}
