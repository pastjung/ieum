import { Room, RoomEvent } from 'livekit-client'
import type { MediaToken } from '../model/types'

export type RoomState = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

export interface InstructorRoom {
  setMicEnabled(enabled: boolean): Promise<void>
  micEnabled(): boolean
  disconnect(): Promise<void>
}

/**
 * 강사 → 학습자 단방향 음성 (API 명세 8.5절). 강사 토큰은 마이크 송출 권한만 있다.
 * 학습자는 숨김 참가자라 여기서 참가자 목록을 볼 수 없다.
 * 브라우저 마이크 권한은 HTTPS 또는 localhost에서만 열린다.
 */
export async function connectInstructorRoom(
  token: MediaToken,
  onState: (state: RoomState) => void,
): Promise<InstructorRoom> {
  const room = new Room()
  let mic = false
  let lastState: RoomState | undefined

  const emit = (state: RoomState) => {
    lastState = state
    onState(state)
  }

  room.on(RoomEvent.Reconnecting, () => emit('reconnecting'))
  room.on(RoomEvent.Reconnected, () => emit('connected'))
  room.on(RoomEvent.Disconnected, () => emit('disconnected'))

  emit('connecting')
  try {
    await room.connect(token.serverUrl, token.token)
  } catch (error) {
    emit('disconnected')
    throw error
  }
  emit('connected')

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

  return {
    async setMicEnabled(enabled) {
      await room.localParticipant.setMicrophoneEnabled(enabled)
      mic = enabled
    },
    micEnabled: () => mic,
    disconnect: () => room.disconnect(),
  }
}
