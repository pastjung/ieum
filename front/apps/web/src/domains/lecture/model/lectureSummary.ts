import type { LectureListItem } from './types'

/** 진행 중 수업 한 줄 요약. 정원·수강생 수는 데이터가 없어 입장한 학생 수만 보인다 */
export function openSummary(item: Pick<LectureListItem, 'problemCount' | 'joinedCount'>): string {
  const problems = item.problemCount > 0 ? `${item.problemCount}문항` : '문항 없음'
  return `${problems} · 입장 ${item.joinedCount}명`
}

/** 종료 수업 한 줄 요약. 정답률은 이 수업의 LIVE 답안 기준이며 제출이 없으면 null이다 */
export function closedSummary(
  item: Pick<LectureListItem, 'participantCount' | 'correctRate'>,
): string {
  const rate = item.correctRate === null ? '제출 없음' : `정답률 ${item.correctRate}%`
  return `참여 ${item.participantCount}명 · ${rate}`
}
