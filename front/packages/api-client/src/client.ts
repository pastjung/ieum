import { ApiError, INVALID_RESPONSE, NETWORK_UNAVAILABLE } from "./errors";
import type { ApiClient, ApiClientConfig, RequestOptions } from "./types";

export function createClient(config: ApiClientConfig): ApiClient {
  const fetchFn = config.fetchFn ?? fetch;

  function buildHeaders(options: RequestOptions): Record<string, string> {
    const headers: Record<string, string> = {
      "X-Client-Platform": config.platform,
    };
    // body가 없는 요청에 Content-Type을 붙이지 않는다 — 보낼 표현이 없다
    if (options.body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (options.auth !== false) {
      const accessToken = config.tokens.getAccess();
      if (accessToken) {
        headers.Authorization = `Bearer ${accessToken}`;
      }
    }
    return headers;
  }

  async function sendOnce<T>(method: string, path: string, options: RequestOptions): Promise<T> {
    let response: Response;
    try {
      response = await fetchFn(`${config.baseUrl}${path}`, {
        method,
        headers: buildHeaders(options),
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      });
    } catch {
      // 원인 메시지를 그대로 노출하지 않는다 — 사용자에게 의미가 없다
      throw new ApiError(0, NETWORK_UNAVAILABLE, "네트워크에 연결할 수 없어요.");
    }

    // 204와, 오류 상태에서 body를 생략한 응답을 같은 경로로 처리한다
    let payload: unknown;
    try {
      const text = await response.text();
      payload = text === "" ? undefined : JSON.parse(text);
    } catch {
      // nginx 등 중간 프록시가 502/503에 HTML 오류 페이지를 끼워 넣을 수 있다 — status는 호출자에게 의미가 있으니 살려서 넘긴다
      throw new ApiError(response.status, INVALID_RESPONSE, "서버 응답을 해석하지 못했어요.");
    }

    if (!response.ok) {
      const body = (payload ?? {}) as { code?: string; message?: string };
      throw new ApiError(
        response.status,
        body.code ?? "UNKNOWN_ERROR",
        body.message ?? "요청을 처리하지 못했어요.",
        payload,
      );
    }
    return payload as T;
  }

  /**
   * 진행 중인 갱신. 동시에 401을 받은 요청들이 같은 Promise를 기다린다.
   * 요청 수만큼 갱신하면 Refresh Token 회전이 서로를 무효화한다.
   */
  let refreshInFlight: Promise<boolean> | null = null;

  async function doRefresh(): Promise<boolean> {
    const refreshToken = await config.tokens.getRefresh();
    if (!refreshToken) {
      // 보관할 Refresh Token 자체가 없다 — 정리하고 만료를 알린다
      await config.tokens.clear();
      config.onSessionExpired();
      return false;
    }
    try {
      const session = await sendOnce<{ accessToken?: unknown; refreshToken?: unknown }>(
        "POST",
        "/access-tokens",
        { body: { refreshToken }, auth: false },
      );
      if (typeof session.accessToken === "string" && typeof session.refreshToken === "string") {
        await config.tokens.save({
          accessToken: session.accessToken,
          refreshToken: session.refreshToken,
        });
        return true;
      }
      // 응답 형식이 어긋난 것은 인증 거부가 아니다 — 토큰은 그대로 둔다
      return false;
    } catch (error) {
      // 자격 증명이 죽었다는 뜻은 서버가 인증을 거부했을 때(401·403)뿐이다.
      // 네트워크 끊김·5xx는 토큰 상태에 대해 아무것도 말해 주지 않으므로 지우지 않는다
      const authRejected = error instanceof ApiError && (error.status === 401 || error.status === 403);
      if (!authRejected) {
        return false;
      }
      // single-flight라 이 정리는 갱신 1회당 한 번만 일어난다
      await config.tokens.clear();
      config.onSessionExpired();
      return false;
    }
  }

  function refreshOnce(): Promise<boolean> {
    if (!refreshInFlight) {
      refreshInFlight = doRefresh().finally(() => {
        refreshInFlight = null;
      });
    }
    return refreshInFlight;
  }

  async function send<T>(method: string, path: string, options: RequestOptions): Promise<T> {
    try {
      return await sendOnce<T>(method, path, options);
    } catch (error) {
      const retriable =
        error instanceof ApiError && error.status === 401 && options.auth !== false;
      if (!retriable) throw error;

      const refreshed = await refreshOnce();
      if (!refreshed) throw error;

      // 재시도는 한 번뿐이다. 또 401이면 갱신으로 풀 수 있는 상황이 아니다
      return await sendOnce<T>(method, path, options);
    }
  }

  return {
    get: <T,>(path: string, options: RequestOptions = {}) => send<T>("GET", path, options),
    post: <T,>(path: string, body?: unknown, options: RequestOptions = {}) =>
      send<T>("POST", path, { ...options, body }),
    put: <T,>(path: string, body?: unknown, options: RequestOptions = {}) =>
      send<T>("PUT", path, { ...options, body }),
  };
}
