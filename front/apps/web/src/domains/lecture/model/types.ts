/** 강사 웹이 쓰는 수업 응답 (API 명세 8.2·8.5절). ID는 10진수 문자열이다 */
export type LectureStatus = 'SCHEDULED' | 'OPEN' | 'CLOSED'

export interface InstructorLecture {
  lectureId: string
  title: string
  description: string | null
  status: LectureStatus
  scheduledAt: string | null
  startedAt: string | null
  endedAt: string | null
  createdAt: string
  participationCode: string
}

export interface LecturePage {
  items: InstructorLecture[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface MediaToken {
  serverUrl: string
  token: string
  expiresAt: string
}

export interface CreateLectureInput {
  title: string
  description?: string
  scheduledAt?: string
}

/** 발언 허용·회수 응답 (API 명세 8.5절). 화면은 이 응답이 아니라 LiveKit 권한 변경 이벤트로 갱신한다 */
export interface SpeakingResult {
  userId: string
  allowed: boolean
}
