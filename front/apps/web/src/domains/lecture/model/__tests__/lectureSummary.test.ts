import { describe, expect, it } from 'vitest'
import { closedSummary, openSummary } from '../lectureSummary'

describe('openSummary', () => {
  it('진행 중 수업은 문항 수와 지금 입장한 학생 수를 보여 준다', () => {
    expect(openSummary({ problemCount: 3, joinedCount: 2 })).toBe('3문항 · 입장 2명')
  })

  it('문제가 없으면 문항 없음', () => {
    expect(openSummary({ problemCount: 0, joinedCount: 0 })).toBe('문항 없음 · 입장 0명')
  })
})

describe('closedSummary', () => {
  it('종료 수업은 참여 학생 수와 정답률을 보여 준다', () => {
    expect(closedSummary({ participantCount: 5, correctRate: 76 })).toBe('참여 5명 · 정답률 76%')
  })

  it('제출이 없으면 정답률을 비운다', () => {
    expect(closedSummary({ participantCount: 5, correctRate: null })).toBe('참여 5명 · 제출 없음')
  })
})
