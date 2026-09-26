/**
 * 강사 화면 학생 카드 한 장 (양방향 음성 설계 §3.3·§4). 원천은 LiveKit 원격 참가자다.
 * 이 모듈은 LiveKit을 import하지 않는다 — 필요한 필드만 구조 타입으로 받아 node 환경에서 테스트한다.
 */
export interface RosterStudent {
  userId: string
  name: string
  /** attributes.hand === 'up' */
  handUp: boolean
  /** LiveKit 권한 permissions.canPublish. attributes.speaking은 표시 전용이라 쓰지 않는다 */
  speakingAllowed: boolean
  micOn: boolean
  isSpeaking: boolean
}

/** roster가 읽는 참가자 필드. livekit-client의 RemoteParticipant가 그대로 만족한다 */
export interface RosterParticipant {
  identity: string
  name?: string
  attributes: Readonly<Record<string, string>>
  permissions?: { canPublish: boolean }
  isSpeaking: boolean
  isMicrophoneEnabled: boolean
}

const IDENTITY = /^user-(\d+)$/

/** identity `user-{userId}` → userId. 규약 밖의 참가자는 null */
export function userIdOf(identity: string): string | null {
  const match = IDENTITY.exec(identity)
  return match ? match[1] : null
}

function toStudent(participant: RosterParticipant): RosterStudent | null {
  const userId = userIdOf(participant.identity)
  if (userId === null) return null
  return {
    userId,
    name: participant.name || participant.identity,
    handUp: participant.attributes.hand === 'up',
    speakingAllowed: participant.permissions?.canPublish === true,
    micOn: participant.isMicrophoneEnabled,
    isSpeaking: participant.isSpeaking,
  }
}

/** 손든 학생(0) → 발언 허용 학생(1) → 나머지(2) */
function group(student: RosterStudent): number {
  if (student.handUp) return 0
  if (student.speakingAllowed) return 1
  return 2
}

export function buildRoster(participants: Iterable<RosterParticipant>): RosterStudent[] {
  const students: RosterStudent[] = []
  for (const participant of participants) {
    const student = toStudent(participant)
    if (student) students.push(student)
  }
  return students.sort(
    (a, b) => group(a) - group(b) || a.name.localeCompare(b.name, 'ko') || a.userId.localeCompare(b.userId),
  )
}
