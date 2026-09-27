import { parseReleaseInfo, RELEASE_INFO_PATH, type ReleaseInfo } from '../model/releaseInfo'

/**
 * 배포 정보를 읽는다. `/api/v1`이 아니라 정적 파일이라 api-client를 쓰지 않는다(인증·JSON 오류 계약이 없다).
 * 파일이 없거나(첫 배포 전), SPA 폴백 HTML이 오거나, 네트워크가 끊겨도 던지지 않고 null — 페이지는 "준비 중이에요".
 */
export async function fetchReleaseInfo(fetchFn: typeof fetch = fetch): Promise<ReleaseInfo | null> {
  try {
    const response = await fetchFn(RELEASE_INFO_PATH, { cache: 'no-store' })
    if (!response.ok) return null
    return parseReleaseInfo(JSON.parse(await response.text()))
  } catch {
    return null
  }
}
