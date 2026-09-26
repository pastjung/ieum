/**
 * 백엔드가 강사 identity에게만 보내는 LiveKit 데이터 메시지 (문제 출제 설계 §2·§3.3).
 * 신호는 "다시 조회하라"는 뜻일 뿐 답 내용은 없다. 이 모듈은 LiveKit을 import하지 않는다.
 */
export const LECTURE_TOPIC = 'lecture'

export interface AnswerSubmittedSignal {
  type: 'ANSWER_SUBMITTED'
  problemId: string
}

const decoder = new TextDecoder('utf-8', { fatal: true })

/** 형식이 어긋나면 null. throw하지 않는다 */
export function parseLectureSignal(
  payload: Uint8Array,
  topic: string | undefined,
): AnswerSubmittedSignal | null {
  if (topic !== LECTURE_TOPIC) return null
  let data: unknown
  try {
    data = JSON.parse(decoder.decode(payload))
  } catch {
    return null
  }
  if (typeof data !== 'object' || data === null) return null
  const { type, problemId } = data as Record<string, unknown>
  if (type !== 'ANSWER_SUBMITTED' || typeof problemId !== 'string' || !/^\d+$/.test(problemId))
    return null
  return { type: 'ANSWER_SUBMITTED', problemId }
}
