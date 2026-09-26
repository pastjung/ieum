import type { ApiClient } from '@ieum/api-client'
import { api } from '../../../shared/api/apiClient'
import type { CreateLectureInput, InstructorLecture, LecturePage, MediaToken } from '../model/types'

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

    get: (lectureId: string) => client.get<InstructorLecture>(`/lectures/${lectureId}`),

    /** 같은 상태를 다시 보내면 서버가 방 작업만 재시도한다 — 503 복구 경로 (API 명세 8.5절) */
    changeStatus: (lectureId: string, status: 'OPEN' | 'CLOSED') =>
      client.put<InstructorLecture>(`/lectures/${lectureId}/status`, { status }),

    /** 강사 토큰은 OPEN일 때만 발급된다. 발급할 때 서버가 방을 다시 열어 metadata를 복구한다 */
    issueMediaToken: (lectureId: string) => client.post<MediaToken>(`/lectures/${lectureId}/media-tokens`),
  }
}

/** `<input type="datetime-local">`의 로컬 시각을 서버가 받는 UTC ISO로 바꾼다 */
export function toScheduledAtUtc(localDateTime: string): string {
  return new Date(localDateTime).toISOString()
}

export const lectureApi = createLectureApi(api)
