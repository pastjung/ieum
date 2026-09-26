import { ApiError, type ApiClient } from '@ieum/api-client'
import { api } from '../../../shared/api/apiClient'
import type {
  CreateLectureInput,
  InstructorLecture,
  LecturePage,
  LinkedProblem,
  MediaToken,
  ProblemAnswers,
  ProblemCandidate,
  SpeakingResult,
} from '../model/types'

export function createLectureApi(client: ApiClient) {
  return {
    list: (page = 0, size = 20) => client.get<LecturePage>(`/lectures?page=${page}&size=${size}`),

    create(input: CreateLectureInput) {
      const body: CreateLectureInput = { title: input.title.trim() }
      const description = input.description?.trim()
      if (description) body.description = description
      if (input.scheduledAt) body.scheduledAt = input.scheduledAt
      return client.post<InstructorLecture>('/lectures', body)
    },

    get: (lectureId: string) => client.get<InstructorLecture>(`/lectures/${encodeURIComponent(lectureId)}`),

    /** 같은 상태를 다시 보내면 서버가 방 작업만 재시도한다 — 503 복구 경로 (API 명세 8.5절) */
    changeStatus: (lectureId: string, status: 'OPEN' | 'CLOSED') =>
      client.put<InstructorLecture>(`/lectures/${encodeURIComponent(lectureId)}/status`, { status }),

    /** 강사 토큰은 OPEN일 때만 발급된다. 발급할 때 서버가 방을 다시 열어 metadata를 복구한다 */
    issueMediaToken: (lectureId: string) => client.post<MediaToken>(`/lectures/${encodeURIComponent(lectureId)}/media-tokens`),

    /**
     * 학생 발언 허용(true)·회수(false). 멱등이며 서버는 저장하지 않는다 — LiveKit 참가자 권한이 유일한 상태다.
     * 허용·회수 모두 서버가 학생의 손(`hand`)을 내린다
     */
    setSpeaking: (lectureId: string, userId: string, allowed: boolean) =>
      client.put<SpeakingResult>(
        `/lectures/${encodeURIComponent(lectureId)}/participants/${encodeURIComponent(userId)}/speaking`,
        { allowed },
      ),

    /**
     * 글자 입력으로 문제를 이 수업에 연결한다. 이미 연결된 문제면 서버가 200으로 같은 문제를 돌려준다.
     * 구분어 없는 낱자가 여러 문제에 해당하면 409 PROBLEM_TEXT_AMBIGUOUS — ambiguousCandidates로 후보를 꺼낸다
     */
    registerProblem: (lectureId: string, targetText: string) =>
      client.post<LinkedProblem>(`/lectures/${encodeURIComponent(lectureId)}/problems`, { targetText: targetText.trim() }),

    /** 출제(OPEN 전용). 503이면 DB는 저장됐고 같은 요청으로 방 metadata만 다시 쓴다 */
    deliverProblem: (lectureId: string, problemId: string) =>
      client.put<InstructorLecture>(`/lectures/${encodeURIComponent(lectureId)}/current-problem`, { problemId }),

    /** 문제별 제출 답안. 제출한 학습자만 온다 — 미착수는 접속자 목록과 맞춰 화면이 판단한다 */
    listAnswers: (lectureId: string, problemId: string) =>
      client.get<ProblemAnswers>(
        `/lectures/${encodeURIComponent(lectureId)}/problems/${encodeURIComponent(problemId)}/answers`,
      ),
  }
}

function isCandidate(value: unknown): value is ProblemCandidate {
  if (typeof value !== 'object' || value === null) return false
  const { input, displayText } = value as Record<string, unknown>
  return typeof input === 'string' && input.trim() !== '' && typeof displayText === 'string'
}

/** 409 PROBLEM_TEXT_AMBIGUOUS 오류 본문의 후보. 다른 오류이거나 올바른 후보가 없으면 null */
export function ambiguousCandidates(error: unknown): ProblemCandidate[] | null {
  if (!(error instanceof ApiError) || error.code !== 'PROBLEM_TEXT_AMBIGUOUS') return null
  const raw = (error.body as { candidates?: unknown } | null | undefined)?.candidates
  if (!Array.isArray(raw)) return null
  const candidates = raw.filter(isCandidate)
  return candidates.length > 0 ? candidates : null
}

/** `<input type="datetime-local">`의 로컬 시각을 서버가 받는 UTC ISO로 바꾼다 */
export function toScheduledAtUtc(localDateTime: string): string {
  return new Date(localDateTime).toISOString()
}

export const lectureApi = createLectureApi(api)
