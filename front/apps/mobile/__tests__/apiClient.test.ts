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
});
