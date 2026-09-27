import {
  fetchLatestRelease,
  kstDay,
  parseReleaseInfo,
  releaseBaseUrl,
  shouldAnnounceUpdate,
} from "@/services/appRelease";

const LATEST = {
  version: "1.0.0",
  versionCode: 2,
  releasedAt: "2026-09-27T12:00:00Z",
  sha256: "ab".repeat(32),
  url: "https://i-eum.site/app/ieum.apk",
  notes: "수업 입장 속도 개선",
};

function fakeResponse(status: number, text: string) {
  return { ok: status >= 200 && status < 300, status, text: async () => text } as unknown as Response;
}

describe("parseReleaseInfo", () => {
  it("계약 형식을 읽는다 (쓰지 않는 필드는 버린다)", () => {
    expect(parseReleaseInfo(LATEST)).toEqual({
      version: "1.0.0",
      versionCode: 2,
      releasedAt: "2026-09-27T12:00:00Z",
      notes: "수업 입장 속도 개선",
    });
  });

  it.each([
    ["versionCode가 문자열", { ...LATEST, versionCode: "2" }],
    ["versionCode가 0", { ...LATEST, versionCode: 0 }],
    ["versionCode가 소수", { ...LATEST, versionCode: 1.5 }],
    ["version이 semver가 아님", { ...LATEST, version: "1.0" }],
    ["releasedAt 없음", { ...LATEST, releasedAt: undefined }],
    ["null", null],
    ["배열", [LATEST]],
  ])("형식이 어긋나면 null (%s)", (_label, raw) => {
    expect(parseReleaseInfo(raw)).toBeNull();
  });
});

describe("releaseBaseUrl", () => {
  it("끝 슬래시와 공백을 지우고, 비어 있으면 null", () => {
    expect(releaseBaseUrl("https://i-eum.site/app/")).toBe("https://i-eum.site/app");
    expect(releaseBaseUrl("  https://i-eum.site/app ")).toBe("https://i-eum.site/app");
    expect(releaseBaseUrl("")).toBeNull();
    expect(releaseBaseUrl(undefined)).toBeNull();
  });
});

describe("kstDay", () => {
  it("한국 시간 기준 날짜를 돌려준다 (UTC 15시가 날짜 경계)", () => {
    expect(kstDay(new Date("2026-09-27T14:59:59Z"))).toBe("2026-09-27");
    expect(kstDay(new Date("2026-09-27T15:00:00Z"))).toBe("2026-09-28");
  });
});

describe("shouldAnnounceUpdate", () => {
  it("설치된 것보다 새 버전일 때만 알린다", () => {
    expect(shouldAnnounceUpdate(2, 1, null, "2026-09-27")).toBe(true);
    expect(shouldAnnounceUpdate(1, 1, null, "2026-09-27")).toBe(false);
    expect(shouldAnnounceUpdate(1, 2, null, "2026-09-27")).toBe(false);
  });

  it("같은 버전은 하루에 한 번만 알린다", () => {
    const notice = { versionCode: 2, day: "2026-09-27" };
    expect(shouldAnnounceUpdate(2, 1, notice, "2026-09-27")).toBe(false);
    expect(shouldAnnounceUpdate(2, 1, notice, "2026-09-28")).toBe(true);
  });

  it("오늘 알린 것보다 더 새 버전이 나오면 다시 알린다", () => {
    expect(shouldAnnounceUpdate(3, 1, { versionCode: 2, day: "2026-09-27" }, "2026-09-27")).toBe(true);
  });
});

describe("fetchLatestRelease", () => {
  it("${base}/latest.json을 읽어 해석한다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(200, JSON.stringify(LATEST)));

    const info = await fetchLatestRelease("https://i-eum.site/app", fetchFn as unknown as typeof fetch);

    expect(info?.versionCode).toBe(2);
    expect((fetchFn.mock.calls[0] as unknown as [string])[0]).toBe("https://i-eum.site/app/latest.json");
  });

  it("404·HTML·네트워크 실패는 모두 null (조용히 넘어간다)", async () => {
    const notFound = jest.fn(async () => fakeResponse(404, "<html>404</html>"));
    const html = jest.fn(async () => fakeResponse(200, "<!doctype html><html></html>"));
    const offline = jest.fn(async () => {
      throw new TypeError("Network request failed");
    });

    await expect(fetchLatestRelease("https://x/app", notFound as unknown as typeof fetch)).resolves.toBeNull();
    await expect(fetchLatestRelease("https://x/app", html as unknown as typeof fetch)).resolves.toBeNull();
    await expect(fetchLatestRelease("https://x/app", offline as unknown as typeof fetch)).resolves.toBeNull();
  });

  it("시간 안에 답이 없으면 끊고 null", async () => {
    jest.useFakeTimers();
    try {
      const hanging = jest.fn(
        (_url: string, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () => reject(new Error("aborted")));
          }),
      );

      const pending = fetchLatestRelease("https://x/app", hanging as unknown as typeof fetch, 5_000);
      jest.advanceTimersByTime(5_000);

      await expect(pending).resolves.toBeNull();
    } finally {
      jest.useRealTimers();
    }
  });
});
