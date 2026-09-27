import { describe, expect, it } from 'vitest'
import { compareCells } from '../answerView'
import type { AnswerCell } from '../types'

function cell(cellIndex: number, inputPattern: number, expectedPattern: number): AnswerCell {
  return {
    cellIndex,
    inputPattern,
    expectedPattern,
    diffMask: inputPattern ^ expectedPattern,
    isCorrect: inputPattern === expectedPattern,
  }
}

describe('compareCells', () => {
  it('맞은 칸은 누락·추가 점이 없다', () => {
    const [first] = compareCells([cell(1, 0b011000, 0b011000)])
    expect(first).toEqual({
      cellIndex: 1,
      input: 0b011000,
      expected: 0b011000,
      missing: [],
      extra: [],
      ok: true,
      note: '맞음',
    })
  })

  it('틀린 칸은 빠진 점과 더 찍은 점을 알려 준다', () => {
    // 정답 2·5점(0b010010), 입력 2·3점(0b000110) → 5점 누락, 3점 추가
    const [first] = compareCells([cell(1, 0b000110, 0b010010)])
    expect(first.missing).toEqual([5])
    expect(first.extra).toEqual([3])
    expect(first.ok).toBe(false)
    expect(first.note).toBe('5점 누락, 3점 추가')
  })

  it('정답보다 짧게 내면 없는 칸을 입력 없음으로 표시한다', () => {
    const rows = compareCells([cell(1, 8, 8), cell(2, 0, 3)])
    expect(rows).toHaveLength(2)
    expect(rows[1]).toMatchObject({ input: null, expected: 3, ok: false, note: '입력 없음' })
  })

  it('정답보다 길게 내면 남는 칸을 정답 없음으로 표시한다', () => {
    const rows = compareCells([cell(1, 8, 8), cell(2, 5, 0)])
    expect(rows[1]).toMatchObject({ input: 5, expected: null, ok: false, note: '남는 칸' })
  })

  it('칸 순서대로 돌려준다', () => {
    const rows = compareCells([cell(2, 1, 1), cell(1, 8, 8)])
    expect(rows.map((r) => r.cellIndex)).toEqual([1, 2])
  })
})
