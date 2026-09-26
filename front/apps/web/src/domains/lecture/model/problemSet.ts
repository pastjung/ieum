import type { LinkedProblem } from './types'

/**
 * 문제 세트 목록의 상태 (문제 출제 설계 §4).
 * 출제됨은 웹 세션 안에서만 기억한다 — 새로고침하면 지금 문제만 알 수 있다.
 */
export type SetItemState = 'current' | 'delivered' | 'waiting'

export interface SetItem {
  problem: LinkedProblem
  state: SetItemState
}

export function problemSetItems(
  problems: readonly LinkedProblem[],
  currentProblemId: string | null,
  deliveredIds: ReadonlySet<string>,
): SetItem[] {
  return problems.map((problem) => {
    if (problem.problemId === currentProblemId) return { problem, state: 'current' }
    return { problem, state: deliveredIds.has(problem.problemId) ? 'delivered' : 'waiting' }
  })
}

/** 등록 응답을 연결 목록에 합친다. 이미 연결된 문제(200)면 같은 배열을 그대로 돌려준다 */
export function mergeLinkedProblem(
  problems: LinkedProblem[],
  problem: LinkedProblem,
): LinkedProblem[] {
  return problems.some((p) => p.problemId === problem.problemId) ? problems : [...problems, problem]
}
