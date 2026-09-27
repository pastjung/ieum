import { ApiError, NETWORK_UNAVAILABLE } from "@ieum/api-client";

/**
 * 음성 복제·합성(백엔드 `/voice/*`) 실패를 사용자에게 읽어 줄 한 문장으로 바꾼다.
 *
 * 화면은 이 문장을 그대로 보여 주고 낭독한다(TalkBack이 한 번에 읽을 수 있게 짧게).
 * 서버 message나 Cartesia 원문은 쓰지 않는다 — 기술 용어가 섞이고, 계약상 502 원문은 오지도 않는다.
 * nginx가 HTML로 답한 경우(본문 해석 실패)도 상태 코드로 구분한다.
 */
export const VOICE_UNAVAILABLE_MESSAGE = "지금은 목소리 기능을 쓸 수 없어요. 기본 목소리로 안내할게요.";
export const VOICE_RATE_LIMITED_MESSAGE = "요청이 너무 많아요. 1분 뒤에 다시 시도해 주세요.";
export const VOICE_FAILED_MESSAGE = "목소리 서버가 응답하지 않았어요. 잠시 뒤 다시 시도해 주세요.";
export const VOICE_TOO_LARGE_MESSAGE = "녹음 파일이 너무 커요. 조금 더 짧게 읽어 주세요.";
export const VOICE_NETWORK_MESSAGE = "네트워크에 연결할 수 없어요. 연결을 확인하고 다시 시도해 주세요.";
export const VOICE_LEARNER_ONLY_MESSAGE = "학습자 계정으로 로그인해야 쓸 수 있어요.";
export const VOICE_GENERIC_MESSAGE = "목소리 기능에서 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요.";

export function voiceFailureMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return VOICE_GENERIC_MESSAGE;
  if (error.code === "VOICE_SYNTHESIS_UNAVAILABLE" || error.status === 503) return VOICE_UNAVAILABLE_MESSAGE;
  if (error.code === "VOICE_RATE_LIMITED" || error.status === 429) return VOICE_RATE_LIMITED_MESSAGE;
  if (error.code === "VOICE_SYNTHESIS_FAILED" || error.status === 502 || error.status === 504) {
    return VOICE_FAILED_MESSAGE;
  }
  if (error.status === 413) return VOICE_TOO_LARGE_MESSAGE;
  if (error.code === "LEARNER_ACCESS_REQUIRED") return VOICE_LEARNER_ONLY_MESSAGE;
  if (error.status === 0 || error.code === NETWORK_UNAVAILABLE) return VOICE_NETWORK_MESSAGE;
  return VOICE_GENERIC_MESSAGE;
}
