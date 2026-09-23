import * as SecureStore from "expo-secure-store";
import type { AuthSessionTokens, TokenStore } from "@ieum/api-client";

const REFRESH_KEY = "ieum.refreshToken";
/** 목 시절에 Access Token을 디스크에 두던 키. 남아 있으면 지운다 */
const LEGACY_ACCESS_KEY = "ieum.accessToken";

/**
 * Access Token은 메모리에만 둔다 — API 명세 3.3.1이 OS 보안 저장소 보관을
 * Refresh Token에만 요구하고, Access는 수명이 짧아 디스크에 남길 이유가 없다.
 */
let accessToken: string | null = null;

export const tokenStore: TokenStore = {
  getAccess: () => accessToken,

  getRefresh: () => SecureStore.getItemAsync(REFRESH_KEY),

  async save(tokens: AuthSessionTokens) {
    accessToken = tokens.accessToken;
    await SecureStore.setItemAsync(REFRESH_KEY, tokens.refreshToken);
  },

  async clear() {
    accessToken = null;
    await SecureStore.deleteItemAsync(REFRESH_KEY);
    await SecureStore.deleteItemAsync(LEGACY_ACCESS_KEY);
  },
};
