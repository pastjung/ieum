import { z } from "zod";

/**
 * 앱 새 버전 확인에 쓰는 순수 함수들 (설계 §5.6). React Native에 의존하지 않는다.
 *
 * 서버 `${EXPO_PUBLIC_APP_RELEASE_URL}/latest.json` 형식(계약):
 * `{ version, versionCode, releasedAt, sha256, url, notes }` — 앱은 비교에 versionCode만,
 * 안내에 version만 쓴다. 형식이 어긋나거나 못 읽으면 null — 새 버전 알림은 **조용히** 실패한다.
 */
export const UPDATE_PROMPT_MESSAGE = "새 버전이 있어요. 설치 페이지를 열까요?";
export const UPDATE_OPEN_FAILED_MESSAGE = "설치 페이지를 열지 못했어요. 잠시 뒤 다시 눌러 주세요.";

const releaseInfoSchema = z.object({
  version: z.string().regex(/^\d+\.\d+\.\d+$/),
  versionCode: z.number().int().positive(),
  releasedAt: z.string().min(1),
  notes: z.string().optional(),
});

export type ReleaseInfo = z.infer<typeof releaseInfoSchema>;

export function parseReleaseInfo(raw: unknown): ReleaseInfo | null {
  const result = releaseInfoSchema.safeParse(raw);
  return result.success ? result.data : null;
}

/** 환경변수 값을 정리한다. 비어 있으면 확인 기능 자체를 끈다(개발 빌드 기본값) */
export function releaseBaseUrl(raw: string | undefined): string | null {
  const trimmed = (raw ?? "").trim().replace(/\/+$/, "");
  return trimmed.length > 0 ? trimmed : null;
}

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** "하루에 한 번"의 하루는 한국 날짜다 */
export function kstDay(now: Date): string {
  return new Date(now.getTime() + KST_OFFSET_MS).toISOString().slice(0, 10);
}

/** 마지막으로 알린 버전과 그 날짜 */
export interface UpdateNotice {
  versionCode: number;
  day: string;
}

export function shouldAnnounceUpdate(
  latest: number,
  installed: number,
  lastNotice: UpdateNotice | null,
  today: string,
): boolean {
  if (latest <= installed) return false;
  return !(lastNotice !== null && lastNotice.versionCode === latest && lastNotice.day === today);
}

/** 절대 던지지 않는다. 못 읽으면 null */
export async function fetchLatestRelease(
  baseUrl: string,
  fetchFn: typeof fetch = fetch,
  timeoutMs = 5_000,
): Promise<ReleaseInfo | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchFn(`${baseUrl}/latest.json`, {
      headers: { "Cache-Control": "no-cache" },
      signal: controller.signal,
    });
    if (!response.ok) return null;
    return parseReleaseInfo(JSON.parse(await response.text()));
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}
