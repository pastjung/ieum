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

  async function send<T>(method: string, path: string, options: RequestOptions): Promise<T> {
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
      );
    }
    return payload as T;
  }

  return {
    get: <T,>(path: string, options: RequestOptions = {}) => send<T>("GET", path, options),
    post: <T,>(path: string, body?: unknown, options: RequestOptions = {}) =>
      send<T>("POST", path, { ...options, body }),
  };
}
