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

/**
 * 출제·수업 시작 응답의 연결 목록은 요청 시점 서버 상태다 — 요청이 진행되는 동안
 * 로컬에서 새로 등록한 문제가 있으면 응답에 없을 수 있으므로, 서버 목록에 되살려 합친다
 */
export function reconcileLinkedProblems(
  serverProblems: LinkedProblem[],
  localProblems: readonly LinkedProblem[],
): LinkedProblem[] {
  return localProblems.reduce(mergeLinkedProblem, serverProblems)
}

/**
 * 지금 문제 제목 글자 크기(px). 긴 낱말·문장이 한 글자씩 세로로 꺾이지 않도록 길이에 따라 줄인다.
 * 공백은 세지 않는다 — 줄바꿈은 낱말 사이에서만 일어난다(word-break: keep-all).
 */
export function problemTitleSize(text: string): number {
  const length = text.replace(/\s/g, '').length
  if (length <= 6) return 42
  if (length <= 12) return 32
  return 26
}
