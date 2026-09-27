import type { ReleaseInfo, UpdateNotice } from "@/services/appRelease";
import { checkForAppUpdate, checkForAppUpdateOnce, type UpdateCheckDeps } from "@/services/appUpdateCheck";
import { useAppUpdateStore, whenAppUpdateStoreHydrated } from "@/stores/appUpdateStore";

jest.mock("@react-native-async-storage/async-storage", () =>
  require("@react-native-async-storage/async-storage/jest/async-storage-mock"),
);
jest.mock("expo-router", () => ({ router: { push: jest.fn() } }));

const RELEASE: ReleaseInfo = { version: "1.1.0", versionCode: 2, releasedAt: "2026-09-27T12:00:00Z" };
const NOON_KST = new Date("2026-09-27T03:00:00Z");

function deps(overrides: Partial<UpdateCheckDeps> = {}) {
  let saved: UpdateNotice | null = null;
  const base: UpdateCheckDeps = {
    baseUrl: "https://i-eum.site/app",
    installedVersionCode: 1,
    fetchLatest: jest.fn(async () => RELEASE),
    now: () => NOON_KST,
    loadLastNotice: jest.fn(async () => saved),
    saveLastNotice: jest.fn((notice: UpdateNotice) => {
      saved = notice;
    }),
    showPrompt: jest.fn(),
  };
  return { ...base, ...overrides };
}

describe("checkForAppUpdate", () => {
  it("새 버전이면 알림 화면을 띄우고 오늘 알렸다고 적는다", async () => {
    const d = deps();

    await expect(checkForAppUpdate(d)).resolves.toBe(true);

    expect(d.fetchLatest).toHaveBeenCalledWith("https://i-eum.site/app");
    expect(d.showPrompt).toHaveBeenCalledWith(RELEASE);
    expect(d.saveLastNotice).toHaveBeenCalledWith({ versionCode: 2, day: "2026-09-27" });
  });

  it("같은 날 같은 버전은 다시 알리지 않고, 다음 날엔 다시 알린다", async () => {
    const d = deps();
    await checkForAppUpdate(d);

    await expect(checkForAppUpdate(d)).resolves.toBe(false);
    await expect(checkForAppUpdate({ ...d, now: () => new Date("2026-09-28T03:00:00Z") })).resolves.toBe(true);
    expect(d.showPrompt).toHaveBeenCalledTimes(2);
  });

  it("설치된 버전이 최신이면 알리지 않는다", async () => {
    const d = deps({ installedVersionCode: 2 });

    await expect(checkForAppUpdate(d)).resolves.toBe(false);
    expect(d.showPrompt).not.toHaveBeenCalled();
  });

  it("주소가 없거나 설치 버전을 모르면 서버를 부르지 않는다", async () => {
    const noUrl = deps({ baseUrl: null });
    const noVersion = deps({ installedVersionCode: null });

    await expect(checkForAppUpdate(noUrl)).resolves.toBe(false);
    await expect(checkForAppUpdate(noVersion)).resolves.toBe(false);
    expect(noUrl.fetchLatest).not.toHaveBeenCalled();
    expect(noVersion.fetchLatest).not.toHaveBeenCalled();
  });

  it("조회 실패·저장소 오류·화면 오류는 조용히 넘어간다", async () => {
    const noRelease = deps({ fetchLatest: jest.fn(async () => null) });
    const brokenStore = deps({
      loadLastNotice: jest.fn(async () => {
        throw new Error("storage");
      }),
    });
    const brokenPrompt = deps({
      showPrompt: jest.fn(() => {
        throw new Error("navigation");
      }),
    });

    await expect(checkForAppUpdate(noRelease)).resolves.toBe(false);
    await expect(checkForAppUpdate(brokenStore)).resolves.toBe(false);
    await expect(checkForAppUpdate(brokenPrompt)).resolves.toBe(false);
  });
});

describe("checkForAppUpdateOnce", () => {
  it("앱 실행마다 한 번만 확인한다", async () => {
    const d = deps();

    await checkForAppUpdateOnce(d);
    await checkForAppUpdateOnce(d);

    expect(d.fetchLatest).toHaveBeenCalledTimes(1);
  });
});

describe("appUpdateStore", () => {
  it("복원을 기다린 뒤 마지막 알림을 읽고 쓴다", async () => {
    await whenAppUpdateStoreHydrated();

    useAppUpdateStore.getState().setLastNotice({ versionCode: 2, day: "2026-09-27" });

    expect(useAppUpdateStore.getState().lastNotice).toEqual({ versionCode: 2, day: "2026-09-27" });
  });
});
