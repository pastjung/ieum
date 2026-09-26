import { describe, expect, it } from 'vitest'
import { buildRoster, userIdOf, type RosterParticipant } from '../roster'

function p(identity: string, name: string, patch: Partial<RosterParticipant> = {}): RosterParticipant {
  return {
    identity,
    name,
    attributes: {},
    permissions: { canPublish: false },
    isSpeaking: false,
    isMicrophoneEnabled: false,
    ...patch,
  }
}

describe('roster', () => {
  it('identity user-{id}에서 userId를 꺼낸다', () => {
    expect(userIdOf('user-42')).toBe('42')
    expect(userIdOf('user-')).toBeNull()
    expect(userIdOf('user-4a')).toBeNull()
    expect(userIdOf('instructor-1')).toBeNull()
  })

  it('참가자를 학생 카드 모델로 바꾼다', () => {
    const roster = buildRoster([
      p('user-7', '이서준', { attributes: { hand: 'up', speaking: 'none' }, isMicrophoneEnabled: true, isSpeaking: true }),
    ])
    expect(roster).toEqual([{ userId: '7', name: '이서준', handUp: true, speakingAllowed: false, micOn: true, isSpeaking: true }])
  })

  it('발언 허용은 speaking 속성이 아니라 LiveKit 권한 canPublish로 판단한다', () => {
    const roster = buildRoster([
      p('user-1', '가', { attributes: { speaking: 'allowed' } }),
      p('user-2', '나', { permissions: { canPublish: true } }),
      p('user-3', '다', { permissions: undefined }),
    ])
    expect(roster.map((s) => [s.userId, s.speakingAllowed])).toEqual([
      ['2', true],
      ['1', false],
      ['3', false],
    ])
  })

  it('이름이 비어 있으면 identity를 보여 준다', () => {
    expect(buildRoster([p('user-5', '')])[0].name).toBe('user-5')
  })

  it('user-{숫자} 형식이 아닌 참가자는 뺀다', () => {
    expect(buildRoster([p('agent-1', '녹음봇'), p('user-x', '?'), p('user-9', '정예준')]).map((s) => s.userId)).toEqual(['9'])
  })

  it('손든 학생 → 발언 허용 학생 → 나머지 순서, 같은 묶음은 이름순', () => {
    const roster = buildRoster([
      p('user-1', '홍시현'),
      p('user-2', '강민서'),
      p('user-3', '이주원', { permissions: { canPublish: true } }),
      p('user-4', '오시우', { attributes: { hand: 'up' } }),
      p('user-5', '김유진', { attributes: { hand: 'up' } }),
    ])
    expect(roster.map((s) => s.name)).toEqual(['김유진', '오시우', '이주원', '강민서', '홍시현'])
  })
})
