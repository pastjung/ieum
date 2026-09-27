import { describe, expect, it } from 'vitest'
import { parseLectureSignal } from '../lectureSignal'

const bytes = (value: unknown) => new TextEncoder().encode(typeof value === 'string' ? value : JSON.stringify(value))

describe('parseLectureSignal', () => {
  it('lecture 토픽의 답안 제출 신호를 읽는다', () => {
    expect(parseLectureSignal(bytes({ type: 'ANSWER_SUBMITTED', problemId: '26578' }), 'lecture')).toEqual({
      type: 'ANSWER_SUBMITTED',
      problemId: '26578',
    })
  })

  it('다른 토픽이나 토픽 없는 메시지는 무시한다', () => {
    const payload = bytes({ type: 'ANSWER_SUBMITTED', problemId: '1' })
    expect(parseLectureSignal(payload, 'chat')).toBeNull()
    expect(parseLectureSignal(payload, undefined)).toBeNull()
  })

  it('JSON이 아니거나 객체가 아니면 null', () => {
    expect(parseLectureSignal(bytes('{broken'), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes('3'), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes('null'), 'lecture')).toBeNull()
    expect(parseLectureSignal(new Uint8Array([0xff, 0xfe]), 'lecture')).toBeNull()
  })

  it('type이 다르거나 problemId가 10진수 문자열이 아니면 null', () => {
    expect(parseLectureSignal(bytes({ type: 'OTHER', problemId: '1' }), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes({ type: 'ANSWER_SUBMITTED', problemId: 26578 }), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes({ type: 'ANSWER_SUBMITTED', problemId: '1a' }), 'lecture')).toBeNull()
    expect(parseLectureSignal(bytes({ type: 'ANSWER_SUBMITTED' }), 'lecture')).toBeNull()
  })
})
