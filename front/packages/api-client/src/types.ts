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
  body?: unknown;
  /** 기본 true. false면 Authorization을 붙이지 않고 401 자동 갱신도 하지 않는다 */
  auth?: boolean;
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
}
