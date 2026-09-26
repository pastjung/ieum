import { ApiError } from '@ieum/api-client'
import { InstructorOnlyError } from '../auth/authApi'

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: '아이디 또는 비밀번호를 확인해 주세요.',
  EMAIL_VERIFICATION_REQUIRED: '이메일 인증을 마친 뒤 로그인해 주세요.',
  LECTURE_NOT_FOUND: '수업을 찾을 수 없어요.',
  INVALID_LECTURE_STATE: '지금 수업 상태에서는 할 수 없는 작업이에요. 새로고침해 주세요.',
  MEDIA_SERVER_UNAVAILABLE: '음성 서버에 연결하지 못했어요. 같은 버튼을 다시 눌러 주세요.',
  LECTURE_PARTICIPANT_NOT_CONNECTED: '지금 연결돼 있지 않은 학생이에요.',
  LECTURE_PARTICIPANT_NOT_FOUND: '수업에 참여 중인 학생이 아니에요.',
  INVALID_REQUEST_INPUT: '입력값을 확인해 주세요.',
  NETWORK_UNAVAILABLE: '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.',
  INVALID_ACCOUNT_INPUT: '아이디와 비밀번호 형식을 확인해 주세요.',
}

const MIC_PERMISSION_ERROR_NAMES = new Set(['NotAllowedError', 'NotFoundError'])

/** 서버 message를 그대로 보여 주지 않고 사유별 문구를 쓴다 */
export function errorMessage(error: unknown): string {
  if (error instanceof InstructorOnlyError) return '강사 계정으로만 로그인할 수 있어요.'
  if (error instanceof ApiError && MESSAGES[error.code]) return MESSAGES[error.code]
  if (error instanceof Error && MIC_PERMISSION_ERROR_NAMES.has(error.name)) {
    return '마이크를 사용할 수 없어요. 브라우저의 마이크 권한을 허용해 주세요.'
  }
  return '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.'
}
