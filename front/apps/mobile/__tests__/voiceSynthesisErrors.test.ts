import { ApiError, INVALID_RESPONSE, NETWORK_UNAVAILABLE } from "@ieum/api-client";
import {
  VOICE_FAILED_MESSAGE,
  VOICE_GENERIC_MESSAGE,
  VOICE_LEARNER_ONLY_MESSAGE,
  VOICE_NETWORK_MESSAGE,
  VOICE_RATE_LIMITED_MESSAGE,
  VOICE_TOO_LARGE_MESSAGE,
  VOICE_UNAVAILABLE_MESSAGE,
  voiceFailureMessage,
} from "@/services/voiceSynthesisErrors";

describe("voiceFailureMessage", () => {
  it.each([
    [new ApiError(503, "VOICE_SYNTHESIS_UNAVAILABLE", "x"), VOICE_UNAVAILABLE_MESSAGE],
    [new ApiError(429, "VOICE_RATE_LIMITED", "x"), VOICE_RATE_LIMITED_MESSAGE],
    [new ApiError(502, "VOICE_SYNTHESIS_FAILED", "x"), VOICE_FAILED_MESSAGE],
    [new ApiError(403, "LEARNER_ACCESS_REQUIRED", "x"), VOICE_LEARNER_ONLY_MESSAGE],
    [new ApiError(0, NETWORK_UNAVAILABLE, "x"), VOICE_NETWORK_MESSAGE],
  ])("계약 오류 코드를 사용자 문구로 바꾼다 (%#)", (error, expected) => {
    expect(voiceFailureMessage(error)).toBe(expected);
  });

  it("nginx가 HTML로 답한 상태 코드도 상태로 구분한다 (본문 해석 실패)", () => {
    expect(voiceFailureMessage(new ApiError(503, INVALID_RESPONSE, "x"))).toBe(VOICE_UNAVAILABLE_MESSAGE);
    expect(voiceFailureMessage(new ApiError(502, INVALID_RESPONSE, "x"))).toBe(VOICE_FAILED_MESSAGE);
    expect(voiceFailureMessage(new ApiError(504, INVALID_RESPONSE, "x"))).toBe(VOICE_FAILED_MESSAGE);
    expect(voiceFailureMessage(new ApiError(413, INVALID_RESPONSE, "x"))).toBe(VOICE_TOO_LARGE_MESSAGE);
  });

  it("그 밖의 오류는 일반 문구로, 서버 message 원문을 그대로 쓰지 않는다", () => {
    expect(voiceFailureMessage(new ApiError(400, "INVALID_REQUEST_PARAMETER", "text 길이 초과"))).toBe(VOICE_GENERIC_MESSAGE);
    expect(voiceFailureMessage(new Error("boom"))).toBe(VOICE_GENERIC_MESSAGE);
    expect(voiceFailureMessage("boom")).toBe(VOICE_GENERIC_MESSAGE);
  });
});
