import { describe, expect, it } from 'vitest'
import { progressCounts, studentAnswerView, submittedPatterns, wrongReason } from '../answerView'
import type { AnswerCell, StudentAnswer } from '../types'

function cell(cellIndex: number, inputPattern: number, expectedPattern: number): AnswerCell {
  const diffMask = inputPattern ^ expectedPattern
  return { cellIndex, inputPattern, expectedPattern, diffMask, isCorrect: diffMask === 0 }
}

function answer(userId: string, isCorrect: boolean, cells: AnswerCell[]): StudentAnswer {
  return { userId, username: `u${userId}`, isCorrect, attemptedAt: '2026-09-27T01:00:00Z', cells }
}

describe('wrongReason', () => {
  it('첫 칸에서 빠진 점', () => {
    // 정답 2·4·6(42), 입력 2·4(10)
    expect(wrongReason([cell(1, 10, 42)])).toBe('첫 칸 6점 누락')
  })

  it('둘째 칸에 더한 점', () => {
    // 첫 칸 맞음, 둘째 칸 정답 1점(1) 입력 1·3점(5)
    expect(wrongReason([cell(1, 8, 8), cell(2, 5, 1)])).toBe('둘째 칸 3점 추가')
  })

  it('누락과 추가가 함께 있으면 쉼표로 잇는다', () => {
    expect(wrongReason([cell(1, 8, 1)])).toBe('첫 칸 1점 누락, 4점 추가')
  })

  it('여러 점은 가운뎃점으로 잇는다', () => {
    expect(wrongReason([cell(1, 1, 7)])).toBe('첫 칸 2·3점 누락')
  })

  it('틀린 칸이 여럿이면 첫 번째 칸만 말한다', () => {
    expect(wrongReason([cell(1, 8, 8), cell(2, 8, 8), cell(3, 0 + 2, 3), cell(4, 1, 2)])).toBe('셋째 칸 1점 누락')
  })

  it('cellIndex 순서로 본다', () => {
    expect(wrongReason([cell(2, 5, 1), cell(1, 10, 42)])).toBe('첫 칸 6점 누락')
  })

  it('입력이 길거나 짧으면 칸 수 다름', () => {
    expect(wrongReason([cell(1, 8, 8), cell(2, 5, 0)])).toBe('칸 수 다름')
    expect(wrongReason([cell(1, 8, 8), cell(2, 0, 5)])).toBe('칸 수 다름')
  })

  it('다른 점이 없으면 오답이라고만 한다', () => {
    expect(wrongReason([cell(1, 8, 8)])).toBe('오답')
  })
})

describe('submittedPatterns', () => {
  it('입력이 없는 뒤쪽 칸을 뺀다', () => {
    expect(submittedPatterns([cell(2, 0, 5), cell(1, 8, 8)])).toEqual([8])
    expect(submittedPatterns([cell(1, 8, 8), cell(2, 5, 0)])).toEqual([8, 5])
    expect(submittedPatterns([cell(1, 0, 8)])).toEqual([])
  })
})

describe('studentAnswerView', () => {
  it('답안이 없으면 미착수', () => {
    expect(studentAnswerView(undefined)).toEqual({ state: 'idle', glyph: null, detail: '미착수' })
  })

  it('정답은 제출 점자와 "정답"', () => {
    expect(studentAnswerView(answer('1', true, [cell(1, 8, 8)]))).toEqual({ state: 'correct', glyph: '⠈', detail: '정답' })
  })

  it('오답은 제출 점자와 틀린 이유', () => {
    expect(studentAnswerView(answer('1', false, [cell(1, 10, 42)]))).toEqual({
      state: 'wrong',
      glyph: '⠊',
      detail: '첫 칸 6점 누락',
    })
  })

  it('입력 칸이 하나도 없으면 점자 없이 이유만', () => {
    expect(studentAnswerView(answer('1', false, [cell(1, 0, 8)]))).toEqual({ state: 'wrong', glyph: null, detail: '칸 수 다름' })
  })
})

describe('progressCounts', () => {
  it('접속 학생 기준으로 제출·정답·오답·미착수를 센다', () => {
    const answers = [answer('1', true, [cell(1, 8, 8)]), answer('2', false, [cell(1, 1, 8)]), answer('9', true, [cell(1, 8, 8)])]
    expect(progressCounts(['1', '2', '3'], answers)).toEqual({ connected: 3, submitted: 2, correct: 1, wrong: 1, notStarted: 1 })
  })

  it('접속 학생이 없으면 모두 0', () => {
    expect(progressCounts([], [answer('1', true, [])])).toEqual({ connected: 0, submitted: 0, correct: 0, wrong: 0, notStarted: 0 })
  })

  it('같은 userId가 두 번 와도 한 명으로 센다', () => {
    expect(progressCounts(['1', '1'], [])).toEqual({ connected: 1, submitted: 0, correct: 0, wrong: 0, notStarted: 1 })
  })
})
