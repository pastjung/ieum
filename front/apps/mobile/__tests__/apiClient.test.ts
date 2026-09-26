import { ApiError, createClient, type TokenStore } from "@ieum/api-client";

/** 클라이언트가 실제로 읽는 필드만 가진 가짜 응답. 런타임 fetch 구현에 의존하지 않는다 */
function fakeResponse(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body === undefined ? "" : JSON.stringify(body)),
  } as unknown as Response;
}

function memoryTokenStore(access: string | null, refresh: string | null) {
  const state = { access, refresh };
  const store: TokenStore = {
    getAccess: () => state.access,
    getRefresh: async () => state.refresh,
    save: async (tokens) => {
      state.access = tokens.accessToken;
      state.refresh = tokens.refreshToken;
    },
    clear: async () => {
      state.access = null;
      state.refresh = null;
    },
  };
  return { store, state };
}

describe("api-client 요청 조립", () => {
  it("base URL을 붙이고 플랫폼·인증 헤더를 보낸다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(200, { username: "hana" }));
    const { store } = memoryTokenStore("access-1", "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await api.get("/users/me");

    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/v1/users/me");
    const headers = init.headers as Record<string, string>;
    expect(headers["X-Client-Platform"]).toBe("MOBILE");
    expect(headers.Authorization).toBe("Bearer access-1");
  });

  it("auth:false면 Authorization을 붙이지 않는다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(201, { email: "a@b.c" }));
    const { store } = memoryTokenStore("access-1", "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await api.post("/users", { username: "hana" }, { auth: false });

    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
    expect(init.body).toBe(JSON.stringify({ username: "hana" }));
  });

  it("오류 body의 code를 ApiError로 옮긴다", async () => {
    const fetchFn = jest.fn(async () =>
      fakeResponse(409, { code: "ACCOUNT_IDENTIFIER_UNAVAILABLE", message: "사용할 수 없는 계정 정보입니다." }),
    );
    const { store } = memoryTokenStore(null, null);
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.post("/users", {}, { auth: false })).rejects.toThrow(ApiError);
    await expect(api.post("/users", {}, { auth: false })).rejects.toMatchObject({
      status: 409,
      code: "ACCOUNT_IDENTIFIER_UNAVAILABLE",
      message: "사용할 수 없는 계정 정보입니다.",
    });
  });

  it("204와 빈 body는 undefined를 돌려준다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(204));
    const { store } = memoryTokenStore("access-1", "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.post("/users/me/withdrawals", { currentPassword: "x" })).resolves.toBeUndefined();
  });

  it("네트워크 실패를 NETWORK_UNAVAILABLE로 바꾼다", async () => {
    const fetchFn = jest.fn(async () => {
      throw new TypeError("Network request failed");
    });
    const { store } = memoryTokenStore(null, null);
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.get("/users/me", { auth: false })).rejects.toMatchObject({
      code: "NETWORK_UNAVAILABLE",
      status: 0,
    });
  });

  it("본문이 JSON이 아니면 INVALID_RESPONSE로 바꾼다 (프록시가 끼워 넣은 HTML 오류 페이지 등)", async () => {
    // fakeResponse는 body를 JSON.stringify하므로 비-JSON 본문은 인라인으로 직접 만든다
    const fetchFn = jest.fn(async () => ({
      ok: false,
      status: 502,
      text: async () => "<html>502 Bad Gateway</html>",
    }) as unknown as Response);
    const { store } = memoryTokenStore(null, null);
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.get("/users/me", { auth: false })).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
      status: 502,
    });
  });
});

const SESSION = {
  accessToken: "test-access-2",
  tokenType: "Bearer",
  accessTokenExpiresAt: "2026-09-23T00:10:00.000Z",
  refreshToken: "test-refresh-2",
  refreshTokenExpiresAt: "2026-10-23T00:00:00.000Z",
  user: { userId: "1", username: "hana" },
};

