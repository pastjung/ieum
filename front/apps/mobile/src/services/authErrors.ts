import { ApiError, NETWORK_UNAVAILABLE } from "@ieum/api-client";

/** 백엔드 문구보다 화면 맥락이 더 친절한 경우만 덮어쓴다 */
const OVERRIDES: Record<string, string> = {
  ACCOUNT_IDENTIFIER_UNAVAILABLE: "이미 쓰고 있는 아이디나 이메일이에요. 다른 값으로 해 주세요.",
  EMAIL_VERIFICATION_REQUIRED: "이메일 인증이 아직이에요. 메일함을 확인해 주세요.",
  /**
   * 백엔드는 길이 위반과 유출 비밀번호를 같은 code·같은 문구("비밀번호 정책을 확인해 주세요")로
   * 돌려준다. 그래서는 무엇을 고쳐야 하는지 알 수 없다 — 낭독으로만 듣는 사용자에게는 막다른 길이다.
   *
   * 프론트가 길이(`PASSWORD_MIN_LENGTH`~`PASSWORD_MAX_LENGTH`)를 제출 전에 막으므로, 서버까지 가서
   * 이 code를 받는 경우는 유출 검사밖에 남지 않는다. 그 전제가 깨지면(스키마에서 길이 검증을 빼면)
   * 이 문구는 거짓이 된다 — 둘은 함께 움직여야 한다.
   */
  INVALID_PASSWORD: "다른 곳에서 유출된 적 있는 비밀번호예요. 흔하지 않은 비밀번호로 해 주세요.",
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
