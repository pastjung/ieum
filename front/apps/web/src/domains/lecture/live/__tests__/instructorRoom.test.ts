import { beforeEach, describe, expect, it, vi } from 'vitest'
import { connectInstructorRoom } from '../instructorRoom'
import type { RosterStudent } from '../roster'

type Listener = (...args: unknown[]) => void

interface FakeParticipant {
  identity: string
  name?: string
  attributes: Record<string, string>
  permissions?: { canPublish: boolean }
  isSpeaking: boolean
  isMicrophoneEnabled: boolean
}

function participant(userId: string, name: string): FakeParticipant {
  return {
    identity: `user-${userId}`,
    name,
    attributes: {},
    permissions: { canPublish: false },
    isSpeaking: false,
    isMicrophoneEnabled: false,
  }
}

function fakeTrack(kind: 'audio' | 'video') {
  return { kind, attach: vi.fn(), detach: vi.fn() }
}

const rooms: FakeRoom[] = []
/** 다음에 만들어질 방에 이미 들어와 있는 학생 */
let present: FakeParticipant[] = []

class FakeRoom {
  listeners = new Map<string, Listener[]>()
  remoteParticipants = new Map<string, FakeParticipant>(present.map((p) => [p.identity, p]))
  canPlaybackAudio = true
  connect = vi.fn(async () => undefined)
  disconnect = vi.fn(async () => {
    this.emit('disconnected')
  })
  startAudio = vi.fn(async (): Promise<void> => {})
  localParticipant = { setMicrophoneEnabled: vi.fn(async () => undefined) }
  constructor() {
    rooms.push(this)
  }
  on(event: string, listener: Listener) {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), listener])
    return this
  }
  emit(event: string, ...args: unknown[]) {
    ;(this.listeners.get(event) ?? []).forEach((l) => l(...args))
  }
  join(p: FakeParticipant) {
    this.remoteParticipants.set(p.identity, p)
    this.emit('participantConnected', p)
  }
  leave(p: FakeParticipant) {
    // LiveKit도 목록에서 먼저 지운 뒤 이벤트를 낸다
    this.remoteParticipants.delete(p.identity)
    this.emit('participantDisconnected', p)
  }
}

vi.mock('livekit-client', () => ({
  Room: vi.fn().mockImplementation(() => new FakeRoom()),
  RoomEvent: {
    Reconnecting: 'reconnecting',
    Reconnected: 'reconnected',
    Disconnected: 'disconnected',
    ParticipantConnected: 'participantConnected',
    ParticipantDisconnected: 'participantDisconnected',
    ParticipantAttributesChanged: 'participantAttributesChanged',
    ParticipantPermissionsChanged: 'participantPermissionsChanged',
    ParticipantNameChanged: 'participantNameChanged',
    TrackPublished: 'trackPublished',
    TrackUnpublished: 'trackUnpublished',
    TrackMuted: 'trackMuted',
    TrackUnmuted: 'trackUnmuted',
    ActiveSpeakersChanged: 'activeSpeakersChanged',
    TrackSubscribed: 'trackSubscribed',
    TrackUnsubscribed: 'trackUnsubscribed',
    AudioPlaybackStatusChanged: 'audioPlaybackChanged',
    DataReceived: 'dataReceived',
  },
  Track: { Kind: { Audio: 'audio', Video: 'video' } },
}))

const token = { serverUrl: 'wss://openvidu.i-eum.site', token: 't', expiresAt: 'x' }

beforeEach(() => {
  rooms.length = 0
  present = []
})

