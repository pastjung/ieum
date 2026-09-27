import { describe, expect, it } from 'vitest'
import {
  mergeLinkedProblem,
  problemSetItems,
  problemTitleSize,
  reconcileLinkedProblems,
} from '../problemSet'
import type { LinkedProblem } from '../types'

function problem(problemId: string, displayText: string): LinkedProblem {
  return {
    problemId,
    targetText: displayText,
    displayText,
    problemType: 'WORD',
    cellCount: 1,
    expectedPattern: [8],
  }
}

describe('problemSetItems', () => {
  const problems = [problem('1', '그래서'), problem('2', '그리고'), problem('3', '초성 ㄱ')]

  it('지금 문제·출제됨·대기를 연결 순서대로 표시한다', () => {
    expect(
      problemSetItems(problems, '2', new Set(['1', '2'])).map((i) => [
        i.problem.problemId,
        i.state,
      ]),
    ).toEqual([
      ['1', 'delivered'],
      ['2', 'current'],
      ['3', 'waiting'],
    ])
  })

  it('현재 문제가 없으면 출제 기록만 본다', () => {
    expect(problemSetItems(problems, null, new Set(['3'])).map((i) => i.state)).toEqual([
      'waiting',
      'waiting',
      'delivered',
    ])
  })

  it('출제 기록에 없어도 지금 문제는 current다 (새로고침 직후)', () => {
    expect(problemSetItems(problems, '3', new Set()).map((i) => i.state)).toEqual([
      'waiting',
      'waiting',
      'current',
    ])
  })

  it('연결된 문제가 없으면 빈 목록을 돌려준다', () => {
    expect(problemSetItems([], null, new Set())).toEqual([])
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

describe('reconcileLinkedProblems', () => {
  it('요청 중 로컬에서 등록된 문제가 서버 응답에 없으면 되살린다', () => {
    const server = [problem('1', '그래서')]
    const local = [problem('1', '그래서'), problem('2', '그리고')]
    expect(reconcileLinkedProblems(server, local).map((p) => p.problemId)).toEqual(['1', '2'])
  })

  it('서버 응답이 이미 로컬보다 최신이면 그대로 둔다', () => {
    const server = [problem('1', '그래서'), problem('2', '그리고'), problem('3', '초성 ㄱ')]
    const local = [problem('1', '그래서')]
    expect(reconcileLinkedProblems(server, local)).toBe(server)
  })
})

describe('problemTitleSize', () => {
  it('짧은 글자는 크게 보인다', () => {
    expect(problemTitleSize('그리고')).toBe(42)
    expect(problemTitleSize('초성 ㄱ')).toBe(42)
  })

  it('길어질수록 글자를 줄인다', () => {
    expect(problemTitleSize('반갑습니다 여러분')).toBe(32)
    expect(problemTitleSize('안녕하세요 오늘도 반갑습니다')).toBe(26)
  })
})
