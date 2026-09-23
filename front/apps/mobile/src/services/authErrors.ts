import { ApiError, NETWORK_UNAVAILABLE } from "@ieum/api-client";

/** 백엔드 문구보다 화면 맥락이 더 친절한 경우만 덮어쓴다 */
const OVERRIDES: Record<string, string> = {
  ACCOUNT_IDENTIFIER_UNAVAILABLE: "이미 쓰고 있는 아이디나 이메일이에요. 다른 값으로 해 주세요.",
  EMAIL_VERIFICATION_REQUIRED: "이메일 인증이 아직이에요. 메일함을 확인해 주세요.",
  [NETWORK_UNAVAILABLE]: "네트워크에 연결할 수 없어요. 연결 상태를 확인해 주세요.",
};

/**
 * 사용자에게 읽어 줄 한 문장을 만든다.
 *
 * 알 수 없는 오류까지 포함해 항상 문장을 돌려준다 — 시각장애인 사용자에게
 * 침묵은 실패를 알리지 않은 것과 같다.
 */
export function describeAuthError(error: unknown): string {
  if (error instanceof ApiError) {
    return OVERRIDES[error.code] ?? error.message;
  }
  return "문제가 생겼어요. 잠시 후 다시 시도해 주세요.";
}
