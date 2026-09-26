import { ApiError } from '@ieum/api-client'
import { describe, expect, it } from 'vitest'
import { InstructorOnlyError } from '../../auth/authApi'
import { errorMessage } from '../errorMessage'

describe('errorMessage', () => {
  it.each([
    ['INVALID_CREDENTIALS', '아이디 또는 비밀번호를 확인해 주세요.'],
    ['EMAIL_VERIFICATION_REQUIRED', '이메일 인증을 마친 뒤 로그인해 주세요.'],
    ['LECTURE_NOT_FOUND', '수업을 찾을 수 없어요.'],
    ['INVALID_LECTURE_STATE', '지금 수업 상태에서는 할 수 없는 작업이에요. 새로고침해 주세요.'],
    ['MEDIA_SERVER_UNAVAILABLE', '음성 서버에 연결하지 못했어요. 같은 버튼을 다시 눌러 주세요.'],
    ['INVALID_REQUEST_INPUT', '입력값을 확인해 주세요.'],
    ['NETWORK_UNAVAILABLE', '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.'],
  ])('%s', (code, text) => {
    expect(errorMessage(new ApiError(400, code, 'server text'))).toBe(text)
  })

  it('강사 전용 오류', () => {
    expect(errorMessage(new InstructorOnlyError())).toBe('강사 계정으로만 로그인할 수 있어요.')
  })

  it('알 수 없는 오류', () => {
    expect(errorMessage(new Error('boom'))).toBe('요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.')
  })
})
