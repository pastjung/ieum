export type ClientPlatform = "MOBILE" | "WEB";

/** 로그인·갱신 응답에서 클라이언트가 보관하는 두 값 */
export interface AuthSessionTokens {
  accessToken: string;
  refreshToken: string;
}

/**
 * 토큰 저장소. 패키지가 expo-secure-store를 직접 알지 않도록 주입받는다.
 *
 * `getAccess`만 동기다 — 매 요청 헤더에 쓰이므로 메모리에 있다고 전제한다.
 */
export interface TokenStore {
  getAccess(): string | null;
  getRefresh(): Promise<string | null>;
  save(tokens: AuthSessionTokens): Promise<void>;
  clear(): Promise<void>;
}

export interface RequestOptions {
  /** JSON으로 보낼 값. `FormData`면 그대로 보내고 Content-Type은 fetch가 정한다(multipart boundary) */
  body?: unknown;
  /** 기본 true. false면 Authorization을 붙이지 않고 401 자동 갱신도 하지 않는다 */
  auth?: boolean;
  /** 요청별 헤더(예: Idempotency-Key). 플랫폼·Content-Type·Authorization은 덮지 못한다 */
  headers?: Readonly<Record<string, string>>;
  /**
   * 성공 응답 해석 방식. 기본 "json". "arrayBuffer"면 본문 바이트를 그대로 돌려준다(예: MP3).
   * 오류 응답은 어느 쪽이든 JSON `{code, message}`로 해석해 ApiError로 던진다
   */
  responseType?: "json" | "arrayBuffer";
  /** 요청 취소(시간 제한 등). 취소되면 NETWORK_UNAVAILABLE ApiError로 끝난다 */
  signal?: AbortSignal;
}

export interface ApiClientConfig {
  /** `/api/v1`까지 포함한다. 끝에 슬래시를 두지 않는다 */
  baseUrl: string;
  platform: ClientPlatform;
  tokens: TokenStore;
  /** Refresh Token으로도 복구할 수 없을 때 한 번 호출된다 */
  onSessionExpired: () => void;
  /** 테스트 주입용. 생략하면 전역 fetch */
  fetchFn?: typeof fetch;
}

export interface ApiClient {
  get<T>(path: string, options?: RequestOptions): Promise<T>;
  post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  /** 단일 값 자원의 교체. 같은 body를 두 번 보내도 결과가 같아야 하는 호출에 쓴다 */
  put<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
}
