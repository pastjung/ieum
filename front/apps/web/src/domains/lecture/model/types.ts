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
