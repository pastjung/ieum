import type { ApiClient } from '@ieum/api-client'
import { describe, expect, it, vi } from 'vitest'
import { createLectureApi, toScheduledAtUtc } from '../lectureApi'

function fakeClient() {
  return { get: vi.fn(async () => ({})), post: vi.fn(async () => ({})), put: vi.fn(async () => ({})) }
}

describe('lectureApi', () => {
  it('내 수업 목록을 최신순 페이지로 조회한다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).list(0, 20)
    expect(client.get).toHaveBeenCalledWith('/lectures?page=0&size=20')
  })

  it('빈 설명과 시작 시각은 보내지 않는다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).create({ title: '약자 4회차', description: '  ' })
    expect(client.post).toHaveBeenCalledWith('/lectures', { title: '약자 4회차' })
  })

  it('설명과 시작 시각이 있으면 함께 보낸다', async () => {
    const client = fakeClient()
    await createLectureApi(client as unknown as ApiClient).create({
      title: 't',
      description: '규칙 중심',
      scheduledAt: '2026-09-26T01:40:00.000Z',
    })
    expect(client.post).toHaveBeenCalledWith('/lectures', {
      title: 't',
      description: '규칙 중심',
      scheduledAt: '2026-09-26T01:40:00.000Z',
    })
  })

  it('상태 변경과 음성 토큰 경로', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    await lectures.get('42')
    await lectures.changeStatus('42', 'OPEN')
    await lectures.issueMediaToken('42')

    expect(client.get).toHaveBeenCalledWith('/lectures/42')
    expect(client.put).toHaveBeenCalledWith('/lectures/42/status', { status: 'OPEN' })
    expect(client.post).toHaveBeenCalledWith('/lectures/42/media-tokens')
  })

  it('lectureId를 경로에 넣을 때 인코딩한다', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    const id = '42/../etc'
    await lectures.get(id)
    await lectures.changeStatus(id, 'OPEN')
    await lectures.issueMediaToken(id)

    const encoded = encodeURIComponent(id)
    expect(client.get).toHaveBeenCalledWith(`/lectures/${encoded}`)
    expect(client.put).toHaveBeenCalledWith(`/lectures/${encoded}/status`, { status: 'OPEN' })
    expect(client.post).toHaveBeenCalledWith(`/lectures/${encoded}/media-tokens`)
  })

  it('datetime-local 값을 UTC ISO로 바꾼다', () => {
    const local = '2026-09-26T10:40'
    expect(toScheduledAtUtc(local)).toBe(new Date(local).toISOString())
  })
})