describe('instructorRoom', () => {
  it('접속하고 마이크를 켠다', async () => {
    const states: string[] = []
    const room = await connectInstructorRoom(token, (s) => states.push(s))

    expect(rooms[0].connect).toHaveBeenCalledWith('wss://openvidu.i-eum.site', 't')
    expect(rooms[0].localParticipant.setMicrophoneEnabled).toHaveBeenCalledWith(true)
    expect(room.micEnabled()).toBe(true)
    expect(states).toEqual(['connecting', 'connected'])
  })

  it('마이크를 끄고 켠다', async () => {
    const room = await connectInstructorRoom(token, () => {})

    await room.setMicEnabled(false)

    expect(rooms[0].localParticipant.setMicrophoneEnabled).toHaveBeenLastCalledWith(false)
    expect(room.micEnabled()).toBe(false)
  })

  it('재연결과 해제 상태를 알린다', async () => {
    const states: string[] = []
    await connectInstructorRoom(token, (s) => states.push(s))

    rooms[0].emit('reconnecting')
    rooms[0].emit('reconnected')
    rooms[0].emit('disconnected')

    expect(states).toEqual(['connecting', 'connected', 'reconnecting', 'connected', 'disconnected'])
  })

  it('접속이 실패하면 오류를 던지고 해제 상태를 알린다', async () => {
    const states: string[] = []
    const { Room } = await import('livekit-client')
    vi.mocked(Room).mockImplementationOnce(() => {
      const room = new FakeRoom()
      room.connect.mockRejectedValue(new Error('ws failed'))
      return room as never
    })

    await expect(connectInstructorRoom(token, (s) => states.push(s))).rejects.toThrow('ws failed')
    expect(states).toEqual(['connecting', 'disconnected'])
  })

  it('마이크를 켜지 못하면 연결을 정리하고 해제 상태를 한 번만 알린다', async () => {
    const states: string[] = []
    const { Room } = await import('livekit-client')
    vi.mocked(Room).mockImplementationOnce(() => {
      const room = new FakeRoom()
      room.localParticipant.setMicrophoneEnabled.mockRejectedValue(new Error('mic denied'))
      return room as never
    })

    await expect(connectInstructorRoom(token, (s) => states.push(s))).rejects.toThrow('mic denied')

    expect(rooms[0].disconnect).toHaveBeenCalled()
    expect(rooms[0].startAudio).not.toHaveBeenCalled()
    expect(states).toEqual(['connecting', 'connected', 'disconnected'])
  })

  it('접속하면 이미 들어와 있는 학생 목록을 알리고 스냅샷으로도 준다', async () => {
    present = [participant('7', '이서준'), participant('3', '김도윤')]
    const rosters: RosterStudent[][] = []

    const room = await connectInstructorRoom(token, () => {}, { onRoster: (r) => rosters.push(r) })

    expect(rosters.map((r) => r.map((s) => s.userId))).toEqual([['3', '7']])
    expect(room.roster().map((s) => s.name)).toEqual(['김도윤', '이서준'])
  })

  it('입장·손들기·발언 권한·마이크·말하는 중·퇴장 때마다 새 목록을 알린다', async () => {
    const rosters: RosterStudent[][] = []
    const last = () => rosters[rosters.length - 1]
    await connectInstructorRoom(token, () => {}, { onRoster: (r) => rosters.push(r) })
    const room = rooms[0]
    const a = participant('1', '박지호')
    const b = participant('2', '강민서')

    room.join(a)
    room.join(b)
    expect(last().map((s) => s.name)).toEqual(['강민서', '박지호'])

    a.attributes = { hand: 'up' }
    room.emit('participantAttributesChanged', { hand: 'up' }, a)
    expect(last().map((s) => [s.name, s.handUp])).toEqual([
      ['박지호', true],
      ['강민서', false],
    ])

    a.attributes = { hand: 'down', speaking: 'allowed' }
    a.permissions = { canPublish: true }
    room.emit('participantPermissionsChanged', { canPublish: false }, a)
    expect(last()[0]).toMatchObject({ userId: '1', handUp: false, speakingAllowed: true })

    a.isMicrophoneEnabled = true
    room.emit('trackPublished', {}, a)
    a.isSpeaking = true
    room.emit('activeSpeakersChanged', [a])
    expect(last()[0]).toMatchObject({ userId: '1', micOn: true, isSpeaking: true })

    room.leave(a)
    expect(last().map((s) => s.userId)).toEqual(['2'])
  })

  it('학생 오디오 트랙만 재생 요소에 붙이고, 구독이 끝나면 뗀다', async () => {
    await connectInstructorRoom(token, () => {})
    const a = participant('1', '박지호')
    const voice = fakeTrack('audio')
    const video = fakeTrack('video')

    rooms[0].emit('trackSubscribed', voice, {}, a)
    rooms[0].emit('trackSubscribed', video, {}, a)
    expect(voice.attach).toHaveBeenCalledTimes(1)
    expect(video.attach).not.toHaveBeenCalled()

    rooms[0].emit('trackUnsubscribed', voice, {}, a)
    rooms[0].emit('trackUnsubscribed', video, {}, a)
    expect(voice.detach).toHaveBeenCalledTimes(1)
    expect(video.detach).not.toHaveBeenCalled()
  })

  it('연결을 끊으면 붙인 소리를 모두 한 번씩 떼고 빈 목록을 알린다', async () => {
    present = [participant('1', '박지호')]
    const rosters: RosterStudent[][] = []
    const room = await connectInstructorRoom(token, () => {}, { onRoster: (r) => rosters.push(r) })
    const voice = fakeTrack('audio')
    rooms[0].emit('trackSubscribed', voice, {}, present[0])

    await room.disconnect()

    expect(rooms[0].disconnect).toHaveBeenCalled()
    expect(voice.detach).toHaveBeenCalledTimes(1)
    expect(rosters[rosters.length - 1]).toEqual([])
  })

  it('내 마이크 소리 크기를 0~1로 알려 준다', async () => {
    const room = await connectInstructorRoom(token, () => {})
    expect(room.micLevel()).toBe(0)
    ;(rooms[0].localParticipant as { audioLevel?: number }).audioLevel = 0.4
    expect(room.micLevel()).toBe(0.4)
  })

  it('마이크를 켠 뒤 소리 재생을 풀어 보고, 막혔으면 다시 시도할 수 있다', async () => {
    const { Room } = await import('livekit-client')
    vi.mocked(Room).mockImplementationOnce(() => {
      const room = new FakeRoom()
      room.startAudio.mockImplementation(async () => {
        room.canPlaybackAudio = false
        throw new DOMException('blocked', 'NotAllowedError')
      })
      return room as never
    })

    const room = await connectInstructorRoom(token, () => {})
    expect(rooms[0].startAudio).toHaveBeenCalledTimes(1)
    expect(room.canPlaybackAudio()).toBe(false)

    rooms[0].startAudio.mockImplementation(async () => {
      rooms[0].canPlaybackAudio = true
    })
    await expect(room.startAudio()).resolves.toBe(true)
    expect(room.canPlaybackAudio()).toBe(true)
  })

  it('권한 프롬프트가 열려 있는 동안 밀려나면 학생 오디오를 붙이지 않고 방을 끊는다', async () => {
    const states: string[] = []
    let resolveMic: () => void = () => {}
    const micPromise = new Promise<undefined>((resolve) => {
      resolveMic = () => resolve(undefined)
    })
    const { Room } = await import('livekit-client')
    vi.mocked(Room).mockImplementationOnce(() => {
      const room = new FakeRoom()
      room.localParticipant.setMicrophoneEnabled.mockImplementation(() => micPromise)
      return room as never
    })

    // 언마운트나 다음 접속으로 이미 밀려난 상태로 가정한다 — 권한 프롬프트는 아직 응답 전이다
    const connecting = connectInstructorRoom(token, (s) => states.push(s), { isStale: () => true })
    await Promise.resolve()
    await Promise.resolve()

    // 권한 프롬프트가 열려 있는 사이 학생 오디오가 구독됐다 — 밀려난 접속이므로 붙이지 않는다
    const voice = fakeTrack('audio')
    rooms[0].emit('trackSubscribed', voice, {}, participant('1', '박지호'))
    expect(voice.attach).not.toHaveBeenCalled()

    resolveMic()
    const room = await connecting

    expect(voice.attach).not.toHaveBeenCalled()
    expect(rooms[0].startAudio).not.toHaveBeenCalled()
    expect(rooms[0].disconnect).toHaveBeenCalledTimes(1)
    expect(states).toEqual(['connecting', 'connected', 'disconnected'])
    expect(room.micEnabled()).toBe(true)
  })

  it('소리 재생 상태 변화를 알린다', async () => {
    const playback: boolean[] = []
    await connectInstructorRoom(token, () => {}, { onAudioPlayback: (playing) => playback.push(playing) })

    rooms[0].emit('audioPlaybackChanged', false)
    rooms[0].emit('audioPlaybackChanged', true)

    expect(playback).toEqual([false, true])
  })

  it('lecture 토픽의 답안 제출 신호에서 problemId를 알린다', async () => {
    const submitted: string[] = []
    await connectInstructorRoom(token, () => {}, { onAnswerSubmitted: (id) => submitted.push(id) })
    const encode = (value: unknown) => new TextEncoder().encode(JSON.stringify(value))

    // 서버가 보낸 메시지는 participant가 undefined다
    rooms[0].emit('dataReceived', encode({ type: 'ANSWER_SUBMITTED', problemId: '26578' }), undefined, 0, 'lecture')
    rooms[0].emit('dataReceived', encode({ type: 'ANSWER_SUBMITTED', problemId: '1' }), undefined, 0, 'chat')
    rooms[0].emit('dataReceived', new TextEncoder().encode('{broken'), undefined, 0, 'lecture')

    expect(submitted).toEqual(['26578'])
  })

  it('밀려난 접속은 답안 신호를 알리지 않는다', async () => {
    let stale = false
    const submitted: string[] = []
    await connectInstructorRoom(token, () => {}, { onAnswerSubmitted: (id) => submitted.push(id), isStale: () => stale })

    stale = true
    rooms[0].emit(
      'dataReceived',
      new TextEncoder().encode(JSON.stringify({ type: 'ANSWER_SUBMITTED', problemId: '26578' })),
      undefined,
      0,
      'lecture',
    )

    expect(submitted).toEqual([])
  })
})
