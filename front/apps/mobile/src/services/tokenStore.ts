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

  /**
   * 조회가 거부돼도 던지지 않는다 — 호출처(세션 복구·로그아웃)는 "읽을 수 없음"과
   * "없음"을 똑같이 다뤄야 하고, 조회 실패가 로컬 정리를 막으면 안 된다.
   * 저장소 자체가 망가졌다면 clear()가 거부해 호출처가 사용자에게 알린다
   */
  async getRefresh() {
    try {
      return await SecureStore.getItemAsync(REFRESH_KEY);
    } catch {
      return null;
    }
  },

  async save(tokens: AuthSessionTokens) {
    accessToken = tokens.accessToken;
    await SecureStore.setItemAsync(REFRESH_KEY, tokens.refreshToken);
  },

  /**
   * 메모리부터 지운다 — SecureStore 삭제가 거부돼도 이 기기의 로그아웃은 성립해야 한다.
   * 거부는 삼키지 않고 올린다. 남은 Refresh Token을 사용자가 알아야 하기 때문이다
   */
  async clear() {
    accessToken = null;
    await SecureStore.deleteItemAsync(REFRESH_KEY);
    await SecureStore.deleteItemAsync(LEGACY_ACCESS_KEY);
  },
};