describe("api-client 401 자동 갱신", () => {
  it("401을 받으면 갱신하고 원요청을 한 번 재시도한다", async () => {
    const fetchFn = jest
      .fn()
      .mockResolvedValueOnce(fakeResponse(401, { code: "UNAUTHORIZED", message: "인증이 필요합니다." }))
      .mockResolvedValueOnce(fakeResponse(201, SESSION))
      .mockResolvedValueOnce(fakeResponse(200, { username: "hana" }));
    const { store, state } = memoryTokenStore("stale", "refresh-1");
    const onSessionExpired = jest.fn();
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired,
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.get("/users/me")).resolves.toEqual({ username: "hana" });

    expect(fetchFn).toHaveBeenCalledTimes(3);
    expect(fetchFn.mock.calls[1][0]).toBe("http://localhost:8080/api/v1/access-tokens");
    expect(state.access).toBe("test-access-2");
    expect(state.refresh).toBe("test-refresh-2");
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it("갱신이 실패하면 토큰을 지우고 만료를 알린 뒤 원래 401을 던진다", async () => {
    const fetchFn = jest
      .fn()
      .mockResolvedValueOnce(fakeResponse(401, { code: "UNAUTHORIZED", message: "인증이 필요합니다." }))
      .mockResolvedValueOnce(
        fakeResponse(401, { code: "INVALID_REFRESH_TOKEN", message: "다시 로그인해 주세요." }),
      );
    const { store, state } = memoryTokenStore("stale", "refresh-1");
    const onSessionExpired = jest.fn();
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired,
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.get("/users/me")).rejects.toMatchObject({ status: 401, code: "UNAUTHORIZED" });

    expect(fetchFn).toHaveBeenCalledTimes(2);
    expect(state.access).toBeNull();
    expect(state.refresh).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("갱신 중 네트워크가 끊기면 Refresh Token을 남기고 만료를 알리지 않는다", async () => {
    const fetchFn = jest
      .fn()
      .mockResolvedValueOnce(fakeResponse(401, { code: "UNAUTHORIZED", message: "인증이 필요합니다." }))
      .mockImplementationOnce(async () => {
        throw new TypeError("Network request failed");
      });
    const { store, state } = memoryTokenStore("stale", "refresh-1");
    const onSessionExpired = jest.fn();
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired,
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    // 갱신 자체는 네트워크 문제로 실패했을 뿐이다 — 원요청의 401이 그대로 던져진다
    await expect(api.get("/users/me")).rejects.toMatchObject({ status: 401, code: "UNAUTHORIZED" });

    expect(fetchFn).toHaveBeenCalledTimes(2);
    expect(state.access).toBe("stale");
    expect(state.refresh).toBe("refresh-1");
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it("Refresh Token이 없으면 갱신을 시도하지 않는다", async () => {
    const fetchFn = jest
      .fn()
      .mockResolvedValueOnce(fakeResponse(401, { code: "UNAUTHORIZED", message: "인증이 필요합니다." }));
    const { store } = memoryTokenStore("stale", null);
    const onSessionExpired = jest.fn();
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired,
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(api.get("/users/me")).rejects.toMatchObject({ status: 401 });

    expect(fetchFn).toHaveBeenCalledTimes(1);
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("동시에 401을 받은 요청 셋이 갱신을 한 번만 호출한다", async () => {
    // 갱신 전에는 401, 갱신 뒤에는 200을 주는 fetch. 재시도가 성공해야 단일 비행을 셀 수 있다
    let refreshed = false;
    const smartFetch = jest.fn(async (url: string) => {
      if (url.endsWith("/access-tokens")) {
        refreshed = true;
        return fakeResponse(201, SESSION);
      }
      return refreshed
        ? fakeResponse(200, { username: "hana" })
        : fakeResponse(401, { code: "UNAUTHORIZED", message: "인증이 필요합니다." });
    });
    const { store } = memoryTokenStore("stale", "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: smartFetch as unknown as typeof fetch,
    });

    const results = await Promise.all([
      api.get("/users/me"),
      api.get("/users/me"),
      api.get("/users/me"),
    ]);

    expect(results).toEqual([{ username: "hana" }, { username: "hana" }, { username: "hana" }]);
    const refreshCalls = smartFetch.mock.calls.filter(([url]) => String(url).endsWith("/access-tokens"));
    expect(refreshCalls).toHaveLength(1);
  });

  it("갱신 요청 자신은 401이어도 다시 갱신하지 않는다", async () => {
    const fetchFn = jest
      .fn()
      .mockResolvedValueOnce(
        fakeResponse(401, { code: "INVALID_REFRESH_TOKEN", message: "다시 로그인해 주세요." }),
      );
    const { store } = memoryTokenStore(null, "refresh-1");
    const api = createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });

    await expect(
      api.post("/access-tokens", { refreshToken: "test-refresh-1" }, { auth: false }),
    ).rejects.toMatchObject({ code: "INVALID_REFRESH_TOKEN" });

    expect(fetchFn).toHaveBeenCalledTimes(1);
  });
});

describe("api-client 요청별 헤더", () => {
  function clientWith(fetchFn: jest.Mock) {
    const { store } = memoryTokenStore("access-1", "refresh-1");
    return createClient({
      baseUrl: "http://localhost:8080/api/v1",
      platform: "MOBILE",
      tokens: store,
      onSessionExpired: () => {},
      fetchFn: fetchFn as unknown as typeof fetch,
    });
  }

  it("요청별 헤더를 인증·플랫폼 헤더와 함께 보낸다 (Idempotency-Key)", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(201, { ok: true }));

    await clientWith(fetchFn).post(
      "/lectures/42/problems/7/answers",
      { cells: [8], durationMs: 0 },
      { headers: { "Idempotency-Key": "k-1" } },
    );

    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers["Idempotency-Key"]).toBe("k-1");
    expect(headers.Authorization).toBe("Bearer access-1");
    expect(headers["X-Client-Platform"]).toBe("MOBILE");
    expect(headers["Content-Type"]).toBe("application/json");
  });

  it("요청별 헤더가 인증·플랫폼 헤더를 덮지 못한다", async () => {
    const fetchFn = jest.fn(async () => fakeResponse(200, {}));

    await clientWith(fetchFn).get("/users/me", {
      headers: { Authorization: "Bearer other", "X-Client-Platform": "WEB" },
    });

    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer access-1");
    expect(headers["X-Client-Platform"]).toBe("MOBILE");
  });
});
