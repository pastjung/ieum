import { describe, expect, it } from 'vitest'
import { mergeLinkedProblem, problemSetItems } from '../problemSet'
import type { LinkedProblem } from '../types'

function problem(problemId: string, displayText: string): LinkedProblem {
  return { problemId, targetText: displayText, displayText, problemType: 'WORD', cellCount: 1, expectedPattern: [8] }
}

describe('problemSetItems', () => {
  const problems = [problem('1', '그래서'), problem('2', '그리고'), problem('3', '초성 ㄱ')]

  it('지금 문제·출제됨·대기를 연결 순서대로 표시한다', () => {
    expect(problemSetItems(problems, '2', new Set(['1', '2'])).map((i) => [i.problem.problemId, i.state])).toEqual([
      ['1', 'delivered'],
      ['2', 'current'],
      ['3', 'waiting'],
    ])
  })

  it('현재 문제가 없으면 출제 기록만 본다', () => {
    expect(problemSetItems(problems, null, new Set(['3'])).map((i) => i.state)).toEqual(['waiting', 'waiting', 'delivered'])
  })

  it('출제 기록에 없어도 지금 문제는 current다 (새로고침 직후)', () => {
    expect(problemSetItems(problems, '3', new Set()).map((i) => i.state)).toEqual(['waiting', 'waiting', 'current'])
  })
})

describe('mergeLinkedProblem', () => {
  it('새 문제는 끝에 붙인다', () => {
    const before = [problem('1', '그래서')]
    const after = mergeLinkedProblem(before, problem('2', '그리고'))
    expect(after.map((p) => p.problemId)).toEqual(['1', '2'])
    expect(before).toHaveLength(1)
  })

  it('이미 연결된 문제면 목록을 늘리지 않고 같은 배열을 돌려준다', () => {
    const before = [problem('1', '그래서'), problem('2', '그리고')]
    expect(mergeLinkedProblem(before, problem('1', '그래서'))).toBe(before)
  })
})
