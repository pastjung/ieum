import * as SecureStore from "expo-secure-store";
import type { SignInInput, SignUpInput } from "@ieum/contracts";

const TOKEN_KEY = "ieum.accessToken";
const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** 인증 목 서비스 — 백엔드 연동 시 교체 지점 */
export const authService = {
  async signUp(input: SignUpInput): Promise<{ email: string }> {
    await delay(400);
    return { email: input.email };
  },

  async signIn(input: SignInInput): Promise<{ userName: string }> {
    await delay(400);
    await SecureStore.setItemAsync(TOKEN_KEY, `mock-token-${input.username}`);
    return { userName: "하나" };
  },

  async getToken(): Promise<string | null> {
    return SecureStore.getItemAsync(TOKEN_KEY);
  },

  async signOut(): Promise<void> {
    await SecureStore.deleteItemAsync(TOKEN_KEY);
  },
};
