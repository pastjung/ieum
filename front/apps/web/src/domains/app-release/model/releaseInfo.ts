/**
 * 앱 배포 정보 `latest.json` (설계 §5.4·§5.5, 계약 형식):
 * `{ version, versionCode, releasedAt, sha256, url, notes }` — 페이지는 버전·배포일·메모만 쓴다.
 * 파일은 호스트 nginx가 `/app/` 아래에서 web보다 먼저 제공한다(같은 출처).
 */
export interface ReleaseInfo {
  version: string
  versionCode: number
  releasedAt: string
  notes: string | null
}

export const RELEASE_INFO_PATH = '/app/latest.json'
export const APK_PATH = '/app/ieum.apk'

const SEMVER = /^\d+\.\d+\.\d+$/

export function parseReleaseInfo(raw: unknown): ReleaseInfo | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null
  const { version, versionCode, releasedAt, notes } = raw as Record<string, unknown>
  if (typeof version !== 'string' || !SEMVER.test(version)) return null
  if (typeof versionCode !== 'number' || !Number.isInteger(versionCode) || versionCode < 1) return null
  if (typeof releasedAt !== 'string' || Number.isNaN(Date.parse(releasedAt))) return null
  return {
    version,
    versionCode,
    releasedAt,
    notes: typeof notes === 'string' && notes.trim() !== '' ? notes.trim() : null,
  }
}

const KST_OFFSET_MS = 9 * 60 * 60 * 1000

/** 보는 사람의 시간대와 무관하게 한국 날짜로 적는다 */
export function formatReleaseDate(iso: string): string {
  const kst = new Date(Date.parse(iso) + KST_OFFSET_MS)
  return `${kst.getUTCFullYear()}년 ${kst.getUTCMonth() + 1}월 ${kst.getUTCDate()}일`
}
