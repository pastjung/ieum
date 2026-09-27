import Constants from "expo-constants";
import { router } from "expo-router";
import { useAppUpdateStore, whenAppUpdateStoreHydrated } from "@/stores/appUpdateStore";
import {
  fetchLatestRelease,
  kstDay,
  releaseBaseUrl,
  shouldAnnounceUpdate,
  type ReleaseInfo,
  type UpdateNotice,
} from "./appRelease";

/**
 * 앱을 켤 때 새 버전을 확인한다 (설계 §5.6).
 *
 * 설치된 빌드 번호는 `Constants.expoConfig.android.versionCode`로 읽는다 — 빌드 때 app.json이
 * 임베드되고 CNG prebuild가 같은 값으로 네이티브 versionCode를 쓰므로 같은 수다(expo-application을
 * 새로 들이지 않기 위해). iOS·Expo Go처럼 값이 없으면 확인하지 않는다.
 * 어떤 실패도 사용자에게 드러내지 않는다.
 */
export interface UpdateCheckDeps {
  baseUrl: string | null;
  installedVersionCode: number | null;
  fetchLatest: (baseUrl: string) => Promise<ReleaseInfo | null>;
  now: () => Date;
  loadLastNotice: () => Promise<UpdateNotice | null>;
  saveLastNotice: (notice: UpdateNotice) => void;
  showPrompt: (release: ReleaseInfo) => void;
}

function installedVersionCode(): number | null {
  const code = Constants.expoConfig?.android?.versionCode;
  return typeof code === "number" && Number.isInteger(code) ? code : null;
}

export function defaultUpdateCheckDeps(): UpdateCheckDeps {
  return {
    baseUrl: releaseBaseUrl(process.env.EXPO_PUBLIC_APP_RELEASE_URL),
    installedVersionCode: installedVersionCode(),
    fetchLatest: (baseUrl) => fetchLatestRelease(baseUrl),
    now: () => new Date(),
    loadLastNotice: async () => {
      await whenAppUpdateStoreHydrated();
      return useAppUpdateStore.getState().lastNotice;
    },
    saveLastNotice: (notice) => useAppUpdateStore.getState().setLastNotice(notice),
    showPrompt: (release) => router.push({ pathname: "/app-update", params: { version: release.version } }),
  };
}

/** @returns 알림 화면을 띄웠으면 true. 절대 던지지 않는다 */
export async function checkForAppUpdate(deps: UpdateCheckDeps): Promise<boolean> {
  try {
    if (deps.baseUrl === null || deps.installedVersionCode === null) return false;
    const latest = await deps.fetchLatest(deps.baseUrl);
    if (latest === null) return false;

    const today = kstDay(deps.now());
    const lastNotice = await deps.loadLastNotice();
    if (!shouldAnnounceUpdate(latest.versionCode, deps.installedVersionCode, lastNotice, today)) return false;

    // 화면을 띄우기 전에 적는다 — 사용자가 어느 버튼을 누르든(또는 앱을 꺼도) 오늘은 다시 묻지 않는다
    deps.saveLastNotice({ versionCode: latest.versionCode, day: today });
    deps.showPrompt(latest);
    return true;
  } catch {
    return false;
  }
}

let checkedThisLaunch = false;

/** 탭 화면이 다시 마운트돼도(로그아웃 → 로그인 등) 앱 실행당 한 번만 확인한다 */
export async function checkForAppUpdateOnce(deps?: UpdateCheckDeps): Promise<boolean> {
  if (checkedThisLaunch) return false;
  checkedThisLaunch = true;
  return checkForAppUpdate(deps ?? defaultUpdateCheckDeps());
}
