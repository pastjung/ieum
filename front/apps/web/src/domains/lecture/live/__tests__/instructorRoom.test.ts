import { beforeEach, describe, expect, it, vi } from 'vitest'
import { connectInstructorRoom } from '../instructorRoom'

type Listener = (...args: unknown[]) => void

const rooms: FakeRoom[] = []

class FakeRoom {
  listeners = new Map<string, Listener[]>()
  connect = vi.fn(async () => undefined)
  disconnect = vi.fn(async () => {
    this.emit('disconnected')
  })
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
}

vi.mock('livekit-client', () => ({
  Room: vi.fn().mockImplementation(() => new FakeRoom()),
  RoomEvent: { Reconnecting: 'reconnecting', Reconnected: 'reconnected', Disconnected: 'disconnected' },
}))

const token = { serverUrl: 'wss://openvidu.i-eum.site', token: 't', expiresAt: 'x' }

beforeEach(() => {
  rooms.length = 0
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
})
