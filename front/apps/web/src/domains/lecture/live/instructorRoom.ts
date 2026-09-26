import { Room, RoomEvent, Track, type RemoteTrack } from 'livekit-client'
import type { MediaToken } from '../model/types'
import { buildRoster, type RosterStudent } from './roster'

export type RoomState = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

export interface RoomListeners {
  /** 학생 목록이 바뀔 때마다 정렬된 새 목록. 방이 끊기면 빈 목록 */
  onRoster?: (students: RosterStudent[]) => void
  /** 브라우저가 학생 소리 재생을 막으면 false, 풀리면 true */
  onAudioPlayback?: (playing: boolean) => void
  /**
   * true면 이 접속은 더 이상 필요 없다(언마운트되었거나 다음 접속에 밀려났다).
   * 마이크 권한 프롬프트가 열려 있는 동안에도 확인하므로, 그 사이 구독된 학생 오디오를 붙이지 않고
   * 권한 응답 뒤에는 방을 바로 끊는다 — 호출자가 반환값을 받기 전에도 재생·연결이 남지 않는다
   */
  isStale?: () => boolean
}

export interface InstructorRoom {
  setMicEnabled(enabled: boolean): Promise<void>
  micEnabled(): boolean
  /** 지금 방에 있는 학생 목록 스냅샷 (정렬됨) */
  roster(): RosterStudent[]
  /** false면 브라우저 자동 재생 제한으로 학생 소리가 나지 않는다 */
  canPlaybackAudio(): boolean
  /** 막힌 소리 재생을 푼다. 사용자 클릭 안에서 부른다. throw하지 않고, 풀렸으면 true */
  startAudio(): Promise<boolean>
  disconnect(): Promise<void>
}

/**
 * 강사 음성 방 (API 명세 8.5절, 양방향 음성 설계 §4). 강사는 마이크를 송출하고,
 * 강사가 발언을 허용한 학생의 목소리를 듣는다. 학생 목록은 LiveKit 원격 참가자에서 만든다.
 * 브라우저 마이크 권한은 HTTPS 또는 localhost에서만 열린다.
 */
export async function connectInstructorRoom(
  token: MediaToken,
  onState: (state: RoomState) => void,
  listeners: RoomListeners = {},
): Promise<InstructorRoom> {
  const room = new Room()
  let mic = false
  let lastState: RoomState | undefined
  const attached = new Set<RemoteTrack>()

  const emit = (state: RoomState) => {
    lastState = state
    onState(state)
  }

  const snapshot = () => buildRoster(room.remoteParticipants.values())
  const publishRoster = () => listeners.onRoster?.(snapshot())

  const detachAll = () => {
    attached.forEach((track) => track.detach())
    attached.clear()
  }

  const startAudio = async () => {
    try {
      await room.startAudio()
    } catch {
      // 여전히 막혀 있다 — 결과는 canPlaybackAudio와 AudioPlaybackStatusChanged로 알린다
    }
    return room.canPlaybackAudio
  }

  room.on(RoomEvent.Reconnecting, () => emit('reconnecting'))
  room.on(RoomEvent.Reconnected, () => emit('connected'))
  room.on(RoomEvent.Disconnected, () => {
    detachAll()
    emit('disconnected')
    listeners.onRoster?.([])
  })

  // 입장·퇴장, 손들기(attributes), 발언 권한(permissions), 이름,
  // 마이크(첫 켜기 = 게시, 끄기/켜기 = 음소거, 발언 회수 = 서버가 내림), 말하는 중
  room
    .on(RoomEvent.ParticipantConnected, publishRoster)
    .on(RoomEvent.ParticipantDisconnected, publishRoster)
    .on(RoomEvent.ParticipantAttributesChanged, publishRoster)
    .on(RoomEvent.ParticipantPermissionsChanged, publishRoster)
    .on(RoomEvent.ParticipantNameChanged, publishRoster)
    .on(RoomEvent.TrackPublished, publishRoster)
    .on(RoomEvent.TrackUnpublished, publishRoster)
    .on(RoomEvent.TrackMuted, publishRoster)
    .on(RoomEvent.TrackUnmuted, publishRoster)
    .on(RoomEvent.ActiveSpeakersChanged, publishRoster)

  // 학생 목소리 재생. attach()가 audio 요소를 만들고 play()까지 부른다 — 문서에 붙이지 않아도 재생된다
  room.on(RoomEvent.TrackSubscribed, (track) => {
    if (track.kind !== Track.Kind.Audio) return
    // 마이크 권한 프롬프트가 열려 있는 동안 이 접속이 밀려났으면(언마운트 등) 붙이지 않는다 —
    // 아무도 참조하지 않을 방의 소리가 다음 화면에서 계속 재생되는 일을 막는다
    if (listeners.isStale?.()) return
    track.attach()
    attached.add(track)
  })
  room.on(RoomEvent.TrackUnsubscribed, (track) => {
    if (attached.delete(track)) track.detach()
  })
  room.on(RoomEvent.AudioPlaybackStatusChanged, (playing) => listeners.onAudioPlayback?.(playing))

  emit('connecting')
  try {
    await room.connect(token.serverUrl, token.token)
  } catch (error) {
    emit('disconnected')
    throw error
  }
  emit('connected')
  publishRoster()

  try {
    await room.localParticipant.setMicrophoneEnabled(true)
  } catch (error) {
    try {
      await room.disconnect()
    } catch {
      // 연결 정리 중 발생한 오류는 원래 마이크 오류를 가리므로 무시한다
    }
    if (lastState !== 'disconnected') {
      emit('disconnected')
    }
    throw error
  }
  mic = true
  if (listeners.isStale?.()) {
    // 권한 프롬프트를 기다리는 사이 이 접속이 밀려났다 — 그새 붙였을 수 있는 오디오를 떼고
    // 방을 바로 끊어, 호출자가 반환값을 확인하기 전에도 연결·재생이 남지 않게 한다
    detachAll()
    try {
      await room.disconnect()
    } catch {
      // 이미 밀려난 접속의 정리 오류는 무시한다
    }
  } else {
    // "수업 시작" 클릭에서 이어진 흐름이면 여기서 재생이 풀린다. 새로고침 자동 접속처럼 제스처가 없으면
    // 막힐 수 있고, 그때는 화면이 "학생 소리 켜기" 버튼으로 다시 부른다
    await startAudio()
  }

  return {
    async setMicEnabled(enabled) {
      await room.localParticipant.setMicrophoneEnabled(enabled)
      mic = enabled
    },
    micEnabled: () => mic,
    roster: snapshot,
    canPlaybackAudio: () => room.canPlaybackAudio,
    startAudio,
    async disconnect() {
      detachAll()
      await room.disconnect()
    },
  }
}
