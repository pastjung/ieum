import { describe, expect, it } from 'vitest'
import { formatReleaseDate, parseReleaseInfo } from '../releaseInfo'

const LATEST = {
  version: '1.0.0',
  versionCode: 1,
  releasedAt: '2026-09-27T12:00:00Z',
  sha256: 'ab'.repeat(32),
  url: 'https://i-eum.site/app/ieum.apk',
  notes: '첫 배포',
}

describe('parseReleaseInfo', () => {
  it('계약 형식에서 화면에 쓰는 필드만 읽는다', () => {
    expect(parseReleaseInfo(LATEST)).toEqual({
      version: '1.0.0',
      versionCode: 1,
      releasedAt: '2026-09-27T12:00:00Z',
      notes: '첫 배포',
    })
  })

  it('notes가 없거나 비었거나 문자열이 아니면 null로 둔다 (정보 자체는 유효)', () => {
    expect(parseReleaseInfo({ ...LATEST, notes: undefined })?.notes).toBeNull()
    expect(parseReleaseInfo({ ...LATEST, notes: '   ' })?.notes).toBeNull()
    expect(parseReleaseInfo({ ...LATEST, notes: 3 })?.notes).toBeNull()
  })

  it.each([
    ['version이 semver가 아님', { ...LATEST, version: '1.0' }],
    ['version이 숫자', { ...LATEST, version: 1 }],
    ['versionCode가 0', { ...LATEST, versionCode: 0 }],
    ['versionCode가 문자열', { ...LATEST, versionCode: '1' }],
    ['releasedAt이 날짜가 아님', { ...LATEST, releasedAt: '어제' }],
    ['null', null],
    ['배열', [LATEST]],
    ['문자열', '<!doctype html>'],
  ])('형식이 어긋나면 null (%s)', (_label, raw) => {
    expect(parseReleaseInfo(raw)).toBeNull()
  })
})

describe('formatReleaseDate', () => {
  it('한국 날짜로 적는다 (UTC 15시가 날짜 경계)', () => {
    expect(formatReleaseDate('2026-09-27T12:00:00Z')).toBe('2026년 9월 27일')
    expect(formatReleaseDate('2026-09-27T15:00:00Z')).toBe('2026년 9월 28일')
  })
})
