import { ApiError } from "@ieum/api-client";
import {
  authSessionSchema,
  userSummarySchema,
  type SignInInput,
  type SignUpInput,
  type UserSummary,
} from "@ieum/contracts";
import { api } from "./apiClient";
import { tokenStore } from "./tokenStore";

/** 로그인·갱신 성공 응답을 검증하고 토큰을 보관한다 */
async function persistSession(payload: unknown) {
  const session = authSessionSchema.parse(payload);
  await tokenStore.save({
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
  });
  return session;
}

export const authService = {
  /** 가입은 토큰을 발급하지 않는다 — 이메일 인증 전에는 로그인할 수 없다 */
  async signUp(input: SignUpInput): Promise<{ email: string }> {
    const user = userSummarySchema.parse(await api.post("/users", input, { auth: false }));
    return { email: user.email };
  },

  async signIn(input: SignInInput): Promise<{ userName: string }> {
    const session = await persistSession(await api.post("/auth-sessions", input, { auth: false }));
    return { userName: session.user.username };
  },

  /**
   * 앱 시작 시 Refresh Token으로 세션을 되살린다.
   *
   * @returns 복구에 성공해 탭 화면으로 보내도 되는지
   */
  async restore(): Promise<boolean> {
    const refreshToken = await tokenStore.getRefresh();
    if (!refreshToken) return false;
    try {
      await persistSession(await api.post("/access-tokens", { refreshToken }, { auth: false }));
      return true;
    } catch (error) {
      // 자격 증명이 죽었다는 뜻은 서버가 인증을 거부했을 때(401·403)뿐이다.
      // 네트워크 끊김·5xx·응답 형식 위반은 토큰 상태에 대해 아무것도 말해 주지 않으므로 그대로 둔다 —
      // 다음 실행에서 네트워크가 돌아오면 다시 시도할 수 있다
      const authRejected = error instanceof ApiError && (error.status === 401 || error.status === 403);
      if (authRejected) {
        try {
          await tokenStore.clear();
        } catch {
          // clear 자체가 실패해도 restore()는 절대 던지지 않는다 — 스플래시가 멈추면 안 된다
        }
      }
      return false;
    }
  },

  async signOut(): Promise<void> {
    const refreshToken = await tokenStore.getRefresh();
    if (refreshToken) {
      try {
        await api.post("/auth-session-revocations", { refreshToken }, { auth: false });
      } catch {
        // 서버 폐기에 실패해도 로컬 정리는 진행한다 — 이 기기에서는 로그아웃돼야 한다
      }
    }
    await tokenStore.clear();
  },

  getMe(): Promise<UserSummary> {
    return api.get<unknown>("/users/me").then((payload) => userSummarySchema.parse(payload));
  },
};
