import { brailleGlyph, dotsOf } from './braille'
import type { AnswerCell, StudentAnswer } from './types'

/** 서버는 한 문제에 최대 12칸까지 받는다 */
const ORDINALS = [
  '첫',
  '둘째',
  '셋째',
  '넷째',
  '다섯째',
  '여섯째',
  '일곱째',
  '여덟째',
  '아홉째',
  '열째',
  '열한째',
  '열두째',
]

function ordinal(cellIndex: number): string {
  return ORDINALS[cellIndex - 1] ?? `${cellIndex}번째`
}

function ordered(cells: readonly AnswerCell[]): AnswerCell[] {
  return [...cells].sort((a, b) => a.cellIndex - b.cellIndex)
}

/**
 * 뒤쪽 0을 뺀 길이. 답안 칸은 max(입력, 정답) 길이이고 없는 쪽은 0으로 채워지므로
 * 이것을 원래 칸 수로 본다 (끝에 빈 칸을 일부러 제출한 경우만 구분하지 못한다)
 */
function lengthOf(patterns: readonly number[]): number {
  let length = patterns.length
  while (length > 0 && patterns[length - 1] === 0) length -= 1
  return length
}

/** 학생이 실제로 제출한 칸의 점형 */
export function submittedPatterns(cells: readonly AnswerCell[]): number[] {
  const inputs = ordered(cells).map((c) => c.inputPattern)
  return inputs.slice(0, lengthOf(inputs))
}

/** 오답 카드의 틀린 이유. 칸 수가 다르면 "칸 수 다름", 아니면 첫 틀린 칸의 누락·추가 점 */
export function wrongReason(cells: readonly AnswerCell[]): string {
  const sorted = ordered(cells)
  const inputLength = lengthOf(sorted.map((c) => c.inputPattern))
  const expectedLength = lengthOf(sorted.map((c) => c.expectedPattern))
  if (inputLength !== expectedLength) return '칸 수 다름'

  const first = sorted.find((c) => (c.inputPattern ^ c.expectedPattern) !== 0)
  if (!first) return '오답'
  const missing = dotsOf(first.expectedPattern & ~first.inputPattern)
  const extra = dotsOf(first.inputPattern & ~first.expectedPattern)
  const parts: string[] = []
  if (missing.length > 0) parts.push(`${missing.join('·')}점 누락`)
  if (extra.length > 0) parts.push(`${extra.join('·')}점 추가`)
  return `${ordinal(first.cellIndex)} 칸 ${parts.join(', ')}`
}

/** 상세 보기 한 칸: 학생 답과 정답을 나란히 놓고 빠진 점·더 찍은 점을 표시한다 */
export interface CellCompare {
  cellIndex: number
  /** 학생이 이 칸을 내지 않았으면(정답보다 짧게 냄) null */
  input: number | null
  /** 정답에 이 칸이 없으면(정답보다 길게 냄) null */
  expected: number | null
  /** 정답에는 있는데 학생이 찍지 않은 점 */
  missing: number[]
  /** 학생이 정답에 없는 점을 더 찍음 */
  extra: number[]
  ok: boolean
  note: string
}

/** 칸별 비교. 답안 칸은 max(입력, 정답) 길이이고 없는 쪽이 0으로 채워져 있으므로 원래 칸 수로 되돌려 비교한다 */
export function compareCells(cells: readonly AnswerCell[]): CellCompare[] {
  const sorted = ordered(cells)
  const inputLength = lengthOf(sorted.map((c) => c.inputPattern))
  const expectedLength = lengthOf(sorted.map((c) => c.expectedPattern))
  return sorted
    .filter((c) => c.cellIndex <= Math.max(inputLength, expectedLength))
    .map((c) => {
      const input = c.cellIndex <= inputLength ? c.inputPattern : null
      const expected = c.cellIndex <= expectedLength ? c.expectedPattern : null
      if (input === null) {
        return {
          cellIndex: c.cellIndex,
          input,
          expected,
          missing: dotsOf(c.expectedPattern),
          extra: [],
          ok: false,
          note: '입력 없음',
        }
      }
      if (expected === null) {
        return {
          cellIndex: c.cellIndex,
          input,
          expected,
          missing: [],
          extra: dotsOf(c.inputPattern),
          ok: false,
          note: '남는 칸',
        }
      }
      const missing = dotsOf(expected & ~input)
      const extra = dotsOf(input & ~expected)
      const parts: string[] = []
      if (missing.length > 0) parts.push(`${missing.join('·')}점 누락`)
      if (extra.length > 0) parts.push(`${extra.join('·')}점 추가`)
      const ok = parts.length === 0
      return {
        cellIndex: c.cellIndex,
        input,
        expected,
        missing,
        extra,
        ok,
        note: ok ? '맞음' : parts.join(', '),
      }
    })
}

export type AnswerState = 'correct' | 'wrong' | 'idle'

export interface StudentAnswerView {
  state: AnswerState
  /** 제출한 점자. 미착수이거나 입력 칸이 없으면 null */
  glyph: string | null
  /** 정답: "정답", 오답: 틀린 이유, 미착수: "미착수". 걸린 시간은 저장하지 않으므로 없다 */
  detail: string
}

export function studentAnswerView(answer: StudentAnswer | undefined): StudentAnswerView {
  if (!answer) return { state: 'idle', glyph: null, detail: '미착수' }
  const patterns = submittedPatterns(answer.cells)
  const glyph = patterns.length > 0 ? brailleGlyph(patterns) : null
  return answer.isCorrect
    ? { state: 'correct', glyph, detail: '정답' }
    : { state: 'wrong', glyph, detail: wrongReason(answer.cells) }
}

export interface ProgressCounts {
  connected: number
  submitted: number
  correct: number
  wrong: number
  notStarted: number
}

/** 제출 현황. 학생 카드와 같은 명단(지금 접속한 학생)으로 센다 */
export function progressCounts(
  connectedUserIds: readonly string[],
  answers: readonly StudentAnswer[],
): ProgressCounts {
  const byUser = new Map(answers.map((a) => [a.userId, a]))
  const connected = new Set(connectedUserIds)
  let correct = 0
  let wrong = 0
  for (const userId of connected) {
    const found = byUser.get(userId)
    if (!found) continue
    if (found.isCorrect) correct += 1
    else wrong += 1
  }
  return {
    connected: connected.size,
    submitted: correct + wrong,
    correct,
    wrong,
    notStarted: connected.size - correct - wrong,
  }
}
