import { uuid } from "expo-modules-core";

/**
 * 수업 답안의 Idempotency-Key(UUID v4). 문제마다 새로 만든다.
 * expo가 이미 싣고 있는 네이티브 생성기(globalThis.expo.uuidv4)를 쓴다 — 의존성을 더하지 않는다.
 * jest에는 네이티브가 없으므로 테스트는 이 모듈을 목으로 바꾼다.
 */
export function newIdempotencyKey(): string {
  return uuid.v4();
}
