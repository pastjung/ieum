import { describe, expect, it, vi } from 'vitest'
import { fetchReleaseInfo } from '../releaseApi'

const LATEST = { version: '1.0.0', versionCode: 1, releasedAt: '2026-09-27T12:00:00Z', notes: '첫 배포' }

describe('fetchReleaseInfo', () => {
  it('같은 출처의 /app/latest.json을 캐시 없이 읽는다', async () => {
    const fetchFn = vi.fn(async () => new Response(JSON.stringify(LATEST), { status: 200 }))

    const info = await fetchReleaseInfo(fetchFn as unknown as typeof fetch)

    expect(info?.version).toBe('1.0.0')
    expect(fetchFn).toHaveBeenCalledWith('/app/latest.json', { cache: 'no-store' })
  })

  it('파일이 없으면(404) null', async () => {
    const fetchFn = vi.fn(async () => new Response('not found', { status: 404 }))
    await expect(fetchReleaseInfo(fetchFn as unknown as typeof fetch)).resolves.toBeNull()
  })

  it('SPA 폴백 HTML(호스트 nginx 설정 전·개발 서버)이면 null', async () => {
    const fetchFn = vi.fn(async () => new Response('<!doctype html><html></html>', { status: 200 }))
    await expect(fetchReleaseInfo(fetchFn as unknown as typeof fetch)).resolves.toBeNull()
  })

  it('네트워크 실패도 던지지 않고 null', async () => {
    const fetchFn = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    await expect(fetchReleaseInfo(fetchFn as unknown as typeof fetch)).resolves.toBeNull()
  })
})
