import { createClient } from "@ieum/api-client";
import { tokenStore } from "./tokenStore";

/**
 * Android 에뮬레이터에서 host PC의 localhost는 10.0.2.2다.
 * iOS 시뮬레이터나 실기기는 .env의 EXPO_PUBLIC_API_URL로 덮는다.
 */
const DEFAULT_BASE_URL = "http://10.0.2.2:8080/api/v1";

/**
 * 만료 처리는 라우터를 아는 곳에서만 할 수 있다. 앱 루트가 여기에 핸들러를 꽂는다.
 * 꽂히기 전에 만료가 나도 조용히 무시된다 — 그 시점엔 보여 줄 화면이 없다.
 */
let sessionExpiredHandler: () => void = () => {};

export function setSessionExpiredHandler(handler: () => void): void {
  sessionExpiredHandler = handler;
}

export const api = createClient({
  baseUrl: process.env.EXPO_PUBLIC_API_URL ?? DEFAULT_BASE_URL,
  platform: "MOBILE",
  tokens: tokenStore,
  onSessionExpired: () => sessionExpiredHandler(),
});
